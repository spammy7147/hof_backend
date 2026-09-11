package app.spammy.hof.automation.service

import app.spammy.hof.automation.convergence.ActionConvergenceResult
import app.spammy.hof.automation.convergence.AutomationActionEvidence
import app.spammy.hof.automation.convergence.AutomationConvergenceMode
import app.spammy.hof.automation.convergence.AutomationConvergenceController
import app.spammy.hof.automation.convergence.ProductionEvidenceShapes
import app.spammy.hof.automation.entity.*
import app.spammy.hof.automation.lease.AccountAutomationLeaseService
import app.spammy.hof.automation.outbox.AutomationConsumedEventService
import app.spammy.hof.automation.outbox.AutomationOutboxPublishMarker
import app.spammy.hof.automation.outbox.AutomationOutboxPublisher
import app.spammy.hof.automation.outbox.AutomationOutboxService
import app.spammy.hof.automation.outbox.AutomationWakeupEvent
import app.spammy.hof.automation.history.AutomationHistoryEventKind
import app.spammy.hof.automation.history.AutomationHistoryQuery
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.home.model.HomeMode
import app.spammy.hof.town.home.parser.HomePageParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

@SpringBootTest(properties = ["hof.automation-convergence.mode=LEGACY"])
class LegacyAutomationFishingBattleDirectReceiptContinuityTest : AutomationFishingBattleDirectReceiptContinuityTest() {
    override val mode = AutomationConvergenceMode.LEGACY
}

@SpringBootTest(properties = ["hof.automation-convergence.mode=SHADOW"])
class ShadowAutomationFishingBattleDirectReceiptContinuityTest : AutomationFishingBattleDirectReceiptContinuityTest() {
    override val mode = AutomationConvergenceMode.SHADOW
}

@SpringBootTest(properties = ["hof.automation-convergence.mode=ACTIVE"])
class ActiveAutomationFishingBattleDirectReceiptContinuityTest : AutomationFishingBattleDirectReceiptContinuityTest() {
    override val mode = AutomationConvergenceMode.ACTIVE
}

@ActiveProfiles("test")
@Import(AutomationRecoveryIntegrationTest.Config::class)
abstract class AutomationFishingBattleDirectReceiptContinuityTest : AutomationRecoveryFixture() {
    @MockitoSpyBean private lateinit var results: AutomationResultCoordinator
    @Autowired private lateinit var mapper: tools.jackson.databind.ObjectMapper
    @Autowired private lateinit var convergence: AutomationConvergenceController
    @MockitoSpyBean private lateinit var actionLifecycle: AutomationActionLifecycleModule
    @Autowired private lateinit var consumedEvents: AutomationConsumedEventService
    @Autowired private lateinit var consumerLease: AccountAutomationLeaseService
    @Autowired private lateinit var publishedMarker: AutomationOutboxPublishMarker
    @Autowired private lateinit var runner: UnifiedAutomationRunner

    @Test
    fun `낚시 전투의 결과를 한 번 보존하고 새 낚시와 독립 자택을 실행한다`() = verifyFishingBattleResult()

    @Test
    fun `낚시 전투 직접 응답 뒤 로컬 완료가 실패해도 원래 결과와 다음 낚시를 복구한다`() =
        verifyFishingBattleResult(failFirstApplication = true)

    @Test
    fun `낚시 전투의 로컬 후처리 대기는 작업권을 놓고 재시도 전에 독립 자택을 실행한다`() =
        verifyFishingBattleResult(failFirstApplication = true, verifyWaitingOwnership = true)

    @Test
    fun `이미 완료한 낚시 전투도 로컬 완료 재시도에서 새 낚시와 원래 이력을 보존한다`() =
        verifyFishingBattleResult(failFirstApplication = true, failAfterProjection = true)

    @ParameterizedTest
    @ValueSource(strings = ["FINGERPRINT", "KIND", "ROUNDS", "NONTERMINAL", "RESULT_ID"])
    fun `손상된 낚시 전투 응답은 같은 항목을 보류하고 독립 자택과 명시 해제 뒤 새 낚시를 보존한다`(corruption: String) =
        verifyFishingBattleResult(failFirstApplication = true, corruption = corruption)

    @Test
    fun `일시정지 중 낚시 보류 해제는 자동화를 재개하지 않고 사용자 재개 뒤 새 낚시를 판단한다`() =
        verifyFishingBattleResult(failFirstApplication = true, corruption = "FINGERPRINT", pauseBeforeRelease = true)

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `늦은 낚시 전투 결과는 새 낚시의 CATCH 작업권과 원래 성공 이력을 보존한다`(receiptStoredBeforeRecovery: Boolean) =
        verifyFishingBattleResult(lateWorker = receiptStoredBeforeRecovery)

    @ParameterizedTest
    @ValueSource(strings = ["DEFEAT", "DRAW"])
    fun `낚시 전투의 패배와 무승부도 원래 응답으로 복원하고 다음 낚시와 독립 자택을 실행한다`(outcome: String) =
        verifyFishingBattleResult(failFirstApplication = true, outcome = BattleAutomationRoundOutcome.valueOf(outcome))

    @Test
    fun `CATCH 뒤 출현한 전투는 별도 판단에서 원래 응답을 복구하고 완료한 낚시와 독립 자택을 보존한다`() =
        verifyFishingBattleResult(failFirstApplication = true, afterCatch = true)

    private fun verifyFishingBattleResult(
        failFirstApplication: Boolean = false,
        verifyWaitingOwnership: Boolean = false,
        failAfterProjection: Boolean = false,
        corruption: String? = null,
        pauseBeforeRelease: Boolean = false,
        lateWorker: Boolean? = null,
        outcome: BattleAutomationRoundOutcome = BattleAutomationRoundOutcome.VICTORY,
        afterCatch: Boolean = false,
    ) {
        var homeAccepted = false
        val homeUrl = "https://hof.zerosic.com/index.php?menu=quest2"
        fun homePage(): String {
            val heading = if (homeAccepted) "진행중인 작업 목록" else "수락 가능한 작업 목록"
            val action = if (homeAccepted) "-" else "<a href='?menu=quest2&amp;action=get&amp;no=A'>수락</a>"
            return """<h4>$heading</h4><table><tr><td>[A] 독립 자택</td>
                <td>미션 0/1</td><td>-</td><td>-</td><td>$action</td></tr></table>"""
        }
        val home = HomePageParser().parse(HomeMode.HOME, homePage(), homeUrl,
            HofFormParser().parse(homePage(), homeUrl)).quests.single()
        val fishingState = setupFishing(obstruction = afterCatch, initialBattle = !afterCatch,
            hiddenBattle = true, castsAfterBattle = if (afterCatch) 0 else 1, homeResponse = { request ->
            if (request.formFields["action"] == "get") {
                assertEquals(HofHttpMethod.GET, request.method)
                assertEquals("A", request.formFields["no"])
                homeAccepted = true
            }
            homePage()
        })
        fishingState.battleOutcome = outcome
        TransactionTemplate(transactions).executeWithoutResult {
            val fishing = entityManager.find(AutomationEntryEntity::class.java, entryId)
            val entry = AutomationEntryEntity(account = fishing.account, type = AutomationType.HOME_QUEST,
                singletonTypeMarker = AutomationType.HOME_QUEST, priority = 1, enabled = true,
                createdAt = clock.now(), updatedAt = clock.now())
            entityManager.persist(entry)
            entityManager.persist(HomeQuestAutomationSelectionEntity(entry = entry, questId = home.id,
                questName = home.name, enabled = true, sourceOrder = 0))
        }
        fun battles() = requests.filter { it.method == HofHttpMethod.POST && it.url.contains("common=fishing_12") }
        fun successes() = journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }
            .filter { it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED && it.actionKind == "BATTLE_MAP" }
        fun assertFishingReleased() {
            val owners = jdbc.queryForList(
                "select work_type from automation_work_sessions where account_id = ? and status = 'RUNNING'",
                String::class.java, accountId)
            assertTrue(owners.size <= 1 && owners.all { it == "HOME_QUEST" },
                "완료한 낚시는 작업권을 놓고, 같은 발행 묶음에서 수락한 자택만 작업권을 보존한다: $owners")
            if (owners.isNotEmpty()) assertTrue(homeAccepted)
        }

        var failureInjected = false
        if (failFirstApplication) {
            val placeholder = Mockito.mock(ManagedAutomationAction::class.java)
            Mockito.doAnswer { invocation ->
                val managed = invocation.getArgument<ManagedAutomationAction>(0)
                val payload = managed.storedAction.payload
                if (!failureInjected && payload is StoredTypedActionPayload.BattleMap &&
                    payload.source == BattleAutomationActionSource.FISHING_AUTOMATION
                ) {
                    assertEquals(1, battles().size)
                    assertIs<AutomationActionEvidence.DirectApplied>(invocation.getArgument<AutomationActionEvidence?>(2))
                    assertEquals(1, runningWorkCount(), "직접 응답은 낚시 작업 완료를 반영하기 전에 보존한다.")
                    if (failAfterProjection) {
                        invocation.callRealMethod()
                        assertEquals(0, runningWorkCount(), "이미 작업을 완료한 뒤 실패하는 경로도 구분한다.")
                    }
                    failureInjected = true
                    throw IllegalStateException("수신한 낚시 전투 결과의 첫 로컬 완료 실패")
                }
                invocation.callRealMethod()
            }.`when`(results).applyDirect(
                Mockito.any<ManagedAutomationAction>() ?: placeholder,
                Mockito.any<TypedAutomationExecution>() ?: TypedAutomationExecution.Completed,
                Mockito.nullable(AutomationActionEvidence::class.java),
                Mockito.nullable(Long::class.javaObjectType),
            )
        }

        val beganAt = clock.now()
        wakeups.wake(accountId, "FISHING_BATTLE_DIRECT_RESULT")
        if (lateWorker != null) {
            verifyLateWorkerWithNewCatch(lateWorker, fishingState, { battles().size }, { homeAccepted })
            return
        }
        publisher.publishBatch()

        val initialPosts = if (afterCatch) listOf("FStart", "FCatch") else emptyList()
        val precedingCast = if (afterCatch) {
            assertEquals(initialPosts, fishingPosts())
            assertEquals(0, battles().size, "CATCH와 전투는 같은 판단의 내부 단계가 아니다.")
            assertEquals(0, runningWorkCount(), "CATCH 응답으로 전투가 나타나도 한 번 낚시 작업은 완료된다.")
            journal.page(accountId, AutomationHistoryQuery()).cycles.single { cycle ->
                cycle.events.any { it.actionKind == "CATCH" && it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED }
            }.also {
                assertEquals(listOf("START", "CATCH"), it.events.filter { e ->
                    e.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED
                }.map { e -> e.actionKind })
                repeat(5) { if (battles().isEmpty()) consumeNextWake() }
            }
        } else null
        assertEquals(1, battles().size)
        assertEquals(initialPosts, fishingPosts(), "전투와 START/CATCH는 별도 판단이다.")
        assertFalse(homeAccepted)
        val identity = assertNotNull(jdbc.queryForObject(
            "select execution_identity from typed_automation_action_runs where account_id = ? and action_kind = 'BATTLE_MAP'",
            String::class.java, accountId))
        val originalWorkId = assertNotNull(jdbc.queryForObject(
            "select id from automation_work_sessions where account_id = ? and work_type = 'FISHING' order by id desc limit 1",
            Long::class.java, accountId))
        if (failFirstApplication) {
            assertTrue(failureInjected)
            if (corruption != null) corruptReceipt(identity, readReceipt(identity), corruption)
            if (verifyWaitingOwnership) {
                assertEquals("RESULT_PENDING", runs().single { it["execution_identity"] == identity }["status"])
                assertEquals(0, runningWorkCount(), "원래 응답의 로컬 후처리를 기다리는 낚시는 작업권을 소유하지 않는다.")
                val waiting = jdbc.queryForMap(
                    "select target_key, status, next_check_at from automation_work_sessions where account_id = ? and work_type = 'FISHING'",
                    accountId)
                assertEquals("DAILY_FISHING", waiting["target_key"])
                assertEquals("WAITING_COOLDOWN", waiting["status"])
                assertEquals(beganAt.plusSeconds(10), (waiting["next_check_at"] as java.time.OffsetDateTime).toInstant())
                consumeNextWake()
                assertTrue(homeAccepted, "낚시 결과 재시도를 기다리는 동안 자택을 실제 수락한다.")
                consumeNextWake()
                assertTrue(clock.now() < beganAt.plusSeconds(10))
                assertTrue(fishingPosts().isEmpty(), "같은 낚시 항목의 새 START는 로컬 결과 보류 범위에 속한다.")
                assertTrue(successes().isEmpty())
                assertEquals("RESULT_PENDING", runs().single { it["execution_identity"] == identity }["status"])
                assertEquals(0, runningWorkCount())
            }
            clock.current = clock.now().plusSeconds(11)
            consumeNextWake()
            if (corruption != null) {
                verifyCorruptReceiptHeld(identity, corruption, { battles().size }, { homeAccepted }, pauseBeforeRelease)
                return
            }
        }
        fun assertOriginalResult() {
            assertEquals("SUCCEEDED", runs().single { it["execution_identity"] == identity }["status"])
            val record = store.get(accountId, identity)
            if (mode == AutomationConvergenceMode.ACTIVE) {
                assertEquals(ActionConvergenceResult.APPLIED, assertNotNull(record).result)
                assertEquals("DIRECT_RESPONSE_APPLIED", record.reasonCode)
            } else assertNull(record)
            assertEquals(if (mode == AutomationConvergenceMode.SHADOW) listOf("APPLIED") else emptyList(),
                jdbc.queryForList("select new_result from automation_convergence_shadow_evaluations where account_id = ? and execution_identity_hash = ?",
                    String::class.java, accountId, ProductionEvidenceShapes.fingerprint(identity)))
        }
        assertOriginalResult()
        if (failFirstApplication) {
            val receipt = readReceipt(identity)
            val response = assertIs<AutomationDirectResponse.BattleMap>(receipt.response)
            assertEquals(identity, response.resultIdentity)
            assertEquals(listOf(outcome), response.outcomes)
            assertEquals("FISHING_OBSTRUCTION_BATTLE", receipt.policyContext?.actionKind?.name)
            assertEquals(entryId, receipt.isolation?.entryId)
            assertEquals("FISHING_ENTRY", receipt.isolation?.scope?.kind?.name)
            assertEquals(entryId.toString(), receipt.isolation?.scope?.key)
            assertTrue(journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }.any {
                it.reasonCode == "STORED_DIRECT_RESPONSE_RESTORE" && it.actionKind == "BATTLE_MAP"
            })
        }
        val firstSuccess = successes().single()
        val firstCycle = journal.page(accountId, AutomationHistoryQuery()).cycles.single { firstSuccess in it.events }.id
        if (precedingCast != null) {
            assertNotEquals(precedingCast.id, firstCycle)
            val retainedCast = journal.page(accountId, AutomationHistoryQuery()).cycles.single { it.id == precedingCast.id }
            assertEquals(precedingCast.events, retainedCast.events, "전투 복원은 앞서 완료한 START/CATCH 이력을 바꾸지 않는다.")
        }
        assertEquals("COMPLETED", jdbc.queryForObject(
            "select status from automation_work_sessions where id = ?", String::class.java, originalWorkId))
        assertFishingReleased()

        repeat(5) { if ("FCatch" !in fishingPosts()) consumeNextWake() }
        assertEquals(listOf("FStart", "FCatch"), fishingPosts())
        assertFishingReleased()
        val castCycles = journal.page(accountId, AutomationHistoryQuery()).cycles
        assertTrue(castCycles.any { it.id != firstCycle && it.selectedEntryId == entryId })
        repeat(5) { if (!homeAccepted) consumeNextWake() }
        assertTrue(homeAccepted, "전투·새 낚시 완료 뒤 독립 자택을 실제로 수락한다.")
        assertEquals(1, requests.count { it.formFields["action"] == "get" })
        repeat(3) { consumeNextWake() }

        assertOriginalResult()
        assertEquals(listOf(firstSuccess), successes(), "후속 작업에도 원래 전투 성공의 귀속과 발생 시각을 유지한다.")
        assertTrue(journal.page(accountId, AutomationHistoryQuery()).cycles.any {
            it.id == firstCycle && firstSuccess in it.events
        })
        assertEquals(1, battles().size)
        assertEquals(listOf("FStart", "FCatch"), fishingPosts())
        assertEquals(0, runningWorkCount())
        assertNull(jdbc.queryForObject("select lease_token from typed_automation_runtime_states where account_id = ?",
            String::class.java, accountId))
        assertTrue(clock.now() <= beganAt.plusSeconds(45), "새 낚시와 독립 판단을 긴 예약 뒤로 미루지 않는다.")
    }

    private fun verifyLateWorkerWithNewCatch(
        receiptStoredBeforeRecovery: Boolean,
        state: FishingFixtureState,
        battleCount: () -> Int,
        homeAccepted: () -> Boolean,
    ) {
        val directReady = CountDownLatch(1)
        val returnDirect = CountDownLatch(1)
        val catchReady = CountDownLatch(1)
        val returnCatch = CountDownLatch(1)
        val firstDirect = AtomicBoolean(true)
        state.beforeCatch = {
            catchReady.countDown()
            check(returnCatch.await(30, TimeUnit.SECONDS))
        }
        val preparedPlaceholder = battle()
        Mockito.doAnswer { invocation ->
            val managed = invocation.callRealMethod() as ManagedAutomationAction
            val payload = managed.storedAction.payload
            if (payload !is StoredTypedActionPayload.BattleMap ||
                payload.source != BattleAutomationActionSource.FISHING_AUTOMATION
            ) return@doAnswer managed
            object : ManagedAutomationAction by managed {
                override fun execute(): TypedAutomationExecution {
                    val execution = managed.execute()
                    if (!receiptStoredBeforeRecovery && firstDirect.compareAndSet(true, false)
                    ) {
                        directReady.countDown()
                        check(returnDirect.await(30, TimeUnit.SECONDS))
                    }
                    return execution
                }
            }
        }.`when`(actionLifecycle).prepare(Mockito.eq(accountId), Mockito.eq(entryId),
            Mockito.any<PreparedAutomationAction>() ?: preparedPlaceholder)
        if (receiptStoredBeforeRecovery) {
            val placeholder = Mockito.mock(ManagedAutomationAction::class.java)
            Mockito.doAnswer { invocation ->
                val managed = invocation.getArgument<ManagedAutomationAction>(0)
                val payload = managed.storedAction.payload
                if (payload is StoredTypedActionPayload.BattleMap &&
                    payload.source == BattleAutomationActionSource.FISHING_AUTOMATION && firstDirect.compareAndSet(true, false)
                ) {
                    directReady.countDown()
                    check(returnDirect.await(30, TimeUnit.SECONDS))
                }
                invocation.callRealMethod()
            }.`when`(results).applyDirect(
                Mockito.any<ManagedAutomationAction>() ?: placeholder,
                Mockito.any<TypedAutomationExecution>() ?: TypedAutomationExecution.Completed,
                Mockito.nullable(AutomationActionEvidence::class.java),
                Mockito.nullable(Long::class.javaObjectType),
            )
        }
        val otherTransport = AutomationRecoveryIntegrationTest.Config()
            .consumerReplayTransport(mapper, consumedEvents, consumerLease, runner, clock)
        val otherPublisher = AutomationOutboxPublisher(outbox, publishedMarker, otherTransport, clock)
        fun successes() = journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }
            .filter { it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED && it.actionKind == "BATTLE_MAP" }
        try {
            Executors.newFixedThreadPool(2).use { executor ->
                var catchWorker: Future<*>? = null
                fun consumeOtherWake(allowCatchWait: Boolean = false) {
                    val next = assertNotNull(jdbc.queryForObject(
                        "select min(available_at) from automation_outbox where account_id = ? and topic = ? and published_at is null",
                        java.time.OffsetDateTime::class.java, accountId, AutomationOutboxService.WAKEUP_TOPIC)).toInstant()
                    clock.current = maxOf(clock.now(), next)
                    val before = otherTransport.delivered.size
                    val future = executor.submit { otherPublisher.publishBatch() }
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (true) {
                        if (allowCatchWait && catchReady.count == 0L) {
                            catchWorker = future
                            return
                        }
                        try {
                            future.get(100, TimeUnit.MILLISECONDS)
                            break
                        } catch (_: TimeoutException) {
                            check(System.nanoTime() < deadline) { "다른 consumer가 다음 판단이나 CATCH 대기에 도달하지 않았다." }
                        }
                    }
                    assertTrue(otherTransport.delivered.size > before)
                    assertTrue(otherTransport.delivered.all { outbox.consumed(it) })
                    assertEquals(0, jdbc.queryForObject("select count(*) from account_automation_leases where account_id = ?",
                        Int::class.java, accountId))
                }
                val oldWorker = executor.submit { publisher.publishBatch() }
                try {
                    assertTrue(directReady.await(10, TimeUnit.SECONDS))
                    assertEquals(1, battleCount())
                    assertTrue(fishingPosts().isEmpty())
                    val original = jdbc.queryForMap(
                        "select status, submitted_at, execution_identity, direct_response_json from typed_automation_action_runs where account_id = ? and action_kind = 'BATTLE_MAP'",
                        accountId)
                    val identity = original["execution_identity"] as String
                    assertEquals("SUBMITTING", original["status"])
                    assertEquals(receiptStoredBeforeRecovery, original["direct_response_json"] != null)
                    val originalCycles = journal.page(accountId, AutomationHistoryQuery()).cycles.map { it.id }.toSet()
                    val originalWorkId = jdbc.queryForObject(
                        "select id from automation_work_sessions where account_id = ? and work_type = 'FISHING'",
                        Long::class.java, accountId)
                    val dispatched = outbox.findUnpublished(clock.now()).single {
                        it.topic == AutomationOutboxService.WAKEUP_TOPIC &&
                            mapper.readValue(it.payload, AutomationWakeupEvent::class.java).reason == "FISHING_BATTLE_DIRECT_RESULT"
                    }
                    publishedMarker.markPublished(dispatched.id)
                    clock.current = clock.now().plusSeconds(301)
                    wakeups.wake(accountId, "FISHING_STORED_RECEIPT_RECOVERY")
                    consumeOtherWake(allowCatchWait = true)
                    if (receiptStoredBeforeRecovery) {
                        assertEquals("SUCCEEDED", runs().single { it["execution_identity"] == identity }["status"])
                    } else {
                        assertTrue(successes().isEmpty(), "최신 관측만으로 원래 전투의 성공을 귀속하지 않는다.")
                        if (mode == AutomationConvergenceMode.ACTIVE) {
                            val held = assertNotNull(store.get(accountId, identity))
                            assertEquals(ActionConvergenceResult.HELD, held.result)
                            assertEquals("AMBIGUOUS", runs().single { it["execution_identity"] == identity }["status"])
                            val beforeRelease = requests.size
                            convergence.allowFreshDecision(accountId, held.attemptId)
                            assertEquals(beforeRelease, requests.size)
                        } else assertEquals("AMBIGUOUS", runs().single { it["execution_identity"] == identity }["status"],
                            "낚시 전투는 원래 단말 증거가 없으면 유니온의 맵 소멸과 달리 미확정 상태를 보존한다.")
                    }
                    val originalSuccess = successes().singleOrNull()
                    val restoredCycle = journal.page(accountId, AutomationHistoryQuery()).cycles.singleOrNull {
                        originalSuccess != null && originalSuccess in it.events
                    }
                    if (receiptStoredBeforeRecovery) {
                        assertTrue(assertNotNull(restoredCycle).id !in originalCycles)
                        assertTrue(restoredCycle.events.any { it.reasonCode == "STORED_DIRECT_RESPONSE_RESTORE" })
                    }
                    repeat(5) { if (catchWorker == null) consumeOtherWake(allowCatchWait = true) }
                    assertNotNull(catchWorker, "새 CATCH 대기에 도달해야 한다: runs=${runs()}, posts=${fishingPosts()}, " +
                        "events=${journal.page(accountId, AutomationHistoryQuery()).cycles.map { it.id to it.events.map { e -> e.reasonCode } }}")
                    assertEquals(listOf("FStart"), fishingPosts())
                    val newWork = jdbc.queryForMap(
                        "select id, work_type, target_key, status, next_check_at, finished_at from automation_work_sessions where account_id = ? and status = 'RUNNING'",
                        accountId)
                    if (receiptStoredBeforeRecovery) assertNotEquals(originalWorkId, newWork["id"])
                    val catchIdentity = assertNotNull(jdbc.queryForObject(
                        "select execution_identity from typed_automation_action_runs where account_id = ? and action_kind = 'FISHING_TOWN' and status = 'SUBMITTING'",
                        String::class.java, accountId))
                    assertNotEquals(identity, catchIdentity)
                    assertTrue(journal.page(accountId, AutomationHistoryQuery()).cycles.any { cycle ->
                        cycle.id !in originalCycles && cycle.events.any {
                            it.kind == AutomationHistoryEventKind.ACTION_STARTED && it.actionKind == "CATCH"
                        }
                    }, "미완료 작업 행의 재사용과 별개로 새 낚시는 새 판단과 행동 식별자로 실행한다.")
                    assertEquals("FISHING", newWork["work_type"])
                    assertEquals("DAILY_FISHING", newWork["target_key"])
                    assertNull(newWork["finished_at"])

                    returnDirect.countDown()
                    oldWorker.get(10, TimeUnit.SECONDS)

                    assertEquals(newWork, jdbc.queryForMap(
                        "select id, work_type, target_key, status, next_check_at, finished_at from automation_work_sessions where id = ?",
                        newWork["id"]), "늦은 전투 완료가 새 CATCH의 작업권을 종료하거나 교체하면 안 된다.")
                    assertEquals(listOf("FStart"), fishingPosts())
                    assertEquals("SUBMITTING", runs().single { it["execution_identity"] == catchIdentity }["status"])
                    assertEquals("SUCCEEDED", runs().single { it["execution_identity"] == identity }["status"])
                    val lateSuccess = successes().single()
                    if (originalSuccess != null) assertEquals(originalSuccess, lateSuccess)
                    assertTrue(journal.page(accountId, AutomationHistoryQuery()).cycles.any {
                        (if (receiptStoredBeforeRecovery) it.id == restoredCycle?.id else it.id in originalCycles) &&
                            lateSuccess in it.events
                    }, "최초 성공이 확인된 복원 또는 원래 제출 cycle의 귀속과 발생 시각을 보존한다.")
                    returnCatch.countDown()
                    catchWorker.get(10, TimeUnit.SECONDS)
                    repeat(5) { if (!homeAccepted()) consumeOtherWake() }
                    assertTrue(homeAccepted())
                    repeat(3) { consumeOtherWake() }
                    assertEquals(listOf("FStart", "FCatch"), fishingPosts())
                    assertEquals(1, battleCount())
                    assertEquals(lateSuccess, successes().single())
                    assertEquals(original["submitted_at"], runs().single { it["execution_identity"] == identity }["submitted_at"])
                    if (mode == AutomationConvergenceMode.ACTIVE || !receiptStoredBeforeRecovery) {
                        assertEquals(ActionConvergenceResult.APPLIED, assertNotNull(store.get(accountId, identity)).result)
                    } else assertNull(store.get(accountId, identity))
                    val expectedShadow = if (mode != AutomationConvergenceMode.SHADOW) emptyList()
                        else if (receiptStoredBeforeRecovery) listOf("APPLIED") else listOf("APPLIED", "RESULT_UNOBSERVED")
                    assertEquals(expectedShadow, jdbc.queryForList(
                        "select new_result from automation_convergence_shadow_evaluations where account_id = ? and execution_identity_hash = ?",
                        String::class.java, accountId, ProductionEvidenceShapes.fingerprint(identity)).map { requireNotNull(it) }.sorted())
                    assertEquals(1, requests.count { it.formFields["action"] == "get" && it.formFields["no"] == "A" })
                    assertEquals(0, runningWorkCount())
                    assertNull(jdbc.queryForObject("select lease_token from typed_automation_runtime_states where account_id = ?",
                        String::class.java, accountId))
                } finally {
                    returnDirect.countDown()
                    returnCatch.countDown()
                    oldWorker.get(10, TimeUnit.SECONDS)
                    catchWorker?.get(10, TimeUnit.SECONDS)
                }
            }
        } finally {
            otherTransport.delivered.forEach {
                jdbc.update("delete from automation_consumed_events where event_id = ?", it)
            }
        }
    }

    private fun readReceipt(identity: String): StoredAutomationDirectResponse = mapper.readValue(
        assertNotNull(jdbc.queryForObject(
            "select direct_response_json from typed_automation_action_runs where account_id = ? and execution_identity = ?",
            String::class.java, accountId, identity)), StoredAutomationDirectResponse::class.java)

    private fun corruptReceipt(identity: String, receipt: StoredAutomationDirectResponse, corruption: String) {
        if (corruption == "FINGERPRINT") {
            jdbc.update("update typed_automation_action_runs set direct_response_json = '{}' where account_id = ? and execution_identity = ?",
                accountId, identity)
            return
        }
        val response = assertIs<AutomationDirectResponse.BattleMap>(receipt.response)
        val invalidResponse = when (corruption) {
            "KIND" -> AutomationDirectResponse.QuestBattle(response.outcomes)
            "ROUNDS" -> response.copy(outcomes = emptyList())
            "NONTERMINAL" -> response.copy(outcomes = listOf(BattleAutomationRoundOutcome.UNKNOWN))
            "RESULT_ID" -> response.copy(resultIdentity = "")
            else -> error("지원하지 않는 손상 fixture: $corruption")
        }
        val invalidJson = mapper.writeValueAsString(receipt.copy(response = invalidResponse))
        assertEquals(invalidResponse, mapper.readValue(invalidJson, StoredAutomationDirectResponse::class.java).response)
        val actionFingerprint = assertNotNull(jdbc.queryForObject(
            "select action_fingerprint from typed_automation_action_runs where account_id = ? and execution_identity = ?",
            String::class.java, accountId, identity))
        // 저장 지문과 응답 의미의 검증을 구분한다.
        val fingerprint = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest("$accountId\n$identity\n$actionFingerprint\n$invalidJson".toByteArray(Charsets.UTF_8)))
        jdbc.update("update typed_automation_action_runs set direct_response_json = ?, direct_response_fingerprint = ? where account_id = ? and execution_identity = ?",
            invalidJson, fingerprint, accountId, identity)
    }

    private fun verifyCorruptReceiptHeld(
        identity: String,
        corruption: String,
        battleCount: () -> Int,
        homeAccepted: () -> Boolean,
        pauseBeforeRelease: Boolean,
    ) {
        fun assertOriginalHeld(afterNewSelection: Boolean = false) {
            assertEquals("RESULT_HELD", runs().single { it["execution_identity"] == identity }["status"])
            if (mode == AutomationConvergenceMode.ACTIVE) {
                assertEquals(ActionConvergenceResult.APPLIED, assertNotNull(store.get(accountId, identity)).result)
            } else assertNull(store.get(accountId, identity))
            assertTrue(journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }.none {
                it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED && it.actionKind == "BATTLE_MAP"
            }, "손상된 원래 전투의 성공 이력을 만들어서는 안 된다.")
            val expectedShadow = if (mode == AutomationConvergenceMode.SHADOW && afterNewSelection) listOf("SUPERSEDED") else emptyList()
            assertEquals(expectedShadow, jdbc.queryForList(
                "select new_result from automation_convergence_shadow_evaluations where account_id = ? and execution_identity_hash = ?",
                String::class.java, accountId, ProductionEvidenceShapes.fingerprint(identity)))
        }
        assertOriginalHeld()
        repeat(4) { consumeNextWake() }
        assertTrue(homeAccepted(), "낚시의 결과 보류가 독립 자택 수락을 막지 않는다.")
        assertTrue(fishingPosts().isEmpty(), "새 START도 같은 FISHING_ENTRY 보류 범위에 속한다.")
        assertEquals(1, battleCount())
        assertOriginalHeld()
        val held = convergence.get(accountId).localResults.single()
        assertEquals(entryId, held.entryId)
        assertEquals("FISHING_OBSTRUCTION_BATTLE", held.actionKind)
        assertEquals(TypedAutomationActionStatus.RESULT_HELD, held.status)
        assertEquals("LOCAL_RESULT_INTEGRITY_FAILED", held.reasonCode)
        assertEquals("낚시 자동화 $entryId", held.impactScope)
        assertTrue(held.canAllowFreshDecision)
        val snippet = assertNotNull(jdbc.queryForObject("select sanitized_snippet from automation_evidence_cases where id = ?",
            String::class.java, assertNotNull(held.evidenceCaseId)))
        assertTrue(snippet.contains("receiptValid=${corruption != "FINGERPRINT"}"), snippet)
        val beforeReleaseWork = jdbc.queryForList(
            "select id, work_type, target_key, status, next_check_at, finished_at from automation_work_sessions where account_id = ? order by id",
            accountId)
        val originalWork = beforeReleaseWork.single { it["work_type"] == "FISHING" }
        assertEquals("WAITING_COOLDOWN", originalWork["status"])
        assertNull(originalWork["finished_at"], "결과 보류 중에는 원래 작업을 완료하지 않는다.")
        assertTrue((originalWork["next_check_at"] as java.time.OffsetDateTime).toInstant() > clock.now())
        if (pauseBeforeRelease) application.pauseTyped(accountId)
        val beforeRelease = requests.size
        val releasedAt = clock.now()
        assertTrue(convergence.allowLocalFreshDecision(accountId, held.actionId).localResults.isEmpty())
        assertEquals(beforeRelease, requests.size, "명시 해제 자체는 HOF에 행동을 제출하지 않는다.")
        assertEquals(releasedAt, jdbc.queryForObject(
            "select next_check_at from automation_work_sessions where id = ?",
            java.time.OffsetDateTime::class.java, originalWork["id"])?.toInstant(),
            "사용자가 보류를 해제하면 원래 대기 작업은 다음 wake에서 최신 상태를 확인할 수 있다.")
        assertEquals(beforeReleaseWork.filter { it["work_type"] != "FISHING" }, jdbc.queryForList(
            "select id, work_type, target_key, status, next_check_at, finished_at from automation_work_sessions where account_id = ? and work_type <> 'FISHING' order by id",
            accountId), "독립 자택의 재확인 시각은 유지한다.")
        if (pauseBeforeRelease) {
            publisher.publishBatch()
            assertEquals("PAUSED", jdbc.queryForObject(
                "select lifecycle_status from typed_automation_runtime_states where account_id = ?",
                String::class.java, accountId))
            assertEquals(beforeRelease, requests.size, "보류 해제 wake를 소비해도 사용자 일시정지를 유지한다.")
            assertEquals(0, runningWorkCount())
            application.resumeTyped(accountId)
        }
        repeat(5) { if ("FCatch" !in fishingPosts()) consumeNextWake() }
        assertEquals(listOf("FStart", "FCatch"), fishingPosts())
        repeat(3) { consumeNextWake() }
        assertOriginalHeld(afterNewSelection = true)
        assertTrue(convergence.get(accountId).localResults.isEmpty())
        assertEquals(1, battleCount())
        assertEquals(listOf("FStart", "FCatch"), fishingPosts())
        assertEquals(1, requests.count { it.formFields["action"] == "get" && it.formFields["no"] == "A" })
        assertEquals(0, runningWorkCount())
        assertNull(jdbc.queryForObject("select lease_token from typed_automation_runtime_states where account_id = ?",
            String::class.java, accountId))
    }
}
