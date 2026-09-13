package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.entity.HofCookieEntity
import app.spammy.hof.auth.service.AccountExecutionAuthorizationReader
import app.spammy.hof.automation.convergence.AutomationConvergenceMode
import app.spammy.hof.automation.convergence.AutomationConvergenceProperties
import app.spammy.hof.automation.entity.*
import app.spammy.hof.automation.history.AutomationDecisionJournal
import app.spammy.hof.automation.history.AutomationHistoryEventKind
import app.spammy.hof.automation.history.AutomationHistoryQuery
import app.spammy.hof.automation.outbox.AutomationOutboxPublisher
import app.spammy.hof.automation.outbox.AutomationOutboxQueryRepository
import app.spammy.hof.automation.port.AutomationWakeupPort
import app.spammy.hof.external.client.HofGateway
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.status.entity.HofStatusSnapshotEntity
import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.home.model.HomeMode
import app.spammy.hof.town.home.parser.HomePageParser
import app.spammy.hof.character.transfer.CharacterTransferFixture
import app.spammy.hof.character.transfer.CharacterTransferService
import app.spammy.hof.character.transfer.CharacterTransferSelection
import app.spammy.hof.character.transfer.CharacterTransferRequest
import app.spammy.hof.character.transfer.CharacterTransferOutcome
import app.spammy.hof.character.transfer.CharacterSavedPatternMapping
import app.spammy.hof.character.dto.CharacterTransferExecuteRequest
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.pattern.CharacterPatternSetting
import app.spammy.hof.character.pattern.CharacterPatternRowValue
import app.spammy.hof.character.service.CharacterOperationJobService
import app.spammy.hof.character.service.CharacterSnapshotSynchronizer
import app.spammy.hof.character.service.CharacterSnapshotArchiveWriter
import app.spammy.hof.external.parser.CharacterDetailParser
import jakarta.persistence.EntityManager
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@SpringBootTest(properties = ["hof.automation-convergence.mode=LEGACY"])
class LegacyAutomationContinuityTest : AutomationModeContinuityTest() {
    override val mode = AutomationConvergenceMode.LEGACY
}

@SpringBootTest(properties = ["hof.automation-convergence.mode=SHADOW"])
class ShadowAutomationContinuityTest : AutomationModeContinuityTest() {
    override val mode = AutomationConvergenceMode.SHADOW

    @Test
    fun `미지원 SHADOW 복원 뒤 같은 자택의 새 지원 행동과 후속 판단도 비교한다`() =
        storedAttemptContextContinuity(unsupportedPolicy = true, unsubmittedRetry = true,
            typedPolicyOnly = true, supportedSameScopeFollowup = true)
}

@SpringBootTest(properties = ["hof.automation-convergence.mode=ACTIVE"])
class ActiveAutomationContinuityTest : AutomationModeContinuityTest() {
    override val mode = AutomationConvergenceMode.ACTIVE

    @Test
    fun `영속 시도의 기준으로 재확인하고 독립 자택과 후속 깨우기를 실행한다`() = storedAttemptContextContinuity()

    @Test
    fun `외부 자택 진전을 과거 행동 성공으로 귀속하지 않고 독립 자택을 실행한다`() =
        storedAttemptContextContinuity(externalAdvance = true)

    @Test
    fun `미지원 정책의 저장 시도만 보류하고 독립 자택과 다음 판단을 실행한다`() =
        storedAttemptContextContinuity(unsupportedPolicy = true)

    @Test
    fun `미지원 미전송 체크포인트를 복원해 재제출 없이 독립 자택과 후속 판단을 실행한다`() =
        storedAttemptContextContinuity(unsupportedPolicy = true, unsubmittedRetry = true)

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `수렴 시도가 없는 typed 정책 문맥도 미지원이면 보존하고 독립 판단을 실행한다`(unsubmitted: Boolean) =
        storedAttemptContextContinuity(unsupportedPolicy = true, unsubmittedRetry = unsubmitted, typedPolicyOnly = true)

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `미지원 최초 PREPARED도 재판단 전에 원래 범위에 보류한다`(typedPolicyOnly: Boolean) =
        storedAttemptContextContinuity(unsupportedPolicy = true, typedPolicyOnly = typedPolicyOnly, preparedWithoutRetry = true)

    @Test
    fun `정책을 기록하지 않은 제출은 현재 정책으로 추정하지 않고 해당 범위만 보류한다`() =
        storedAttemptContextContinuity(typedPolicyOnly = true, unrecordedPolicy = true)

    @Test
    fun `제출 뒤 결과 기록 전 재시작은 typed 제출 시각으로 예산을 닫고 독립 판단을 실행한다`() =
        storedAttemptContextContinuity(missingSubmissionFacts = true)
}

/** Each supported mode is assembled from its real startup property, without mocking rollout. */
@ActiveProfiles("test")
@Import(AutomationRecoveryIntegrationTest.Config::class)
abstract class AutomationModeContinuityTest {
    protected abstract val mode: AutomationConvergenceMode
    @Autowired private lateinit var properties: AutomationConvergenceProperties
    @Autowired private lateinit var convergenceStore: app.spammy.hof.automation.convergence.ConvergenceStore
    @Autowired private lateinit var actionCodec: StoredTypedAutomationActionCodec
    @Autowired private lateinit var objectMapper: tools.jackson.databind.ObjectMapper
    @Autowired private lateinit var entityManager: EntityManager
    @Autowired private lateinit var transactions: PlatformTransactionManager
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var clock: AutomationRecoveryIntegrationTest.RecoveryClock
    @Autowired private lateinit var transport: AutomationRecoveryIntegrationTest.ConsumerReplayTransport
    @Autowired private lateinit var publisher: AutomationOutboxPublisher
    @Autowired private lateinit var outbox: AutomationOutboxQueryRepository
    @Autowired private lateinit var wakeups: AutomationWakeupPort
    @Autowired private lateinit var journal: AutomationDecisionJournal
    @MockitoBean private lateinit var gateway: HofGateway
    @MockitoBean private lateinit var preflight: AutomationDailyPreflight
    @MockitoBean private lateinit var authorization: AccountExecutionAuthorizationReader
    @Autowired private lateinit var characterGate: app.spammy.hof.character.command.CharacterAutomationGate
    @Autowired private lateinit var characterRecovery: app.spammy.hof.character.service.CharacterDeepSyncRecovery
    @Autowired private lateinit var characterTransfers: CharacterTransferService
    @Autowired private lateinit var characterJobs: CharacterOperationJobService
    @Autowired private lateinit var snapshots: CharacterSnapshotSynchronizer
    @Autowired private lateinit var archive: CharacterSnapshotArchiveWriter
    @Autowired private lateinit var characterParser: CharacterDetailParser
    @Autowired private lateinit var automation: UnifiedAutomationService
    @MockitoBean(name = "characterSyncTaskExecutor") private lateinit var characterTasks: org.springframework.core.task.TaskExecutor

    private fun independentHomePage(accepted: Boolean): String {
        val heading = if (accepted) "진행중인 작업 목록" else "수락 가능한 작업 목록"
        val action = if (accepted) "-" else "<a href='?menu=housing&amp;action=get&amp;no=A'>수락</a>"
        return """<div id='menu2'>Funds : $ 1 Time : 100/100</div><h4>$heading</h4><table>
            <tr><td>[A] 독립 자택</td><td>미션 0/1</td><td>-</td><td>-</td><td>$action</td></tr></table>"""
    }

    private fun nextWake(accountId: Long) {
        clock.current = maxOf(clock.now(), assertNotNull(jdbc.queryForObject(
            "select min(available_at) from automation_outbox where account_id = ? and published_at is null",
            java.time.OffsetDateTime::class.java, accountId)).toInstant())
        publisher.publishBatch()
    }

    @Test
    fun `미지원 저장 정책의 실행 차단은 ACTIVE에만 적용하고 SHADOW는 비교 근거를 남긴다`() =
        storedAttemptContextContinuity(unsupportedPolicy = true, unsubmittedRetry = true, typedPolicyOnly = true)

    protected fun storedAttemptContextContinuity(
        externalAdvance: Boolean = false,
        unsupportedPolicy: Boolean = false,
        unsubmittedRetry: Boolean = false,
        typedPolicyOnly: Boolean = false,
        preparedWithoutRetry: Boolean = false,
        unrecordedPolicy: Boolean = false,
        missingSubmissionFacts: Boolean = false,
        supportedSameScopeFollowup: Boolean = false,
    ) {
        val unsubmitted = unsubmittedRetry || preparedWithoutRetry
        val legacyProduction = mode != AutomationConvergenceMode.ACTIVE
        clock.current = Instant.parse("2026-09-10T08:00:00Z")
        transport.delivered.clear()
        val requests = mutableListOf<HofRequest>()
        val url = "https://hof.zerosic.com/index.php?menu=housing"
        var accepted = false
        var externallyAccepted = false
        fun page() = independentHomePage(externallyAccepted) + independentHomePage(accepted)
            .replace("[A] 독립 자택", "[B] 독립 자택").replace("no=A", "no=B")
        val quests = HomePageParser().parse(HomeMode.HOME, page(), url, HofFormParser().parse(page(), url)).quests
        var baseline = "b".repeat(64)
        val submittedAt = clock.now().minusSeconds(if (missingSubmissionFacts) 121 else 10)
        val executionIdentity = "stored-policy-${UUID.randomUUID()}"
        val (accountId, existingAttemptId) = requireNotNull(TransactionTemplate(transactions).execute {
            val account = HofAccountEntity(loginId = "stored-policy-${UUID.randomUUID()}", encryptedPassword = "test", createdAt = clock.now())
            entityManager.persist(account)
            val entry = AutomationEntryEntity(account = account, type = AutomationType.HOME_QUEST, priority = 0,
                enabled = true, createdAt = clock.now(), updatedAt = clock.now())
            entityManager.persist(entry)
            quests.forEachIndexed { index, quest -> entityManager.persist(HomeQuestAutomationSelectionEntity(
                entry = entry, questId = quest.id, questName = quest.name, enabled = true, sourceOrder = index)) }
            entityManager.persist(HofCookieEntity(account = account, name = "PHPSESSID", value = "fixture", updatedAt = clock.now()))
            entityManager.persist(HofStatusSnapshotEntity(account = account, playerName = "테스트", funds = 1,
                timeCurrent = 100, timeMax = 100, work = "", auction = "", observedAt = clock.now()))
            entityManager.persist(TypedAutomationRuntimeStateEntity(accountId = account.id, account = account,
                lifecycleStatus = TypedAutomationLifecycle.RUNNING, createdAt = clock.now(), updatedAt = clock.now()))
            val draft = StoredTypedAutomationAction(entry.id, executionIdentity,
                StoredTypedActionPayload.HomeQuest(quests.first().id, requireNotNull(quests.first().actionId), HomeQuestAutomationActionType.ACCEPT),
                settingsRevision = entry.settingsRevision)
            val selected = app.spammy.hof.automation.convergence.StoredActionConvergenceSelectionFactory()
                .create(draft).let {
                    if (missingSubmissionFacts) baseline = it.baselineFingerprint
                    it.copy(
                    baselineFingerprint = baseline,
                    policyVersion = if (unsupportedPolicy) "unsupported-fixture-version" else it.policyVersion,
                ) }
            val stored = if ((typedPolicyOnly && !unrecordedPolicy) || missingSubmissionFacts) draft.copy(policyContext = StoredActionPolicyContext(
                selected.policyVersion, selected.actionKind, selected.scope, selected.baselineFingerprint,
            )) else draft
            val encoded = actionCodec.encode(stored)
            entityManager.persist(TypedAutomationActionRunEntity(account = account, entry = entry,
                executionIdentity = stored.executionIdentity, actionKind = stored.payload.kind(),
                payloadJson = encoded.json, actionFingerprint = encoded.fingerprint,
                status = when {
                    missingSubmissionFacts -> TypedAutomationActionStatus.SUBMITTING
                    unsubmitted -> TypedAutomationActionStatus.PREPARED
                    typedPolicyOnly -> TypedAutomationActionStatus.RECONCILING
                    else -> TypedAutomationActionStatus.AMBIGUOUS
                },
                leaseToken = "finished-fixture", retryAttempt = if (unsubmittedRetry) 1 else 0,
                nextAttemptAt = if (unsubmittedRetry) clock.now() else null,
                createdAt = submittedAt, submittedAt = if (unsubmitted) null else submittedAt,
                finishedAt = if (unsubmitted || typedPolicyOnly || missingSubmissionFacts) null else clock.now().minusSeconds(1),
                updatedAt = clock.now()))
            val attempt = if (typedPolicyOnly) null else convergenceStore.createOrGet(account.id, selected, clock.now().minusSeconds(10))
            if (attempt != null) {
                attempt.submittedAt = if (unsubmitted || missingSubmissionFacts) null else submittedAt
                attempt.firstPendingAt = attempt.submittedAt
                attempt.nextProbeAt = if (unsubmitted) null else clock.now()
                if (unsubmittedRetry) {
                    attempt.result = app.spammy.hof.automation.convergence.ActionConvergenceResult.NOT_APPLIED
                    attempt.reasonCode = "SUBMISSION_NOT_ATTEMPTED"
                    attempt.finishedAt = clock.now().minusSeconds(1)
                }
                convergenceStore.save(attempt)
            }
            entityManager.flush()
            entityManager.clear()
            account.id to attempt?.attemptId
        })
        try {
            Mockito.`when`(preflight.ensureReady(accountId)).thenReturn(AutomationDailyPreflight.Result.Ready)
            Mockito.`when`(authorization.isExecutionAllowed(accountId)).thenReturn(true)
            Mockito.doAnswer { invocation ->
                val request = invocation.getArgument<HofRequest>(1)
                requests += request
                if (request.formFields["action"] == "get" && request.formFields["no"] == "B") accepted = true
                if (legacyProduction && request.formFields["action"] == "get" && request.formFields["no"] == "A") externallyAccepted = true
                HofHttpResponse(200, url, page(), emptyMap())
            }.`when`(gateway).execute(Mockito.eq(accountId), Mockito.any<HofRequest>()
                ?: HofRequest(HofHttpMethod.GET, "https://example.test"), Mockito.anyMap())

            wakeups.wake(accountId, "STORED_POLICY_CONTEXT")
            publisher.publishBatch()
            val attemptId = existingAttemptId ?: convergenceStore.get(accountId, executionIdentity)?.attemptId
            if (legacyProduction) {
                assertEquals(listOf("SUCCEEDED"), jdbc.queryForList(
                    "select status from typed_automation_action_runs where account_id = ?", String::class.java, accountId),
                    jdbc.queryForList("select status, last_error from typed_automation_action_runs where account_id = ?", accountId).toString() +
                        requests.map { it.method to it.formFields })
                assertEquals(if (mode == AutomationConvergenceMode.SHADOW) listOf("HELD") else emptyList(), jdbc.queryForList(
                    "select new_result from automation_convergence_shadow_evaluations where account_id = ? and policy_version = 'unsupported-fixture-version' and legacy_decision = 'APPLIED' and evidence_kind = 'PolicyUnavailable'",
                    String::class.java, accountId))
            } else {
            assertNotNull(attemptId)
            if (missingSubmissionFacts) {
                val held = assertNotNull(convergenceStore.get(attemptId))
                assertEquals(app.spammy.hof.automation.convergence.ActionConvergenceResult.HELD, held.result)
                assertEquals("PENDING_BUDGET_EXHAUSTED", held.reasonCode)
                assertEquals(submittedAt, held.submittedAt)
                assertEquals(submittedAt, held.firstPendingAt)
                assertEquals(0, held.successfulObservationCount)
                assertTrue(requests.isEmpty(), "이미 끝난 관측 예산으로 HOF 요청을 새로 시작하면 안 된다.")
            } else if (unsupportedPolicy || unrecordedPolicy) {
                val expectedPolicy = if (unrecordedPolicy) "legacy-unrecorded" else "unsupported-fixture-version"
                val held = assertNotNull(convergenceStore.get(attemptId))
                assertEquals(app.spammy.hof.automation.convergence.ActionConvergenceResult.HELD, held.result)
                assertEquals("POLICY_VERSION_UNSUPPORTED", held.reasonCode)
                assertEquals(expectedPolicy, held.selection.policyVersion)
                assertEquals(if (unsubmitted) null else Instant.parse("2026-09-10T07:59:50Z"), held.submittedAt)
                assertEquals(0, held.successfulObservationCount)
                val evidence = jdbc.queryForMap("select evidence_source, observation_completeness, observation_freshness, policy_version from automation_evidence_cases where attempt_id = ?", attemptId)
                assertEquals("POLICY_UNAVAILABLE", evidence["EVIDENCE_SOURCE"] ?: evidence["evidence_source"])
                assertEquals(expectedPolicy, evidence["POLICY_VERSION"] ?: evidence["policy_version"])
                assertEquals(null, evidence["OBSERVATION_COMPLETENESS"] ?: evidence["observation_completeness"])
                assertEquals(null, evidence["OBSERVATION_FRESHNESS"] ?: evidence["observation_freshness"])
                if (unrecordedPolicy) assertTrue(requests.isEmpty(), "원래 정책을 모르는 제출은 HOF 재관측도 판정 전에 하지 않는다.")
            } else {
                assertEquals(listOf(baseline), jdbc.queryForList(
                    "select state_fingerprint from automation_evidence_cases where attempt_id = ? and reason_code = 'AUTHORITATIVE_STATE_UNCHANGED'",
                    String::class.java, attemptId))
            }
            if (!unrecordedPolicy) assertEquals(baseline, convergenceStore.get(attemptId)?.selection?.baselineFingerprint)
            if (unsubmitted) {
                assertEquals(listOf("FAILED"), jdbc.queryForList(
                    "select status from typed_automation_action_runs where account_id = ?", String::class.java, accountId))
                assertEquals(0, jdbc.queryForObject(
                    "select count(*) from typed_automation_action_runs where account_id = ? and submitted_at is not null",
                    Int::class.java, accountId))
            }
            }
            externallyAccepted = externallyAccepted || externalAdvance
            if (supportedSameScopeFollowup) externallyAccepted = false
            if (externalAdvance) clock.current = assertNotNull(convergenceStore.get(assertNotNull(attemptId))?.nextProbeAt)
            val initialCycleIds = journal.page(accountId, AutomationHistoryQuery()).cycles.map { it.id }.toSet()
            repeat(if (supportedSameScopeFollowup) 4 else 3) { nextWake(accountId) }
            if (externalAdvance) {
                assertEquals(app.spammy.hof.automation.convergence.ActionConvergenceResult.SUPERSEDED,
                    convergenceStore.get(assertNotNull(attemptId))?.result)
            }
            assertTrue(accepted, requests.map { it.method to it.formFields }.toString())
            assertEquals(if (supportedSameScopeFollowup) 2 else if (legacyProduction) 1 else 0,
                requests.count { it.formFields["action"] == "get" && it.formFields["no"] == "A" })
            assertEquals(1, requests.count { it.formFields["action"] == "get" && it.formFields["no"] == "B" })
            if (supportedSameScopeFollowup) {
                assertEquals(listOf("APPLIED"), jdbc.queryForList("""
                    select new_result from automation_convergence_shadow_evaluations
                    where account_id = ? and policy_version = ? and scope_key_hash in (
                        select scope_key_hash from automation_convergence_shadow_evaluations
                        where account_id = ? and policy_version = 'unsupported-fixture-version'
                    )
                """.trimIndent(), String::class.java, accountId,
                    app.spammy.hof.automation.convergence.ProductionActionEvidenceInterpreter.VERSION_1, accountId))
            }
            val followingCycles = journal.page(accountId, AutomationHistoryQuery()).cycles.filter { it.id !in initialCycleIds }
            assertTrue(followingCycles.size >= 2, "최초 복원·처리 이후 새 판단이 두 번 이상 실행되어야 한다: $followingCycles")
            assertTrue(transport.delivered.all { outbox.consumed(it) })
            assertEquals(0, jdbc.queryForObject("select count(*) from automation_work_sessions where account_id = ? and running_slot is not null", Int::class.java, accountId))
            assertEquals(0, jdbc.queryForObject(
                "select count(*) from typed_automation_runtime_states where account_id = ? and (lease_token is not null or lease_until is not null)",
                Int::class.java, accountId))
            assertEquals(0, jdbc.queryForObject("select count(*) from account_automation_leases where account_id = ?", Int::class.java, accountId))
        } finally {
            transport.delivered.forEach { jdbc.update("delete from automation_consumed_events where event_id = ?", it) }
            jdbc.update("delete from hof_accounts where id = ?", accountId)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `설정 가져오기의 완료 또는 미리보기 변경 후 영속 복귀 깨우기에서 낚시와 후속 판단을 이어간다`(previewChanged: Boolean) {
        assertEquals(mode, properties.mode)
        clock.current = Instant.parse("2026-09-09T00:00:00Z")
        transport.delivered.clear()
        val requests = mutableListOf<HofRequest>()
        val tasks = ArrayDeque<Runnable>()
        lateinit var source: CharacterEntity
        lateinit var target: CharacterEntity
        val accountId = TransactionTemplate(transactions).execute {
            val account = HofAccountEntity(loginId = "transfer-mode-${UUID.randomUUID()}", encryptedPassword = "test", createdAt = clock.now())
            entityManager.persist(account)
            entityManager.persist(AutomationEntryEntity(account = account, type = AutomationType.FISHING, priority = 0,
                enabled = true, createdAt = clock.now(), updatedAt = clock.now()))
            entityManager.persist(HofCookieEntity(account = account, name = "PHPSESSID", value = "test-session", updatedAt = clock.now()))
            entityManager.persist(TypedAutomationRuntimeStateEntity(accountId = account.id, account = account,
                lifecycleStatus = TypedAutomationLifecycle.RUNNING, createdAt = clock.now(), updatedAt = clock.now()))
            source = CharacterEntity(account = account, hofCharacterId = "transfer-source", name = "원본", job = "Knight", updatedAt = clock.now())
            target = CharacterEntity(account = account, hofCharacterId = "transfer-target", name = "대상", job = "Knight", updatedAt = clock.now())
            entityManager.persist(source)
            entityManager.persist(target)
            account.id
        }
        var current = CharacterTransferFixture.setting("0")
        val slots = mutableMapOf<String, CharacterPatternSetting?>("0" to null)
        val sourceCurrent = CharacterTransferFixture.setting("1")
        val sourceSaved = CharacterTransferFixture.setting("2")
        var fishingBattle = false
        fun fishingFixture(name: String) = requireNotNull(javaClass.getResource("/fixtures/town/fishing/$name.html")).readText()
        try {
            snapshots.writeParsed(accountId, source.hofCharacterId, characterParser.parsePage(source.hofCharacterId,
                CharacterTransferFixture.page(source.hofCharacterId, sourceCurrent, mapOf("0" to sourceSaved))))
            archive.savePatternSlot(source, "0", characterParser.parsePage(source.hofCharacterId,
                CharacterTransferFixture.page(source.hofCharacterId, sourceSaved, mapOf("0" to sourceSaved))))
            snapshots.writeParsed(accountId, target.hofCharacterId, characterParser.parsePage(target.hofCharacterId,
                CharacterTransferFixture.page(target.hofCharacterId, current, slots)))
            Mockito.doAnswer { tasks.addLast(it.getArgument(0)); null }
                .`when`(characterTasks).execute(Mockito.any(Runnable::class.java))
            Mockito.`when`(preflight.ensureReady(accountId)).thenReturn(AutomationDailyPreflight.Result.Ready)
            Mockito.`when`(authorization.isExecutionAllowed(accountId)).thenReturn(true)
            Mockito.doAnswer { invocation ->
                val request = invocation.getArgument<HofRequest>(1)
                requests += request
                val fields = request.formFields
                val body = if (request.url.contains("?char=")) {
                    check(request.url.substringAfter("char=") == target.hofCharacterId)
                    when {
                        "ChangePattern" in fields -> current = current.copy(rows = listOf(CharacterPatternRowValue(
                            fields.getValue("judge0"), fields.getValue("quantity0"), fields.getValue("skill0"))))
                        "ChangePosition" in fields -> current = current.copy(position = fields.getValue("position"), guard = fields.getValue("guard"))
                        "savepattern" in fields -> slots[fields.getValue("patternno")] = current
                    }
                    CharacterTransferFixture.page(target.hofCharacterId, current, slots)
                } else {
                    "<div id='menu2'>Funds : $ 1 Time : 100/100</div>" + when {
                        "FStart" in fields -> fishingFixture("waiting")
                        "FCatch" in fields -> {
                            fishingBattle = true
                            fishingFixture("caught").substringBefore("<form") + fishingFixture("monster")
                        }
                        request.url.contains("menu=fishing") -> fishingFixture("reset")
                        else -> """<div id='contents'><a href='?common=0001'>일반 맵</a>
                            ${if (fishingBattle) "<a href='?common=Fish03'>Fishing- 악어</a>" else ""}</div>
                            <div id='foot'><h5>Copy Right sanitized</h5><h6>H.O.F Korean Ver sanitized</h6><img src='zerohof.gif'></div>"""
                    }
                }
                HofHttpResponse(200, request.url, body, emptyMap())
            }.`when`(gateway).execute(Mockito.eq(accountId), Mockito.any<HofRequest>()
                ?: HofRequest(HofHttpMethod.GET, "https://example.test"), Mockito.anyMap())

            val transferRequest = CharacterTransferRequest(includeCurrentPattern = true,
                savedPatternMappings = listOf(CharacterSavedPatternMapping("0", "0")))
            val preview = characterTransfers.preview(accountId, CharacterTransferSelection(source.id, target.id, transferRequest))
            if (previewChanged) {
                snapshots.writeParsed(accountId, source.hofCharacterId, characterParser.parsePage(source.hofCharacterId,
                    CharacterTransferFixture.page(source.hofCharacterId, sourceSaved, mapOf("0" to sourceSaved))))
            }
            val started = characterJobs.startTransfer(accountId, CharacterTransferExecuteRequest(source.id, target.id,
                transferRequest, confirmationToken = preview.confirmationToken))
            requests.clear()
            wakeups.wake(accountId, "TRANSFER_PENDING")
            publisher.publishBatch()
            assertTrue(requests.isEmpty(), "가져오기 대기 중 새 자동화 행동을 실행하지 않는다.")
            tasks.removeFirst().run()
            assertEquals(if (previewChanged) CharacterTransferOutcome.PREVIEW_CHANGED else CharacterTransferOutcome.COMPLETED,
                characterJobs.find(accountId, started.id).transfer?.outcome)
            val expectedCurrent = if (previewChanged) CharacterTransferFixture.setting("0") else sourceCurrent
            assertEquals(expectedCurrent, current)
            assertEquals(if (previewChanged) null else sourceSaved, slots["0"])
            if (previewChanged) assertTrue(requests.none { it.url.contains("?char=") && it.method == HofHttpMethod.POST })
            assertEquals(TypedAutomationLifecycle.RUNNING, automation.getTyped(accountId).runtime.lifecycle)
            publisher.publishBatch()
            val firstCycles = journal.page(accountId, AutomationHistoryQuery()).cycles.map { it.id }.toSet()
            assertEquals(1, requests.count { "FStart" in it.formFields })
            assertEquals(1, requests.count { "FCatch" in it.formFields })
            clock.current = assertNotNull(jdbc.queryForObject(
                "select min(available_at) from automation_outbox where account_id = ? and published_at is null",
                java.time.OffsetDateTime::class.java, accountId)).toInstant()
            publisher.publishBatch()
            val cycles = journal.page(accountId, AutomationHistoryQuery()).cycles
            assertTrue(cycles.any { it.id !in firstCycles }, "복귀 행동 이후 새 판단을 실제 소비해야 한다.")
            assertEquals(1, requests.count { "FStart" in it.formFields })
            assertEquals(1, requests.count { "FCatch" in it.formFields })
            assertEquals(expectedCurrent, current)
            assertTrue(transport.delivered.all { outbox.consumed(it) })
        } finally {
            transport.delivered.forEach { jdbc.update("delete from automation_consumed_events where event_id = ?", it) }
            jdbc.update("delete from hof_accounts where id = ?", accountId)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["NONE", "START", "CATCH"])
    fun `각 모드의 낚시는 CATCH 뒤 숨은 전투를 확인하고 새 START를 반복하지 않는다`(lostResponse: String) {
        assertEquals(mode, properties.mode)
        clock.current = Instant.parse("2026-09-08T00:00:00Z")
        transport.delivered.clear()
        val requests = mutableListOf<HofRequest>()
        val accountId = TransactionTemplate(transactions).execute {
            val account = HofAccountEntity(loginId = "fishing-mode-${UUID.randomUUID()}", encryptedPassword = "test", createdAt = clock.now())
            entityManager.persist(account)
            entityManager.persist(AutomationEntryEntity(account = account, type = AutomationType.FISHING, priority = 0,
                enabled = true, createdAt = clock.now(), updatedAt = clock.now()))
            entityManager.persist(HofCookieEntity(account = account, name = "PHPSESSID", value = "test-session", updatedAt = clock.now()))
            entityManager.persist(HofStatusSnapshotEntity(account = account, playerName = "테스트", funds = 1,
                timeCurrent = 100, timeMax = 100, work = "", auction = "", observedAt = clock.now()))
            entityManager.persist(TypedAutomationRuntimeStateEntity(accountId = account.id, account = account,
                lifecycleStatus = TypedAutomationLifecycle.RUNNING, createdAt = clock.now(), updatedAt = clock.now()))
            account.id
        }
        fun fixture(name: String) = requireNotNull(javaClass.getResource("/fixtures/town/fishing/$name.html")).readText()
        var battle = false
        var started = false
        try {
            Mockito.`when`(preflight.ensureReady(accountId)).thenReturn(AutomationDailyPreflight.Result.Ready)
            Mockito.`when`(authorization.isExecutionAllowed(accountId)).thenReturn(true)
            Mockito.doAnswer { invocation ->
                val request = invocation.arguments[1] as HofRequest
                requests += request
                val body = when {
                    "FStart" in request.formFields -> {
                        started = true
                        if (lostResponse == "START") throw IllegalStateException(
                            "fixture response lost", java.io.IOException("fixture response lost"))
                        fixture("waiting")
                    }
                    "FCatch" in request.formFields -> {
                        battle = true
                        if (lostResponse == "CATCH") throw IllegalStateException(
                            "fixture response lost", java.io.IOException("fixture response lost"))
                        fixture("caught").substringBefore("<form") + fixture("monster")
                    }
                    request.url.contains("menu=fishing") -> fixture(if (started && !battle) "waiting" else "reset")
                    else -> """<div id='contents'><a href='?common=0001'>일반 맵</a>
                        ${if (battle) "<a href='?common=Fish03'>Fishing- 악어</a>" else ""}</div>
                        <div id='foot'><h5>Copy Right sanitized</h5><h6>H.O.F Korean Ver sanitized</h6><img src='zerohof.gif'></div>"""
                }
                HofHttpResponse(200, request.url, "<div id='menu2'>Funds : $ 1 Time : 100/100</div>" + body, emptyMap())
            }.`when`(gateway).execute(Mockito.eq(accountId), Mockito.any<HofRequest>()
                ?: HofRequest(HofHttpMethod.GET, "https://example.test"), Mockito.anyMap())
            wakeups.wake(accountId, "FISHING_MODE_CONTINUITY")
            publisher.publishBatch()
            if (lostResponse == "NONE") assertEquals(
                listOf(HofHttpMethod.GET, HofHttpMethod.GET, HofHttpMethod.POST, HofHttpMethod.POST), requests.map { it.method })
            if (lostResponse != "NONE") clock.current = clock.now().plusSeconds(31)
            repeat(if (lostResponse == "NONE") 2 else 6) { nextWake(accountId) }
            assertEquals(1, requests.count { "FStart" in it.formFields })
            assertEquals(1, requests.count { "FCatch" in it.formFields },
                "requests=${requests.map { it.url to it.formFields }}; history=${journal.page(accountId, AutomationHistoryQuery())}")
            assertEquals(2, requests.count { it.method == HofHttpMethod.POST })
            val savedPolicies = jdbc.queryForList(
                "select payload_json from typed_automation_action_runs where account_id = ? order by id",
                String::class.java, accountId,
            ).map { objectMapper.readTree(it).path("policyContext") }
            assertEquals(listOf("FISHING_START", "FISHING_CATCH"), savedPolicies.map { it.path("actionKind").asText() })
            savedPolicies.forEach { policy ->
                assertEquals("automation-action-convergence-v1", policy.path("policyVersion").asText())
                assertEquals("FISHING_ENTRY", policy.path("scope").path("kind").asText())
                assertEquals(64, policy.path("baselineFingerprint").asText().length)
            }
            val history = journal.page(accountId, AutomationHistoryQuery())
            assertTrue(history.cycles.any { cycle -> cycle.events.any { it.reasonCode == "FISHING_PRESET_MISSING" } }, history.toString())
            if (lostResponse != "NONE") {
                val lostEvents = history.cycles.flatMap { it.events }.filter { it.actionKind == lostResponse }
                assertTrue(lostEvents.any { it.kind == AutomationHistoryEventKind.ACTION_STARTED }, lostEvents.toString())
                assertTrue(lostEvents.none { it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED }, lostEvents.toString())
                if (mode == AutomationConvergenceMode.ACTIVE) assertEquals(listOf("SUPERSEDED"), jdbc.queryForList(
                    "select c.result from automation_action_convergences c join automation_action_attempts a on a.id = c.attempt_id where a.account_id = ? and a.action_kind = ?",
                    String::class.java, accountId, "FISHING_$lostResponse"))
                if (mode == AutomationConvergenceMode.SHADOW) {
                    val results = jdbc.queryForList(
                        "select new_result from automation_convergence_shadow_evaluations where account_id = ? and action_kind = ?",
                        String::class.java, accountId, "FISHING_$lostResponse")
                    assertTrue("SUPERSEDED" in results && "APPLIED" !in results, results.toString())
                }
            }
            if (mode == AutomationConvergenceMode.SHADOW && lostResponse == "NONE") {
                assertEquals(listOf("FISHING_CATCH:APPLIED", "FISHING_START:APPLIED"), jdbc.queryForList(
                    "select action_kind || ':' || new_result from automation_convergence_shadow_evaluations where account_id = ? order by action_kind",
                    String::class.java, accountId))
            }
            assertEquals(0, jdbc.queryForObject("select count(*) from automation_work_sessions where account_id = ? and status = 'RUNNING'", Int::class.java, accountId))
            assertTrue(transport.delivered.all { outbox.consumed(it) })
        } finally {
            transport.delivered.forEach { jdbc.update("delete from automation_consumed_events where event_id = ?", it) }
            jdbc.update("delete from hof_accounts where id = ?", accountId)
        }
    }

    @ParameterizedTest
    @CsvSource("false,false,false", "true,false,false", "true,false,true", "true,true,false", "true,true,true")
    fun `레이드 시작의 같은 READY는 유한하게 확인하고 다른 항목과 다음 판단을 이어간다`(
        externalAdvance: Boolean,
        directAdvance: Boolean,
        genericNotice: Boolean,
    ) {
        assertEquals(mode, properties.mode)
        clock.current = Instant.parse("2026-09-10T01:00:00Z")
        transport.delivered.clear()
        val requests = mutableListOf<HofRequest>()
        val raidUrl = "https://hof.zerosic.com/index.php?menu=raidpub"
        val homeUrl = "https://hof.zerosic.com/index.php?menu=housing"
        val ready = requireNotNull(javaClass.getResource("/fixtures/town/raid/raidpub.html")).readText()
            .replace("Funds : $ 1,000", "Funds : $ 1,000 Time : 100/100")
            .replace("현재 상태는 신청 대기 (신청 가능까지 6분 58초)", "현재 상태는 신청 가능")
            .replace("현재 상태 : 모집 중", "현재 상태 : 출발 가능")
            .replace("name=\"register_goblin\" value=\"등록한다\"", "name=\"start_goblin\" value=\"전투를 시작한다\"")
        val noBattle = requireNotNull(javaClass.getResource("/fixtures/raid/raid-complete-absent.html")).readText()
        var homeAccepted = false
        var externallyStarted = false

        val homeQuest = HomePageParser().parse(HomeMode.HOME, independentHomePage(homeAccepted), homeUrl, HofFormParser().parse(independentHomePage(homeAccepted), homeUrl)).quests.single()
        val accountId = TransactionTemplate(transactions).execute {
            val account = HofAccountEntity(loginId = "raid-ready-mode-${UUID.randomUUID()}", encryptedPassword = "test", createdAt = clock.now())
            entityManager.persist(account)
            val raidEntry = AutomationEntryEntity(account = account, type = AutomationType.RAID, priority = 0,
                enabled = true, createdAt = clock.now(), updatedAt = clock.now())
            entityManager.persist(raidEntry)
            entityManager.persist(RaidAutomationTargetEntity(entry = raidEntry, raidId = "RaidGoblin", displayName = "고블린 전투 마차",
                presetMode = PresetSelectionMode.PRIMARY, executionOrder = 0))
            entityManager.persist(RaidAutomationCycleEntity(account = account, entry = raidEntry, raidId = "RaidGoblin",
                raidName = "고블린 전투 마차", status = RaidAutomationCycleStatus.REGISTERED_WAITING,
                lastObservedStatus = "출발 가능", startedAt = clock.now(), updatedAt = clock.now()))
            val homeEntry = AutomationEntryEntity(account = account, type = AutomationType.HOME_QUEST, priority = 1,
                enabled = true, createdAt = clock.now(), updatedAt = clock.now())
            entityManager.persist(homeEntry)
            entityManager.persist(HomeQuestAutomationSelectionEntity(entry = homeEntry, questId = homeQuest.id,
                questName = homeQuest.name, enabled = true, sourceOrder = 0))
            entityManager.persist(HofCookieEntity(account = account, name = "PHPSESSID", value = "fixture", updatedAt = clock.now()))
            entityManager.persist(HofStatusSnapshotEntity(account = account, playerName = "테스트", funds = 1,
                timeCurrent = 100, timeMax = 100, work = "", auction = "", observedAt = clock.now()))
            entityManager.persist(TypedAutomationRuntimeStateEntity(accountId = account.id, account = account,
                lifecycleStatus = TypedAutomationLifecycle.RUNNING, createdAt = clock.now(), updatedAt = clock.now()))
            account.id
        }
        try {
            Mockito.`when`(preflight.ensureReady(accountId)).thenReturn(AutomationDailyPreflight.Result.Ready)
            Mockito.`when`(authorization.isExecutionAllowed(accountId)).thenReturn(true)
            Mockito.doAnswer { invocation ->
                val request = invocation.getArgument<HofRequest>(1)
                requests += request
                if (request.formFields["action"] == "get") homeAccepted = true
                if (directAdvance && request.formFields.containsKey("start_goblin")) externallyStarted = true
                when {
                    request.url.contains("raid_hunt") -> HofHttpResponse(200, "https://hof.zerosic.com/index.php?raid_hunt", noBattle, emptyMap())
                    request.url.contains("raidpub") || request.formFields.containsKey("start_goblin") -> HofHttpResponse(200, raidUrl,
                        if (externallyStarted) ready.replace("현재 상태 : 출발 가능", "현재 상태 : 전투 중")
                            .let {
                                val notice = if (directAdvance) "전투 정보실 안내를 확인했습니다."
                                    else "전투가 신청 가능 상태로 바뀌었습니다."
                                if (genericNotice) it.replace("</body>", "<div class=\"notice\">$notice</div></body>") else it
                            }
                        else ready, emptyMap())
                    else -> HofHttpResponse(200, homeUrl, independentHomePage(homeAccepted), emptyMap())
                }
            }.`when`(gateway).execute(Mockito.eq(accountId), Mockito.any<HofRequest>()
                ?: HofRequest(HofHttpMethod.GET, "https://example.test"), Mockito.anyMap())
            fun startCount() = requests.count { it.method == HofHttpMethod.POST && it.formFields.containsKey("start_goblin") }
            fun convergenceResults() = jdbc.queryForList("select c.result from automation_action_convergences c join automation_action_attempts a on a.id = c.attempt_id where a.account_id = ? and a.action_kind = 'RAID_START' order by c.id", String::class.java, accountId)

            wakeups.wake(accountId, "RAID_SAME_READY")
            publisher.publishBatch()
            assertEquals(1, startCount(), requests.map { it.url to it.formFields.keys }.toString())
            assertEquals(listOf(when {
                directAdvance && mode == AutomationConvergenceMode.ACTIVE -> "FAILED"
                mode == AutomationConvergenceMode.ACTIVE -> "AMBIGUOUS"
                else -> "RECONCILING"
            }),
                jdbc.queryForList("select status from typed_automation_action_runs where account_id = ? order by id", String::class.java, accountId))
            assertEquals(if (mode == AutomationConvergenceMode.ACTIVE) listOf(if (directAdvance) "SUPERSEDED" else "PENDING") else emptyList(), convergenceResults())
            if (mode == AutomationConvergenceMode.SHADOW) assertEquals(listOf(if (directAdvance) "SUPERSEDED" else "PENDING"), jdbc.queryForList(
                "select new_result from automation_convergence_shadow_evaluations where account_id = ? and action_kind = 'RAID_START' order by created_at", String::class.java, accountId))

            if (externalAdvance) externallyStarted = true
            clock.current = clock.now().plusSeconds(if (externalAdvance) 31 else 121)
            repeat(12) {
                clock.current = maxOf(clock.now(), assertNotNull(jdbc.queryForObject(
                    "select min(available_at) from automation_outbox where account_id = ? and published_at is null",
                    java.time.OffsetDateTime::class.java, accountId)).toInstant())
                publisher.publishBatch()
            }
            assertEquals(1, startCount(), "$mode 같은 READY를 다시 시작하면 안 된다.")
            assertEquals(when {
                !externalAdvance -> listOf("HELD")
                mode == AutomationConvergenceMode.ACTIVE -> listOf("SUPERSEDED")
                else -> emptyList()
            }, convergenceResults(), "$mode 동일 상태의 예산 종료와 외부 진전을 구분해야 한다.")
            if (!externalAdvance) assertEquals(1, jdbc.queryForObject(
                "select count(*) from automation_action_convergences where account_id = ? and result = 'HELD' and suppression_released_at is null",
                Int::class.java, accountId))
            if (externalAdvance) {
                val startEvents = journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }
                    .filter { it.type == AutomationType.RAID && it.actionKind == "START" }
                assertTrue(startEvents.any { it.kind == AutomationHistoryEventKind.ACTION_STARTED }, startEvents.toString())
                assertTrue(startEvents.none { it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED },
                    "$mode 공유 단계 진전은 내 START 성공으로 기록하면 안 된다: $startEvents")
                assertEquals("IN_BATTLE", jdbc.queryForObject(
                    "select status from raid_automation_cycles where account_id = ? and open_marker = 1", String::class.java, accountId))
                if (mode == AutomationConvergenceMode.SHADOW) assertEquals("SUPERSEDED", jdbc.queryForList(
                    "select new_result from automation_convergence_shadow_evaluations where account_id = ? and action_kind = 'RAID_START' order by created_at",
                    String::class.java, accountId).last())
            }
            assertTrue(homeAccepted, "$mode 결과 확인 중에도 독립 자택을 실행해야 한다.")
            assertTrue(journal.page(accountId, AutomationHistoryQuery()).cycles.size >= 3)
            assertTrue(transport.delivered.all { outbox.consumed(it) })
            assertEquals(0, jdbc.queryForObject("select count(*) from automation_work_sessions where account_id = ? and running_slot is not null", Int::class.java, accountId))
            assertEquals(0, jdbc.queryForObject("select count(*) from account_automation_leases where account_id = ?", Int::class.java, accountId))
        } finally {
            transport.delivered.forEach { jdbc.update("delete from automation_consumed_events where event_id = ?", it) }
            jdbc.update("delete from hof_accounts where id = ?", accountId)
        }
    }

    @ParameterizedTest
    @CsvSource("INITIAL,COMPLETE", "POST_REWARD,COMPLETE", "DUE,COMPLETE",
        "POST_REWARD,LATER_COMPLETE", "POST_REWARD,STAYS_INCOMPLETE", "INITIAL,STAYS_INCOMPLETE")
    fun `레이드 갱신은 직접 상태와 후속 관측을 구분하며 독립 항목과 새 판단을 이어간다`(phase: String, observationCase: String) {
        assertEquals(mode, properties.mode)
        clock.current = Instant.parse("2026-09-10T04:00:00Z")
        val deadline = clock.now().plusSeconds(600)
        transport.delivered.clear()
        val requests = mutableListOf<HofRequest>()
        val raidUrl = "https://hof.zerosic.com/index.php?menu=raidpub"
        val homeUrl = "https://hof.zerosic.com/index.php?menu=housing"
        val before = requireNotNull(javaClass.getResource("/fixtures/town/raid/raidpub.html")).readText()
            .replace("Funds : $ 1,000", "Funds : $ 1,000 Time : 100/100")
            .replace("[《테스트 길드》현재사용자]", "[다른 신청자]")
            .replace("<input type=\"submit\" name=\"reward_nonce\" value=\"보상 확인\">",
                "<input type=\"submit\" name=\"refresh_nonce\" value=\"상태 갱신\">")
        var serverDeadline = deadline
        fun after(complete: Boolean): String {
            val remaining = (serverDeadline.epochSecond - clock.now().epochSecond).coerceAtLeast(0)
            val header = if (complete) "현재 상태는 신청 대기입니다.(신청 가능 까지 ${remaining}초)" else ""
            return before.replace("현재 상태는 신청 대기 (신청 가능까지 6분 58초)", header)
        }
        var refreshCount = 0
        var homeAccepted = false
        var recoveredObservation = false

        val homeQuest = HomePageParser().parse(HomeMode.HOME, independentHomePage(homeAccepted), homeUrl, HofFormParser().parse(independentHomePage(homeAccepted), homeUrl)).quests.single()
        val accountId = TransactionTemplate(transactions).execute {
            val account = HofAccountEntity(loginId = "raid-refresh-mode-${UUID.randomUUID()}", encryptedPassword = "test", createdAt = clock.now())
            entityManager.persist(account)
            val raidEntry = AutomationEntryEntity(account = account, type = AutomationType.RAID, priority = 0,
                enabled = true, createdAt = clock.now(), updatedAt = clock.now())
            entityManager.persist(raidEntry)
            entityManager.persist(RaidAutomationTargetEntity(entry = raidEntry, raidId = "RaidGoblin", displayName = "고블린 전투 마차",
                presetMode = PresetSelectionMode.PRIMARY, executionOrder = 0))
            if (phase != "INITIAL") entityManager.persist(RaidAutomationCycleEntity(account = account, entry = raidEntry,
                raidId = "RaidGoblin", raidName = "고블린 전투 마차",
                status = if (phase == "POST_REWARD") RaidAutomationCycleStatus.POST_REWARD_CHECK else RaidAutomationCycleStatus.REGISTRATION_COOLDOWN,
                nextCheckAt = clock.now().takeIf { phase == "DUE" }, startedAt = clock.now(), updatedAt = clock.now()))
            val homeEntry = AutomationEntryEntity(account = account, type = AutomationType.HOME_QUEST, priority = 1,
                enabled = true, createdAt = clock.now(), updatedAt = clock.now())
            entityManager.persist(homeEntry)
            entityManager.persist(HomeQuestAutomationSelectionEntity(entry = homeEntry, questId = homeQuest.id,
                questName = homeQuest.name, enabled = true, sourceOrder = 0))
            entityManager.persist(HofCookieEntity(account = account, name = "PHPSESSID", value = "fixture", updatedAt = clock.now()))
            entityManager.persist(HofStatusSnapshotEntity(account = account, playerName = "테스트", funds = 1,
                timeCurrent = 100, timeMax = 100, work = "", auction = "", observedAt = clock.now()))
            entityManager.persist(TypedAutomationRuntimeStateEntity(accountId = account.id, account = account,
                lifecycleStatus = TypedAutomationLifecycle.RUNNING, createdAt = clock.now(), updatedAt = clock.now()))
            account.id
        }
        try {
            Mockito.`when`(preflight.ensureReady(accountId)).thenReturn(AutomationDailyPreflight.Result.Ready)
            Mockito.`when`(authorization.isExecutionAllowed(accountId)).thenReturn(true)
            Mockito.doAnswer { invocation ->
                val request = invocation.getArgument<HofRequest>(1)
                requests += request
                if (request.formFields["action"] == "get") homeAccepted = true
                if (request.formFields.containsKey("refresh_nonce")) {
                    refreshCount++
                    if (!clock.now().isBefore(serverDeadline)) serverDeadline = clock.now().plusSeconds(600)
                }
                if (request.url.contains("raidpub") || request.formFields.containsKey("refresh_nonce"))
                    HofHttpResponse(200, raidUrl, if (refreshCount == 0) before else after(
                        recoveredObservation || observationCase == "COMPLETE" || observationCase == "LATER_COMPLETE" &&
                            (request.method == HofHttpMethod.GET || refreshCount > 1)), emptyMap())
                else HofHttpResponse(200, homeUrl, independentHomePage(homeAccepted), emptyMap())
            }.`when`(gateway).execute(Mockito.eq(accountId), Mockito.any<HofRequest>()
                ?: HofRequest(HofHttpMethod.GET, "https://example.test"), Mockito.anyMap())
            fun refreshResults() = jdbc.queryForList("select c.result from automation_action_convergences c join automation_action_attempts a on a.id = c.attempt_id where a.account_id = ? and a.action_kind = 'RAID_REFRESH' order by c.id", String::class.java, accountId)

            wakeups.wake(accountId, "RAID_REFRESH_CONTINUITY")
            publisher.publishBatch()
            assertEquals(1, refreshCount, "$mode/$phase ${requests.map { it.url to it.formFields.keys }}")
            val firstResult = if (observationCase == "COMPLETE") "APPLIED" else "PENDING"
            assertEquals(if (mode == AutomationConvergenceMode.ACTIVE) listOf(firstResult) else emptyList(), refreshResults())
            if (mode == AutomationConvergenceMode.SHADOW) assertEquals(listOf(firstResult), jdbc.queryForList(
                "select new_result from automation_convergence_shadow_evaluations where account_id = ? and action_kind = 'RAID_REFRESH' order by created_at", String::class.java, accountId))
            if (observationCase == "COMPLETE") {
                val firstEvents = journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }
                val expectedEventKind = if (phase == "POST_REWARD") AutomationHistoryEventKind.CYCLE_COMPLETED else AutomationHistoryEventKind.WAITING
                assertTrue(firstEvents.any { it.type == AutomationType.RAID && it.actionKind == "REFRESH" && it.kind == expectedEventKind }, firstEvents.toString())
                assertTrue(firstEvents.any { it.nextRunAt == deadline }, firstEvents.toString())
            } else {
                clock.current = clock.now().plusSeconds(31)
                repeat(3) { nextWake(accountId) }
                assertEquals(1, refreshCount, "$mode/$observationCase 결과 재확인에 저장 REFRESH를 다시 POST하면 안 된다.")
                if (observationCase == "STAYS_INCOMPLETE") {
                    clock.current = deadline.minusSeconds(600).plusSeconds(121)
                    repeat(6) { nextWake(accountId) }
                    assertEquals("HELD", refreshResults().first(), "$mode 과거 불완전 결과를 성공으로 바꾸면 안 된다.")
                    assertTrue(homeAccepted, "$mode/$phase 레이드 관측이 계속 불완전해도 낮은 우선순위의 독립 자택을 실행해야 한다.")
                    repeat(12) { if (refreshCount < 2) nextWake(accountId) }
                    assertEquals(2, refreshCount, "$mode 과거 결과 억제를 닫고 새 상태 갱신을 한 번 실행해야 한다.")
                    val identities = jdbc.queryForList(
                        "select execution_identity from typed_automation_action_runs where account_id = ? and action_kind = 'RAID_TOWN' order by id",
                        String::class.java, accountId)
                    assertEquals(2, identities.distinct().size, "저장된 갱신을 재전송하지 않고 별도 실행으로 관측한다.")
                    assertNotNull(jdbc.queryForObject(
                        "select c.suppression_released_at from automation_action_convergences c join automation_action_attempts a on a.id = c.attempt_id where a.account_id = ? and a.execution_identity = ?",
                        java.sql.Timestamp::class.java, accountId, identities.first()))
                    if (mode != AutomationConvergenceMode.ACTIVE) {
                        val timings = jdbc.queryForList(
                            "select submitted_at, finished_at from typed_automation_action_runs where account_id = ? and action_kind = 'RAID_TOWN' order by id",
                            accountId)
                        val finishedAt = (timings.first()["finished_at"] as java.time.OffsetDateTime).toInstant()
                        val submittedAt = (timings.last()["submitted_at"] as java.time.OffsetDateTime).toInstant()
                        assertFalse(submittedAt.isBefore(finishedAt.plusSeconds(30)), "불완전 조회는 30초 간격을 두고 새 상태를 확인해야 한다.")
                    }
                    recoveredObservation = true
                    repeat(6) { nextWake(accountId) }
                    assertEquals(if (mode == AutomationConvergenceMode.ACTIVE) listOf("HELD", "SUPERSEDED") else listOf("HELD"),
                        refreshResults(), "최신 관측으로 이어가되 과거 요청에 성공을 귀속하지 않는다.")
                    assertEquals(2, refreshCount, "활성 갱신은 GET으로 확인하며 쿨다운 동안 새 POST를 만들지 않는다.")
                    assertTrue(homeAccepted)
                    assertTrue(transport.delivered.all { outbox.consumed(it) })
                    assertEquals(0, jdbc.queryForObject("select count(*) from automation_work_sessions where account_id = ? and running_slot is not null", Int::class.java, accountId))
                    assertEquals(0, jdbc.queryForObject("select count(*) from account_automation_leases where account_id = ?", Int::class.java, accountId))
                    return
                }
                assertEquals(if (mode == AutomationConvergenceMode.ACTIVE) listOf("SUPERSEDED") else emptyList(), refreshResults())
                if (mode == AutomationConvergenceMode.SHADOW) {
                    val comparisons = jdbc.queryForList("select new_result from automation_convergence_shadow_evaluations where account_id = ? and action_kind = 'RAID_REFRESH'", String::class.java, accountId)
                    assertTrue("SUPERSEDED" in comparisons, comparisons.toString())
                    assertTrue("APPLIED" !in comparisons, comparisons.toString())
                }
            }
            repeat(6) { nextWake(accountId) }
            assertTrue(clock.now().isBefore(deadline))
            assertEquals(1, refreshCount)
            assertTrue(homeAccepted, "$mode/$phase 레이드 대기 중 독립 자택을 실행해야 한다.")
            assertTrue(journal.page(accountId, AutomationHistoryQuery()).cycles.size >= 3)
            clock.current = deadline
            repeat(4) { if (refreshCount < 2) nextWake(accountId) }
            assertEquals(2, refreshCount, "$mode/$phase 등록 대기 종료 뒤 새 갱신을 실행해야 한다.")
            val expectedResults = if (observationCase == "COMPLETE") listOf("APPLIED", "APPLIED") else listOf("SUPERSEDED", "APPLIED")
            assertEquals(if (mode == AutomationConvergenceMode.ACTIVE) expectedResults else emptyList(), refreshResults())
            assertTrue(transport.delivered.all { outbox.consumed(it) })
            assertEquals(0, jdbc.queryForObject("select count(*) from automation_work_sessions where account_id = ? and running_slot is not null", Int::class.java, accountId))
            assertEquals(0, jdbc.queryForObject("select count(*) from account_automation_leases where account_id = ?", Int::class.java, accountId))
        } finally {
            transport.delivered.forEach { jdbc.update("delete from automation_consumed_events where event_id = ?", it) }
            jdbc.update("delete from hof_accounts where id = ?", accountId)
        }
    }

    @ParameterizedTest
    @CsvSource("REGISTER,COMPLETE", "RESET,COMPLETE", "RESET,REGISTER_AVAILABLE", "RESET,LATER_COMPLETE", "RESET,STAYS_INCOMPLETE", "REWARD,LATER_COMPLETE", "REWARD,STAYS_INCOMPLETE")
    fun `레이드 직접 응답의 완전성에 따라 결과를 수렴하고 독립 항목과 다음 판단을 이어간다`(action: String, observationCase: String) {
        assertEquals(mode, properties.mode)
        val directComplete = observationCase in setOf("COMPLETE", "REGISTER_AVAILABLE")
        clock.current = Instant.parse("2026-09-10T06:00:00Z")
        val startedAt = clock.now()
        transport.delivered.clear()
        val requests = mutableListOf<HofRequest>()
        val raidUrl = "https://hof.zerosic.com/index.php?menu=raidpub"
        val homeUrl = "https://hof.zerosic.com/index.php?menu=housing"
        val fixture = requireNotNull(javaClass.getResource("/fixtures/town/raid/raidpub.html")).readText()
            .replace("Funds : $ 1,000", "Funds : $ 1,000 Time : 100/100")
            .replace("현재 상태는 신청 대기 (신청 가능까지 6분 58초)", "현재 상태는 신청 가능")
        val before = when (action) {
            "REGISTER" -> fixture
                .replace("[《테스트 길드》현재사용자]", "[다른 신청자]")
                .replace("현재 상태 : 모집 중", "현재 상태 : 파티 모집 중 (신청 안됨)")
            "RESET" -> fixture
                .replace("[《테스트 길드》현재사용자]", "[다른 신청자]")
                .replace("현재 상태 : 모집 중", "현재 상태 : 보상 확인 종료(리셋 가능)")
                .replace("name=\"register_goblin\" value=\"등록한다\"", "name=\"reset_goblin\" value=\"전투를 리셋한다\"")
            else -> fixture.replace("현재 상태 : 모집 중", "현재 상태 : 보상 확인 시간 (남은 시간 앞으로 0시간 29분 56초)")
        }
        val noBattle = requireNotNull(javaClass.getResource("/fixtures/raid/raid-complete-absent.html")).readText()
        val actionField = when (action) {
            "REGISTER" -> "register_goblin"
            "RESET" -> "reset_goblin"
            else -> "reward_nonce"
        }
        var followupRegistrations = 0
        fun registered() = action == "REGISTER" || followupRegistrations > 0
        fun after() = fixture
            .replace("[《테스트 길드》현재사용자]", if (registered()) "[《테스트 길드》현재사용자]" else "[다른 신청자]")
            .replace("현재 상태 : 모집 중", if (registered()) "현재 상태 : 418초 후 출발" else "현재 상태 : 파티 모집 중 (신청 안됨)")
            .let { if (observationCase == "REGISTER_AVAILABLE" && !registered()) it else
                it.replace("현재 상태는 신청 가능", "현재 상태는 신청 대기입니다.(신청 가능 까지 600초)") }
            .replace("name=\"reward_nonce\" value=\"보상 확인\"", "name=\"refresh_nonce\" value=\"상태 갱신\"")
        var actionCount = 0
        var homeAccepted = false

        val homeQuest = HomePageParser().parse(HomeMode.HOME, independentHomePage(homeAccepted), homeUrl, HofFormParser().parse(independentHomePage(homeAccepted), homeUrl)).quests.single()
        val accountId = TransactionTemplate(transactions).execute {
            val account = HofAccountEntity(loginId = "raid-action-mode-${UUID.randomUUID()}", encryptedPassword = "test", createdAt = clock.now())
            entityManager.persist(account)
            val raidEntry = AutomationEntryEntity(account = account, type = AutomationType.RAID, priority = 0,
                enabled = true, createdAt = clock.now(), updatedAt = clock.now())
            entityManager.persist(raidEntry)
            entityManager.persist(RaidAutomationTargetEntity(entry = raidEntry, raidId = "RaidGoblin", displayName = "고블린 전투 마차",
                presetMode = PresetSelectionMode.PRIMARY, executionOrder = 0))
            entityManager.persist(RaidAutomationCycleEntity(account = account, entry = raidEntry,
                raidId = "RaidGoblin", raidName = "고블린 전투 마차",
                status = if (action == "REWARD") RaidAutomationCycleStatus.REWARD_PENDING else RaidAutomationCycleStatus.PREPARING,
                startedAt = clock.now(), updatedAt = clock.now()))
            val homeEntry = AutomationEntryEntity(account = account, type = AutomationType.HOME_QUEST, priority = 1,
                enabled = true, createdAt = clock.now(), updatedAt = clock.now())
            entityManager.persist(homeEntry)
            entityManager.persist(HomeQuestAutomationSelectionEntity(entry = homeEntry, questId = homeQuest.id,
                questName = homeQuest.name, enabled = true, sourceOrder = 0))
            entityManager.persist(HofCookieEntity(account = account, name = "PHPSESSID", value = "fixture", updatedAt = clock.now()))
            entityManager.persist(HofStatusSnapshotEntity(account = account, playerName = "테스트", funds = 1,
                timeCurrent = 100, timeMax = 100, work = "", auction = "", observedAt = clock.now()))
            entityManager.persist(TypedAutomationRuntimeStateEntity(accountId = account.id, account = account,
                lifecycleStatus = TypedAutomationLifecycle.RUNNING, createdAt = clock.now(), updatedAt = clock.now()))
            account.id
        }
        try {
            Mockito.`when`(preflight.ensureReady(accountId)).thenReturn(AutomationDailyPreflight.Result.Ready)
            Mockito.`when`(authorization.isExecutionAllowed(accountId)).thenReturn(true)
            Mockito.doAnswer { invocation ->
                val request = invocation.getArgument<HofRequest>(1)
                requests += request
                if (request.formFields["action"] == "get") homeAccepted = true
                val submitted = request.method == HofHttpMethod.POST && request.formFields.containsKey(actionField)
                if (submitted) actionCount++
                val followupRegistration = observationCase == "REGISTER_AVAILABLE" && request.method == HofHttpMethod.POST &&
                    request.formFields.containsKey("register_goblin")
                if (followupRegistration) followupRegistrations++
                if (request.url.contains("raid_hunt")) {
                    HofHttpResponse(200, "https://hof.zerosic.com/index.php?raid_hunt", noBattle, emptyMap())
                } else if (request.url.contains("raidpub") || submitted || followupRegistration || request.formFields.containsKey("refresh_nonce")) {
                    val html = if (actionCount == 0) before else after().let {
                        if (submitted && !directComplete || observationCase == "STAYS_INCOMPLETE") it.substringBefore("<div id=\"foot\"") else it
                    }
                    HofHttpResponse(200, raidUrl, html, emptyMap())
                } else HofHttpResponse(200, homeUrl, independentHomePage(homeAccepted), emptyMap())
            }.`when`(gateway).execute(Mockito.eq(accountId), Mockito.any<HofRequest>()
                ?: HofRequest(HofHttpMethod.GET, "https://example.test"), Mockito.anyMap())
            fun results() = jdbc.queryForList("select c.result from automation_action_convergences c join automation_action_attempts a on a.id = c.attempt_id where a.account_id = ? and a.action_kind = ? order by c.id", String::class.java, accountId, "RAID_$action")

            wakeups.wake(accountId, "RAID_ACTION_RESPONSE_CONTINUITY")
            publisher.publishBatch()
            assertEquals(1, actionCount, requests.map { it.url to it.formFields.keys }.toString())
            val firstResult = if (directComplete) "APPLIED" else "PENDING"
            assertEquals(if (mode == AutomationConvergenceMode.ACTIVE) listOf(firstResult) else emptyList(), results())
            if (directComplete) {
                val stored = actionCodec.decode(assertNotNull(jdbc.queryForObject(
                    "select payload_json from typed_automation_action_runs where account_id = ?", String::class.java, accountId)))
                val payload = assertIs<StoredTypedActionPayload.RaidTown>(stored.payload)
                assertEquals(action, payload.action.name)
                assertEquals("RaidGoblin", payload.targetRaidId)
                fun assertApplied() {
                    assertEquals("SUCCEEDED", jdbc.queryForObject(
                        "select status from typed_automation_action_runs where account_id = ? and execution_identity = ?",
                        String::class.java, accountId, stored.executionIdentity))
                    assertEquals(if (mode == AutomationConvergenceMode.ACTIVE) listOf("APPLIED") else emptyList(), results())
                    if (mode == AutomationConvergenceMode.SHADOW) assertEquals(listOf("APPLIED"), jdbc.queryForList(
                        "select new_result from automation_convergence_shadow_evaluations where account_id = ? and execution_identity_hash = ?",
                        String::class.java, accountId,
                        app.spammy.hof.automation.convergence.ProductionEvidenceShapes.fingerprint(stored.executionIdentity)))
                }
                assertApplied()
                val eventKind = if (action == "REGISTER") AutomationHistoryEventKind.WAITING else AutomationHistoryEventKind.ACTION_SUCCEEDED
                val originalCycle = journal.page(accountId, AutomationHistoryQuery()).cycles.single { cycle ->
                    cycle.events.any { it.actionKind == action && it.kind == eventKind }
                }
                val originalResult = originalCycle.events.single {
                    it.actionKind == action && it.kind == eventKind
                }
                assertEquals("RaidGoblin", originalResult.targetKey)
                if (action == "REGISTER") {
                    assertEquals("RAID_WAITING_TO_START", originalResult.reasonCode)
                    assertEquals(clock.now().plusSeconds(418), originalResult.nextRunAt)
                }
                repeat(6) { nextWake(accountId) }
                assertApplied()
                assertEquals(1, actionCount)
                if (observationCase == "REGISTER_AVAILABLE") {
                    assertEquals(1, followupRegistrations)
                    assertEquals(listOf("reset_goblin", "register_goblin"), requests.flatMap { request ->
                        listOf("reset_goblin", "register_goblin").filter { it in request.formFields && request.method == HofHttpMethod.POST }
                    })
                }
                assertEquals(1, requests.count { it.formFields["action"] == "get" && it.formFields["no"] == "A" })
                val cycles = journal.page(accountId, AutomationHistoryQuery()).cycles
                assertTrue(cycles.size >= 3)
                assertEquals(originalResult, cycles.single { it.id == originalCycle.id }.events.single {
                    it.actionKind == action && it.kind == eventKind
                })
                assertTrue(transport.delivered.all { outbox.consumed(it) })
                assertEquals(0, jdbc.queryForObject("select count(*) from automation_work_sessions where account_id = ? and running_slot is not null", Int::class.java, accountId))
                assertEquals(0, jdbc.queryForObject("select count(*) from account_automation_leases where account_id = ?", Int::class.java, accountId))
                return
            }
            clock.current = startedAt.plusSeconds(31)
            repeat(3) { nextWake(accountId) }
            if (observationCase == "STAYS_INCOMPLETE") clock.current = maxOf(clock.now(), startedAt.plusSeconds(121))
            repeat(6) { nextWake(accountId) }
            val expected = if (observationCase == "STAYS_INCOMPLETE") listOf("HELD")
                else if (mode == AutomationConvergenceMode.ACTIVE) listOf("SUPERSEDED") else emptyList()
            assertEquals(expected, results(), "$mode/$action/$observationCase ${journal.page(accountId, AutomationHistoryQuery()).cycles}")
            if (mode == AutomationConvergenceMode.SHADOW) {
                val comparisons = jdbc.queryForList("select new_result from automation_convergence_shadow_evaluations where account_id = ? and action_kind = ? order by created_at", String::class.java, accountId, "RAID_$action")
                assertTrue("APPLIED" !in comparisons, comparisons.toString())
                if (observationCase == "LATER_COMPLETE") assertTrue("SUPERSEDED" in comparisons, comparisons.toString())
            }
            assertEquals(1, actionCount, "$mode 결과 재확인에 $action POST를 재생하면 안 된다.")
            assertTrue(homeAccepted, "$mode 레이드 결과 대기 중 독립 자택이 실행되어야 한다.")
            val cycles = journal.page(accountId, AutomationHistoryQuery()).cycles
            assertTrue(cycles.size >= 3, cycles.toString())
            val actionEvents = cycles.flatMap { it.events }.filter { it.actionKind == action }
            assertTrue(actionEvents.none { it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED }, actionEvents.toString())
            assertTrue(transport.delivered.all { outbox.consumed(it) })
            assertEquals(0, jdbc.queryForObject("select count(*) from automation_work_sessions where account_id = ? and running_slot is not null", Int::class.java, accountId))
            assertEquals(0, jdbc.queryForObject("select count(*) from account_automation_leases where account_id = ?", Int::class.java, accountId))
        } finally {
            transport.delivered.forEach { jdbc.update("delete from automation_consumed_events where event_id = ?", it) }
            jdbc.update("delete from hof_accounts where id = ?", accountId)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["ACCEPT", "CLAIM"])
    fun `유실된 자택 응답 뒤 외부 진전은 성공 귀속 없이 닫고 독립 자택과 다음 판단을 실행한다`(action: String) {
        assertEquals(mode, properties.mode)
        clock.current = Instant.parse("2026-09-10T09:00:00Z")
        transport.delivered.clear()
        val requests = mutableListOf<HofRequest>()
        val url = "https://hof.zerosic.com/index.php?menu=housing"
        var externalAdvance = false
        var independentAccepted = false
        fun page(): String {
            val original = independentHomePage(false).let {
                if (action == "CLAIM") it.replace("action=get", "action=complete").replace(">수락</a>", ">보상 수령</a>") else it
            }
            return (if (externalAdvance) independentHomePage(true) else original) + independentHomePage(independentAccepted)
                .replace("[A] 독립 자택", "[B] 독립 자택").replace("no=A", "no=B")
        }
        val quests = HomePageParser().parse(HomeMode.HOME, page(), url, HofFormParser().parse(page(), url)).quests
        val accountId = requireNotNull(TransactionTemplate(transactions).execute {
            val account = HofAccountEntity(loginId = "home-observation-${UUID.randomUUID()}", encryptedPassword = "test", createdAt = clock.now())
            entityManager.persist(account)
            val entry = AutomationEntryEntity(account = account, type = AutomationType.HOME_QUEST, priority = 0,
                enabled = true, createdAt = clock.now(), updatedAt = clock.now())
            entityManager.persist(entry)
            quests.forEachIndexed { index, quest -> entityManager.persist(HomeQuestAutomationSelectionEntity(
                entry = entry, questId = quest.id, questName = quest.name, enabled = true, sourceOrder = index)) }
            entityManager.persist(HofCookieEntity(account = account, name = "PHPSESSID", value = "fixture", updatedAt = clock.now()))
            entityManager.persist(HofStatusSnapshotEntity(account = account, playerName = "테스트", funds = 1,
                timeCurrent = 100, timeMax = 100, work = "", auction = "", observedAt = clock.now()))
            entityManager.persist(TypedAutomationRuntimeStateEntity(accountId = account.id, account = account,
                lifecycleStatus = TypedAutomationLifecycle.RUNNING, createdAt = clock.now(), updatedAt = clock.now()))
            account.id
        })
        try {
            Mockito.`when`(preflight.ensureReady(accountId)).thenReturn(AutomationDailyPreflight.Result.Ready)
            Mockito.`when`(authorization.isExecutionAllowed(accountId)).thenReturn(true)
            Mockito.doAnswer { invocation ->
                val request = invocation.getArgument<HofRequest>(1)
                requests += request
                if (request.formFields["action"] != null) {
                    if (request.formFields["no"] == "A") throw IllegalStateException(
                        "fixture response lost", java.io.IOException("fixture response lost"))
                    if (request.formFields["no"] == "B") independentAccepted = true
                }
                HofHttpResponse(200, url, page(), emptyMap())
            }.`when`(gateway).execute(Mockito.eq(accountId), Mockito.any<HofRequest>()
                ?: HofRequest(HofHttpMethod.GET, "https://example.test"), Mockito.anyMap())
            fun actionCount(target: String) = requests.count { it.formFields["action"] != null && it.formFields["no"] == target }

            wakeups.wake(accountId, "HOME_LOST_RESPONSE")
            publisher.publishBatch()
            assertEquals(1, actionCount("A"), requests.toString())
            val identity = assertNotNull(jdbc.queryForObject(
                "select execution_identity from typed_automation_action_runs where account_id = ? order by id limit 1", String::class.java, accountId))
            externalAdvance = true
            clock.current = clock.now().plusSeconds(31)
            repeat(6) { nextWake(accountId) }

            assertEquals(1, actionCount("A"))
            assertEquals(1, actionCount("B"))
            val cycles = journal.page(accountId, AutomationHistoryQuery()).cycles
            val events = cycles.flatMap { it.events }.filter { it.targetKey == quests.first().id }
            assertTrue(events.any { it.kind == AutomationHistoryEventKind.ACTION_STARTED }, events.toString())
            assertTrue(events.none { it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED }, events.toString())
            assertTrue(cycles.size >= 3)
            if (mode == AutomationConvergenceMode.ACTIVE) assertEquals(listOf("SUPERSEDED"), jdbc.queryForList(
                "select c.result from automation_action_convergences c join automation_action_attempts a on a.id = c.attempt_id where a.account_id = ? and a.execution_identity = ?",
                String::class.java, accountId, identity))
            if (mode == AutomationConvergenceMode.SHADOW) {
                val comparisons = jdbc.queryForList(
                    "select new_result from automation_convergence_shadow_evaluations where account_id = ? and execution_identity_hash = ?",
                    String::class.java, accountId, app.spammy.hof.automation.convergence.ProductionEvidenceShapes.fingerprint(identity))
                assertTrue("SUPERSEDED" in comparisons && "APPLIED" !in comparisons, comparisons.toString())
            }
            assertTrue(transport.delivered.all { outbox.consumed(it) })
            assertEquals(0, jdbc.queryForObject("select count(*) from automation_work_sessions where account_id = ? and running_slot is not null", Int::class.java, accountId))
            assertEquals(0, jdbc.queryForObject("select count(*) from account_automation_leases where account_id = ?", Int::class.java, accountId))
        } finally {
            transport.delivered.forEach { jdbc.update("delete from automation_consumed_events where event_id = ?", it) }
            jdbc.update("delete from hof_accounts where id = ?", accountId)
        }
    }

    @Test
    fun `자택의 UNKNOWN 직접 응답도 사후 상태로 수락과 보상을 닫고 다음 판단을 소비한다`() {
        assertEquals(mode, properties.mode)
        clock.current = Instant.parse("2026-09-10T00:00:00Z")
        transport.delivered.clear()
        val requests = mutableListOf<HofRequest>()
        val url = "https://hof.zerosic.com/index.php?menu=housing"
        var stage = 0
        fun page(): String {
            val heading = when (stage) { 0 -> "수락 가능한 퀘스트"; 1 -> "완료 가능한 퀘스트"; else -> "대기 중인 퀘스트" }
            val link = when (stage) {
                0 -> "<a href='?menu=housing&amp;action=get&amp;no=A'>수락</a>"
                1 -> "<a href='?menu=housing&amp;action=complete&amp;no=A'>보상 수령</a>"
                else -> "-"
            }
            return """<div id="menu2">Funds : $ 1 Time : 100/100</div><h4>$heading</h4><table>
                <tr><td>[A] 전환 검증</td><td>미션 1/1</td><td>-</td><td>-</td><td>$link</td></tr></table>"""
        }
        val quest = HomePageParser().parse(HomeMode.HOME, page(), url, HofFormParser().parse(page(), url)).quests.single()
        val accountId = TransactionTemplate(transactions).execute {
            val account = HofAccountEntity(loginId = "home-mode-${UUID.randomUUID()}", encryptedPassword = "test", createdAt = clock.now())
            entityManager.persist(account)
            val entry = AutomationEntryEntity(account = account, type = AutomationType.HOME_QUEST, priority = 0,
                enabled = true, createdAt = clock.now(), updatedAt = clock.now())
            entityManager.persist(entry)
            entityManager.persist(HomeQuestAutomationSelectionEntity(entry = entry, questId = quest.id,
                questName = quest.name, enabled = true, sourceOrder = 0))
            entityManager.persist(HofCookieEntity(account = account, name = "PHPSESSID", value = "fixture", updatedAt = clock.now()))
            entityManager.persist(HofStatusSnapshotEntity(account = account, playerName = "테스트", funds = 1,
                timeCurrent = 100, timeMax = 100, work = "", auction = "", observedAt = clock.now()))
            entityManager.persist(TypedAutomationRuntimeStateEntity(accountId = account.id, account = account,
                lifecycleStatus = TypedAutomationLifecycle.RUNNING, createdAt = clock.now(), updatedAt = clock.now()))
            account.id
        }
        try {
            Mockito.`when`(preflight.ensureReady(accountId)).thenReturn(AutomationDailyPreflight.Result.Ready)
            Mockito.`when`(authorization.isExecutionAllowed(accountId)).thenReturn(true)
            Mockito.doAnswer { invocation ->
                val request = invocation.getArgument<HofRequest>(1)
                requests += request
                when (request.formFields["action"]) { "get" -> stage = 1; "complete" -> stage = 2 }
                val body = page()
                assertEquals("UNKNOWN", app.spammy.hof.town.fishing.dto.TownActionResultResponse.from(
                    app.spammy.hof.town.common.parser.HofResultParser().parse(body)).status)
                HofHttpResponse(200, url, body, emptyMap())
            }.`when`(gateway).execute(Mockito.eq(accountId), Mockito.any<HofRequest>()
                ?: HofRequest(HofHttpMethod.GET, "https://example.test"), Mockito.anyMap())

            wakeups.wake(accountId, "HOME_UNKNOWN_POSTSTATE")
            publisher.publishBatch()
            assertEquals(listOf("SUCCEEDED"), jdbc.queryForList(
                "select status from typed_automation_action_runs where account_id = ? order by id", String::class.java, accountId))
            repeat(2) {
                clock.current = assertNotNull(jdbc.queryForObject(
                    "select min(available_at) from automation_outbox where account_id = ? and published_at is null",
                    java.time.OffsetDateTime::class.java, accountId)).toInstant()
                publisher.publishBatch()
            }

            assertEquals(listOf("get", "complete"), requests.mapNotNull { it.formFields["action"] })
            assertEquals(listOf("SUCCEEDED", "SUCCEEDED"), jdbc.queryForList(
                "select status from typed_automation_action_runs where account_id = ? order by id", String::class.java, accountId))
            assertTrue(journal.page(accountId, AutomationHistoryQuery()).cycles.size >= 3)
            assertTrue(transport.delivered.all { outbox.consumed(it) })
            assertEquals(0, jdbc.queryForObject("select count(*) from automation_work_sessions where account_id = ? and running_slot is not null", Int::class.java, accountId))
            assertEquals(0, jdbc.queryForObject("select count(*) from account_automation_leases where account_id = ?", Int::class.java, accountId))
            val applied = jdbc.queryForList("select result from automation_action_convergences where account_id = ? order by id", String::class.java, accountId)
            val shadow = jdbc.queryForList("select new_result from automation_convergence_shadow_evaluations where account_id = ? order by created_at", String::class.java, accountId)
            assertEquals(if (mode == AutomationConvergenceMode.ACTIVE) listOf("APPLIED", "APPLIED") else emptyList(), applied)
            assertEquals(if (mode == AutomationConvergenceMode.SHADOW) listOf("APPLIED", "APPLIED") else emptyList(), shadow)
        } finally {
            transport.delivered.forEach { jdbc.update("delete from automation_consumed_events where event_id = ?", it) }
            jdbc.update("delete from hof_accounts where id = ?", accountId)
        }
    }

    @Test
    fun `동기화 복구 뒤 같은 자택 응답은 한 번 제출하고 후속 판단에서 진행 대기로 양보한다`() {
        assertEquals(mode, properties.mode)
        clock.current = Instant.parse("2026-09-06T00:00:00Z")
        transport.delivered.clear()
        val requests = mutableListOf<HofRequest>()
        val url = "https://hof.zerosic.com/index.php?menu=housing"
        var accepted = false
        fun page() = """<div id="menu2">Funds : $ 1 Time : 100/100</div><h4>수락 가능한 퀘스트</h4><table>
            <tr><td>[A] 모드 기준</td><td>미션 0/1</td><td>-</td><td>-</td><td>${if (accepted) "-" else "<a href='?menu=housing&amp;action=get&amp;no=A'>수락</a>"}</td></tr></table>"""
        val quest = HomePageParser().parse(HomeMode.HOME, page(), url, HofFormParser().parse(page(), url)).quests.single()
        lateinit var characterJob: app.spammy.hof.character.entity.CharacterOperationJobEntity
        val accountId = TransactionTemplate(transactions).execute {
            val account = HofAccountEntity(loginId = "mode-${UUID.randomUUID()}", encryptedPassword = "test", createdAt = clock.now())
            entityManager.persist(account)
            entityManager.flush()
            val entry = AutomationEntryEntity(account = account, type = AutomationType.HOME_QUEST, priority = 0,
                enabled = true, createdAt = clock.now(), updatedAt = clock.now())
            entityManager.persist(entry)
            entityManager.persist(HomeQuestAutomationSelectionEntity(entry = entry, questId = quest.id,
                questName = quest.name, enabled = true, sourceOrder = 0))
            entityManager.persist(HofCookieEntity(account = account, name = "PHPSESSID", value = "test-session", updatedAt = clock.now()))
            entityManager.persist(HofStatusSnapshotEntity(account = account, playerName = "테스트", funds = 1,
                timeCurrent = 100, timeMax = 100, work = "", auction = "", observedAt = clock.now()))
            entityManager.persist(TypedAutomationRuntimeStateEntity(accountId = account.id, account = account,
                lifecycleStatus = TypedAutomationLifecycle.RUNNING, createdAt = clock.now(), updatedAt = clock.now()))
            val character = app.spammy.hof.character.entity.CharacterEntity(account = account, hofCharacterId = "mode-character",
                name = "fixture", job = "Knight", updatedAt = clock.now())
            entityManager.persist(character)
            characterJob = app.spammy.hof.character.entity.CharacterOperationJobEntity(account = account,
                operationType = app.spammy.hof.character.entity.CharacterOperationType.DEEP_SYNC, targetCharacterId = character.id,
                recoveryStatus = app.spammy.hof.character.entity.CharacterRecoveryStatus.NOT_STARTED,
                startedAt = clock.now(), updatedAt = clock.now())
            entityManager.persist(characterJob)
            account.id
        }
        try {
            Mockito.`when`(preflight.ensureReady(accountId)).thenReturn(AutomationDailyPreflight.Result.Ready)
            Mockito.`when`(authorization.isExecutionAllowed(accountId)).thenReturn(true)
            Mockito.doAnswer { invocation ->
                val request = invocation.arguments[1] as HofRequest
                requests += request
                if (request.formFields["action"] == "get") accepted = true
                HofHttpResponse(200, url, page() + if (accepted) "<div id='result'>수락했습니다.</div>" else "", emptyMap())
            }.`when`(gateway).execute(Mockito.eq(accountId), Mockito.any<HofRequest>()
                ?: HofRequest(HofHttpMethod.GET, "https://example.test"), Mockito.anyMap())

            val original = app.spammy.hof.character.service.CharacterRestoreState("mode-character",
                listOf(app.spammy.hof.external.model.HofActionPatternRow(0, judge = "0", quantity = "0", skill = "0")), emptyList(), "front", "0")
            characterGate.executeJob(accountId, characterJob.id, { error("동기화 일시정지 실패") }) {
                characterRecovery.save(characterJob.id, accountId, characterJob.targetCharacterId,
                    app.spammy.hof.character.service.CharacterDeepSyncCheckpoint(original))
                wakeups.wake(accountId, "MODE_CHARACTER_RECOVERY_PENDING")
                publisher.publishBatch()
                assertTrue(requests.isEmpty(), "$mode 미복원 중 새 HOF 행동을 실행하면 안 된다.")
                characterRecovery.save(characterJob.id, accountId, characterJob.targetCharacterId,
                    app.spammy.hof.character.service.CharacterDeepSyncCheckpoint(original,
                        status = app.spammy.hof.character.entity.CharacterRecoveryStatus.RESTORED, collectionComplete = true))
            }
            publisher.publishBatch()
            val first = journal.page(accountId, AutomationHistoryQuery()).cycles.single()
            assertEquals(1, requests.count { it.formFields["action"] == "get" },
                first.toString() + requests.map { it.method to it.url })
            val next = assertNotNull(jdbc.queryForObject(
                "select min(available_at) from automation_outbox where account_id = ? and published_at is null",
                java.time.OffsetDateTime::class.java, accountId)).toInstant()
            clock.current = next
            publisher.publishBatch()

            assertTrue(transport.delivered.size >= 2)
            assertTrue(transport.delivered.all { outbox.consumed(it) })
            val cycles = journal.page(accountId, AutomationHistoryQuery()).cycles
            assertEquals(2, cycles.size)
            assertNotEquals(first.id, cycles.first().id)
            assertEquals(1, requests.count { it.formFields["action"] == "get" })
            // Home acceptance is a mutating GET; count the exact action query, not POST.
            assertEquals(HofHttpMethod.GET, requests.single { it.formFields["action"] == "get" }.method)
            assertEquals("A", requests.single { it.formFields["action"] == "get" }.formFields["no"])
            assertEquals(listOf("SUCCEEDED"), jdbc.queryForList(
                "select status from typed_automation_action_runs where account_id = ?", String::class.java, accountId))
            assertEquals(listOf("WAITING_COOLDOWN"), jdbc.queryForList(
                "select status from automation_work_sessions where account_id = ?", String::class.java, accountId))
            assertEquals(0, jdbc.queryForObject("select count(*) from account_automation_leases where account_id = ?", Int::class.java, accountId))
        } finally {
            transport.delivered.forEach { jdbc.update("delete from automation_consumed_events where event_id = ?", it) }
            jdbc.update("delete from hof_accounts where id = ?", accountId)
        }
    }
}
