package app.spammy.hof.automation.convergence

import app.spammy.hof.automation.outbox.AutomationOutboxService
import app.spammy.hof.automation.service.AutomationDirectResponseStore
import app.spammy.hof.automation.entity.TypedAutomationActionStatus
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.security.CurrentAccountId
import app.spammy.hof.common.time.TimeProvider
import jakarta.persistence.EntityManager
import java.time.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class AutomationConvergenceStatusResponse(
    val battleGate: AutomationBattleGateStatusResponse?,
    val items: List<AutomationConvergenceItemResponse>,
    val localResults: List<AutomationLocalResultResponse> = emptyList(),
)

data class AutomationLocalResultResponse(
    val actionId: Long,
    val entryId: Long?,
    val entryDisplayName: String?,
    val actionKind: String,
    val status: TypedAutomationActionStatus,
    val remoteResult: ActionConvergenceResult?,
    val evidenceCaseId: String?,
    val retryAttempt: Int,
    val nextAttemptAt: Instant?,
    val reasonCode: String,
    val reasonMessage: String,
    val impactScope: String,
    val releaseCondition: String,
    val canAllowFreshDecision: Boolean,
)

data class AutomationBattleGateStatusResponse(
    val challengeId: Long?,
    val reason: String,
    val openedAt: Instant,
    val impactScope: String = "모든 전투 자동화",
    val releaseCondition: String = "캡차 완료 후 최신 상태에서 다시 판단",
)

data class AutomationConvergenceItemResponse(
    val attemptId: Long,
    val entryId: Long?,
    val actionKind: AutomationActionKind,
    val scopeKind: AutomationIsolationScopeKind,
    val scopeKey: String,
    val result: ActionConvergenceResult,
    val successfulObservationCount: Int,
    val nextProbeAt: Instant?,
    val reasonCode: String?,
    val reasonMessage: String,
    val evidenceCaseId: String?,
    val impactScope: String,
    val releaseCondition: String,
    val canAllowFreshDecision: Boolean,
)

fun interface AutomationConvergenceStatusReader {
    fun read(accountId: Long): AutomationConvergenceStatusResponse
}

@Service
class JpaAutomationConvergenceStatusReader(
    private val entityManager: EntityManager,
    private val directResponses: AutomationDirectResponseStore,
) : AutomationConvergenceStatusReader {
    @Transactional(readOnly = true)
    override fun read(accountId: Long): AutomationConvergenceStatusResponse {
        val gate = entityManager.find(AccountBattleGateEntity::class.java, accountId)
            ?.takeIf { it.resolvedAt == null }
            ?.let {
                AutomationBattleGateStatusResponse(
                    challengeId = it.challengeId,
                    reason = it.reason,
                    openedAt = it.openedAt,
                )
            }
        val convergences = entityManager.createQuery(
            """
            select convergence from ActionConvergenceEntity convergence
            join fetch convergence.attempt attempt
            left join fetch attempt.entry
            where convergence.accountId = :accountId
              and (
                convergence.activeMarker = 1
                or (
                  convergence.result in :heldResults
                  and convergence.suppressionReleasedAt is null
                )
              )
            order by convergence.updatedAt desc, convergence.id desc
            """.trimIndent(),
            ActionConvergenceEntity::class.java,
        ).setParameter("accountId", accountId)
            .setParameter(
                "heldResults",
                setOf(ActionConvergenceResult.HELD, ActionConvergenceResult.RESULT_UNOBSERVED),
            )
            .resultList
        val pending = directResponses.pendingResults(accountId)
        val remoteResults = if (pending.isEmpty()) emptyMap() else entityManager.createQuery(
            """select convergence from ActionConvergenceEntity convergence
                join fetch convergence.attempt attempt
                where convergence.accountId = :accountId and attempt.executionIdentity in :identities""".trimIndent(),
            ActionConvergenceEntity::class.java,
        ).setParameter("accountId", accountId)
            .setParameter("identities", pending.map { it.executionIdentity }).resultList
            .associate { it.attempt.executionIdentity to it.result }
        return AutomationConvergenceStatusResponse(
            battleGate = gate,
            items = convergences.map { convergence ->
                val result = convergence.result
                val policyUnavailable = result in setOf(ActionConvergenceResult.HELD, ActionConvergenceResult.RESULT_UNOBSERVED) &&
                    !ProductionActionEvidenceInterpreter.supportsVersion(convergence.attempt.policyVersion)
                AutomationConvergenceItemResponse(
                    attemptId = requireNotNull(convergence.attempt.id),
                    entryId = convergence.attempt.entry?.id,
                    actionKind = convergence.attempt.actionKind,
                    scopeKind = convergence.scopeKind,
                    scopeKey = convergence.scopeKey,
                    result = result,
                    successfulObservationCount = convergence.successfulObservationCount,
                    nextProbeAt = convergence.nextProbeAt,
                    reasonCode = convergence.reasonCode,
                    reasonMessage = if (policyUnavailable) ProductionActionEvidenceInterpreter.UNSUPPORTED_POLICY_REASON.reasonMessage(result)
                        else convergence.reasonCode.reasonMessage(result),
                    evidenceCaseId = convergence.evidenceCaseId,
                    impactScope = convergence.scopeKind.impactLabel(convergence.scopeKey),
                    releaseCondition = if (policyUnavailable) "사용자가 새 행동 판단을 허용하면 보류 해제" else result.releaseCondition(),
                    canAllowFreshDecision = result in setOf(
                        ActionConvergenceResult.HELD,
                        ActionConvergenceResult.RESULT_UNOBSERVED,
                    ),
                )
            },
            localResults = pending.map { result ->
                val held = result.status == TypedAutomationActionStatus.RESULT_HELD
                AutomationLocalResultResponse(
                    actionId = result.actionId, entryId = result.entryId, entryDisplayName = result.entryDisplayName,
                    actionKind = result.actionKind, status = result.status,
                    remoteResult = remoteResults[result.executionIdentity], retryAttempt = result.retryAttempt,
                    evidenceCaseId = result.evidenceCaseId,
                    nextAttemptAt = result.nextAttemptAt,
                    reasonCode = if (held) "LOCAL_RESULT_INTEGRITY_FAILED" else "LOCAL_RESULT_RETRY",
                    reasonMessage = if (held) "보존한 응답의 후처리를 안전하게 이어갈 수 없어 이 범위의 새 행동을 보류했습니다."
                        else "원래 응답을 보존한 채 후처리를 다시 시도하고 있습니다. 다른 자동화는 계속 실행합니다.",
                    impactScope = result.scope?.let { it.kind.impactLabel(it.key) }
                        ?: (result.entryDisplayName ?: "자동화 항목 ${result.entryId ?: "미상"}"),
                    releaseCondition = if (held) "사용자가 새 행동 판단을 허용하면 최신 상태에서 다시 판단"
                        else "원래 응답의 후처리가 완료되면 자동으로 해제",
                    canAllowFreshDecision = held,
                )
            },
        )
    }

    private fun AutomationIsolationScopeKind.impactLabel(key: String): String = when (this) {
        AutomationIsolationScopeKind.QUEST_TARGET -> "퀘스트 대상 $key"
        AutomationIsolationScopeKind.HOME_TARGET -> "자택 퀘스트 대상 $key"
        AutomationIsolationScopeKind.BATTLE_COOLDOWN_SCOPE -> "공유 전투 쿨타임 $key"
        AutomationIsolationScopeKind.UNION_ENTRY -> "유니온 자동화 $key"
        AutomationIsolationScopeKind.FISHING_ENTRY -> "낚시 자동화 $key"
        AutomationIsolationScopeKind.RAID_ENTRY -> "레이드 사이클 $key"
    }

    private fun ActionConvergenceResult.releaseCondition(): String = when (this) {
        ActionConvergenceResult.PENDING -> "최대 5회 또는 2분까지 읽기 전용으로 재확인"
        ActionConvergenceResult.HELD -> "상태 변경 또는 사용자의 새 행동 판단 허용"
        ActionConvergenceResult.RESULT_UNOBSERVED -> "상태 변경 또는 사용자의 새 행동 판단 허용"
        else -> "종료됨"
    }

    private fun String?.reasonMessage(result: ActionConvergenceResult): String = when (this) {
        ProductionActionEvidenceInterpreter.UNSUPPORTED_POLICY_REASON ->
            "저장된 행동의 판정 규칙을 사용할 수 없어 이 범위의 자동 실행을 보류했습니다."
        "HOME_ACTION_ID_MISSING" -> "자택 퀘스트 실행 식별자를 읽지 못해 최신 상태를 다시 확인하고 있습니다."
        "FISHING_BATTLE_TARGET_MISSING" -> "낚시를 막은 전투 대상 식별자를 읽지 못해 최신 상태를 다시 확인하고 있습니다."
        "OBSERVATION_INCOMPLETE" -> "최신 권위 상태가 완전하지 않아 아직 결과를 확정하지 못했습니다."
        "OBSERVATION_NETWORK_FAILURE" -> "상태 확인 중 연결 오류가 발생했으며 이 시도는 관측 횟수에 포함하지 않습니다."
        "AUTHORITATIVE_STATE_UNCHANGED" -> "최신 권위 상태가 이전과 같아 적용 여부를 계속 확인하고 있습니다."
        "PENDING_BUDGET_EXHAUSTED" -> "최대 5회 또는 2분의 자동 관측 예산 안에 결과를 확정하지 못했습니다."
        "ORPHAN_RESULT_RECONCILED" -> "이전 실행의 누락된 결과 상태를 복구해 다시 확인하고 있습니다."
        "RESULT_UNOBSERVED" -> "행동 결과를 권위 있게 식별하지 못해 자동 재제출을 막았습니다."
        "RESULT_UNOBSERVED_FRESH_DECISION" ->
            "이전 행동 결과는 귀속하지 않고 최신 퀘스트 진행도에서 새 행동을 판단했습니다."
        "BATTLE_GATE_REQUIRED" -> "전투에 캡차 확인이 필요해 전투 범위만 보류했습니다."
        "AUTHORITATIVE_STATE_ADVANCED" -> "외부 상태가 이미 바뀌어 저장된 행동을 새로 제출하지 않았습니다."
        "DIRECT_RESPONSE_APPLIED" -> "행동별 성공 조건을 만족하는 결과를 확인했습니다."
        "DIRECT_RESPONSE_REJECTED" -> "행동이 적용되지 않았다는 명시적 결과를 확인했습니다."
        else -> when (result) {
            ActionConvergenceResult.PENDING -> "중복 실행 없이 최신 권위 상태를 다시 확인하고 있습니다."
            ActionConvergenceResult.HELD -> "자동 관측 예산이 끝나 이 범위만 보류했습니다."
            ActionConvergenceResult.RESULT_UNOBSERVED -> "결과를 식별하지 못해 자동 재제출을 막았습니다."
            else -> "행동 결과 확인이 종료되었습니다."
        }
    }
}

@Service
class AutomationConvergenceCommandService(
    private val module: AutomationActionConvergenceModule,
    private val statusReader: AutomationConvergenceStatusReader,
    private val outbox: AutomationOutboxService,
    private val timeProvider: TimeProvider,
    private val directResponses: AutomationDirectResponseStore,
) {
    @Transactional
    fun allowFreshDecision(accountId: Long, attemptId: Long): AutomationConvergenceStatusResponse {
        if (!module.allowFreshDecision(accountId, attemptId, timeProvider.now())) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "새 판단을 허용할 보류 항목을 찾지 못했습니다.")
        }
        outbox.enqueue(accountId, "TYPED_CONVERGENCE_USER_ALLOWED")
        return statusReader.read(accountId)
    }

    @Transactional
    fun allowLocalFreshDecision(accountId: Long, actionId: Long): AutomationConvergenceStatusResponse {
        val identity = directResponses.allowFreshDecision(accountId, actionId)
            ?: throw ApiException(ErrorCode.INVALID_REQUEST, "새 판단을 허용할 후처리 보류를 찾지 못했습니다.")
        module.allowLocalResultFreshDecision(accountId, identity, timeProvider.now())
        outbox.enqueue(accountId, "TYPED_LOCAL_RESULT_USER_ALLOWED")
        return statusReader.read(accountId)
    }
}

@RestController
@RequestMapping("/api/automation/unified/convergence")
class AutomationConvergenceController(
    private val statusReader: AutomationConvergenceStatusReader,
    private val commandService: AutomationConvergenceCommandService,
) {
    /** 원격 행동을 실행하지 않는 순수 상태 새로고침이다. */
    @GetMapping
    fun get(@CurrentAccountId accountId: Long): AutomationConvergenceStatusResponse =
        statusReader.read(accountId)

    /** 저장 요청을 재생하지 않고 지정 보류만 닫은 뒤 fresh selection을 깨운다. */
    @PostMapping("/{attemptId}/allow-fresh-decision")
    fun allowFreshDecision(
        @CurrentAccountId accountId: Long,
        @PathVariable attemptId: Long,
    ): AutomationConvergenceStatusResponse = commandService.allowFreshDecision(accountId, attemptId)

    @PostMapping("/local-results/{actionId}/allow-fresh-decision")
    fun allowLocalFreshDecision(
        @CurrentAccountId accountId: Long,
        @PathVariable actionId: Long,
    ): AutomationConvergenceStatusResponse = commandService.allowLocalFreshDecision(accountId, actionId)
}
