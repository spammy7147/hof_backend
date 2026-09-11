package app.spammy.hof.automation.service

import app.spammy.hof.automation.convergence.*
import app.spammy.hof.automation.entity.*
import app.spammy.hof.automation.history.AutomationHistoryEventKind
import app.spammy.hof.automation.history.AutomationHistoryQuery
import app.spammy.hof.automation.lease.AccountAutomationLeaseService
import app.spammy.hof.automation.outbox.*
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.entity.CharacterPatternSlotEntity
import app.spammy.hof.character.service.currentPatternForm
import app.spammy.hof.character.service.savedPatternLoadForm
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.party.entity.PartyPresetEntity
import app.spammy.hof.party.entity.PartyPresetMemberEntity
import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.home.model.HomeMode
import app.spammy.hof.town.home.parser.HomePageParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

@SpringBootTest(properties = ["hof.automation-convergence.mode=LEGACY"])
class LegacyAutomationAdventureDirectReceiptContinuityTest : AutomationAdventureDirectReceiptContinuityTest() {
    override val mode = AutomationConvergenceMode.LEGACY
}

@SpringBootTest(properties = ["hof.automation-convergence.mode=SHADOW"])
class ShadowAutomationAdventureDirectReceiptContinuityTest : AutomationAdventureDirectReceiptContinuityTest() {
    override val mode = AutomationConvergenceMode.SHADOW
}

@SpringBootTest(properties = ["hof.automation-convergence.mode=ACTIVE"])
class ActiveAutomationAdventureDirectReceiptContinuityTest : AutomationAdventureDirectReceiptContinuityTest() {
    override val mode = AutomationConvergenceMode.ACTIVE
}

@ActiveProfiles("test")
@Import(AutomationRecoveryIntegrationTest.Config::class)
abstract class AutomationAdventureDirectReceiptContinuityTest : AutomationRecoveryFixture() {
    @MockitoSpyBean private lateinit var results: AutomationResultCoordinator
    @Autowired private lateinit var convergence: AutomationConvergenceController
    @MockitoSpyBean private lateinit var actionLifecycle: AutomationActionLifecycleModule
    @Autowired private lateinit var mapper: tools.jackson.databind.ObjectMapper
    @Autowired private lateinit var consumedEvents: AutomationConsumedEventService
    @Autowired private lateinit var consumerLease: AccountAutomationLeaseService
    @Autowired private lateinit var publishedMarker: AutomationOutboxPublishMarker
    @Autowired private lateinit var runner: UnifiedAutomationRunner

    @Test
    fun `정상 모험맵 전투도 직접 단말 결과와 작업 완료를 보존하고 독립 판단을 이어간다`() =
        verifyAdventureDirectResponse(failFirstApplication = false)

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `모험맵 로컬 반영 전후 실패에도 원래 결과와 작업 완료를 복원한다`(failAfterProjection: Boolean) =
        verifyAdventureDirectResponse(failAfterProjection = failAfterProjection)

    @ParameterizedTest
    @ValueSource(strings = ["DEFEAT", "DRAW"])
    fun `모험맵 패배와 무승부도 단말 결과를 복원하고 재전송 없이 사이클을 완료한다`(outcome: String) =
        verifyAdventureDirectResponse(outcome = BattleAutomationRoundOutcome.valueOf(outcome))

    @ParameterizedTest
    @ValueSource(strings = ["FINGERPRINT", "KIND", "ROUNDS", "NONTERMINAL"])
    fun `손상된 모험맵 응답은 공유 전투만 보류하고 명시 해제 뒤 새 판단을 허용한다`(corruption: String) =
        verifyAdventureDirectResponse(corruption = corruption)

    @ParameterizedTest
    @CsvSource("false,false", "false,true", "true,false", "true,true")
    fun `늦은 모험맵 결과는 새 작업의 완료와 후처리 대기를 보존한다`(
        receiptStoredBeforeRecovery: Boolean,
        newBattleCompletes: Boolean,
    ) = verifyAdventureDirectResponse(failFirstApplication = false,
        receiptStoredBeforeRecovery = receiptStoredBeforeRecovery, newBattleCompletes = newBattleCompletes)

    private fun verifyAdventureDirectResponse(
        failFirstApplication: Boolean = true,
        failAfterProjection: Boolean = false,
        outcome: BattleAutomationRoundOutcome = BattleAutomationRoundOutcome.VICTORY,
        corruption: String? = null,
        receiptStoredBeforeRecovery: Boolean? = null,
        newBattleCompletes: Boolean = true,
    ) {
        Mockito.doCallRealMethod().`when`(decisions).select(accountId)
        var battles = 0
        var freshDecisionAllowed = false
        val relatedEntries = mutableListOf<Long>()
        var homeAccepted = false
        val homeUrl = "https://hof.zerosic.com/index.php?menu=quest2"
        fun homePage(): String {
            val heading = if (homeAccepted) "진행중인 작업 목록" else "수락 가능한 작업 목록"
            val action = if (homeAccepted) "-" else "<a href='?menu=quest2&amp;action=get&amp;no=A'>수락</a>"
            return """<div id='menu2'>Funds : $ 1 Time : 100/100</div><h4>$heading</h4><table>
                <tr><td>[A] 독립 자택</td><td>미션 0/1</td><td>-</td><td>-</td><td>$action</td></tr></table>"""
        }
        fun relatedMaps(): String {
            if (corruption == null) return ""
            val cooldown = if (battles > 1) " (1분) 남음" else ""
            return """<p><a href='index.php?sp_common=0005'>공유 모험$cooldown</a> 2 가능</p>"""
        }
        fun maps(general: Boolean = false) = """<html><body><div id='menu2'>Funds : $ 1 Time : 100/100</div>
            <div id='contents'><div>공유 지역 (2)</div><div id='mapgroup1'>
            <p><a href='index.php?${if (general) "common=0004" else "sp_common=0003"}'>도적소탕${if (battles > 0 && (battles > 1 || (corruption == null && !freshDecisionAllowed))) " (1분) 남음" else ""}</a> 2 가능</p>${if (general) "" else relatedMaps()}</div></div>
            <div id='foot'><h5>Copy Right sanitized fixture</h5><h6>H.O.F Korean Ver sanitized fixture</h6>
            <img src='image/zerohof.gif'></div></body></html>"""
        val home = HomePageParser().parse(HomeMode.HOME, homePage(), homeUrl,
            HofFormParser().parse(homePage(), homeUrl)).quests.single()
        TransactionTemplate(transactions).executeWithoutResult {
            val entry = entityManager.find(AutomationEntryEntity::class.java, entryId)
            entry.type = AutomationType.ADVENTURE_MAP
            entry.singletonTypeMarker = null
            entityManager.persist(AdventureAutomationMapEntity(entry = entry, categoryId = "adventure_map",
                mapCode = "0003", presetMode = PresetSelectionMode.PRIMARY, executionOrder = 0))
            val preset = PartyPresetEntity(account = entry.account, name = "모험맵 파티", isPrimary = true,
                createdAt = clock.now(), updatedAt = clock.now())
            entityManager.persist(preset)
            entityManager.createQuery("select c from CharacterEntity c where c.account.id = :id order by c.id", CharacterEntity::class.java)
                .setParameter("id", accountId).resultList.forEachIndexed { index, character ->
                    val pattern = CharacterPatternSlotEntity(character = character, slotCode = "1", label = "기본", canLoad = true)
                    entityManager.persist(pattern)
                    entityManager.persist(PartyPresetMemberEntity(preset, index, character, pattern))
                }
            if (corruption != null) {
                val sharedBattle = AutomationEntryEntity(account = entry.account, type = AutomationType.BATTLE_MAP,
                    priority = 1, enabled = true, createdAt = clock.now(), updatedAt = clock.now())
                val sharedAdventure = AutomationEntryEntity(account = entry.account, type = AutomationType.ADVENTURE_MAP,
                    priority = 2, enabled = true, createdAt = clock.now(), updatedAt = clock.now())
                entityManager.persist(sharedBattle)
                entityManager.persist(sharedAdventure)
                entityManager.persist(BattleAutomationMapEntity(entry = sharedBattle, categoryId = "battle_map",
                    mapCode = "0004", dailyTargetCount = 1, presetMode = PresetSelectionMode.PRIMARY, executionOrder = 0))
                entityManager.persist(AdventureAutomationMapEntity(entry = sharedAdventure, categoryId = "adventure_map",
                    mapCode = "0005", presetMode = PresetSelectionMode.PRIMARY, executionOrder = 0))
                entityManager.flush()
                relatedEntries += listOf(sharedBattle.id, sharedAdventure.id)
            }
            val homeEntry = AutomationEntryEntity(account = entry.account, type = AutomationType.HOME_QUEST,
                priority = if (corruption == null) 1 else 3, enabled = true, createdAt = clock.now(), updatedAt = clock.now())
            entityManager.persist(homeEntry)
            entityManager.persist(HomeQuestAutomationSelectionEntity(entry = homeEntry, questId = home.id,
                questName = home.name, enabled = true, sourceOrder = 0))
        }
        Mockito.doAnswer { invocation ->
            val request = invocation.getArgument<HofRequest>(1)
            requests += request
            val body = when {
                request.url.contains("?char=") -> "<div>Funds : $ 1 Time : 100/100</div>" + currentPatternForm() + savedPatternLoadForm(1)
                request.url.contains("menu=quest2") -> {
                    if (request.formFields["action"] == "get") {
                        assertEquals(HofHttpMethod.GET, request.method)
                        assertEquals("A", request.formFields["no"])
                        assertFalse(homeAccepted)
                        homeAccepted = true
                    }
                    homePage()
                }
                request.method == HofHttpMethod.GET -> maps(general = request.url.contains("?hunt"))
                else -> {
                    assertEquals(HofHttpMethod.POST, request.method)
                    assertEquals("https://hof.zerosic.com/index.php?sp_common=0003", request.url)
                    assertTrue(++battles <= if (freshDecisionAllowed) 2 else 1, "명시 해제 전에는 새 모험맵 전투를 제출하지 않는다.")
                    val title = when (outcome) {
                        BattleAutomationRoundOutcome.VICTORY -> "테스트은(는) 승리했다!"
                        BattleAutomationRoundOutcome.DEFEAT -> "도적은(는) 승리했다!"
                        BattleAutomationRoundOutcome.DRAW -> "무승부!"
                        else -> error("직접 단말 응답만 사용한다.")
                    }
                    """<div id='menu2'>Funds : $ 1 Time : 100/100</div><h2>Show Detail( 1 turns. )</h2><h1>$title</h1>
                        <div>남은 HP : 0/100 생존자 : 0/1 총 데미지 : 0</div>
                        <div>남은 HP : 100/100 생존자 : 1/1 총 데미지 : 100 턴 : 1/100 획득 경험치 : 1 획득 Funds : $ 1</div>"""
                }
            }
            HofHttpResponse(200, request.url, body, emptyMap())
        }.`when`(gateway).execute(Mockito.eq(accountId), anyRequest(), Mockito.anyMap())
        if (receiptStoredBeforeRecovery != null) {
            verifyLateWorkerPreservesNewWork(receiptStoredBeforeRecovery, newBattleCompletes,
                { battles }, { homeAccepted }) { freshDecisionAllowed = true }
            return
        }
        var failedOnce = false
        val placeholder = Mockito.mock(ManagedAutomationAction::class.java)
        Mockito.doAnswer { invocation ->
            val managed = invocation.getArgument<ManagedAutomationAction>(0)
            if (failFirstApplication && !failedOnce && managed.storedAction.payload is StoredTypedActionPayload.AdventureMap) {
                assertEquals(1, battles)
                if (failAfterProjection) {
                    invocation.callRealMethod()
                    assertEquals("COMPLETED", jdbc.queryForObject(
                        "select status from automation_work_sessions where account_id = ? and automation_entry_id = ? and work_type = 'ADVENTURE_MAP'",
                        String::class.java, accountId, entryId))
                }
                failedOnce = true
                throw IllegalStateException("수신한 모험맵 단말 결과의 첫 로컬 반영 실패")
            }
            invocation.callRealMethod()
        }.`when`(results).applyDirect(
            Mockito.any<ManagedAutomationAction>() ?: placeholder,
            Mockito.any<TypedAutomationExecution>() ?: TypedAutomationExecution.Completed,
            Mockito.nullable(AutomationActionEvidence::class.java),
            Mockito.nullable(Long::class.javaObjectType),
        )

        val beganAt = clock.now()
        wakeups.wake(accountId, "ADVENTURE_DIRECT_RESULT")
        publisher.publishBatch()
        assertEquals(failFirstApplication, failedOnce, "runs=" + runs() + "; requests=" + requests.map { it.url } + "; events=" + journal.page(accountId, AutomationHistoryQuery()).cycles)
        assertEquals(1, battles)
        assertFalse(homeAccepted)
        val identity = assertIs<String>(runs().single()["execution_identity"])
        assertEquals(if (failFirstApplication) "RESULT_PENDING" else "SUCCEEDED", runs().single()["status"])
        val json = assertNotNull(jdbc.queryForObject(
            "select direct_response_json from typed_automation_action_runs where account_id = ? and execution_identity = ?",
            String::class.java, accountId, identity))
        val mapper = jacksonObjectMapper()
        val receipt = mapper.readValue(json, StoredAutomationDirectResponse::class.java)
        assertEquals("ADVENTURE_BATTLE", mapper.readTree(json)["response"]["kind"].asString())
        assertEquals(outcome.name, mapper.readTree(json)["response"]["outcomes"][0].asString())
        assertEquals(AutomationActionKind.ADVENTURE_BATTLE, assertNotNull(receipt.policyContext).actionKind)
        assertEquals(DirectResponseActionScope(entryId,
            AutomationIsolationScope(AutomationIsolationScopeKind.BATTLE_COOLDOWN_SCOPE, "shared-battle-cooldown")), receipt.isolation)
        assertEquals(0, runningWorkCount())
        if (failFirstApplication) {
            if (!failAfterProjection) {
                val waiting = jdbc.queryForMap("select target_key, status, next_check_at from automation_work_sessions where account_id = ? and work_type = 'ADVENTURE_MAP'", accountId)
                assertEquals("adventure_map/0003", waiting["target_key"])
                assertEquals("WAITING_COOLDOWN", waiting["status"])
                assertEquals(beganAt.plusSeconds(10), (waiting["next_check_at"] as java.time.OffsetDateTime).toInstant())
            }
            consumeNextWake()
            assertTrue(homeAccepted, "모험맵 로컬 결과 재시도 전에 독립 자택을 실제 수락한다.")
            assertTrue(clock.now().isBefore(beganAt.plusSeconds(10)))
            assertEquals("RESULT_PENDING", runs().single { it["execution_identity"] == identity }["status"])
            if (corruption != null) corruptReceipt(identity, receipt, corruption)
            clock.current = receipt.capturedAt.plusSeconds(11)
            repeat(6) {
                if (runs().single { it["execution_identity"] == identity }["status"] !in setOf("SUCCEEDED", "RESULT_HELD")) consumeNextWake()
            }
        }
        if (corruption != null) {
            verifyCorruptReceiptHeld(identity, corruption, relatedEntries, { battles }, { homeAccepted }) {
                freshDecisionAllowed = true
            }
            return
        }
        assertEquals("SUCCEEDED", runs().single { it["execution_identity"] == identity }["status"])
        assertEquals("COMPLETED", jdbc.queryForObject(
            "select status from automation_work_sessions where account_id = ? and automation_entry_id = ? and work_type = 'ADVENTURE_MAP'",
            String::class.java, accountId, entryId), "복원한 단말 결과는 원래 모험맵 사이클을 완료한다.")
        assertEquals(json, jdbc.queryForObject("select direct_response_json from typed_automation_action_runs where account_id = ? and execution_identity = ?", String::class.java, accountId, identity))
        if (mode == AutomationConvergenceMode.ACTIVE) {
            val record = assertNotNull(store.get(accountId, identity))
            assertEquals(ActionConvergenceResult.APPLIED, record.result)
            assertEquals(AutomationActionKind.ADVENTURE_BATTLE, record.selection.actionKind)
        } else assertNull(store.get(accountId, identity))
        assertEquals(if (mode == AutomationConvergenceMode.SHADOW) listOf("APPLIED") else emptyList(),
            jdbc.queryForList("select new_result from automation_convergence_shadow_evaluations where account_id = ? and execution_identity_hash = ?",
                String::class.java, accountId, ProductionEvidenceShapes.fingerprint(identity)))
        val success = journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }
            .single { it.type == AutomationType.ADVENTURE_MAP && it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED }
        assertEquals("ADVENTURE_MAP", success.actionKind)
        assertEquals("adventure_map/0003", success.targetKey)
        repeat(4) { consumeNextWake() }
        assertEquals(success, journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }.single { it.id == success.id })
        assertEquals(1, battles)
        assertEquals(1, requests.count { it.formFields["action"] == "get" && it.formFields["no"] == "A" })
        assertEquals(0, runningWorkCount())
        assertEquals(0, jdbc.queryForObject("select count(*) from account_automation_leases where account_id = ?", Int::class.java, accountId))
        assertNull(jdbc.queryForObject("select lease_token from typed_automation_runtime_states where account_id = ?", String::class.java, accountId))
    }

    private fun verifyLateWorkerPreservesNewWork(
        receiptStoredBeforeRecovery: Boolean,
        newBattleCompletes: Boolean,
        battleCount: () -> Int,
        homeAccepted: () -> Boolean,
        allowFixtureBattle: () -> Unit,
    ) {
        val directReady = CountDownLatch(1)
        val returnDirect = CountDownLatch(1)
        val firstDirect = AtomicBoolean(true)
        val firstIdentity = AtomicReference<String>()
        val failedNewResponse = AtomicBoolean(false)
        Mockito.doAnswer { invocation ->
            val managed = invocation.callRealMethod() as ManagedAutomationAction
            if (managed.storedAction.payload !is StoredTypedActionPayload.AdventureMap) return@doAnswer managed
            firstIdentity.compareAndSet(null, managed.storedAction.executionIdentity)
            object : ManagedAutomationAction by managed {
                override fun execute(): TypedAutomationExecution {
                    val execution = managed.execute()
                    if (!receiptStoredBeforeRecovery && firstDirect.compareAndSet(true, false)) {
                        directReady.countDown()
                        check(returnDirect.await(30, TimeUnit.SECONDS))
                    }
                    return execution
                }
            }
        }.`when`(actionLifecycle).prepare(Mockito.eq(accountId), Mockito.eq(entryId),
            Mockito.any<PreparedAutomationAction>() ?: battle())
        val placeholder = Mockito.mock(ManagedAutomationAction::class.java)
        Mockito.doAnswer { invocation ->
            val managed = invocation.getArgument<ManagedAutomationAction>(0)
            if (managed.storedAction.payload is StoredTypedActionPayload.AdventureMap) {
                if (receiptStoredBeforeRecovery && firstDirect.compareAndSet(true, false)) {
                    directReady.countDown()
                    check(returnDirect.await(30, TimeUnit.SECONDS))
                }
                if (!newBattleCompletes && managed.storedAction.executionIdentity != firstIdentity.get() &&
                    failedNewResponse.compareAndSet(false, true)) {
                    throw IllegalStateException("새 모험맵 직접 응답의 첫 후처리를 재시도로 양보한다.")
                }
            }
            invocation.callRealMethod()
        }.`when`(results).applyDirect(
            Mockito.any<ManagedAutomationAction>() ?: placeholder,
            Mockito.any<TypedAutomationExecution>() ?: TypedAutomationExecution.Completed,
            Mockito.nullable(AutomationActionEvidence::class.java),
            Mockito.nullable(Long::class.javaObjectType),
        )
        val otherTransport = AutomationRecoveryIntegrationTest.Config()
            .consumerReplayTransport(mapper, consumedEvents, consumerLease, runner, clock)
        val otherPublisher = AutomationOutboxPublisher(outbox, publishedMarker, otherTransport, clock)
        fun consumeOtherWake() {
            val next = assertNotNull(jdbc.queryForObject(
                "select min(available_at) from automation_outbox where account_id = ? and topic = ? and published_at is null",
                java.time.OffsetDateTime::class.java, accountId, AutomationOutboxService.WAKEUP_TOPIC)).toInstant()
            clock.current = maxOf(clock.now(), next)
            val before = otherTransport.delivered.size
            otherPublisher.publishBatch()
            assertTrue(otherTransport.delivered.size > before)
            assertTrue(otherTransport.delivered.all { outbox.consumed(it) })
            assertEquals(0, jdbc.queryForObject("select count(*) from account_automation_leases where account_id = ?",
                Int::class.java, accountId))
        }
        fun cycles() = journal.page(accountId, AutomationHistoryQuery(limit = 100)).cycles
        fun successes() = cycles().flatMap { it.events }.filter {
            it.type == AutomationType.ADVENTURE_MAP && it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED
        }
        fun workFor(identity: String) = jdbc.queryForMap(
            "select id, status, next_check_at, finished_at, last_prepared_execution_identity from automation_work_sessions where account_id = ? and last_prepared_execution_identity = ?",
            accountId, identity)
        wakeups.wake(accountId, "ADVENTURE_LATE_DIRECT")
        try {
            Executors.newSingleThreadExecutor().use { executor ->
                val oldWorker = executor.submit { publisher.publishBatch() }
                try {
                    assertTrue(directReady.await(10, TimeUnit.SECONDS))
                    assertEquals(1, battleCount())
                    val original = jdbc.queryForMap(
                        "select status, execution_identity, direct_response_json, submitted_at from typed_automation_action_runs where account_id = ? and action_kind = 'ADVENTURE_MAP'",
                        accountId)
                    val identity = assertIs<String>(original["execution_identity"])
                    assertEquals(firstIdentity.get(), identity)
                    assertEquals("SUBMITTING", original["status"])
                    assertEquals(receiptStoredBeforeRecovery, original["direct_response_json"] != null)
                    assertEquals("RUNNING", workFor(identity)["status"])
                    assertTrue(successes().isEmpty())
                    val originalCycles = cycles().map { it.id }.toSet()
                    val dispatched = outbox.findUnpublished(clock.now()).single {
                        it.topic == AutomationOutboxService.WAKEUP_TOPIC &&
                            mapper.readValue(it.payload, AutomationWakeupEvent::class.java).reason == "ADVENTURE_LATE_DIRECT"
                    }
                    publishedMarker.markPublished(dispatched.id)
                    clock.current = clock.now().plusSeconds(301)
                    wakeups.wake(accountId, "ADVENTURE_STORED_RECEIPT_RECOVERY")
                    consumeOtherWake()
                    val originalSuccess = successes().singleOrNull()
                    if (receiptStoredBeforeRecovery) {
                        assertEquals("SUCCEEDED", runs().single { it["execution_identity"] == identity }["status"])
                        assertNotNull(originalSuccess)
                        assertTrue(cycles().any { it.id !in originalCycles && originalSuccess in it.events })
                    } else {
                        assertNull(originalSuccess, "최신 쿨다운만으로 원래 전투의 성공을 귀속하지 않는다.")
                        if (mode == AutomationConvergenceMode.ACTIVE) {
                            assertEquals("AMBIGUOUS", runs().single { it["execution_identity"] == identity }["status"])
                            val held = assertNotNull(store.get(accountId, identity))
                            assertEquals(ActionConvergenceResult.HELD, held.result)
                            val beforeRelease = requests.size
                            convergence.allowFreshDecision(accountId, held.attemptId)
                            assertEquals(beforeRelease, requests.size)
                        } else {
                            assertEquals("FAILED", runs().single { it["execution_identity"] == identity }["status"])
                            assertTrue(cycles().flatMap { it.events }.any { it.reasonCode == "ACTION_SUPERSEDED_BY_FRESH_STATE" })
                        }
                    }
                    allowFixtureBattle()
                    repeat(20) { if (battleCount() < 2) consumeOtherWake() }
                    assertEquals(2, battleCount(), "복구 이후 실제 새 모험맵 전투에 도달한다: " + runs())
                    val newIdentity = assertNotNull(jdbc.queryForObject(
                        "select execution_identity from typed_automation_action_runs where account_id = ? and action_kind = 'ADVENTURE_MAP' and execution_identity <> ?",
                        String::class.java, accountId, identity))
                    assertNotEquals(identity, newIdentity)
                    val newStatus = if (newBattleCompletes) "SUCCEEDED" else "RESULT_PENDING"
                    assertEquals(newStatus, runs().single { it["execution_identity"] == newIdentity }["status"])
                    assertEquals(!newBattleCompletes, failedNewResponse.get())
                    val newWork = workFor(newIdentity)
                    assertEquals(if (newBattleCompletes) "COMPLETED" else "WAITING_COOLDOWN", newWork["status"])
                    val newReceipt = jdbc.queryForObject(
                        "select direct_response_json from typed_automation_action_runs where account_id = ? and execution_identity = ?",
                        String::class.java, accountId, newIdentity)
                    assertNotNull(newReceipt)
                    val successesBefore = successes()
                    assertEquals((if (receiptStoredBeforeRecovery) 1 else 0) + (if (newBattleCompletes) 1 else 0), successesBefore.size)

                    returnDirect.countDown()
                    oldWorker.get(10, TimeUnit.SECONDS)

                    assertEquals(newWork, workFor(newIdentity), "늦은 원래 결과가 새 작업의 상태·식별자·재확인 시각을 바꾸면 안 된다.")
                    assertEquals(newStatus, runs().single { it["execution_identity"] == newIdentity }["status"])
                    assertEquals(newReceipt, jdbc.queryForObject(
                        "select direct_response_json from typed_automation_action_runs where account_id = ? and execution_identity = ?",
                        String::class.java, accountId, newIdentity))
                    assertEquals("SUCCEEDED", runs().single { it["execution_identity"] == identity }["status"])
                    assertEquals(original["submitted_at"], runs().single { it["execution_identity"] == identity }["submitted_at"])
                    if (originalSuccess != null) assertTrue(originalSuccess in successes())
                    else assertEquals(1, cycles().filter { it.id in originalCycles }.flatMap { it.events }.count {
                        it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED && it.type == AutomationType.ADVENTURE_MAP
                    }, "늦게 수신한 원래 성공은 최초 제출 판단에 귀속한다.")
                    assertTrue(successes().containsAll(successesBefore))
                    repeat(10) {
                        if (!homeAccepted() || runs().single { it["execution_identity"] == newIdentity }["status"] != "SUCCEEDED") {
                            consumeOtherWake()
                        }
                    }
                    assertEquals("SUCCEEDED", runs().single { it["execution_identity"] == newIdentity }["status"])
                    assertEquals("COMPLETED", workFor(newIdentity)["status"])
                    assertTrue(homeAccepted())
                    val finalSuccesses = successes()
                    assertEquals(2, finalSuccesses.size)
                    repeat(3) { consumeOtherWake() }
                    assertEquals(finalSuccesses, successes())
                    assertEquals(2, battleCount())
                    assertEquals(1, requests.count { it.formFields["action"] == "get" && it.formFields["no"] == "A" })
                    assertEquals(0, runningWorkCount())
                    assertNull(jdbc.queryForObject("select lease_token from typed_automation_runtime_states where account_id = ?",
                        String::class.java, accountId))
                } finally {
                    returnDirect.countDown()
                    oldWorker.get(10, TimeUnit.SECONDS)
                }
            }
        } finally {
            otherTransport.delivered.forEach { jdbc.update("delete from automation_consumed_events where event_id = ?", it) }
        }
    }

    private fun corruptReceipt(identity: String, receipt: StoredAutomationDirectResponse, corruption: String) {
        val response = assertIs<AutomationDirectResponse.AdventureBattle>(receipt.response)
        val invalidResponse = when (corruption) {
            "KIND" -> AutomationDirectResponse.QuestBattle(response.outcomes)
            "ROUNDS" -> response.copy(outcomes = emptyList())
            "NONTERMINAL" -> response.copy(outcomes = listOf(BattleAutomationRoundOutcome.UNKNOWN))
            "FINGERPRINT" -> response
            else -> error("지원하지 않는 손상 fixture")
        }
        val invalid = jacksonObjectMapper().writeValueAsString(receipt.copy(response = invalidResponse))
        val actionFingerprint = assertNotNull(jdbc.queryForObject(
            "select action_fingerprint from typed_automation_action_runs where account_id = ? and execution_identity = ?",
            String::class.java, accountId, identity))
        val fingerprint = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(listOf(accountId, identity, actionFingerprint, invalid).joinToString("\n").toByteArray(Charsets.UTF_8)))
        jdbc.update("update typed_automation_action_runs set direct_response_json = ?, direct_response_fingerprint = ? where account_id = ? and execution_identity = ?",
            if (corruption == "FINGERPRINT") "{}" else invalid, fingerprint, accountId, identity)
    }

    private fun verifyCorruptReceiptHeld(
        identity: String,
        corruption: String,
        relatedEntries: List<Long>,
        battleCount: () -> Int,
        homeAccepted: () -> Boolean,
        allowFixtureBattle: () -> Unit,
    ) {
        val invalidJson = jdbc.queryForObject(
            "select direct_response_json from typed_automation_action_runs where account_id = ? and execution_identity = ?",
            String::class.java, accountId, identity)
        fun events() = journal.page(accountId, AutomationHistoryQuery(limit = 100)).cycles.flatMap { it.events }
        fun successes() = events().filter {
            it.type == AutomationType.ADVENTURE_MAP && it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED
        }
        fun assertOriginalHeld(afterNewSelection: Boolean = false) {
            assertEquals("RESULT_HELD", runs().single { it["execution_identity"] == identity }["status"])
            if (mode == AutomationConvergenceMode.ACTIVE) {
                assertEquals(ActionConvergenceResult.APPLIED, assertNotNull(store.get(accountId, identity)).result)
            } else assertNull(store.get(accountId, identity))
            assertEquals(if (mode == AutomationConvergenceMode.SHADOW && afterNewSelection) listOf("SUPERSEDED") else emptyList(),
                jdbc.queryForList("select new_result from automation_convergence_shadow_evaluations where account_id = ? and execution_identity_hash = ?",
                    String::class.java, accountId, ProductionEvidenceShapes.fingerprint(identity)))
            assertEquals(invalidJson, jdbc.queryForObject(
                "select direct_response_json from typed_automation_action_runs where account_id = ? and execution_identity = ?",
                String::class.java, accountId, identity))
        }
        assertOriginalHeld()
        repeat(4) { consumeNextWake() }
        assertTrue(homeAccepted(), "공유 전투 보류가 독립 자택의 실제 수락을 막지 않는다.")
        assertEquals(1, battleCount())
        assertTrue(successes().isEmpty())
        relatedEntries.forEach { related ->
            assertTrue(events().any { it.entryId == related && it.reasonCode == "DIRECT_RESULT_PENDING" },
                "같은 공유 쿨다운의 일반 전투맵·다른 모험맵도 결과 보류로 건너뛴다: " + related + " " + events())
        }
        assertOriginalHeld()
        val originalWork = jdbc.queryForMap(
            "select id, status, finished_at, last_prepared_execution_identity from automation_work_sessions where account_id = ? and automation_entry_id = ?",
            accountId, entryId)
        assertEquals("WAITING_COOLDOWN", originalWork["status"],
            "손상 응답의 보류만으로 원래 모험맵 작업을 완료하지 않는다: " + originalWork)
        assertNull(originalWork["finished_at"])
        assertEquals(identity, originalWork["last_prepared_execution_identity"])
        val held = convergence.get(accountId).localResults.single()
        assertEquals(entryId, held.entryId)
        assertEquals("ADVENTURE_BATTLE", held.actionKind)
        assertEquals(TypedAutomationActionStatus.RESULT_HELD, held.status)
        assertEquals("LOCAL_RESULT_INTEGRITY_FAILED", held.reasonCode)
        assertEquals("공유 전투 쿨타임 shared-battle-cooldown", held.impactScope)
        assertTrue(held.canAllowFreshDecision)
        val snippet = assertNotNull(jdbc.queryForObject(
            "select sanitized_snippet from automation_evidence_cases where id = ?", String::class.java,
            assertNotNull(held.evidenceCaseId)))
        assertTrue(snippet.contains("receiptValid=" + (corruption != "FINGERPRINT")), snippet)
        val requestsBeforeRelease = requests.size
        val releasedAt = clock.now()
        assertTrue(convergence.allowLocalFreshDecision(accountId, held.actionId).localResults.isEmpty())
        assertEquals(requestsBeforeRelease, requests.size, "명시 해제 자체는 원격 행동을 제출하지 않는다.")
        assertEquals(releasedAt, jdbc.queryForObject(
            "select next_check_at from automation_work_sessions where account_id = ? and automation_entry_id = ?",
            java.time.OffsetDateTime::class.java, accountId, entryId)?.toInstant())
        allowFixtureBattle()
        repeat(6) { if (battleCount() < 2) consumeNextWake() }
        assertEquals(2, battleCount(), "해제 뒤 최신 관측에서 새 모험맵 전투를 한 번 판단한다.")
        val nextIdentity = assertNotNull(jdbc.queryForObject(
            "select execution_identity from typed_automation_action_runs where account_id = ? and action_kind = 'ADVENTURE_MAP' and execution_identity <> ?",
            String::class.java, accountId, identity))
        assertNotEquals(identity, nextIdentity)
        assertEquals("SUCCEEDED", runs().single { it["execution_identity"] == nextIdentity }["status"])
        val work = jdbc.queryForMap(
            "select id, status, last_prepared_execution_identity from automation_work_sessions where account_id = ? and automation_entry_id = ?",
            accountId, entryId)
        assertEquals(originalWork["id"], work["id"], "같은 대상의 유효한 대기 작업을 새 판단에서 이어간다.")
        assertEquals("COMPLETED", work["status"])
        assertEquals(nextIdentity, work["last_prepared_execution_identity"])
        val newResult = successes().single()
        assertEquals("adventure_map/0003", newResult.targetKey)
        repeat(3) { consumeNextWake() }
        assertOriginalHeld(afterNewSelection = true)
        assertEquals(listOf(newResult), successes(), "새 전투의 성공만 기록하고 손상된 원래 결과는 성공으로 만들지 않는다.")
        assertTrue(convergence.get(accountId).localResults.isEmpty())
        assertEquals(2, battleCount())
        assertEquals(1, requests.count { it.formFields["action"] == "get" && it.formFields["no"] == "A" })
        assertEquals(0, runningWorkCount())
        assertNull(jdbc.queryForObject("select lease_token from typed_automation_runtime_states where account_id = ?",
            String::class.java, accountId))
    }
}
