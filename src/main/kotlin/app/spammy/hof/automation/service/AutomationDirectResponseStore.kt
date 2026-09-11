package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.TypedAutomationActionRunEntity
import app.spammy.hof.automation.entity.TypedAutomationActionStatus
import app.spammy.hof.automation.entity.TypedAutomationLifecycle
import app.spammy.hof.automation.convergence.AutomationIsolationScope
import app.spammy.hof.automation.convergence.JpaEvidenceCaseRecorder
import app.spammy.hof.automation.convergence.StoredActionConvergenceSelectionFactory
import app.spammy.hof.automation.repository.TypedAutomationQueryRepository
import app.spammy.hof.automation.outbox.AutomationOutboxService
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.quest.model.QuestSnapshot
import app.spammy.hof.town.home.model.HomeQuestState
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

/** 원격 응답 중 판정·후처리에 필요한 정규화 값만 보존한다. HTML·쿠키·form은 포함하지 않는다. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(AutomationDirectResponse.QuestPage::class, name = "QUEST_PAGE"),
    JsonSubTypes.Type(AutomationDirectResponse.HomePage::class, name = "HOME_PAGE"),
    JsonSubTypes.Type(AutomationDirectResponse.QuestBattle::class, name = "QUEST_BATTLE"),
    JsonSubTypes.Type(AutomationDirectResponse.BattleMap::class, name = "BATTLE_MAP"),
)
sealed interface AutomationDirectResponse {
    data class QuestPage(val quests: List<QuestSnapshot>, val complete: Boolean) : AutomationDirectResponse

    data class QuestBattle(val outcomes: List<BattleAutomationRoundOutcome>) : AutomationDirectResponse

    data class BattleMap(
        val resultIdentity: String,
        val outcomes: List<BattleAutomationRoundOutcome>,
        val lootNames: List<String>,
        val questTexts: List<String>,
    ) : AutomationDirectResponse

    data class HomePage(val quests: List<Quest>, val resultStatus: String?) : AutomationDirectResponse {
        data class Quest(
            val id: String,
            val state: HomeQuestState,
            val actionId: String?,
            val stateObserved: Boolean,
        )
    }
}

data class StoredAutomationDirectResponse(
    val version: Int,
    val capturedAt: Instant,
    val response: AutomationDirectResponse,
    val isolation: DirectResponseActionScope? = null,
    val policyContext: StoredActionPolicyContext? = null,
)

data class DirectResponseActionScope(val entryId: Long, val scope: AutomationIsolationScope) {
    init { require(entryId > 0) }
}

data class PendingDirectResponse(
    val actionId: Long,
    val executionIdentity: String,
    val entryId: Long?,
    val entryDisplayName: String?,
    val actionKind: String,
    val status: TypedAutomationActionStatus,
    val scope: AutomationIsolationScope?,
    val retryAttempt: Int,
    val nextAttemptAt: Instant?,
    val evidenceCaseId: String?,
)

data class DirectResponseIsolation(
    val scopes: Set<AutomationIsolationScope> = emptySet(),
    val entries: Set<Long> = emptySet(),
) {
    val isEmpty: Boolean get() = scopes.isEmpty() && entries.isEmpty()
    fun blocks(entryId: Long, scope: AutomationIsolationScope): Boolean = entryId in entries || scope in scopes
}

/** 원래 행동의 불변 payload에 응답을 결합한다. 후처리 transaction과 별도로 commit한다. */
@Service
class AutomationDirectResponseStore(
    private val queries: TypedAutomationQueryRepository,
    private val codec: StoredTypedAutomationActionCodec,
    private val mapper: ObjectMapper,
    private val clock: TimeProvider,
    private val evidenceCases: JpaEvidenceCaseRecorder,
    private val outbox: AutomationOutboxService,
) {
    /** 원래 응답 후처리는 typed runtime이 재시도하며 일반 관측 probe로 대체하지 않는다. */
    @Transactional(readOnly = true)
    fun pendingExecutionIdentities(accountId: Long): Set<String> =
        queries.findPendingDirectResponses(accountId).mapTo(linkedSetOf()) { it.executionIdentity }

    /** 수렴 관측이나 운행 모드와 무관하게, 아직 반영하지 못한 원래 결과의 대상만 억제한다. */
    @Transactional(readOnly = true)
    fun pendingIsolation(accountId: Long): DirectResponseIsolation {
        val scopes = linkedSetOf<AutomationIsolationScope>()
        val entries = linkedSetOf<Long>()
        queries.findPendingDirectResponses(accountId).forEach { row ->
            val original = originalScope(row, accountId)
            if (original != null) scopes += original.scope
            else row.entry?.let { entries += it.id }
        }
        return DirectResponseIsolation(scopes, entries)
    }

    @Transactional(readOnly = true)
    fun pendingResults(accountId: Long): List<PendingDirectResponse> =
        queries.findPendingDirectResponses(accountId).map { row ->
            val original = runCatching { codec.verifyPersisted(row, accountId) }.getOrNull()
            val policy = original?.policyContext ?: runCatching { read(row)?.policyContext }.getOrNull()
            PendingDirectResponse(row.id, row.executionIdentity, row.entry?.id, row.entryDisplayName,
                policy?.actionKind?.name ?: original?.payload?.kind() ?: "UNKNOWN",
                row.status, originalScope(row, accountId)?.scope, row.retryAttempt, row.nextAttemptAt,
                row.directResponseEvidenceCaseId)
        }

    /** 이미 잠근 행동의 RESULT_HELD 전이와 같은 transaction에서 한 번만 기록한다. */
    @Transactional
    fun recordIntegrityFailure(row: TypedAutomationActionRunEntity, at: Instant) {
        require(row.status == TypedAutomationActionStatus.RESULT_HELD)
        if (row.directResponseEvidenceCaseId != null) return
        val original = runCatching { codec.verifyPersisted(row, row.account.id) }.getOrNull()
        val receipt = runCatching { read(row) }.getOrNull()
        val policy = original?.policyContext ?: receipt?.policyContext
        val actionKind = policy?.actionKind?.name ?: original?.payload?.kind() ?: "UNKNOWN"
        // 오류 메시지·payload·응답 원문을 복사하지 않고 허용한 구조 정보만 남긴다.
        val snippet = "actionKind=$actionKind;payloadValid=${original != null};" +
            "receiptPresent=${row.directResponseJson != null};receiptValid=${receipt != null}"
        row.directResponseEvidenceCaseId = evidenceCases.recordLocalResultIntegrity(row, policy, snippet, at)
    }

    /** 원래 응답과 보류 사실은 남기고, 사용자 의사에 따라 그 억제만 영구 종료한다. */
    @Transactional
    fun allowFreshDecision(accountId: Long, actionId: Long): String? {
        val runtime = queries.lockRuntimeState(accountId) ?: return null
        val row = queries.lockTypedAction(actionId) ?: return null
        if (row.account.id != accountId || row.status != TypedAutomationActionStatus.RESULT_HELD ||
            row.directResponseSuppressionReleasedAt != null
        ) return null
        val now = clock.now()
        row.directResponseSuppressionReleasedAt = now
        row.updatedAt = now
        if (runtime.lifecycleStatus == TypedAutomationLifecycle.RUNNING && !runtime.authSuspended &&
            runtime.leaseToken == null && queries.findActiveTypedAction(accountId) == null
        ) {
            runtime.nextAttemptAt = null
            runtime.waitReason = null
        }
        return row.executionIdentity
    }

    private fun originalScope(row: TypedAutomationActionRunEntity, accountId: Long): DirectResponseActionScope? = try {
        val stored = codec.verifyPersisted(row, accountId)
        DirectResponseActionScope(stored.entryId, StoredActionConvergenceSelectionFactory().create(stored).scope)
    } catch (_: RuntimeException) {
        // payload를 실행에 사용하지 않는다. 독립 지문이 유효한 응답의 원래 대상만 격리한다.
        runCatching { read(row)?.isolation }.getOrNull()
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun record(accountId: Long, stored: StoredTypedAutomationAction, response: AutomationDirectResponse): StoredAutomationDirectResponse {
        requireNotNull(queries.lockRuntimeState(accountId))
        val row = requireNotNull(queries.lockTypedActionByExecutionIdentity(accountId, stored.executionIdentity))
        verifyAction(row, accountId, stored)
        verifyResponseKind(stored, response)
        requireNotNull(row.submittedAt) { "A direct response requires an original submission." }
        read(row)?.let { existing ->
            require(existing.response == response) { "An original direct response cannot be replaced." }
            return existing
        }
        val isolation = DirectResponseActionScope(stored.entryId, StoredActionConvergenceSelectionFactory().create(stored).scope)
        val receipt = StoredAutomationDirectResponse(1, clock.now(), response, isolation, stored.policyContext)
        val json = mapper.writeValueAsString(receipt)
        row.directResponseJson = json
        row.directResponseFingerprint = fingerprint(row, json)
        if (row.status in setOf(TypedAutomationActionStatus.FAILED, TypedAutomationActionStatus.AMBIGUOUS)) {
            // 결과 복구 worker가 먼저 종결했어도 원래 응답의 후처리는 유실하지 않는다.
            row.status = TypedAutomationActionStatus.RESULT_PENDING
            row.nextAttemptAt = receipt.capturedAt
            row.finishedAt = null
            row.updatedAt = receipt.capturedAt
            row.lastError = "늦게 도착한 원래 응답의 로컬 후처리를 기다립니다."
            outbox.enqueue(accountId, "TYPED_LATE_DIRECT_RESPONSE_RECEIVED", receipt.capturedAt)
        }
        return receipt
    }

    @Transactional(readOnly = true)
    fun load(accountId: Long, stored: StoredTypedAutomationAction): StoredAutomationDirectResponse? {
        val row = queries.findTypedActionByExecutionIdentity(accountId, stored.executionIdentity) ?: return null
        if (row.directResponseJson == null && row.directResponseFingerprint == null) return null
        verifyAction(row, accountId, stored)
        return read(row)?.also {
            requireNotNull(row.submittedAt) { "A direct response requires an original submission." }
            verifyResponseKind(stored, it.response)
            it.isolation?.let { isolation ->
                require(isolation == DirectResponseActionScope(stored.entryId, StoredActionConvergenceSelectionFactory().create(stored).scope)) {
                    "Direct response isolation does not match its original action."
                }
            }
        }
    }

    private fun verifyAction(row: TypedAutomationActionRunEntity, accountId: Long, expected: StoredTypedAutomationAction) {
        val persisted = codec.verifyPersisted(row, accountId)
        require(persisted.copy(policyContext = null) == expected.copy(policyContext = null)) {
            "Direct response action does not match its original payload."
        }
        require(persisted.policyContext == expected.policyContext) { "Direct response policy context mismatch." }
    }

    private fun read(row: TypedAutomationActionRunEntity): StoredAutomationDirectResponse? {
        val json = row.directResponseJson ?: run {
            require(row.directResponseFingerprint == null) { "Direct response body is missing." }
            return null
        }
        require(fingerprint(row, json) == row.directResponseFingerprint) { "Direct response fingerprint mismatch." }
        return try {
            require(mapper.readTree(json)["version"]?.asInt() == 1) { "Unsupported direct response format." }
            mapper.readValue(json, StoredAutomationDirectResponse::class.java)
        } catch (error: RuntimeException) {
            throw IllegalArgumentException("Stored direct response cannot be decoded.", error)
        }
    }

    private fun verifyResponseKind(stored: StoredTypedAutomationAction, response: AutomationDirectResponse) {
        when (response) {
            is AutomationDirectResponse.QuestPage -> require(
                stored.payload is StoredTypedActionPayload.QuestAccept || stored.payload is StoredTypedActionPayload.QuestClaim,
            ) { "Quest response cannot be attached to a different action kind." }
            is AutomationDirectResponse.HomePage -> require(stored.payload is StoredTypedActionPayload.HomeQuest) {
                "Home response cannot be attached to a different action kind."
            }
            is AutomationDirectResponse.QuestBattle -> require(stored.payload is StoredTypedActionPayload.QuestBattle) {
                "Quest battle response cannot be attached to a different action kind."
            }
            is AutomationDirectResponse.BattleMap -> require(
                stored.payload is StoredTypedActionPayload.BattleMap &&
                    stored.payload.source == BattleAutomationActionSource.BATTLE_MAP_AUTOMATION,
            ) { "Battle-map response cannot be attached to a different action source." }
        }
    }

    private fun fingerprint(row: TypedAutomationActionRunEntity, json: String): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(
            "${row.account.id}\n${row.executionIdentity}\n${row.actionFingerprint}\n$json".toByteArray(Charsets.UTF_8),
        ),
    )
}
