package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.entity.HofCookieEntity
import app.spammy.hof.auth.service.AccountExecutionAuthorizationReader
import app.spammy.hof.automation.convergence.*
import app.spammy.hof.automation.entity.*
import app.spammy.hof.automation.history.AutomationDecisionJournal
import app.spammy.hof.automation.history.AutomationHistoryEventKind
import app.spammy.hof.automation.history.AutomationHistoryQuery
import app.spammy.hof.automation.lease.AccountAutomationLeaseService
import app.spammy.hof.automation.outbox.*
import app.spammy.hof.automation.port.AutomationWakeupPort
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.entity.CharacterPatternSlotEntity
import app.spammy.hof.party.entity.PartyPresetEntity
import app.spammy.hof.party.entity.PartyPresetMemberEntity
import app.spammy.hof.status.entity.HofStatusSnapshotEntity
import app.spammy.hof.external.client.HofGateway
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import jakarta.persistence.EntityManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
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
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper

@ActiveProfiles("test")
@TestPropertySource(properties = ["spring.datasource.hikari.connection-init-sql=SET LOCK_TIMEOUT 15000"])
@Import(AutomationRecoveryIntegrationTest.Config::class)
abstract class AutomationLateResultIntegrationTest {
    @MockitoSpyBean private lateinit var runtime: TypedAutomationRuntimeService
    @MockitoSpyBean private lateinit var runtimeQueries: app.spammy.hof.automation.repository.TypedAutomationQueryRepository
    @MockitoSpyBean private lateinit var results: AutomationResultCoordinator
    @MockitoSpyBean private lateinit var actionLifecycle: AutomationActionLifecycleModule
    @MockitoSpyBean private lateinit var shadowRecorder: AutomationConvergenceShadowRecorder
    @MockitoSpyBean private lateinit var evidenceCases: EvidenceCaseRecorder
    @MockitoSpyBean private lateinit var battleRuns: app.spammy.hof.battle.service.BattleRunService
    @MockitoSpyBean private lateinit var fishing: app.spammy.hof.town.fishing.service.FishingService
    @MockitoSpyBean private lateinit var battleOutcomes: ConservativeBattleOutcomeReconciler
    @MockitoSpyBean private lateinit var actionCommands: app.spammy.hof.automation.repository.TypedAutomationActionRunCommandRepository
    @Autowired private lateinit var codec: StoredTypedAutomationActionCodec
    @MockitoBean private lateinit var captchaSolver: app.spammy.hof.captcha.service.CaptchaAutoSolveCoordinator
    @Autowired private lateinit var captchaService: app.spammy.hof.captcha.service.CaptchaService
    @Autowired private lateinit var properties: AutomationConvergenceProperties
    @Autowired private lateinit var runner: UnifiedAutomationRunner
    @MockitoSpyBean private lateinit var store: ConvergenceStore
    @MockitoSpyBean private lateinit var journal: AutomationDecisionJournal
    @Autowired private lateinit var entityManager: EntityManager
    @Autowired private lateinit var transactions: PlatformTransactionManager
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var clock: AutomationRecoveryIntegrationTest.RecoveryClock
    @Autowired private lateinit var publisher: AutomationOutboxPublisher
    @Autowired private lateinit var outbox: AutomationOutboxQueryRepository
    @Autowired private lateinit var transport: AutomationRecoveryIntegrationTest.ConsumerReplayTransport
    @Autowired private lateinit var consumedEvents: AutomationConsumedEventService
    @Autowired private lateinit var consumerLease: AccountAutomationLeaseService
    @Autowired private lateinit var publishedMarker: AutomationOutboxPublishMarker
    @Autowired private lateinit var mapper: ObjectMapper
    @Autowired private lateinit var application: UnifiedAutomationService
    @Autowired private lateinit var lifecycle: TypedAutomationLifecycleBridge
    @MockitoBean private lateinit var gateway: HofGateway
    @MockitoBean private lateinit var preflight: AutomationDailyPreflight
    @MockitoBean private lateinit var authorization: AccountExecutionAuthorizationReader
    @Autowired private lateinit var wakeups: AutomationWakeupPort
    private var accountId = 0L
    private var entryId = 0L
    private val characters = listOf("fixture-character")
    private val requests = CopyOnWriteArrayList<HofRequest>()
    private val mode get() = properties.mode

    @BeforeEach
    fun prepareAccount() {
        transport.delivered.clear()
        clock.current = Instant.parse("2026-09-04T00:00:00Z")
        TransactionTemplate(transactions).executeWithoutResult {
            val account = HofAccountEntity(loginId = "late-result-${UUID.randomUUID()}", encryptedPassword = "test", createdAt = clock.now())
            entityManager.persist(account)
            entityManager.flush()
            accountId = account.id
            val entry = AutomationEntryEntity(account = account, type = AutomationType.FISHING, priority = 0,
                singletonTypeMarker = AutomationType.FISHING, enabled = true, createdAt = clock.now(), updatedAt = clock.now())
            entityManager.persist(entry)
            entityManager.flush()
            entryId = entry.id
            entityManager.persist(HofCookieEntity(account = account, name = "PHPSESSID", value = "test-session", updatedAt = clock.now()))
            entityManager.persist(HofStatusSnapshotEntity(account = account, playerName = "테스트", funds = 1,
                timeCurrent = 100, timeMax = 100, work = "", auction = "", observedAt = clock.now()))
            val character = CharacterEntity(account = account, hofCharacterId = characters.single(), name = "테스트", job = "Knight", updatedAt = clock.now())
            entityManager.persist(character)
            val pattern = CharacterPatternSlotEntity(character = character, slotCode = "1", label = "기본", canLoad = true)
            entityManager.persist(pattern)
            val preset = PartyPresetEntity(account = account, name = "낚시 파티", createdAt = clock.now(), updatedAt = clock.now(), isPrimary = true)
            entityManager.persist(preset)
            entityManager.persist(PartyPresetMemberEntity(preset, 0, character, pattern))
            entityManager.persist(TypedAutomationRuntimeStateEntity(accountId = accountId, account = account,
                lifecycleStatus = TypedAutomationLifecycle.RUNNING, createdAt = clock.now(), updatedAt = clock.now()))
        }
        Mockito.`when`(preflight.ensureReady(accountId)).thenReturn(AutomationDailyPreflight.Result.Ready)
        Mockito.`when`(authorization.isExecutionAllowed(accountId)).thenReturn(true)
    }

    @AfterEach
    fun removeAccount() {
        transport.delivered.forEach { jdbc.update("delete from automation_consumed_events where event_id = ?", it) }
        if (accountId != 0L) jdbc.update("delete from hof_accounts where id = ?", accountId)
    }

    // 설정값을 바꿔 읽는 검사와 구분해 실제 최초 행동의 수렴 경로를 확인한다.
    private fun assertBattleMode(identity: String) {
        val attempts = jdbc.queryForObject("select count(*) from automation_action_attempts where account_id = ? and execution_identity = ?",
            Int::class.java, accountId, identity)
        assertEquals(if (mode == AutomationConvergenceMode.ACTIVE) 1 else 0, attempts,
            "최초 전투는 Spring 생성 시 선택한 $mode 경로를 실행해야 한다.")
    }

    @ParameterizedTest
    @ValueSource(strings = ["NONE", "STOP", "PAUSE", "AUTH", "EXPIRED"])
    fun `캡차 해소 중 도착한 직접 전투 응답은 행동 상태와 성공 이력에 같은 사실을 남긴다`(control: String) {
        val submitted = CountDownLatch(1)
        val respond = CountDownLatch(1)
        setupFishing(beforeResponse = { request ->
            if (request.method == HofHttpMethod.POST && request.url.contains("common=fishing_12")) {
                submitted.countDown()
                check(respond.await(10, TimeUnit.SECONDS)) { "전투 응답 반환 허용을 기다렸습니다." }
            }
        })
        wakeups.wake(accountId, "CAPTCHA_DURING_BATTLE_RESPONSE")
        Executors.newSingleThreadExecutor().use { executor ->
            val consumed = executor.submit { publisher.publishBatch() }
            try {
                assertTrue(submitted.await(10, TimeUnit.SECONDS), "실제 전투 POST에 도달해야 한다.")
                val pending = runs().single()
                assertBattleMode(pending["execution_identity"] as String)
                assertEquals("SUBMITTING", pending["status"])
                assertNotNull(pending["submitted_at"])
                val lease = jdbc.queryForObject("select lease_token from typed_automation_runtime_states where account_id = ?",
                    String::class.java, accountId)
                assertNotNull(lease)
                val account = TransactionTemplate(transactions).execute {
                    entityManager.find(HofAccountEntity::class.java, accountId)
                }!!
                assertNotNull(captchaService.detectAndRecord(account,
                    "<div>자경단에서 통행증을 발급받아주세요.</div>", "https://hof.zerosic.com/index.php?police", false))
                if (control == "EXPIRED") clock.current = clock.now().plusSeconds(301)
                assertTrue(captchaService.resolveCurrentPassChallenge(accountId))
                assertEquals(1, battleRequests().size)
                assertEquals(if (control == "EXPIRED") null else lease, jdbc.queryForObject("select lease_token from typed_automation_runtime_states where account_id = ?",
                    String::class.java, accountId), "진행 중인 응답의 실행권은 캡차 해소가 폐기하지 않는다.")
                when (control) {
                    "STOP" -> application.stopTyped(accountId)
                    "PAUSE" -> application.pauseTyped(accountId)
                    "AUTH" -> {
                        Mockito.doReturn(false).`when`(authorization).isExecutionAllowed(accountId)
                        TransactionTemplate(transactions).executeWithoutResult {
                            lifecycle.suspendForAuthentication(accountId, "AUTH_EXPIRED_DURING_RESPONSE")
                        }
                    }
                }
                val intent = jdbc.queryForObject("select intent_revision from typed_automation_runtime_states where account_id = ?",
                    Long::class.java, accountId)
                respond.countDown()
                consumed.get(10, TimeUnit.SECONDS)

                val completed = runs().single()
                val history = journal.page(accountId, AutomationHistoryQuery()).cycles.single()
                assertEquals(1, history.events.count { it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED },
                    history.events.map { it.kind to it.message }.toString())
                assertEquals(pending["execution_identity"], completed["execution_identity"])
                assertEquals("SUCCEEDED", completed["status"], "직접 성공 응답과 성공 이력에 연결된 같은 행동을 FAILED로 남기지 않는다: $completed")
                if (mode == AutomationConvergenceMode.ACTIVE) {
                    val attemptId = jdbc.queryForObject("select id from automation_action_attempts where account_id = ? and execution_identity = ?",
                        Long::class.java, accountId, completed["execution_identity"])!!
                    val evidence = assertNotNull(store.get(attemptId))
                    assertEquals(ActionConvergenceResult.APPLIED, evidence.result)
                    assertEquals("DIRECT_RESPONSE_APPLIED", evidence.reasonCode)
                } else {
                    assertEquals(0, jdbc.queryForObject("select count(*) from automation_action_attempts where account_id = ?",
                        Int::class.java, accountId), "LEGACY/SHADOW의 직접 성공은 새 ACTIVE 행동 시도를 만들지 않는다.")
                }
                assertEquals(intent, jdbc.queryForObject("select intent_revision from typed_automation_runtime_states where account_id = ?",
                    Long::class.java, accountId))
                val shadowCount = jdbc.queryForObject("select count(*) from automation_convergence_shadow_evaluations where account_id = ?",
                    Int::class.java, accountId)!!
                assertEquals(mode == AutomationConvergenceMode.SHADOW, shadowCount > 0,
                    "SHADOW만 직접 전투의 비교 기록을 남겨야 한다.")
                assertEquals(0, runningWorkCount())
                if (control in setOf("STOP", "PAUSE", "AUTH")) {
                    assertEquals(if (control == "STOP") "STOPPED" else "PAUSED", jdbc.queryForObject("select lifecycle_status from typed_automation_runtime_states where account_id = ?",
                        String::class.java, accountId))
                    val count = requests.size
                    wakeups.wake(accountId, "STALE_WAKE_AFTER_STOP")
                    publisher.publishBatch()
                    assertEquals(count, requests.size, "직접 결과 보존이 사용자 정지를 되돌리거나 원격 제출을 재개하지 않는다.")
                    when (control) {
                        "STOP" -> application.startTyped(accountId)
                        "PAUSE" -> application.resumeTyped(accountId)
                        else -> {
                            Mockito.doReturn(true).`when`(authorization).isExecutionAllowed(accountId)
                            TransactionTemplate(transactions).executeWithoutResult {
                                assertTrue(lifecycle.resumeAfterAuthentication(accountId, "AUTH_RESTORED_AFTER_RESPONSE"))
                            }
                        }
                    }
                }
                consumeFishingWakeUntil { "FCatch" in fishingPosts() }
                assertEquals(listOf("FStart", "FCatch"), fishingPosts())
                assertEquals(1, requests.count { it.method == HofHttpMethod.POST && it.url.contains("common=fishing_12") })
                assertTrue(journal.page(accountId, AutomationHistoryQuery()).cycles.any { it.id != history.id })
            } finally {
                respond.countDown()
                consumed.get(10, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun `같은 행동을 새 실행권으로 복구하는 동안 늦은 직접 결과와 성공 이력을 보존한다`() =
        verifyLateBattleReacquisition(afterObservation = false)

    @ParameterizedTest
    @CsvSource("FStart, STOP", "FCatch, STOP", "FStart, PAUSE", "FCatch, PAUSE", "FStart, AUTH", "FCatch, AUTH")
    fun `낚시 응답 중 중단해도 적용된 단계의 결과와 성공 이력을 보존한다`(stage: String, control: String) {
        val submitted = CountDownLatch(1)
        val respond = CountDownLatch(1)
        setupFishing(initialBattle = false, beforeResponse = { request ->
            if (stage in request.formFields) {
                submitted.countDown()
                check(respond.await(30, TimeUnit.SECONDS))
            }
        })
        wakeups.wake(accountId, "STOP_DURING_FISHING_RESPONSE")
        Executors.newSingleThreadExecutor().use { executor ->
            val worker = executor.submit { publisher.publishBatch() }
            try {
                assertTrue(submitted.await(10, TimeUnit.SECONDS))
                val identity = runs().last()["execution_identity"]
                assertEquals("SUBMITTING", runs().last()["status"])
                when (control) {
                    "STOP" -> application.stopTyped(accountId)
                    "PAUSE" -> application.pauseTyped(accountId)
                    "AUTH" -> {
                        Mockito.doReturn(false).`when`(authorization).isExecutionAllowed(accountId)
                        TransactionTemplate(transactions).executeWithoutResult {
                            lifecycle.suspendForAuthentication(accountId, "AUTH_EXPIRED_DURING_FISHING_RESPONSE")
                        }
                    }
                }
                respond.countDown()
                worker.get(10, TimeUnit.SECONDS)
                val action = runs().single { it["execution_identity"] == identity }
                assertEquals("SUCCEEDED", action["status"], "정지 전에 제출한 직접 적용 결과를 잃지 않는다: $action")
                val expectedReason = if (stage == "FStart") "FISHING_START_APPLIED" else "FISHING_CATCH_APPLIED"
                val history = journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }
                assertEquals(1, history.count { it.reasonCode == expectedReason && it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED },
                    "저장 행동의 직접 성공과 같은 단계의 성공 이력을 함께 보존한다: ${history.map { it.reasonCode }}")
                assertEquals(if (control == "STOP") "STOPPED" else "PAUSED", jdbc.queryForObject("select lifecycle_status from typed_automation_runtime_states where account_id = ?",
                    String::class.java, accountId))
                assertEquals(if (stage == "FStart") listOf("FStart") else listOf("FStart", "FCatch"), fishingPosts())
                val requestCount = requests.size
                wakeups.wake(accountId, "STALE_WAKE_AFTER_FISHING_STOP")
                publisher.publishBatch()
                assertEquals(requestCount, requests.size)
                when (control) {
                    "STOP" -> application.startTyped(accountId)
                    "PAUSE" -> application.resumeTyped(accountId)
                    else -> {
                        Mockito.doReturn(true).`when`(authorization).isExecutionAllowed(accountId)
                        TransactionTemplate(transactions).executeWithoutResult {
                            assertTrue(lifecycle.resumeAfterAuthentication(accountId, "AUTH_RESTORED_AFTER_FISHING_RESPONSE"))
                        }
                    }
                }
                consumeFishingWakeUntil { "FCatch" in fishingPosts() }
                assertEquals(listOf("FStart", "FCatch"), fishingPosts(), "재개는 저장된 START를 재전송하지 않고 현재 CATCH를 이어간다.")
                assertEquals(0, runningWorkCount())
                val cycles = journal.page(accountId, AutomationHistoryQuery()).cycles.size
                consumeNextWake()
                assertTrue(journal.page(accountId, AutomationHistoryQuery()).cycles.size > cycles)
            } finally {
                respond.countDown()
                worker.get(10, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun `START 직접 응답에 CATCH form이 없으면 다음 wake의 관측으로 한 번만 잡는다`() {
        setupFishing(initialBattle = false, startWithoutCatch = true, beforeResponse = {})
        wakeups.wake(accountId, "FISHING_CATCH_FORM_PENDING")
        publisher.publishBatch()

        assertEquals(listOf("FStart"), fishingPosts())
        assertEquals("SUCCEEDED", runs().single()["status"])
        assertEquals(1, requests.count { it.method == HofHttpMethod.GET && it.url.contains("menu=fishing") },
            "START 응답의 form 부재를 같은 실행의 추가 GET으로 우회하지 않는다.")
        val firstCycle = journal.page(accountId, AutomationHistoryQuery()).cycles.single()
        assertTrue(firstCycle.events.any { it.reasonCode == "FISHING_WAITING_FOR_CATCH" })

        consumeFishingWakeUntil { "FCatch" in fishingPosts() }
        assertEquals(listOf("FStart", "FCatch"), fishingPosts())
        assertEquals(listOf("SUCCEEDED", "SUCCEEDED"), runs().map { it["status"] })
        assertEquals(2, runs().map { it["execution_identity"] }.distinct().size)
        assertEquals(0, runningWorkCount())
        val completedCycle = journal.page(accountId, AutomationHistoryQuery()).cycles.maxOf { it.id }
        consumeWakeUntilNextDecision(completedCycle)
        assertEquals(listOf("FStart", "FCatch"), fishingPosts())
    }

    @ParameterizedTest
    @ValueSource(strings = ["SAVE", "RESTORE"])
    fun `CATCH 준비가 실패해도 직접 적용된 START는 보존하고 최신 상태에서 잡기를 이어간다`(failure: String) {
        setupFishing(initialBattle = false, beforeResponse = {})
        var failed = false
        val placeholder = Mockito.mock(TypedAutomationActionRunEntity::class.java)
        val repositoryDelegate = Mockito.mockingDetails(actionCommands).mockCreationSettings.defaultAnswer
        if (failure == "RESTORE") {
            val storedPlaceholder = Mockito.mock(StoredTypedAutomationAction::class.java)
            Mockito.doAnswer { invocation ->
                val stored = invocation.getArgument<StoredTypedAutomationAction>(0)
                val restored = invocation.callRealMethod()
                if (!failed && (stored.payload as? StoredTypedActionPayload.FishingTown)?.action ==
                    app.spammy.hof.town.fishing.model.FishingAction.CATCH
                ) {
                    failed = true
                    throw IllegalStateException("CATCH restoration failed after START applied")
                }
                restored
            }.`when`(actionLifecycle).restoreVerified(
                Mockito.any(StoredTypedAutomationAction::class.java) ?: storedPlaceholder, Mockito.eq(accountId))
        } else Mockito.doAnswer { invocation ->
            val row = invocation.getArgument<TypedAutomationActionRunEntity>(0)
            // Spring Data의 추상 interface가 아니라 실제 repository proxy로 저장을 위임한다.
            val result = repositoryDelegate.answer(invocation)
            if (!failed && row.account.id == accountId &&
                (codec.decode(row.payloadJson).payload as? StoredTypedActionPayload.FishingTown)?.action ==
                    app.spammy.hof.town.fishing.model.FishingAction.CATCH
            ) {
                failed = true
                throw org.springframework.dao.DataAccessResourceFailureException("CATCH checkpoint write failed after insert")
            }
            result
        }.`when`(actionCommands).save(Mockito.any(TypedAutomationActionRunEntity::class.java) ?: placeholder)
        wakeups.wake(accountId, "FISHING_CATCH_CHECKPOINT_FAILURE")
        publisher.publishBatch()
        assertTrue(failed, "실제 CATCH $failure 지점의 실패에 도달해야 한다.")
        assertEquals(listOf("FStart"), fishingPosts())
        assertEquals("SUCCEEDED", runs().single()["status"], "CATCH 저장 실패를 이미 적용된 START의 결과 미확정으로 바꾸지 않는다: ${runs()}")
        val first = journal.page(accountId, AutomationHistoryQuery()).cycles.single()
        assertEquals(1, first.events.count { it.reasonCode == "FISHING_START_APPLIED" && it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED })
        assertTrue(first.events.none { it.reasonCode == "FISHING_STAGE_AMBIGUOUS" })
        consumeFishingWakeUntil { "FCatch" in fishingPosts() }
        assertEquals(listOf("FStart", "FCatch"), fishingPosts())
        assertEquals(0, runningWorkCount())
        assertTrue(journal.page(accountId, AutomationHistoryQuery()).cycles.any { it.id != first.id })
        consumeNextWake()
    }

    @Test
    fun `같은 실행권의 이전 START 종결은 제출 중인 CATCH 실행권을 해제하지 않는다`() {
        var startExecution: TypedRuntimeExecutionRight? = null
        var leaseBefore: String? = null
        var leaseAfter: String? = null
        var duplicateProjection: TypedRuntimeProjection? = null
        var accepted = 0
        val executionPlaceholder = Mockito.mock(TypedRuntimeExecutionRight::class.java)
        Mockito.doAnswer { invocation ->
            if (startExecution == null) startExecution = invocation.getArgument(0)
            invocation.callRealMethod()
        }.`when`(runtime).beginSubmission(Mockito.any(TypedRuntimeExecutionRight::class.java) ?: executionPlaceholder)
        setupFishing(initialBattle = false, beforeResponse = { request ->
            if ("FCatch" in request.formFields) {
                leaseBefore = jdbc.queryForObject("select lease_token from typed_automation_runtime_states where account_id = ?",
                    String::class.java, accountId)
                duplicateProjection = runtime.complete(requireNotNull(startExecution),
                    TypedRuntimeOutcome.ActionSucceeded("DUPLICATE_START_RESULT")) { accepted++ }
                leaseAfter = jdbc.queryForObject("select lease_token from typed_automation_runtime_states where account_id = ?",
                    String::class.java, accountId)
            }
        })
        wakeups.wake(accountId, "OLD_START_RESULT_DURING_CATCH")
        publisher.publishBatch()
        assertNotNull(leaseBefore, "실제 CATCH가 같은 실행권으로 제출 중이어야 한다.")
        assertEquals(leaseBefore, leaseAfter, "완료된 START의 재종결은 현재 CATCH 실행권을 놓지 않는다.")
        assertEquals(false, assertNotNull(duplicateProjection).applied)
        assertEquals(1, accepted, "현재 실행권을 변경하지 않아도 이전 단계의 직접 사실은 수용한다.")
        assertEquals(listOf("FStart", "FCatch"), fishingPosts())
        assertTrue(runs().all { it["status"] == "SUCCEEDED" })
        val history = journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }
        assertEquals(1, history.count { it.reasonCode == "FISHING_START_APPLIED" })
        assertEquals(1, history.count { it.reasonCode == "FISHING_CATCH_APPLIED" })
        assertEquals(0, runningWorkCount())
        consumeWakeUntilNextDecision(journal.page(accountId, AutomationHistoryQuery()).cycles.maxOf { it.id })
        assertEquals(listOf("FStart", "FCatch"), fishingPosts())
    }

    @Test
    fun `START 적용과 CATCH 준비를 확정한 뒤 성공 이력을 기록한다`() {
        val prepared = CountDownLatch(1)
        val continueCatch = CountDownLatch(1)
        val placeholder = Mockito.mock(TypedAutomationActionRunEntity::class.java)
        val repositoryDelegate = Mockito.mockingDetails(actionCommands).mockCreationSettings.defaultAnswer
        Mockito.doAnswer { invocation ->
            val row = invocation.getArgument<TypedAutomationActionRunEntity>(0)
            val saved = repositoryDelegate.answer(invocation)
            if ((codec.decode(row.payloadJson).payload as? StoredTypedActionPayload.FishingTown)?.action ==
                app.spammy.hof.town.fishing.model.FishingAction.CATCH
            ) {
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    object : org.springframework.transaction.support.TransactionSynchronization {
                        override fun afterCompletion(status: Int) {
                            if (status == org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED) {
                                prepared.countDown()
                                check(continueCatch.await(30, TimeUnit.SECONDS))
                            }
                        }
                    })
            }
            saved
        }.`when`(actionCommands).save(Mockito.any(TypedAutomationActionRunEntity::class.java) ?: placeholder)
        setupFishing(initialBattle = false, beforeResponse = {})
        wakeups.wake(accountId, "START_RESULT_WITH_CATCH_PREPARATION")
        Executors.newSingleThreadExecutor().use { executor ->
            val worker = executor.submit { publisher.publishBatch() }
            try {
                assertTrue(prepared.await(10, TimeUnit.SECONDS))
                assertEquals(listOf("SUCCEEDED", "PREPARED"), runs().map { it["status"] })
                val history = journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }
                assertTrue(history.none { it.reasonCode == "FISHING_START_APPLIED" },
                    "성공 이력은 runtime proxy가 commit을 마치고 반환한 뒤 기록한다.")
                if (mode == AutomationConvergenceMode.ACTIVE) {
                    assertEquals(ActionConvergenceResult.APPLIED, assertNotNull(store.get(accountId, runs().first()["execution_identity"] as String)).result)
                }
                continueCatch.countDown()
                worker.get(10, TimeUnit.SECONDS)
                assertEquals(1, journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }
                    .count { it.reasonCode == "FISHING_START_APPLIED" && it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED })
                assertEquals(listOf("FStart", "FCatch"), fishingPosts())
                assertTrue(runs().all { it["status"] == "SUCCEEDED" })
                assertEquals(0, runningWorkCount())
                consumeWakeUntilNextDecision(journal.page(accountId, AutomationHistoryQuery()).cycles.maxOf { it.id })
            } finally {
                continueCatch.countDown()
                worker.get(10, TimeUnit.SECONDS)
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["FISHING_START_APPLIED", "FISHING_CATCH_APPLIED", "TYPED_ACTION_COMPLETED"])
    fun `성공 이력 INSERT의 DB 실패는 확정한 행동과 다음 판단을 취소하지 않는다`(reason: String) {
        val battle = reason == "TYPED_ACTION_COMPLETED"
        var failed = false
        var failureSqlState: String? = null
        setupFishing(initialBattle = battle, beforeResponse = {})
        val placeholder = app.spammy.hof.automation.history.AutomationActionTrace(AutomationHistoryEventKind.WAITING, "matcher", "matcher")
        Mockito.doAnswer { invocation ->
            val trace = invocation.getArgument<app.spammy.hof.automation.history.AutomationActionTrace>(1)
            val result = invocation.callRealMethod()
            if (!failed && trace.reasonCode == reason) {
                entityManager.flush()
                failed = true
                // 실제 성공 이력 INSERT 후 같은 transaction에서 DB 제약 실패를 일으킨다.
                try {
                    entityManager.createNativeQuery("update automation_decision_events set reason_code = null where decision_cycle_id = :cycleId")
                        .setParameter("cycleId", invocation.getArgument<Long>(0)).executeUpdate()
                } catch (error: RuntimeException) {
                    failureSqlState = generateSequence<Throwable>(error) { it.cause }
                        .filterIsInstance<java.sql.SQLException>().firstOrNull()?.sqlState
                    throw error
                }
            }
            result
        }.`when`(journal).appendActionResult(Mockito.anyLong(),
            Mockito.any(app.spammy.hof.automation.history.AutomationActionTrace::class.java) ?: placeholder)
        wakeups.wake(accountId, "DIRECT_HISTORY_DB_FAILURE")
        publisher.publishBatch()
        assertTrue(failed, "실제 성공 이력 INSERT와 DB 제약 실패에 도달해야 한다.")
        assertEquals("23502", failureSqlState, "잘못된 fixture SQL과 NOT NULL 제약 실패를 구분한다.")
        assertTrue(runs().all { it["status"] == "SUCCEEDED" }, "이력 DB 실패로 확인한 직접 결과를 미확정으로 바꾸지 않는다: ${runs()}")
        if (mode == AutomationConvergenceMode.ACTIVE) {
            runs().forEach { row -> assertEquals(ActionConvergenceResult.APPLIED,
                assertNotNull(store.get(accountId, row["execution_identity"] as String)).result) }
        }
        val history = journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }
        assertTrue(history.none { it.reasonCode == reason }, "실패한 이력 INSERT는 독립 transaction에서 롤백된다.")
        assertTrue(history.none { it.reasonCode in setOf("FISHING_STAGE_AMBIGUOUS", "ACTION_FAILED", "TYPED_RECONCILIATION_BUDGET_EXHAUSTED") })
        consumeFishingWakeUntil { "FCatch" in fishingPosts() }
        assertEquals(listOf("FStart", "FCatch"), fishingPosts())
        assertEquals(if (battle) 1 else 0, battleRequests().size)
        assertEquals(0, runningWorkCount())
        consumeWakeUntilNextDecision(journal.page(accountId, AutomationHistoryQuery()).cycles.maxOf { it.id })
    }

    protected fun verifyLateCatchWithFailedObservation(failure: String, afterRecoveryCompletes: Boolean = false, afterGuard: Boolean = false, staleObservation: Boolean = false, duringCanonicalCreation: Boolean = false, staleHistory: Boolean = false, beforeReconciliationHistory: Boolean = false) {
        val recoverWithinObservationBudget = mode == AutomationConvergenceMode.ACTIVE && failure in setOf("ADVANCED", "CASTS_EXHAUSTED")
        if (recoverWithinObservationBudget) {
            val firstPreflight = java.util.concurrent.atomic.AtomicBoolean(true)
            Mockito.`when`(preflight.ensureReady(accountId)).thenAnswer {
                // 실행권 획득 뒤 준비에 시간을 쓰되 START/CATCH의 실제 제출 시각은 보존한다.
                if (firstPreflight.compareAndSet(true, false)) clock.current = clock.now().plusSeconds(240)
                AutomationDailyPreflight.Result.Ready
            }
        }
        val catchReturned = CountDownLatch(1)
        val returnCatch = CountDownLatch(1)
        val observationRead = CountDownLatch(1)
        val saveObservation = CountDownLatch(1)
        val observing = CountDownLatch(1)
        val failObservation = CountDownLatch(1)
        val guardReached = CountDownLatch(1)
        val continueGuard = CountDownLatch(1)
        val canonicalCreated = CountDownLatch(1)
        val commitCanonical = CountDownLatch(1)
        val directLockRequested = CountDownLatch(1)
        val directThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        val pauseAfterGuard = java.util.concurrent.atomic.AtomicBoolean(false)
        if (beforeReconciliationHistory) pauseBeforeReconciliationHistory(pauseAfterGuard, guardReached, continueGuard)
        else if (afterGuard) pauseAfterResultCheck(pauseAfterGuard, guardReached, continueGuard)
        val failNextObservation = java.util.concurrent.atomic.AtomicBoolean(true)
        setupFishing(initialBattle = false, battleAfterCatch = failure == "ADVANCED", beforeResponse = { request ->
            if (catchReturned.count == 0L && request.method == HofHttpMethod.GET && request.url.contains("menu=fishing") &&
                failNextObservation.compareAndSet(true, false)
            ) {
                observing.countDown()
                check(failObservation.await(30, TimeUnit.SECONDS))
                if (failure == "503") throw app.spammy.hof.external.client.HofAutomationDeferredException(
                    clock.now().plusSeconds(5), 1, requestAttempted = true, actionSubmissionAttempted = false,
                )
                if (failure !in setOf("ADVANCED", "CASTS_EXHAUSTED")) throw java.io.IOException("Fishing observation response failed")
            }
        })
        val boundary = app.spammy.hof.town.common.service.TownSubmissionBoundary { it() }
        val beforeCatch: (app.spammy.hof.town.fishing.dto.FishingResponse) -> Unit = {}
        if (duringCanonicalCreation) {
            val managedPlaceholder = Mockito.mock(ManagedFishingAutomationAction::class.java)
            Mockito.doAnswer { invocation ->
                val result = invocation.callRealMethod()
                if (fishingPosts().lastOrNull() == "FCatch") {
                    // 도메인 후처리가 runtime 잠금을 놓은 뒤 직접 귀속만 보류한다.
                    directThread.set(Thread.currentThread())
                    catchReturned.countDown()
                    check(returnCatch.await(30, TimeUnit.SECONDS))
                }
                result
            }.`when`(results).applyFishingDirect(
                Mockito.any(ManagedFishingAutomationAction::class.java) ?: managedPlaceholder,
                Mockito.any(TypedAutomationExecution::class.java) ?: TypedAutomationExecution.Completed,
                Mockito.any(), Mockito.any(), Mockito.anyString(),
            )
            Mockito.doAnswer { invocation ->
                if (Thread.currentThread() == directThread.get()) directLockRequested.countDown()
                invocation.callRealMethod()
            }.`when`(runtimeQueries).lockRuntimeState(accountId)
        } else Mockito.doAnswer { invocation ->
            // 실제 START/CATCH 응답 파싱과 계정 요청 잠금 해제 뒤 반환만 지연한다.
            val result = invocation.callRealMethod()
            catchReturned.countDown()
            check(returnCatch.await(30, TimeUnit.SECONDS))
            result
        }.`when`(fishing).executeOneCastForAutomation(
            Mockito.eq(accountId),
            Mockito.any<app.spammy.hof.town.fishing.service.FishingAutomationObservation>(),
            Mockito.any<app.spammy.hof.town.common.service.TownSubmissionBoundary>() ?: boundary,
            Mockito.any<(app.spammy.hof.town.fishing.dto.FishingResponse) -> Unit>() ?: beforeCatch,
        )
        wakeups.wake(accountId, "LATE_CATCH_WITH_FAILED_OBSERVATION")
        Executors.newFixedThreadPool(2).use { executor ->
            val otherTransport = AutomationRecoveryIntegrationTest.Config().consumerReplayTransport(mapper, consumedEvents, consumerLease, runner, clock)
            val otherPublisher = AutomationOutboxPublisher(outbox, publishedMarker, otherTransport, clock)
            val oldWorker = executor.submit { publisher.publishBatch() }
            var recoveryWorker: java.util.concurrent.Future<*>? = null
            try {
                assertTrue(catchReturned.await(10, TimeUnit.SECONDS))
                markBrokerAcceptedWake("LATE_CATCH_WITH_FAILED_OBSERVATION")
                val identity = runs().last()["execution_identity"]
                assertEquals("SUBMITTING", runs().last()["status"])
                assertEquals(listOf("FStart", "FCatch"), fishingPosts())
                if (staleHistory) {
                    val tracePlaceholder = app.spammy.hof.automation.history.AutomationActionTrace(AutomationHistoryEventKind.WAITING, "matcher", "matcher")
                    Mockito.doAnswer { invocation ->
                        val trace = invocation.getArgument<app.spammy.hof.automation.history.AutomationActionTrace>(1)
                        if (trace.reasonCode == "FISHING_RESULT_STATE_ADVANCED") {
                            observationRead.countDown()
                            check(saveObservation.await(30, TimeUnit.SECONDS))
                        }
                        invocation.callRealMethod()
                    }.`when`(journal).appendResultObservation(Mockito.eq(accountId),
                        Mockito.any(app.spammy.hof.automation.history.AutomationActionTrace::class.java) ?: tracePlaceholder)
                }
                if (duringCanonicalCreation) {
                    val selectionPlaceholder = Mockito.mock(SelectedAutomationAction::class.java)
                    Mockito.doAnswer { invocation ->
                        val result = invocation.callRealMethod()
                        canonicalCreated.countDown()
                        check(commitCanonical.await(30, TimeUnit.SECONDS))
                        result
                    }.`when`(store).createOrGet(Mockito.eq(accountId),
                        Mockito.any(SelectedAutomationAction::class.java) ?: selectionPlaceholder,
                        Mockito.any(Instant::class.java) ?: Instant.EPOCH)
                }
                if (staleObservation) {
                    val recordPlaceholder = Mockito.mock(ActionConvergenceRecord::class.java)
                    val evidencePlaceholder = AutomationActionEvidence.StateAdvanced(Instant.EPOCH, "placeholder")
                    Mockito.doAnswer { invocation ->
                        val record = invocation.getArgument<ActionConvergenceRecord>(0)
                        if (record.accountId == accountId && invocation.getArgument<AutomationActionEvidence>(1) is AutomationActionEvidence.StateAdvanced) {
                            // 실제 수렴 상태 조회 이후, 증거 INSERT와 결과 저장 직전의 순서를 고정한다.
                            observationRead.countDown()
                            check(saveObservation.await(30, TimeUnit.SECONDS))
                        }
                        invocation.callRealMethod()
                    }.`when`(evidenceCases).record(
                        Mockito.any(ActionConvergenceRecord::class.java) ?: recordPlaceholder,
                        Mockito.any(AutomationActionEvidence::class.java) ?: evidencePlaceholder,
                        Mockito.anyString())
                }
                if (recoverWithinObservationBudget) {
                    val typedLeaseUntil = assertNotNull(jdbc.queryForObject(
                        "select lease_until from typed_automation_runtime_states where account_id = ?",
                        java.sql.Timestamp::class.java, accountId,
                    )).toInstant()
                    val accountLeaseUntil = assertNotNull(jdbc.queryForObject(
                        "select lease_until from account_automation_leases where account_id = ?",
                        java.sql.Timestamp::class.java, accountId,
                    )).toInstant()
                    clock.current = maxOf(typedLeaseUntil, accountLeaseUntil).plusSeconds(1)
                    val submittedAt = assertNotNull(jdbc.queryForObject(
                        "select submitted_at from typed_automation_action_runs where account_id = ? and execution_identity = ?",
                        java.sql.Timestamp::class.java, accountId, identity,
                    )).toInstant()
                    assertEquals(61L, java.time.Duration.between(submittedAt, clock.now()).seconds,
                        "두 실행권은 만료되지만 CATCH 관측의 2분 예산은 남아 있어야 한다.")
                } else {
                    clock.current = clock.now().plusSeconds(301)
                }
                wakeups.wake(accountId, "EXPIRED_CATCH_LEASE_RECOVERY")
                recoveryWorker = executor.submit {
                    repeat(4) {
                        otherPublisher.publishBatch()
                        if (observing.count == 0L) return@submit
                        // 실제 예약을 소비하며 저장 행동의 재관측에 도달한다.
                        advanceToNextWake()
                    }
                }
                assertTrue(observing.await(10, TimeUnit.SECONDS), "실제 낚시 재조회가 외부 HOF adapter에 도달해야 한다.")
                assertEquals("RECONCILING", runs().last()["status"])
                val newLease = jdbc.queryForObject("select lease_token from typed_automation_runtime_states where account_id = ?",
                    String::class.java, accountId)
                assertNotNull(newLease)
                if (afterGuard || beforeReconciliationHistory) {
                    pauseAfterGuard.set(true)
                    failObservation.countDown()
                    assertTrue(guardReached.await(10, TimeUnit.SECONDS), "정상 관측 뒤 종결 확인의 commit에 도달해야 한다.")
                }
                if (afterRecoveryCompletes) {
                    failObservation.countDown()
                    if (staleObservation || staleHistory) {
                        // 첫 복구 wake가 이미 probe를 실행한다. 관측 저장을 멈춘 채 직접 응답을 반환한다.
                        assertTrue(observationRead.await(10, TimeUnit.SECONDS), "실제 probe가 현재 수렴을 읽고 상태 진전을 판정해야 한다.")
                    } else {
                        recoveryWorker.get(10, TimeUnit.SECONDS)
                        assertEquals("AMBIGUOUS", runs().single { it["execution_identity"] == identity }["status"])
                        if (failure in setOf("ADVANCED", "CASTS_EXHAUSTED")) {
                            assertEquals(ActionConvergenceResult.SUPERSEDED, assertNotNull(store.get(accountId, identity as String)).result,
                                "실제 후속 probe의 관측 결과를 먼저 저장해야 한다: ${store.get(accountId, identity)}")
                        } else {
                            assertTrue(store.findSuppressedBaselines(accountId).isNotEmpty())
                        }
                    }
                }
                if (duringCanonicalCreation) {
                    failObservation.countDown()
                    assertTrue(canonicalCreated.await(10, TimeUnit.SECONDS), "복구가 첫 수렴 기록을 저장하고 commit 직전에 도달해야 한다.")
                }
                returnCatch.countDown()
                if (duringCanonicalCreation) {
                    assertTrue(directLockRequested.await(10, TimeUnit.SECONDS), "직접 결과의 runtime 잠금 진입 뒤 첫 수렴 저장을 commit한다.")
                    commitCanonical.countDown()
                }
                if (staleObservation || staleHistory) {
                    try {
                        oldWorker.get(5, TimeUnit.SECONDS)
                    } catch (_: java.util.concurrent.TimeoutException) {
                        // 판정·저장을 잠근 구현에서는 직접 결과가 그 잠금을 기다리는 순서도 허용한다.
                    } finally {
                        saveObservation.countDown()
                    }
                }
                oldWorker.get(10, TimeUnit.SECONDS)
                assertEquals("SUCCEEDED", runs().single { it["execution_identity"] == identity }["status"])
                if (!afterRecoveryCompletes && !duringCanonicalCreation && !beforeReconciliationHistory) {
                    assertEquals(newLease, jdbc.queryForObject("select lease_token from typed_automation_runtime_states where account_id = ?",
                        String::class.java, accountId))
                }
                failObservation.countDown()
                continueGuard.countDown()
                recoveryWorker.get(10, TimeUnit.SECONDS)
                val history = journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }
                assertEquals(1, history.count { it.reasonCode == "FISHING_CATCH_APPLIED" && it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED })
                if (staleHistory) {
                    val successId = history.single { it.reasonCode == "FISHING_CATCH_APPLIED" }.id
                    assertTrue(history.none { it.id > successId && it.reasonCode == "FISHING_RESULT_STATE_ADVANCED" },
                        "직접 성공 뒤 오래된 probe의 행동 대체 이력을 추가하지 않는다.")
                }
                if (afterGuard || beforeReconciliationHistory) {
                    val successId = history.single { it.reasonCode == "FISHING_CATCH_APPLIED" }.id
                    assertTrue(history.none { it.id > successId && it.reasonCode == "ACTION_SUPERSEDED_BY_FRESH_STATE" },
                        "늦은 직접 성공 뒤 오래된 상태 진전 관측으로 대체 이력을 추가하지 않는다.")
                }
                if (afterRecoveryCompletes || duringCanonicalCreation) {
                    assertEquals(ActionConvergenceResult.APPLIED, assertNotNull(store.get(accountId, identity as String)).result)
                } else {
                    assertTrue(history.none { it.reasonCode == "TYPED_RECONCILIATION_BUDGET_EXHAUSTED" },
                        "직접 성공 뒤 실패한 재관측으로 미관측 종결 이력을 추가하지 않는다: ${history.map { it.reasonCode }}")
                }
                assertTrue(store.findSuppressedBaselines(accountId).isEmpty(), "직접 적용된 CATCH를 미관측 보류하지 않는다.")
                // 실패는 한 번만 주입하며 이후 관측도 실제 CATCH 이후 상태를 유지한다.
                val latest = journal.page(accountId, AutomationHistoryQuery()).cycles.maxOf { it.id }
                consumeWakeUntilNextDecision(latest)
                assertEquals(0, runningWorkCount())
                assertEquals(listOf("FStart", "FCatch"), fishingPosts())
            } finally {
                returnCatch.countDown()
                failObservation.countDown()
                continueGuard.countDown()
                saveObservation.countDown()
                commitCanonical.countDown()
                oldWorker.get(10, TimeUnit.SECONDS)
                recoveryWorker?.get(10, TimeUnit.SECONDS)
                otherTransport.delivered.forEach { jdbc.update("delete from automation_consumed_events where event_id = ?", it) }
            }
        }
    }

    protected fun verifyHomeStateAdvancedBeforeDirect() {
        val applied = CountDownLatch(1)
        val returnDirect = CountDownLatch(1)
        var accepted = false
        val url = "https://hof.zerosic.com/index.php?menu=housing"
        fun page() = """<div id="menu2">Funds : $ 1 Time : 100/100</div>
            <h4>${if (accepted) "진행중인 작업 목록" else "수락 가능한 작업 목록"}</h4><table><tr><td>[A] 작업 A</td><td>미션 0/1</td><td>-</td><td>-</td>
            <td>${if (accepted) "-" else "<a href='?menu=housing&amp;action=get&amp;no=A'>수락</a>"}</td></tr></table>"""
        val quest = app.spammy.hof.town.home.parser.HomePageParser().parse(
            app.spammy.hof.town.home.model.HomeMode.HOME, page(), url,
            app.spammy.hof.town.common.parser.HofFormParser().parse(page(), url),
        ).quests.single()
        TransactionTemplate(transactions).executeWithoutResult {
            val entry = entityManager.find(AutomationEntryEntity::class.java, entryId)
            entry.type = AutomationType.HOME_QUEST
            entry.singletonTypeMarker = AutomationType.HOME_QUEST
            entityManager.persist(HomeQuestAutomationSelectionEntity(entry = entry,
                questId = quest.id, questName = quest.name, enabled = true, sourceOrder = 0))
        }
        Mockito.doAnswer { invocation ->
            val request = invocation.getArgument<HofRequest>(1)
            requests += request
            if (request.formFields["action"] == "get") accepted = true
            HofHttpResponse(200, url, page(), emptyMap())
        }.`when`(gateway).execute(Mockito.eq(accountId), Mockito.any<HofRequest>()
            ?: HofRequest(HofHttpMethod.GET, url), Mockito.anyMap())
        val managedPlaceholder = Mockito.mock(ManagedAutomationAction::class.java)
        Mockito.doAnswer { invocation ->
            val result = invocation.callRealMethod()
            applied.countDown()
            check(returnDirect.await(30, TimeUnit.SECONDS))
            result
        }.`when`(results).applyDirect(Mockito.any(ManagedAutomationAction::class.java) ?: managedPlaceholder,
            Mockito.any(TypedAutomationExecution::class.java) ?: TypedAutomationExecution.Completed, Mockito.any(), Mockito.any())
        wakeups.wake(accountId, "HOME_DIRECT_AFTER_RECONCILIATION")
        Executors.newFixedThreadPool(2).use { executor ->
            val otherTransport = AutomationRecoveryIntegrationTest.Config().consumerReplayTransport(mapper, consumedEvents, consumerLease, runner, clock)
            val otherPublisher = AutomationOutboxPublisher(outbox, publishedMarker, otherTransport, clock)
            val oldWorker = executor.submit { publisher.publishBatch() }
            try {
                assertTrue(applied.await(10, TimeUnit.SECONDS), "실제 자택 수락 응답과 도메인 후처리를 완료해야 한다.")
                markBrokerAcceptedWake("HOME_DIRECT_AFTER_RECONCILIATION")
                val originalCycle = journal.page(accountId, AutomationHistoryQuery()).cycles.single().id
                assertEquals("SUBMITTING", runs().single()["status"])
                clock.current = clock.now().plusSeconds(301)
                wakeups.wake(accountId, "HOME_ACCEPT_RECOVERY")
                executor.submit { otherPublisher.publishBatch() }.get(10, TimeUnit.SECONDS)
                assertEquals("FAILED", runs().single()["status"], "최신 ACTIVE 관측은 성공 귀속 없이 기존 수락을 대체한다.")
                val observedEvents = journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }
                assertTrue(observedEvents.any { it.reasonCode == "ACTION_SUPERSEDED_BY_FRESH_STATE" })
                assertTrue(observedEvents.none { it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED })
                returnDirect.countDown()
                oldWorker.get(10, TimeUnit.SECONDS)
                val history = journal.page(accountId, AutomationHistoryQuery())
                assertEquals(1, history.cycles.single { it.id == originalCycle }.events.count {
                    it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED && it.reasonCode == "TYPED_ACTION_COMPLETED"
                }, "관측으로 대체된 뒤에도 원래 직접 응답의 성공 이력을 한 번 기록한다.")
                val latest = history.cycles.maxOf { it.id }
                consumeWakeUntilNextDecision(latest)
                assertEquals(1, requests.count { it.formFields["action"] == "get" })
                assertEquals(0, runningWorkCount())
            } finally {
                returnDirect.countDown()
                oldWorker.get(10, TimeUnit.SECONDS)
                otherTransport.delivered.forEach { jdbc.update("delete from automation_consumed_events where event_id = ?", it) }
            }
        }
    }

    protected fun verifyCanonicalDirectRollback(kind: String, failAfterCallback: Boolean = false, resumeAfterRollback: Boolean = false) {
        val actionKind = AutomationActionKind.valueOf(kind)
        var failed = false
        var failureSqlState: String? = null
        var directWrites = 0
        setupFishing(initialBattle = actionKind.battle, beforeResponse = {})
        val recordPlaceholder = Mockito.mock(ActionConvergenceRecord::class.java)
        val evidencePlaceholder = AutomationActionEvidence.DirectApplied(Instant.EPOCH, "placeholder")
        Mockito.doAnswer { invocation ->
            val record = invocation.getArgument<ActionConvergenceRecord>(0)
            val evidence = invocation.getArgument<AutomationActionEvidence>(1)
            val result = invocation.callRealMethod()
            if (record.accountId == accountId && record.selection.actionKind == actionKind && evidence is AutomationActionEvidence.DirectApplied) {
                directWrites++
                if (!failed) {
                    entityManager.flush()
                    failed = true
                    if (failAfterCallback) {
                        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                            object : org.springframework.transaction.support.TransactionSynchronization {
                                override fun beforeCommit(readOnly: Boolean) {
                                    throw IllegalStateException("Before-commit fixture failure after direct callback completed")
                                }
                                override fun afterCompletion(status: Int) {
                                    if (resumeAfterRollback) {
                                        check(status == org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK)
                                        Executors.newSingleThreadExecutor().use { executor ->
                                            executor.submit {
                                                application.pauseTyped(accountId)
                                                application.resumeTyped(accountId)
                                            }.get(10, TimeUnit.SECONDS)
                                        }
                                    }
                                }
                            })
                        return@doAnswer result
                    }
                    try {
                        entityManager.createNativeQuery("update automation_evidence_cases set policy_version = null where attempt_id = :attemptId")
                            .setParameter("attemptId", record.attemptId).executeUpdate()
                    } catch (error: RuntimeException) {
                        failureSqlState = generateSequence<Throwable>(error) { it.cause }
                            .filterIsInstance<java.sql.SQLException>().firstOrNull()?.sqlState
                        throw error
                    }
                }
            }
            result
        }.`when`(evidenceCases).record(Mockito.any(ActionConvergenceRecord::class.java) ?: recordPlaceholder,
            Mockito.any(AutomationActionEvidence::class.java) ?: evidencePlaceholder, Mockito.anyString())
        wakeups.wake(accountId, "DIRECT_CANONICAL_ROLLBACK")
        val publication = runCatching { publisher.publishBatch() }
        if (resumeAfterRollback) {
            assertTrue(failed)
            assertNull(publication.exceptionOrNull())
            assertEquals(1, directWrites)
            assertEquals("SUBMITTING", runs().last()["status"])
            assertEquals(ActionConvergenceResult.PENDING,
                assertNotNull(store.get(accountId, runs().last()["execution_identity"] as String)).result)
            assertTrue(journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }
                .none { it.reasonCode == "FISHING_START_APPLIED" },
                "rollback된 callback만으로 START 성공 이력을 기록하지 않는다.")
            val identity = runs().last()["execution_identity"] as String
            repeat(4) {
                if (store.get(accountId, identity)?.active == true) consumeNextWake()
            }
            assertEquals(ActionConvergenceResult.SUPERSEDED, assertNotNull(store.get(accountId, identity)).result,
                "rollback된 직접 증거 없이 최신 관측만으로 START 성공을 추정하지 않는다.")
            assertEquals(listOf("FStart", "FCatch"), fishingPosts(), "START 재제출 없이 최신 CATCH 단계까지 이어간다.")
            assertTrue(journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }
                .none { it.reasonCode == "FISHING_START_APPLIED" },
                "복구 뒤에도 rollback된 START의 성공 이력을 추정하지 않는다.")
            consumeWakeUntilNextDecision(journal.page(accountId, AutomationHistoryQuery()).cycles.maxOf { it.id })
            assertEquals(0, runningWorkCount())
            return
        }
        if (failAfterCallback) {
            assertTrue(failed)
            assertNotNull(publication.exceptionOrNull(), "commit 단계 오류는 직접 callback rollback 재시도 대상으로 삼지 않는다.")
            assertEquals(1, directWrites)
            assertEquals("SUBMITTING", runs().last()["status"])
            assertEquals(ActionConvergenceResult.PENDING,
                assertNotNull(store.get(accountId, runs().last()["execution_identity"] as String)).result)
            val history = journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }
            assertTrue(history.none { it.reasonCode in setOf("TYPED_ACTION_COMPLETED", "FISHING_CATCH_APPLIED", "FISHING_STAGE_AMBIGUOUS", "ACTION_FAILED") })
            assertEquals(if (actionKind.battle) 1 else 0, battleRequests().size)
            return
        }
        assertEquals("23502", failureSqlState)
        assertNull(publication.exceptionOrNull())
        assertTrue(runs().all { it["status"] == "SUCCEEDED" }, "한 번 rollback된 직접 증거만 다시 저장하고 원래 성공을 보존한다: ${runs()}")
        assertEquals(2, directWrites, "원격 행동 대신 같은 직접 증거 저장만 한 번 다시 시도한다.")
        val reason = when (actionKind) {
            AutomationActionKind.FISHING_START -> "FISHING_START_APPLIED"
            AutomationActionKind.FISHING_CATCH -> "FISHING_CATCH_APPLIED"
            else -> "TYPED_ACTION_COMPLETED"
        }
        assertEquals(1, journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }.count { it.reasonCode == reason })
        runs().forEach { row -> assertEquals(ActionConvergenceResult.APPLIED,
            assertNotNull(store.get(accountId, row["execution_identity"] as String)).result) }
        consumeFishingWakeUntil { "FCatch" in fishingPosts() }
        assertEquals(listOf("FStart", "FCatch"), fishingPosts())
        assertEquals(if (actionKind.battle) 1 else 0, battleRequests().size)
        assertEquals(0, runningWorkCount())
        assertTrue(store.findSuppressedBaselines(accountId).isEmpty())
        consumeWakeUntilNextDecision(journal.page(accountId, AutomationHistoryQuery()).cycles.maxOf { it.id })
    }

    protected fun verifyReconciliation503HistoryFailure() {
        var failureSqlState: String? = null
        val placeholder = app.spammy.hof.automation.history.AutomationActionTrace(AutomationHistoryEventKind.WAITING, "matcher", "matcher")
        Mockito.doAnswer { invocation ->
            val trace = invocation.getArgument<app.spammy.hof.automation.history.AutomationActionTrace>(1)
            val result = invocation.callRealMethod()
            if (failureSqlState == null && trace.reasonCode == "RECONCILIATION_HOF_DEFERRED") {
                entityManager.flush()
                try {
                    entityManager.createNativeQuery("update automation_decision_events set reason_code = null where decision_cycle_id = :cycleId")
                        .setParameter("cycleId", invocation.getArgument<Long>(0)).executeUpdate()
                } catch (error: RuntimeException) {
                    failureSqlState = generateSequence<Throwable>(error) { it.cause }
                        .filterIsInstance<java.sql.SQLException>().firstOrNull()?.sqlState
                    throw error
                }
            }
            result
        }.`when`(journal).appendActionResult(Mockito.anyLong(),
            Mockito.any(app.spammy.hof.automation.history.AutomationActionTrace::class.java) ?: placeholder)
        val responseLost = java.util.concurrent.atomic.AtomicBoolean(false)
        val deferred = java.util.concurrent.atomic.AtomicBoolean(false)
        setupFishing(initialBattle = false, beforeResponse = { request ->
            if (responseLost.get() && request.method == HofHttpMethod.GET && request.url.contains("menu=fishing") &&
                deferred.compareAndSet(false, true)
            ) throw app.spammy.hof.external.client.HofAutomationDeferredException(
                clock.now().plusSeconds(5), 1, requestAttempted = true, actionSubmissionAttempted = false)
        })
        val boundary = app.spammy.hof.town.common.service.TownSubmissionBoundary { it() }
        val beforeCatch: (app.spammy.hof.town.fishing.dto.FishingResponse) -> Unit = {}
        Mockito.doAnswer { invocation ->
            val result = invocation.callRealMethod()
            if (responseLost.compareAndSet(false, true)) throw java.io.IOException("CATCH response lost after remote application")
            result
        }.`when`(fishing).executeOneCastForAutomation(Mockito.eq(accountId),
            Mockito.any<app.spammy.hof.town.fishing.service.FishingAutomationObservation>(),
            Mockito.any<app.spammy.hof.town.common.service.TownSubmissionBoundary>() ?: boundary,
            Mockito.any<(app.spammy.hof.town.fishing.dto.FishingResponse) -> Unit>() ?: beforeCatch)
        wakeups.wake(accountId, "RECONCILIATION_503_HISTORY_FAILURE")
        publisher.publishBatch()
        assertTrue(responseLost.get())
        assertEquals("RECONCILING", runs().last()["status"])
        consumeNextWake()
        assertEquals("23502", failureSqlState)
        assertEquals("RECONCILING", runs().last()["status"])
        assertNull(jdbc.queryForObject("select lease_token from typed_automation_runtime_states where account_id = ?",
            String::class.java, accountId), "이력 저장 실패 뒤에도 재관측 대기의 실행권을 놓는다.")
        repeat(4) { if (runs().last()["status"] == "RECONCILING") consumeNextWake() }
        assertEquals(listOf("SUCCEEDED", "FAILED"), runs().map { it["status"] })
        val observedEvents = journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }
        assertTrue(observedEvents.any { it.reasonCode == "ACTION_SUPERSEDED_BY_FRESH_STATE" })
        assertTrue(observedEvents.none { it.actionKind == "CATCH" && it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED })
        assertEquals(listOf("FStart", "FCatch"), fishingPosts())
        assertEquals(0, runningWorkCount())
        assertTrue(journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }
            .none { it.reasonCode == "RECONCILIATION_HOF_DEFERRED" })
        consumeWakeUntilNextDecision(journal.page(accountId, AutomationHistoryQuery()).cycles.maxOf { it.id })
    }

    protected fun verifyProbeHistoryFailure() {
        var failureSqlState: String? = null
        val placeholder = app.spammy.hof.automation.history.AutomationActionTrace(AutomationHistoryEventKind.WAITING, "matcher", "matcher")
        Mockito.doAnswer { invocation ->
            val trace = invocation.getArgument<app.spammy.hof.automation.history.AutomationActionTrace>(1)
            val result = invocation.callRealMethod()
            if (failureSqlState == null && trace.reasonCode == "FISHING_RESULT_STATE_ADVANCED") {
                entityManager.flush()
                try {
                    entityManager.createNativeQuery("update automation_decision_events set reason_code = null where decision_cycle_id = :cycleId")
                        .setParameter("cycleId", result as Long).executeUpdate()
                } catch (error: RuntimeException) {
                    failureSqlState = generateSequence<Throwable>(error) { it.cause }
                        .filterIsInstance<java.sql.SQLException>().firstOrNull()?.sqlState
                    throw error
                }
            }
            result
        }.`when`(journal).appendResultObservation(Mockito.eq(accountId),
            Mockito.any(app.spammy.hof.automation.history.AutomationActionTrace::class.java) ?: placeholder)
        verifyLateCatchWithFailedObservation("ADVANCED", afterRecoveryCompletes = true)
        assertEquals("23502", failureSqlState)
        assertTrue(journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }
            .none { it.reasonCode == "FISHING_RESULT_STATE_ADVANCED" }, "실패한 관측 이력만 rollback되고 수렴·후속 판단은 유지된다.")
    }

    protected fun verifyShadowRecordingFailure() {
        val failed = java.util.concurrent.atomic.AtomicBoolean(false)
        val placeholder = Mockito.mock(DurableShadowEvaluation::class.java)
        Mockito.doAnswer { invocation ->
            val evaluation = invocation.getArgument<DurableShadowEvaluation>(0)
            val result = invocation.callRealMethod()
            if (evaluation.accountId == accountId && evaluation.legacyDecision == LegacyConvergenceDecision.RESULT_UNOBSERVED &&
                failed.compareAndSet(false, true)
            ) {
                entityManager.flush()
                // 실제 비교 기록 INSERT 뒤 DB 제약 위반을 주입한다.
                entityManager.createNativeQuery("update automation_convergence_shadow_evaluations set policy_version = null where account_id = :accountId")
                    .setParameter("accountId", accountId).executeUpdate()
            }
            result
        }.`when`(shadowRecorder).record(Mockito.any(DurableShadowEvaluation::class.java) ?: placeholder)
        verifyLateBattleReacquisition(afterObservation = false, afterRecoveryCompletes = true)
        assertTrue(failed.get(), "복구 결과의 실제 SHADOW 저장 transaction에서 실패해야 한다.")
    }

    protected fun verifyLateBattleReacquisition(afterObservation: Boolean, afterRecoveryCompletes: Boolean = false, afterGuard: Boolean = false, duringCanonicalCreation: Boolean = false) {
        val battleReturned = CountDownLatch(1)
        val returnBattle = CountDownLatch(1)
        val reacquired = CountDownLatch(1)
        val continueRecovery = CountDownLatch(1)
        val directLockRequested = CountDownLatch(1)
        val directThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        val pauseAfterGuard = java.util.concurrent.atomic.AtomicBoolean(false)
        val failNextObservation = java.util.concurrent.atomic.AtomicBoolean(afterRecoveryCompletes || duringCanonicalCreation)
        setupFishing(beforeResponse = { request ->
            if (battleReturned.count == 0L && request.method == HofHttpMethod.GET &&
                request.url.contains("menu=fishing") && failNextObservation.compareAndSet(true, false)
            ) throw java.io.IOException("Battle recovery observation response failed")
        })
        if (duringCanonicalCreation) {
            val managedPlaceholder = Mockito.mock(ManagedAutomationAction::class.java)
            Mockito.doAnswer { invocation ->
                val result = invocation.callRealMethod()
                directThread.set(Thread.currentThread())
                battleReturned.countDown()
                check(returnBattle.await(30, TimeUnit.SECONDS))
                result
            }.`when`(results).applyDirect(
                Mockito.any(ManagedAutomationAction::class.java) ?: managedPlaceholder,
                Mockito.any(TypedAutomationExecution::class.java) ?: TypedAutomationExecution.Completed,
                Mockito.any(), Mockito.any(),
            )
            Mockito.doAnswer { invocation ->
                if (Thread.currentThread() == directThread.get()) directLockRequested.countDown()
                invocation.callRealMethod()
            }.`when`(runtimeQueries).lockRuntimeState(accountId)
        } else Mockito.doAnswer { invocation ->
            val result = invocation.callRealMethod()
            battleReturned.countDown()
            check(returnBattle.await(30, TimeUnit.SECONDS))
            result
        }.`when`(battleRuns).runBattle(Mockito.eq(accountId),
            Mockito.any(app.spammy.hof.battle.dto.RunBattleRequest::class.java)
                ?: app.spammy.hof.battle.dto.RunBattleRequest("battle_map", "fishing_12", characters),
            Mockito.eq(app.spammy.hof.external.model.HofRequestOrigin.AUTOMATION)
                ?: app.spammy.hof.external.model.HofRequestOrigin.AUTOMATION)
        wakeups.wake(accountId, "LATE_BATTLE_BEFORE_LEASE_RECOVERY")
        Executors.newFixedThreadPool(2).use { executor ->
            val otherTransport = AutomationRecoveryIntegrationTest.Config().consumerReplayTransport(mapper, consumedEvents, consumerLease, runner, clock)
            val otherPublisher = AutomationOutboxPublisher(outbox, publishedMarker, otherTransport, clock)
            val oldWorker = executor.submit { publisher.publishBatch() }
            var recoveryWorker: java.util.concurrent.Future<*>? = null
            try {
                assertTrue(battleReturned.await(10, TimeUnit.SECONDS))
                markBrokerAcceptedWake("LATE_BATTLE_BEFORE_LEASE_RECOVERY")
                val identity = runs().single()["execution_identity"] as String
                val oldCycleId = journal.page(accountId, AutomationHistoryQuery()).cycles.single().id
                assertBattleMode(identity)
                val oldLease = jdbc.queryForObject("select lease_token from typed_automation_runtime_states where account_id = ?",
                    String::class.java, accountId)
                assertNotNull(oldLease)
                // 기존 preflight adapter의 대기 위치만 사용한다. acquire의 실제
                // transaction이 끝난 뒤 멈추므로 runtime 행 잠금을 잡고 기다리지 않는다.
                if (duringCanonicalCreation) {
                    val selectionPlaceholder = Mockito.mock(SelectedAutomationAction::class.java)
                    Mockito.doAnswer { invocation ->
                        val result = invocation.callRealMethod()
                        reacquired.countDown()
                        check(continueRecovery.await(30, TimeUnit.SECONDS))
                        result
                    }.`when`(store).createOrGet(Mockito.eq(accountId),
                        Mockito.any(SelectedAutomationAction::class.java) ?: selectionPlaceholder,
                        Mockito.any(Instant::class.java) ?: Instant.EPOCH)
                } else if (afterObservation || afterGuard) {
                    val placeholder = Mockito.mock(BattleMapAutomationAction::class.java)
                    Mockito.doAnswer { invocation ->
                        val result = invocation.callRealMethod()
                        if (afterGuard) {
                            pauseAfterGuard.set(true)
                        } else {
                            reacquired.countDown()
                            check(continueRecovery.await(30, TimeUnit.SECONDS))
                        }
                        result
                    }.`when`(battleOutcomes).reloadRecentAuthoritativeEvidence(
                        Mockito.any(BattleMapAutomationAction::class.java) ?: placeholder)
                    if (afterGuard) {
                        pauseAfterResultCheck(pauseAfterGuard, reacquired, continueRecovery)
                    }
                } else {
                    Mockito.doAnswer {
                        reacquired.countDown()
                        check(continueRecovery.await(30, TimeUnit.SECONDS))
                        AutomationDailyPreflight.Result.Ready
                    }.`when`(preflight).ensureReady(accountId)
                }
                clock.current = clock.now().plusSeconds(301)
                wakeups.wake(accountId, "EXPIRED_BATTLE_LEASE_RECOVERY")
                recoveryWorker = executor.submit { otherPublisher.publishBatch() }
                assertTrue(reacquired.await(10, TimeUnit.SECONDS))
                val recovered = runs().single()
                assertEquals(identity, recovered["execution_identity"])
                assertEquals("RECONCILING", recovered["status"])
                val newLease = jdbc.queryForObject("select lease_token from typed_automation_runtime_states where account_id = ?",
                    String::class.java, accountId)
                assertNotNull(newLease)
                assertNotEquals(oldLease, newLease)
                if (afterRecoveryCompletes) {
                    continueRecovery.countDown()
                    recoveryWorker.get(10, TimeUnit.SECONDS)
                    assertEquals("AMBIGUOUS", runs().single { it["execution_identity"] == identity }["status"])
                    assertTrue(store.findSuppressedBaselines(accountId).isNotEmpty(),
                        "늦은 응답 전에 실제 미관측 종결과 보류가 저장되어야 한다.")
                }
                returnBattle.countDown()
                if (duringCanonicalCreation) {
                    assertTrue(directLockRequested.await(10, TimeUnit.SECONDS),
                        "직접 결과가 runtime 잠금에 진입한 뒤 복구의 첫 canonical 저장을 commit한다.")
                    continueRecovery.countDown()
                }
                oldWorker.get(10, TimeUnit.SECONDS)
                if (!afterRecoveryCompletes && !duringCanonicalCreation) {
                    assertEquals(newLease, jdbc.queryForObject("select lease_token from typed_automation_runtime_states where account_id = ?",
                        String::class.java, accountId), "늦은 결과 보존은 새 worker의 실행권을 해제하지 않는다.")
                }
                continueRecovery.countDown()
                recoveryWorker.get(10, TimeUnit.SECONDS)
                assertEquals("SUCCEEDED", runs().first()["status"],
                    "직접 성공 결과 뒤 복구 판단이 같은 행동을 미확정 또는 실패로 되돌리지 않는다: ${runs()}")
                val history = journal.page(accountId, AutomationHistoryQuery())
                assertEquals(1, history.cycles.single { it.id == oldCycleId }.events.count { it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED })
                if (afterGuard) {
                    val successId = history.cycles.single { it.id == oldCycleId }.events.single { it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED }.id
                    assertTrue(history.cycles.flatMap { it.events }.none {
                        it.id > successId && it.reasonCode in setOf("ACTION_SUPERSEDED_BY_FRESH_STATE", "QUEST_PROGRESS_FRESH_DECISION", "TYPED_RECONCILIATION_BUDGET_EXHAUSTED")
                    }, "종결 확인 직후 도착한 직접 성공 뒤 오래된 관측의 종결 이력을 기록하지 않는다: ${history.cycles.flatMap { it.events }.map { it.reasonCode }}")
                }
                if (afterRecoveryCompletes || duringCanonicalCreation) {
                    val attemptId = jdbc.queryForObject("select id from automation_action_attempts where account_id = ? and execution_identity = ?",
                        Long::class.java, accountId, identity)!!
                    assertEquals(ActionConvergenceResult.APPLIED, assertNotNull(store.get(attemptId)).result,
                        "같은 행동의 현재 종결 결과는 늦게 확인한 직접 성공과 일치해야 한다.")
                    assertTrue(store.findSuppressedBaselines(accountId).isEmpty())
                } else {
                    assertTrue(history.cycles.flatMap { it.events }.none { it.reasonCode == "TYPED_RECONCILIATION_BUDGET_EXHAUSTED" },
                        "이미 보존한 직접 성공을 결과 미관측 이력으로 다시 기록하지 않는다.")
                }
                consumeFishingWakeUntil { "FCatch" in fishingPosts() }
                assertEquals(listOf("FStart", "FCatch"), fishingPosts())
                assertEquals(1, battleRequests().size)
                assertEquals(0, runningWorkCount())
                val cycles = journal.page(accountId, AutomationHistoryQuery()).cycles.size
                consumeNextWake()
                assertTrue(journal.page(accountId, AutomationHistoryQuery()).cycles.size > cycles)
            } finally {
                returnBattle.countDown()
                continueRecovery.countDown()
                oldWorker.get(10, TimeUnit.SECONDS)
                recoveryWorker?.get(10, TimeUnit.SECONDS)
                otherTransport.delivered.forEach { jdbc.update("delete from automation_consumed_events where event_id = ?", it) }
            }
        }
    }

    protected fun verifyLateBattleWithAnotherWorker(afterPendingExpires: Boolean = false, staleDue: Boolean = false) {
        val battleReturned = CountDownLatch(1)
        val returnBattle = CountDownLatch(1)
        val dueRead = CountDownLatch(1)
        val continueDue = CountDownLatch(1)
        val nextSubmitted = CountDownLatch(1)
        val returnNext = CountDownLatch(1)
        setupFishing(beforeResponse = { request ->
            if ("FStart" in request.formFields) {
                nextSubmitted.countDown()
                check(returnNext.await(10, TimeUnit.SECONDS))
            }
        })
        // 실제 전투 파싱·저장·계정 mutation 잠금 해제까지 실행한다. 반환 직후의
        // worker 교체 순서만 고정하고 결과나 후처리 구현을 대체하지 않는다.
        Mockito.doAnswer { invocation ->
            val result = invocation.callRealMethod()
            battleReturned.countDown()
            check(returnBattle.await(30, TimeUnit.SECONDS))
            result
        }.`when`(battleRuns).runBattle(Mockito.eq(accountId),
            Mockito.any(app.spammy.hof.battle.dto.RunBattleRequest::class.java)
                ?: app.spammy.hof.battle.dto.RunBattleRequest("battle_map", "fishing_12", characters),
            Mockito.eq(app.spammy.hof.external.model.HofRequestOrigin.AUTOMATION)
                ?: app.spammy.hof.external.model.HofRequestOrigin.AUTOMATION)
        wakeups.wake(accountId, "LATE_BATTLE_OLD_WORKER")
        Executors.newFixedThreadPool(2).use { executor ->
            val otherTransport = AutomationRecoveryIntegrationTest.Config().consumerReplayTransport(mapper, consumedEvents, consumerLease, runner, clock)
            val otherPublisher = AutomationOutboxPublisher(outbox, publishedMarker, otherTransport, clock)
            val oldWorker = executor.submit { publisher.publishBatch() }
            var nextWorker: java.util.concurrent.Future<*>? = null
            try {
                assertTrue(battleReturned.await(10, TimeUnit.SECONDS))
                markBrokerAcceptedWake("LATE_BATTLE_OLD_WORKER")
                val previousIdentity = runs().single()["execution_identity"]
                assertBattleMode(previousIdentity as String)
                val originalCycle = journal.page(accountId, AutomationHistoryQuery()).cycles.single().id
                clock.current = clock.now().plusSeconds(301)
                val account = TransactionTemplate(transactions).execute {
                    entityManager.find(HofAccountEntity::class.java, accountId)
                }!!
                captchaService.detectAndRecord(account, "<div>자경단에서 통행증을 발급받아주세요.</div>",
                    "https://hof.zerosic.com/index.php?police", false)
                assertTrue(captchaService.resolveCurrentPassChallenge(accountId))
                application.stopTyped(accountId)
                application.startTyped(accountId)
                if (mode == AutomationConvergenceMode.ACTIVE) {
                    // ACTIVE는 같은 낚시 범위의 미확정 전투가 새 START를 억제한다.
                    // 실제 별도 소비자가 대기를 판단한 뒤 직접 응답이 이를 해소하는 경로다.
                    nextWorker = executor.submit {
                        repeat(3) {
                            advanceToNextWake()
                            otherPublisher.publishBatch()
                        }
                    }
                    nextWorker.get(10, TimeUnit.SECONDS)
                    assertTrue(fishingPosts().isEmpty())
                    assertEquals(0, runningWorkCount())
                    val attemptId = jdbc.queryForObject("select id from automation_action_attempts where account_id = ? and execution_identity = ?",
                        Long::class.java, accountId, previousIdentity)!!
                    assertEquals(ActionConvergenceResult.PENDING, assertNotNull(store.get(attemptId)).result)
                    if (afterPendingExpires) {
                        val pendingSince = assertNotNull(assertNotNull(store.get(attemptId)).firstPendingAt)
                        clock.current = pendingSince.plusSeconds(121)
                        if (staleDue) {
                            Mockito.doAnswer { invocation ->
                                val due = invocation.callRealMethod() as ActionConvergenceRecord?
                                if (due?.attemptId == attemptId) {
                                    dueRead.countDown()
                                    check(continueDue.await(30, TimeUnit.SECONDS))
                                }
                                due
                            }.`when`(store).findDue(Mockito.eq(accountId), Mockito.any(Instant::class.java) ?: Instant.EPOCH)
                        }
                        nextWorker = executor.submit { otherPublisher.publishBatch() }
                        if (staleDue) {
                            assertTrue(dueRead.await(10, TimeUnit.SECONDS), "실제 예산 만료 판단이 due 수렴을 조회해야 한다.")
                        } else {
                            assertTrue(nextSubmitted.await(10, TimeUnit.SECONDS),
                                "보류 종료 뒤 최신 상태에서 새 낚시 START까지 판단해야 한다.")
                            assertEquals(ActionConvergenceResult.HELD, assertNotNull(store.get(attemptId)).result,
                                "실제 후속 wake에서 유한 수렴 예산이 먼저 종결되어야 한다.")
                        }
                    }
                    val nextLease = jdbc.queryForObject("select lease_token from typed_automation_runtime_states where account_id = ?",
                        String::class.java, accountId)
                    returnBattle.countDown()
                    oldWorker.get(10, TimeUnit.SECONDS)
                    assertEquals("SUCCEEDED", runs().single { it["execution_identity"] == previousIdentity }["status"])
                    assertEquals(ActionConvergenceResult.APPLIED, assertNotNull(store.get(attemptId)).result)
                    assertEquals("DIRECT_RESPONSE_APPLIED", assertNotNull(store.get(attemptId)).reasonCode)
                    val originalEvents = journal.page(accountId, AutomationHistoryQuery()).cycles.single { it.id == originalCycle }.events
                    assertEquals(1, originalEvents.count { it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED })
                    assertTrue(originalEvents.none { it.kind == AutomationHistoryEventKind.ACTION_FAILED })
                    if (afterPendingExpires && !staleDue) {
                        assertEquals(nextLease, jdbc.queryForObject("select lease_token from typed_automation_runtime_states where account_id = ?",
                            String::class.java, accountId))
                        assertEquals("SUBMITTING", runs().last()["status"])
                        assertEquals(1, runningWorkCount(), "옛 성공은 새 START의 작업권을 완료하지 않는다.")
                    }
                    continueDue.countDown()
                    returnNext.countDown()
                    nextWorker.get(10, TimeUnit.SECONDS)
                    assertEquals(ActionConvergenceResult.APPLIED, assertNotNull(store.get(attemptId)).result,
                        "이전에 읽은 예산 만료 정보로 직접 적용을 보류로 되돌리지 않는다.")
                    consumeFishingWakeUntil { "FCatch" in fishingPosts() }
                    assertEquals(listOf("FStart", "FCatch"), fishingPosts())
                    assertEquals(1, battleRequests().size)
                    assertEquals(0, runningWorkCount())
                    val latestCycle = journal.page(accountId, AutomationHistoryQuery()).cycles.maxOf { it.id }
                    consumeWakeUntilNextDecision(latestCycle)
                    return@use
                }
                nextWorker = executor.submit {
                    // 만료된 결과의 수렴이 첫 wake를 소비한 뒤 새 판단 wake를 만든다.
                    // 등록 여부만 보지 않고 별도 worker가 그 후속 wake도 실제 소비한다.
                    val deadline = clock.now().plusSeconds(120)
                    repeat(16) {
                        advanceToNextWake()
                        assertTrue(clock.now() <= deadline, "새 낚시 판단이 수렴 시간 예산 안에 도달해야 한다.")
                        otherPublisher.publishBatch()
                        if (nextSubmitted.count == 0L) return@submit
                    }
                    error("새 worker의 후속 판단이 낚시 START에 도달하지 못했습니다.")
                }
                assertTrue(nextSubmitted.await(10, TimeUnit.SECONDS), "별도 worker가 새 START를 제출해야 한다.")
                val nextIdentity = runs().last()["execution_identity"]
                assertNotEquals(previousIdentity, nextIdentity)
                val owner = jdbc.queryForObject("select id from automation_work_sessions where account_id = ? and status = 'RUNNING'",
                    Long::class.java, accountId)!!
                val nextLease = jdbc.queryForObject("select lease_token from typed_automation_runtime_states where account_id = ?",
                    String::class.java, accountId)
                returnBattle.countDown()
                oldWorker.get(10, TimeUnit.SECONDS)
                assertEquals("SUCCEEDED", runs().first()["status"])
                assertEquals("RUNNING", jdbc.queryForObject("select status from automation_work_sessions where id = ?",
                    String::class.java, owner), "옛 전투 결과가 새 낚시의 작업권을 완료하면 안 된다.")
                assertEquals(nextLease, jdbc.queryForObject("select lease_token from typed_automation_runtime_states where account_id = ?",
                    String::class.java, accountId))
                assertEquals("SUBMITTING", runs().last()["status"])
                returnNext.countDown()
                nextWorker.get(10, TimeUnit.SECONDS)
                assertEquals(listOf("FStart", "FCatch"), fishingPosts())
                assertEquals(1, requests.count { it.method == HofHttpMethod.POST && it.url.contains("common=fishing_12") })
                assertEquals(0, runningWorkCount())
            } finally {
                returnBattle.countDown()
                continueDue.countDown()
                returnNext.countDown()
                oldWorker.get(10, TimeUnit.SECONDS)
                nextWorker?.get(10, TimeUnit.SECONDS)
                otherTransport.delivered.forEach { jdbc.update("delete from automation_consumed_events where event_id = ?", it) }
            }
        }
    }

    private fun markBrokerAcceptedWake(reason: String) {
        // 동기 replay는 소비 완료까지 publish 반환을 미룬다. 원격 요청에 도달한
        // wake는 실제 broker처럼 게시를 확정하여 두 publisher가 같은 행을 재전송하지 않는다.
        val dispatched = outbox.findUnpublished(clock.now()).single {
            mapper.readValue(it.payload, AutomationWakeupEvent::class.java).reason == reason
        }
        publishedMarker.markPublished(dispatched.id)
    }

    private fun pauseBeforeReconciliationHistory(armed: java.util.concurrent.atomic.AtomicBoolean, reached: CountDownLatch, resume: CountDownLatch) {
        val right = Mockito.mock(TypedRuntimeExecutionRight::class.java)
        val outcome = TypedRuntimeOutcome.ActionSuperseded("fixture", "fixture")
        val history: () -> Unit = {}
        Mockito.doAnswer { invocation ->
            if (armed.compareAndSet(true, false)) {
                // 결과 transaction은 이미 commit됐고 이력 transaction은 아직 행을 잠그지 않았다.
                reached.countDown()
                check(resume.await(30, TimeUnit.SECONDS))
            }
            invocation.callRealMethod()
        }.`when`(runtime).persistReconciliationHistory(
            Mockito.any(TypedRuntimeExecutionRight::class.java) ?: right,
            Mockito.any(TypedRuntimeOutcome::class.java) ?: outcome,
            Mockito.any<() -> Unit>() ?: history,
        )
    }

    private fun pauseAfterResultCheck(armed: java.util.concurrent.atomic.AtomicBoolean, reached: CountDownLatch, resume: CountDownLatch) {
        val right = Mockito.mock(TypedRuntimeExecutionRight::class.java)
        Mockito.doAnswer { invocation ->
            val result = invocation.callRealMethod()
            if (result == false && armed.compareAndSet(true, false)) {
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    object : org.springframework.transaction.support.TransactionSynchronization {
                        override fun afterCompletion(status: Int) {
                            check(status == org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED)
                            // DB 잠금이 해제된 뒤 확인과 종결 쓰기 사이에 이전 응답을 돌려준다.
                            reached.countDown()
                            check(resume.await(30, TimeUnit.SECONDS))
                        }
                    },
                )
            }
            result
        }.`when`(runtime).completeRecordedAction(Mockito.any(TypedRuntimeExecutionRight::class.java) ?: right)
    }

    private fun setupFishing(
        initialBattle: Boolean = true,
        battleAfterCatch: Boolean = false,
        startWithoutCatch: Boolean = false,
        beforeResponse: (HofRequest) -> Unit,
    ) {
        var phase = "reset"
        var battle = initialBattle
        fun fixture(name: String) = requireNotNull(javaClass.getResource("/fixtures/town/fishing/$name.html")).readText()
        val header = """<table id='menu2'><tr><td>《테스트》테스트</td><td>Funds : $ 1<br>Work : Nothing</td>
            <td>Time : 100/100<br>Auction : Nothing</td></tr></table>"""
        Mockito.doAnswer { invocation ->
            val request = invocation.getArgument<HofRequest>(1)
            requests += request
            val body = when {
                request.url.contains("?char=") -> app.spammy.hof.character.service.currentPatternForm() +
                    app.spammy.hof.character.service.savedPatternLoadForm(1)
                "FStart" in request.formFields -> {
                    phase = "waiting"
                    fixture(if (startWithoutCatch) "waiting-no-catch" else phase)
                }
                "FCatch" in request.formFields -> {
                    phase = "exhausted"
                    battle = battleAfterCatch
                    fixture(if (battleAfterCatch) "monster" else "caught")
                }
                request.method == HofHttpMethod.POST -> {
                    battle = false
                    // CATCH로 소진된 횟수는 방해 전투를 마쳐도 다시 늘어나지 않는다.
                    if (!(battleAfterCatch && phase == "exhausted")) phase = "reset"
                    """<h2>Show Detail( 1 turns. )</h2><h1>《테스트》테스트은(는) 승리했다!</h1>
                    <div>남은 HP : 0/100 생존자 : 0/1 총 데미지 : 0</div>
                    <div>남은 HP : 100/100 생존자 : 1/1 총 데미지 : 100 턴 : 1/100 획득 경험치 : 1 획득 Funds : $ 1</div>"""
                }
                request.url.contains("menu=fishing") -> when {
                    phase == "exhausted" && battleAfterCatch && battle -> fixture("monster")
                    phase == "exhausted" -> fixture("reset").replace("18회", "0회")
                    else -> fixture(phase)
                }
                else -> """<div id='contents'><div id='mapgroup1'><a href='index.php?common=0001'>일반 맵</a></div>
                    ${if (battle) "<a href='index.php?common=fishing_12'>Fishing- 악어</a>" else ""}</div>
                    <div id='foot'><h5>Copy Right sanitized fixture</h5><h6>H.O.F Korean Ver sanitized fixture</h6>
                    <img src='image/zerohof.gif'></div>"""
            }
            beforeResponse(request)
            HofHttpResponse(200, request.url, header + body, emptyMap())
        }.`when`(gateway).execute(Mockito.eq(accountId), Mockito.any<HofRequest>()
            ?: HofRequest(HofHttpMethod.GET, "https://example.test"), Mockito.anyMap())
    }

    private fun consumeFishingWakeUntil(done: () -> Boolean) {
        val deadline = clock.now().plusSeconds(30)
        repeat(4) {
            if (done()) return
            consumeNextWake()
            assertTrue(clock.now() <= deadline, "낚시 후속 소비가 30초를 넘었다")
        }
        assertTrue(done(), "네 번의 실제 후속 소비 안에 진전해야 한다")
    }

    private fun consumeWakeUntilNextDecision(previousCycleId: Long) {
        val deadline = clock.now().plusSeconds(30)
        val consumed = mutableListOf<String>()
        repeat(4) {
            val queued = jdbc.queryForList("select payload, available_at from automation_outbox where account_id = ? and published_at is null order by available_at, id",
                accountId)
            consumeNextWake()
            consumed += queued.toString()
            assertTrue(clock.now() <= deadline, "후속 판단은 30초 안에 도달해야 한다: $consumed")
            if (journal.page(accountId, AutomationHistoryQuery()).cycles.any { cycle -> cycle.id > previousCycleId }) {
                println("AUTO-02 후속 판단 도달: 실제 wake 소비 ${it + 1}회, 예약=$consumed")
                return
            }
        }
        fail("네 번의 실제 wake 소비 안에 새 판단에 도달해야 한다: $consumed")
    }

    private fun advanceToNextWake() {
        val next = jdbc.queryForObject("select min(available_at) from automation_outbox where account_id = ? and topic = ? and published_at is null",
            java.time.OffsetDateTime::class.java, accountId, AutomationOutboxService.WAKEUP_TOPIC)?.toInstant()
        clock.current = maxOf(clock.now(), assertNotNull(next))
    }

    private fun consumeNextWake() {
        advanceToNextWake()
        val before = transport.delivered.size
        publisher.publishBatch()
        assertTrue(transport.delivered.size > before)
        assertTrue(transport.delivered.all { outbox.consumed(it) })
        assertEquals(0, jdbc.queryForObject("select count(*) from account_automation_leases where account_id = ?", Int::class.java, accountId))
    }

    private fun runningWorkCount() = jdbc.queryForObject(
        "select count(*) from automation_work_sessions where account_id = ? and status = 'RUNNING'", Int::class.java, accountId)
    private fun runs() = jdbc.queryForList(
        "select status, submitted_at, execution_identity, last_error from typed_automation_action_runs where account_id = ? order by id", accountId)
    private fun battleRequests() = requests.filter { it.method == HofHttpMethod.POST && it.url.contains("common=fishing_12") }
    private fun fishingPosts() = requests.mapNotNull { request -> listOf("FStart", "FCatch").singleOrNull { it in request.formFields } }
}

@SpringBootTest(properties = ["hof.automation-convergence.mode=LEGACY"])
class LegacyAutomationLateResultIntegrationTest : AutomationLateResultIntegrationTest() {
    @Test fun `재관측 결과 commit 후 도착한 직접 성공 뒤 오래된 대체 이력을 저장하지 않는다`() =
        verifyLateCatchWithFailedObservation("ADVANCED", beforeReconciliationHistory = true)
    @Test fun `재관측 503 이력 DB 실패에도 대기와 실행권 해제 및 다음 판단을 보존한다`() = verifyReconciliation503HistoryFailure()
    @Test fun `첫 CATCH 복구 기록과 직접 성공이 경합해도 성공 증거와 이력이 일치한다`() =
        verifyLateCatchWithFailedObservation("ERROR", duringCanonicalCreation = true)
    @Test fun `자택 수락을 관측으로 먼저 종결해도 원래 직접 성공 이력을 보존한다`() = verifyHomeStateAdvancedBeforeDirect()
    @Test fun `복구 기록을 처음 저장하는 중 도착한 직접 성공도 같은 행동에 귀속한다`() =
        verifyLateBattleReacquisition(afterObservation = false, duringCanonicalCreation = true)
    @Test fun `정상 관측의 종결 확인 직후 CATCH 성공이 도착해도 대체 이력을 저장하지 않는다`() =
        verifyLateCatchWithFailedObservation("ADVANCED", afterGuard = true)
    @Test fun `종결 확인 직후 늦은 직접 성공이 도착해도 오래된 관측 이력을 저장하지 않는다`() =
        verifyLateBattleReacquisition(afterObservation = false, afterGuard = true)
    @Test fun `먼저 보류된 CATCH도 늦은 직접 적용을 기록하고 다음 판단을 진행한다`() =
        verifyLateCatchWithFailedObservation("ERROR", afterRecoveryCompletes = true)
    @Test fun `이전 전투의 늦은 직접 결과는 새 worker의 낚시 작업권을 완료하지 않는다`() = verifyLateBattleWithAnotherWorker()
    @Test fun `늦게 반환한 재관측 결과는 이미 보존한 직접 성공을 미관측으로 바꾸지 않는다`() = verifyLateBattleReacquisition(afterObservation = true)
    @ParameterizedTest
    @ValueSource(strings = ["ERROR", "503"])
    fun `늦은 CATCH 성공 뒤 재조회 실패는 보류와 미관측 이력을 남기지 않는다`(failure: String) = verifyLateCatchWithFailedObservation(failure)
    @Test fun `먼저 미관측으로 닫힌 행동도 늦은 직접 성공을 귀속하고 보류를 해제한다`() =
        verifyLateBattleReacquisition(afterObservation = false, afterRecoveryCompletes = true)
}

@SpringBootTest(properties = ["hof.automation-convergence.mode=SHADOW"])
class ShadowAutomationLateResultIntegrationTest : AutomationLateResultIntegrationTest() {
    @Test fun `재관측 결과 commit 후 도착한 직접 성공 뒤 오래된 대체 이력을 저장하지 않는다`() =
        verifyLateCatchWithFailedObservation("ADVANCED", beforeReconciliationHistory = true)
    @Test fun `재관측 503 이력 DB 실패에도 대기와 실행권 해제 및 다음 판단을 보존한다`() = verifyReconciliation503HistoryFailure()
    @Test fun `첫 CATCH 복구 기록과 직접 성공이 경합해도 성공 증거와 이력이 일치한다`() =
        verifyLateCatchWithFailedObservation("ERROR", duringCanonicalCreation = true)
    @Test fun `자택 수락을 관측으로 먼저 종결해도 원래 직접 성공 이력을 보존한다`() = verifyHomeStateAdvancedBeforeDirect()
    @Test fun `복구 기록을 처음 저장하는 중 도착한 직접 성공도 같은 행동에 귀속한다`() =
        verifyLateBattleReacquisition(afterObservation = false, duringCanonicalCreation = true)
    @Test fun `SHADOW 비교 저장의 DB 실패는 복구 종결과 후속 판단을 롤백하지 않는다`() = verifyShadowRecordingFailure()
    @Test fun `정상 관측의 종결 확인 직후 CATCH 성공이 도착해도 대체 이력을 저장하지 않는다`() =
        verifyLateCatchWithFailedObservation("ADVANCED", afterGuard = true)
    @Test fun `종결 확인 직후 늦은 직접 성공이 도착해도 오래된 관측 이력을 저장하지 않는다`() =
        verifyLateBattleReacquisition(afterObservation = false, afterGuard = true)
    @Test fun `먼저 보류된 CATCH도 늦은 직접 적용을 기록하고 다음 판단을 진행한다`() =
        verifyLateCatchWithFailedObservation("ERROR", afterRecoveryCompletes = true)
    @Test fun `이전 전투의 늦은 직접 결과는 새 worker의 낚시 작업권을 완료하지 않는다`() = verifyLateBattleWithAnotherWorker()
    @Test fun `늦게 반환한 재관측 결과는 이미 보존한 직접 성공을 미관측으로 바꾸지 않는다`() = verifyLateBattleReacquisition(afterObservation = true)
    @ParameterizedTest
    @ValueSource(strings = ["ERROR", "503"])
    fun `늦은 CATCH 성공 뒤 재조회 실패는 보류와 미관측 이력을 남기지 않는다`(failure: String) = verifyLateCatchWithFailedObservation(failure)
    @Test fun `먼저 미관측으로 닫힌 행동도 늦은 직접 성공을 귀속하고 보류를 해제한다`() =
        verifyLateBattleReacquisition(afterObservation = false, afterRecoveryCompletes = true)
}

@SpringBootTest(properties = ["hof.automation-convergence.mode=ACTIVE"])
class ActiveAutomationLateResultIntegrationTest : AutomationLateResultIntegrationTest() {
    @Test fun `START 저장 rollback 뒤 일시정지 재개로 결과 수용이 취소되면 성공 이력을 쓰지 않는다`() =
        verifyCanonicalDirectRollback("FISHING_START", failAfterCallback = true, resumeAfterRollback = true)
    @Test fun `관측 이력 DB 실패는 확정한 수렴과 실행권 해제 및 다음 판단을 취소하지 않는다`() = verifyProbeHistoryFailure()
    @ParameterizedTest
    @ValueSource(strings = ["FISHING_CATCH", "FISHING_OBSTRUCTION_BATTLE"])
    fun `직접 callback 완료 뒤 commit 오류는 자동 DB 재시도로 숨기지 않는다`(kind: String) =
        verifyCanonicalDirectRollback(kind, failAfterCallback = true)
    @ParameterizedTest
    @ValueSource(strings = ["FISHING_START", "FISHING_CATCH", "FISHING_OBSTRUCTION_BATTLE"])
    fun `직접 적용 증거 저장의 일시 rollback은 같은 DB 저장만 다시 시도한다`(kind: String) = verifyCanonicalDirectRollback(kind)
    @Test fun `직접 CATCH 성공과 경합한 probe의 대체 이력도 같은 순서로 확정한다`() =
        verifyLateCatchWithFailedObservation("ADVANCED", afterRecoveryCompletes = true, staleHistory = true)
    @Test fun `예산 만료 조회 뒤 도착한 직접 성공을 보류로 되돌리지 않는다`() =
        verifyLateBattleWithAnotherWorker(afterPendingExpires = true, staleDue = true)
    @Test fun `먼저 읽은 수렴 상태로 늦은 직접 적용을 덮어쓰지 않는다`() =
        verifyLateCatchWithFailedObservation("ADVANCED", afterRecoveryCompletes = true, staleObservation = true)
    @Test fun `잔여 횟수 소진으로 대체된 CATCH도 원래 직접 응답으로 성공을 귀속한다`() =
        verifyLateCatchWithFailedObservation("CASTS_EXHAUSTED", afterRecoveryCompletes = true)
    @Test fun `최신 상태로 대체된 CATCH도 늦은 직접 응답으로 원래 행동 적용을 귀속한다`() =
        verifyLateCatchWithFailedObservation("ADVANCED", afterRecoveryCompletes = true)
    @Test fun `미확정 전투는 새 낚시 제출을 억제하고 늦은 직접 응답 뒤 후속 판단을 진행한다`() = verifyLateBattleWithAnotherWorker()
    @Test fun `결과 확인 예산이 끝난 뒤에도 늦은 직접 성공을 보존하고 다음 낚시를 판단한다`() =
        verifyLateBattleWithAnotherWorker(afterPendingExpires = true)
}
