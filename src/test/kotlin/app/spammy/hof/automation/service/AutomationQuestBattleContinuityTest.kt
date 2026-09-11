package app.spammy.hof.automation.service

import app.spammy.hof.automation.convergence.*
import app.spammy.hof.automation.entity.*
import app.spammy.hof.automation.history.AutomationHistoryEventKind
import app.spammy.hof.automation.history.AutomationHistoryQuery
import app.spammy.hof.automation.lease.AccountAutomationLeaseService
import app.spammy.hof.automation.outbox.*
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.entity.CharacterPatternSlotEntity
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.party.entity.PartyPresetEntity
import app.spammy.hof.party.entity.PartyPresetMemberEntity
import app.spammy.hof.quest.parser.QuestPageParser
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
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

@SpringBootTest(properties = ["hof.automation-convergence.mode=LEGACY"])
class LegacyAutomationQuestBattleContinuityTest : AutomationQuestBattleContinuityTest() {
    override val mode = AutomationConvergenceMode.LEGACY
}

@SpringBootTest(properties = ["hof.automation-convergence.mode=SHADOW"])
class ShadowAutomationQuestBattleContinuityTest : AutomationQuestBattleContinuityTest() {
    override val mode = AutomationConvergenceMode.SHADOW
}

@SpringBootTest(properties = ["hof.automation-convergence.mode=ACTIVE"])
class ActiveAutomationQuestBattleContinuityTest : AutomationQuestBattleContinuityTest() {
    override val mode = AutomationConvergenceMode.ACTIVE
}

@ActiveProfiles("test")
@Import(AutomationRecoveryIntegrationTest.Config::class)
abstract class AutomationQuestBattleContinuityTest : AutomationRecoveryFixture() {
    @MockitoSpyBean private lateinit var results: AutomationResultCoordinator
    @MockitoSpyBean private lateinit var actionLifecycle: AutomationActionLifecycleModule
    @Autowired private lateinit var mapper: tools.jackson.databind.ObjectMapper
    @Autowired private lateinit var convergence: AutomationConvergenceController
    @Autowired private lateinit var consumedEvents: AutomationConsumedEventService
    @Autowired private lateinit var consumerLease: AccountAutomationLeaseService
    @Autowired private lateinit var publishedMarker: AutomationOutboxPublishMarker
    @Autowired private lateinit var runner: UnifiedAutomationRunner

    @Test
    fun `퀘스트 전투의 직접 승리를 한 번 기록하고 독립 자택과 다음 판단을 이어간다`() =
        verifyQuestBattleDirectResult()

    @Test
    fun `수신한 퀘스트 전투 승리의 로컬 완료가 실패해도 원래 결과와 후속 판단을 복구한다`() =
        verifyQuestBattleDirectResult(failFirstApplication = true)

    @ParameterizedTest
    @ValueSource(strings = ["DEFEAT", "DRAW"])
    fun `패배와 무승부의 직접 결과는 복원해도 퀘스트 승리를 늘리지 않는다`(outcome: String) =
        verifyQuestBattleDirectResult(failFirstApplication = true,
            outcomes = listOf(BattleAutomationRoundOutcome.valueOf(outcome)), expectedVictories = 0)

    @Test
    fun `세 회차의 승패와 무승부를 복원해 확인된 승리만 한 번 반영한다`() =
        verifyQuestBattleDirectResult(failFirstApplication = true, outcomes = listOf(
            BattleAutomationRoundOutcome.VICTORY, BattleAutomationRoundOutcome.DEFEAT, BattleAutomationRoundOutcome.DRAW,
        ), expectedVictories = 1)

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `원래 전투 응답의 저장 전후에 복구가 먼저 끝나도 늦은 worker는 승리와 이력을 반복하지 않는다`(
        receiptStoredBeforeRecovery: Boolean,
    ) = verifyQuestBattleDirectResult(receiptStoredBeforeRecovery = receiptStoredBeforeRecovery)

    @ParameterizedTest
    @ValueSource(strings = ["FINGERPRINT", "KIND", "ROUNDS", "NONTERMINAL"])
    fun `손상된 전투 응답은 원래 퀘스트만 보류하고 독립 판단과 명시 해제를 허용한다`(corruption: String) =
        verifyQuestBattleDirectResult(failFirstApplication = true, corruption = corruption)

    private fun verifyQuestBattleDirectResult(
        failFirstApplication: Boolean = false,
        corruption: String? = null,
        outcomes: List<BattleAutomationRoundOutcome> = listOf(BattleAutomationRoundOutcome.VICTORY),
        expectedVictories: Int = 1,
        receiptStoredBeforeRecovery: Boolean? = null,
    ) {
        Mockito.doCallRealMethod().`when`(decisions).select(accountId)
        val questUrl = "https://hof.zerosic.com/index.php?menu=quest"
        val homeUrl = "https://hof.zerosic.com/index.php?menu=quest2"
        val availableTime = if (outcomes.size == 3) 300 else 100
        val questFixture = requireNotNull(javaClass.getResource("/fixtures/quest/quest-complete-empty.html")).readText()
            .replaceFirst("</table>", """<tr><td class="td7s">[0571] 저택 서관 열쇠 수집</td>
                <td>미션 : 몬스터 처치( Killer Maid ) - [ 12 / 30 ]</td><td>-</td><td>-</td>
                <td class="td8s">-</td></tr></table>""")
        var battleCount = 0
        var homeAccepted = false
        fun questPage() = questFixture.replace("[ 12 / 30 ]",
            if (battleCount == 0) "[ 12 / 30 ]" else "[ ${12 + expectedVictories} / 30 ]")
        fun homePage(): String {
            val heading = if (homeAccepted) "진행중인 작업 목록" else "수락 가능한 작업 목록"
            val action = if (homeAccepted) "-" else "<a href='?menu=quest2&amp;action=get&amp;no=A'>수락</a>"
            return """<div id='menu2'>Funds : $ 1 Time : $availableTime/$availableTime</div><h4>$heading</h4><table>
                <tr><td>[A] 독립 자택</td><td>미션 0/1</td><td>-</td><td>-</td><td>$action</td></tr></table>"""
        }
        val threeBattleForm = if (outcomes.size == 3)
            "<form action='index.php?common=0003'><input type='submit' name='monster_battle_10' value='Battle !'></form>" else ""
        fun maps() = """<html><body><div id='menu2'>Funds : $ 1 Time : $availableTime/$availableTime</div>
            <div id='contents'><div>공유 지역 (2)</div><div id='mapgroup1'>
            <p><a href='index.php?common=0003'>도적소탕${if (battleCount > 0) " (1분) 남음" else ""}</a> 3 가능</p>$threeBattleForm</div></div>
            <div id='foot'><h5>Copy Right sanitized fixture</h5><h6>H.O.F Korean Ver sanitized fixture</h6>
            <img src='image/zerohof.gif'></div></body></html>"""
        val observation = QuestPageParser().parseObservation(questPage(), questUrl)
        assertTrue(observation.complete)
        val quest = observation.quests.single { it.displayCode == "0571" }
        val mission = quest.missions.single()
        assertEquals(12, mission.progress?.current)
        val home = HomePageParser().parse(HomeMode.HOME, homePage(), homeUrl, HofFormParser().parse(homePage(), homeUrl)).quests.single()
        TransactionTemplate(transactions).executeWithoutResult {
            val status = entityManager.createQuery(
                "select s from HofStatusSnapshotEntity s where s.account.id = :id",
                app.spammy.hof.status.entity.HofStatusSnapshotEntity::class.java,
            ).setParameter("id", accountId).singleResult
            status.timeCurrent = availableTime
            status.timeMax = availableTime
            val entry = entityManager.find(AutomationEntryEntity::class.java, entryId)
            entry.type = AutomationType.QUEST
            entry.singletonTypeMarker = AutomationType.QUEST
            val selection = QuestAutomationSelectionEntity(entry = entry, questKey = quest.questKey,
                displayCode = quest.displayCode, questName = quest.name, enabled = true, sourceOrder = 0)
            entityManager.persist(selection)
            entityManager.persist(QuestAutomationMapEntity(questSelection = selection, missionKey = mission.key,
                categoryId = "battle_map", mapCode = "0003", presetMode = PresetSelectionMode.PRIMARY,
                executionOrder = 0, manuallyOverridden = true))
            val preset = PartyPresetEntity(account = entry.account, name = "퀘스트 전투 파티", isPrimary = true,
                createdAt = clock.now(), updatedAt = clock.now())
            entityManager.persist(preset)
            entityManager.createQuery("select c from CharacterEntity c where c.account.id = :id order by c.id", CharacterEntity::class.java)
                .setParameter("id", accountId).resultList.forEachIndexed { index, character ->
                    val pattern = CharacterPatternSlotEntity(character = character, slotCode = "1", label = "기본", canLoad = true)
                    entityManager.persist(pattern)
                    entityManager.persist(PartyPresetMemberEntity(preset, index, character, pattern))
                }
            val homeEntry = AutomationEntryEntity(account = entry.account, type = AutomationType.HOME_QUEST,
                priority = 1, enabled = true, createdAt = clock.now(), updatedAt = clock.now())
            entityManager.persist(homeEntry)
            entityManager.persist(HomeQuestAutomationSelectionEntity(entry = homeEntry, questId = home.id,
                questName = home.name, enabled = true, sourceOrder = 0))
        }
        Mockito.doAnswer { invocation ->
            val request = invocation.getArgument<HofRequest>(1)
            requests += request
            val body = when {
                request.url.contains("?char=") -> "<div>Funds : $ 1 Time : $availableTime/$availableTime</div>" +
                    app.spammy.hof.character.service.currentPatternForm() + app.spammy.hof.character.service.savedPatternLoadForm(1)
                request.url.contains("menu=housing") || request.url.contains("menu=quest2") -> {
                    if (request.formFields["action"] == "get") {
                        assertEquals(HofHttpMethod.GET, request.method)
                        assertEquals("A", request.formFields["no"])
                        homeAccepted = true
                    }
                    homePage()
                }
                request.url.contains("menu=quest") -> {
                    assertEquals(HofHttpMethod.GET, request.method, "선택하지 않은 퀘스트의 수락·수령을 제출하면 안 된다.")
                    questPage()
                }
                request.method == HofHttpMethod.GET -> maps()
                else -> {
                    assertEquals("https://hof.zerosic.com/index.php?common=0003", request.url)
                    assertEquals(outcomes.size == 3, "monster_battle_10" in request.formFields)
                    battleCount++
                    "<div id='menu2'>Funds : $ 1 Time : $availableTime/$availableTime</div>" + outcomes.joinToString("\n") { outcome ->
                    val title = when (outcome) {
                        BattleAutomationRoundOutcome.VICTORY -> "테스트은(는) 승리했다!"
                        BattleAutomationRoundOutcome.DEFEAT -> "Frosty Mountain- 대충산(마도사의 은신처)은(는) 승리했다!"
                        BattleAutomationRoundOutcome.DRAW -> "무승부!"
                        else -> error("단말 결과 fixture만 사용한다.")
                    }
                    val enemyHp = if (outcome == BattleAutomationRoundOutcome.VICTORY) 0 else 100
                    val allyHp = if (outcome == BattleAutomationRoundOutcome.DEFEAT) 0 else 100
                    val rewards = if (outcome == BattleAutomationRoundOutcome.VICTORY) "획득 경험치 : 1 획득 Funds : $ 1" else ""
                    """<h2>Show Detail( 1 turns. )</h2><h1>$title</h1>
                        <div>남은 HP : $enemyHp/100 생존자 : ${if (enemyHp == 0) 0 else 1}/1 총 데미지 : 0</div>
                        <div>남은 HP : $allyHp/100 생존자 : ${if (allyHp == 0) 0 else 1}/1 총 데미지 : 100 턴 : 1/100 $rewards</div>"""
                    }
                }
            }
            HofHttpResponse(200, request.url, body, emptyMap())
        }.`when`(gateway).execute(Mockito.eq(accountId), anyRequest(), Mockito.anyMap())

        var failureInjected = false
        var receivedIdentity: String? = null
        if (failFirstApplication) {
            val managedPlaceholder = Mockito.mock(ManagedAutomationAction::class.java)
            Mockito.doAnswer { invocation ->
                val managed = invocation.getArgument<ManagedAutomationAction>(0)
                if (!failureInjected && managed.storedAction.payload is StoredTypedActionPayload.QuestBattle) {
                    assertEquals(1, battleCount)
                    assertIs<AutomationActionEvidence.DirectApplied>(invocation.getArgument<AutomationActionEvidence?>(2))
                    receivedIdentity = managed.storedAction.executionIdentity
                    failureInjected = true
                    throw IllegalStateException("수신된 퀘스트 전투 응답의 첫 로컬 완료 실패")
                }
                invocation.callRealMethod()
            }.`when`(results).applyDirect(
                Mockito.any<ManagedAutomationAction>() ?: managedPlaceholder,
                Mockito.any<TypedAutomationExecution>() ?: TypedAutomationExecution.Completed,
                Mockito.nullable(AutomationActionEvidence::class.java),
                Mockito.nullable(Long::class.javaObjectType),
            )
        }

        wakeups.wake(accountId, "QUEST_BATTLE_DIRECT_RESULT")
        if (receiptStoredBeforeRecovery != null) consumeRecoveryBeforeOriginalWorkerReturns(receiptStoredBeforeRecovery)
        else publisher.publishBatch()

        assertEquals(1, battleCount)
        assertFalse(homeAccepted, "첫 판단은 앞선 퀘스트 전투 하나만 제출한다.")
        val identity = assertNotNull(jdbc.queryForObject(
            "select execution_identity from typed_automation_action_runs where account_id = ? and action_kind = 'QUEST_BATTLE'",
            String::class.java, accountId))
        val payload = jacksonObjectMapper().readTree(assertNotNull(jdbc.queryForObject(
            "select payload_json from typed_automation_action_runs where account_id = ? and execution_identity = ?",
            String::class.java, accountId, identity)))["payload"]
        assertEquals("QUEST_BATTLE", payload["kind"].asString())
        assertEquals(quest.questKey, payload["questKey"].asString())
        assertEquals(mission.key, payload["missionKey"].asString())
        assertEquals(outcomes.size, payload["battleCount"].asInt())
        assertEquals(1, jdbc.queryForObject(
            "select count(*) from typed_automation_action_runs where account_id = ? and execution_identity = ? " +
                "and direct_response_json is not null and direct_response_fingerprint is not null",
            Int::class.java, accountId, identity))
        val originalReceipt = mapper.readValue(assertNotNull(jdbc.queryForObject(
            "select direct_response_json from typed_automation_action_runs where account_id = ? and execution_identity = ?",
            String::class.java, accountId, identity)), StoredAutomationDirectResponse::class.java)
        assertEquals(outcomes, assertIs<AutomationDirectResponse.QuestBattle>(originalReceipt.response).outcomes)
        if (corruption != null) {
            val receipt = mapper.readValue(assertNotNull(jdbc.queryForObject(
                "select direct_response_json from typed_automation_action_runs where account_id = ? and execution_identity = ?",
                String::class.java, accountId, identity)), StoredAutomationDirectResponse::class.java)
            assertEquals(listOf(BattleAutomationRoundOutcome.VICTORY),
                assertIs<AutomationDirectResponse.QuestBattle>(receipt.response).outcomes)
            if (corruption == "FINGERPRINT") {
                jdbc.update("update typed_automation_action_runs set direct_response_json = '{}' where account_id = ? and execution_identity = ?",
                    accountId, identity)
            } else {
                val invalidResponse = when (corruption) {
                    "KIND" -> AutomationDirectResponse.QuestPage(emptyList(), true)
                    "ROUNDS" -> AutomationDirectResponse.QuestBattle(emptyList())
                    "NONTERMINAL" -> AutomationDirectResponse.QuestBattle(listOf(BattleAutomationRoundOutcome.UNKNOWN))
                    else -> error("지원하지 않는 손상 fixture: $corruption")
                }
                val invalidJson = mapper.writeValueAsString(receipt.copy(response = invalidResponse))
                assertEquals(invalidResponse, mapper.readValue(invalidJson, StoredAutomationDirectResponse::class.java).response)
                val actionFingerprint = assertNotNull(jdbc.queryForObject(
                    "select action_fingerprint from typed_automation_action_runs where account_id = ? and execution_identity = ?",
                    String::class.java, accountId, identity))
                // 지문 검사를 통과하는 잘못된 종류·회차를 주입해 의미 검증과 지문 오류를 구분한다.
                val fingerprint = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest("$accountId\n$identity\n$actionFingerprint\n$invalidJson".toByteArray(Charsets.UTF_8)))
                jdbc.update("update typed_automation_action_runs set direct_response_json = ?, direct_response_fingerprint = ? where account_id = ? and execution_identity = ?",
                    invalidJson, fingerprint, accountId, identity)
            }
        }
        fun originalEvents() = journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }
            .filter { it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED && it.actionKind == "QUEST_BATTLE" }
        fun assertResultPreserved() {
            assertEquals(if (corruption == null) "SUCCEEDED" else "RESULT_HELD",
                runs().single { it["execution_identity"] == identity }["status"])
            val record = store.get(accountId, identity)
            if (mode == AutomationConvergenceMode.ACTIVE) {
                assertNotNull(record)
                assertEquals(AutomationActionKind.QUEST_BATTLE, record.selection.actionKind)
                assertEquals(ActionConvergenceResult.APPLIED, record.result)
                assertEquals("DIRECT_RESPONSE_APPLIED", record.reasonCode)
            } else assertNull(record)
            val shadows = jdbc.queryForList(
                "select new_result from automation_convergence_shadow_evaluations where account_id = ? and execution_identity_hash = ?",
                String::class.java, accountId, ProductionEvidenceShapes.fingerprint(identity))
            if (mode == AutomationConvergenceMode.SHADOW && receiptStoredBeforeRecovery == false) {
                assertEquals(listOf("APPLIED", "RESULT_UNOBSERVED"), shadows.map { requireNotNull(it) }.sorted(), "원래 전투의 SHADOW 평가")
            } else assertEquals(if (mode == AutomationConvergenceMode.SHADOW && corruption == null) listOf("APPLIED") else emptyList(), shadows)
            assertEquals(if (corruption == null) expectedVictories else 0, jdbc.queryForObject(
                "select coalesce(sum(successful_runs), 0) from quest_map_execution_counters where account_id = ? and quest_code = ? and mission_key = ? and category_id = 'battle_map' and map_code = '0003'",
                Int::class.java, accountId, quest.questKey, mission.key))
            assertEquals(if (corruption == null) 1 else 0, jdbc.queryForObject(
                "select count(*) from quest_automation_processed_results where account_id = ? and result_kind = 'BATTLE_VICTORY'",
                Int::class.java, accountId))
            assertEquals(if (corruption == null) 1 else 0, originalEvents().size)
        }
        if (failFirstApplication) {
            assertTrue(failureInjected, "실제 직접 적용 근거를 수신한 뒤 로컬 완료에서만 실패해야 한다.")
            assertEquals(identity, receivedIdentity)
            clock.current = clock.now().plusSeconds(11)
            consumeNextWake()
        }
        assertResultPreserved()
        val result = originalEvents().singleOrNull()
        if (corruption == null) assertEquals("battle_map/0003", assertNotNull(result).targetKey)
        val initialCycles = journal.page(accountId, AutomationHistoryQuery()).cycles.map { it.id }.toSet()

        repeat(4) { consumeNextWake() }

        assertTrue(homeAccepted, "퀘스트 전투 쿨다운은 독립 자택의 실제 제출을 막으면 안 된다. " +
            "requests=${requests.map { it.method to it.url }}, history=${journal.page(accountId, AutomationHistoryQuery()).cycles}")
        assertEquals(1, requests.count { it.method == HofHttpMethod.GET && it.formFields["action"] == "get" && it.formFields["no"] == "A" })
        assertEquals(1, battleCount, "현재 쿨다운과 원래 실행의 직접 결과를 보존해 같은 전투를 중복 제출하지 않는다.")
        assertResultPreserved()
        val cycles = journal.page(accountId, AutomationHistoryQuery()).cycles
        assertTrue(cycles.count { it.id !in initialCycles } >= 2)
        assertEquals(result, originalEvents().singleOrNull())
        assertEquals(0, runningWorkCount())
        assertNull(jdbc.queryForObject("select lease_token from typed_automation_runtime_states where account_id = ?", String::class.java, accountId))
        if (corruption != null) {
            val held = convergence.get(accountId).localResults.single()
            assertEquals("QUEST_BATTLE", held.actionKind)
            assertEquals(TypedAutomationActionStatus.RESULT_HELD, held.status)
            assertEquals("LOCAL_RESULT_INTEGRITY_FAILED", held.reasonCode)
            assertTrue(held.canAllowFreshDecision)
            assertTrue(held.impactScope.contains(quest.questKey), held.impactScope)
            val snippet = assertNotNull(jdbc.queryForObject(
                "select sanitized_snippet from automation_evidence_cases where id = ?",
                String::class.java, assertNotNull(held.evidenceCaseId)))
            assertTrue(snippet.contains("receiptValid=${corruption != "FINGERPRINT"}"), snippet)
            val beforeRelease = requests.size
            assertTrue(convergence.allowLocalFreshDecision(accountId, held.actionId).localResults.isEmpty())
            assertEquals(beforeRelease, requests.size, "보류 해제 요청 자체는 원격 행동을 제출하지 않는다.")
            repeat(2) { consumeNextWake() }
            assertResultPreserved()
            assertEquals(1, battleCount)
            assertEquals(0, runningWorkCount())
            assertTrue(convergence.get(accountId).localResults.isEmpty())
        }
    }

    private fun consumeRecoveryBeforeOriginalWorkerReturns(receiptStoredBeforeRecovery: Boolean) {
        val directReady = CountDownLatch(1)
        val returnDirect = CountDownLatch(1)
        val firstDirect = AtomicBoolean(true)
        if (receiptStoredBeforeRecovery) {
            val managedPlaceholder = Mockito.mock(ManagedAutomationAction::class.java)
            Mockito.doAnswer { invocation ->
                val managed = invocation.getArgument<ManagedAutomationAction>(0)
                val result = invocation.callRealMethod()
                if (managed.storedAction.payload is StoredTypedActionPayload.QuestBattle && firstDirect.compareAndSet(true, false)) {
                    directReady.countDown()
                    check(returnDirect.await(30, TimeUnit.SECONDS))
                }
                result
            }.`when`(results).applyDirect(
                Mockito.any<ManagedAutomationAction>() ?: managedPlaceholder,
                Mockito.any<TypedAutomationExecution>() ?: TypedAutomationExecution.Completed,
                Mockito.nullable(AutomationActionEvidence::class.java),
                Mockito.nullable(Long::class.javaObjectType),
            )
        } else {
            val preparedPlaceholder = battle()
            Mockito.doAnswer { invocation ->
                val managed = invocation.callRealMethod() as ManagedAutomationAction
                if (managed.storedAction.payload !is StoredTypedActionPayload.QuestBattle) managed
                else object : ManagedAutomationAction by managed {
                    override fun execute(): TypedAutomationExecution {
                        val execution = managed.execute()
                        directReady.countDown()
                        check(returnDirect.await(30, TimeUnit.SECONDS))
                        return execution
                    }
                }
            }.`when`(actionLifecycle).prepare(Mockito.eq(accountId), Mockito.eq(entryId),
                Mockito.any<PreparedAutomationAction>() ?: preparedPlaceholder)
        }
        val otherTransport = AutomationRecoveryIntegrationTest.Config()
            .consumerReplayTransport(mapper, consumedEvents, consumerLease, runner, clock)
        val otherPublisher = AutomationOutboxPublisher(outbox, publishedMarker, otherTransport, clock)
        Executors.newFixedThreadPool(2).use { executor ->
            val oldWorker = executor.submit { publisher.publishBatch() }
            try {
                assertTrue(directReady.await(10, TimeUnit.SECONDS), "원래 전투 응답의 지정된 저장 전후 지점에 도달해야 한다.")
                val original = jdbc.queryForMap(
                    "select status, execution_identity, direct_response_json from typed_automation_action_runs " +
                        "where account_id = ? and action_kind = 'QUEST_BATTLE'", accountId)
                val identity = original["execution_identity"] as String
                assertEquals("SUBMITTING", original["status"])
                assertEquals(receiptStoredBeforeRecovery, original["direct_response_json"] != null)
                val originalCycles = journal.page(accountId, AutomationHistoryQuery()).cycles.map { it.id }.toSet()
                // 동기 consumer fixture에서도 broker가 받은 최초 wake는 게시 완료로 확정한다.
                val dispatched = outbox.findUnpublished(clock.now()).single {
                    mapper.readValue(it.payload, AutomationWakeupEvent::class.java).reason == "QUEST_BATTLE_DIRECT_RESULT"
                }
                publishedMarker.markPublished(dispatched.id)
                clock.current = clock.now().plusSeconds(301)
                wakeups.wake(accountId, "QUEST_BATTLE_STORED_RECEIPT_RECOVERY")
                executor.submit { otherPublisher.publishBatch() }.get(10, TimeUnit.SECONDS)
                val recovered = journal.page(accountId, AutomationHistoryQuery()).cycles
                val restored = recovered.singleOrNull { cycle ->
                    cycle.events.any { it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED && it.actionKind == "QUEST_BATTLE" }
                }
                if (receiptStoredBeforeRecovery) {
                    assertEquals("SUCCEEDED", runs().single { it["execution_identity"] == identity }["status"])
                    assertTrue(assertNotNull(restored).id !in originalCycles)
                    assertTrue(restored.events.any { it.reasonCode == "STORED_DIRECT_RESPONSE_RESTORE" })
                    assertEquals(1, jdbc.queryForObject(
                        "select coalesce(sum(successful_runs), 0) from quest_map_execution_counters where account_id = ?",
                        Int::class.java, accountId))
                } else {
                    assertNull(restored, "최신 퀘스트 진행만으로 원래 전투의 성공 이력을 만들지 않는다.")
                    if (mode == AutomationConvergenceMode.ACTIVE) {
                        assertEquals("AMBIGUOUS", runs().single { it["execution_identity"] == identity }["status"])
                        val record = assertNotNull(store.get(accountId, identity))
                        assertEquals(ActionConvergenceResult.HELD, record.result)
                        assertEquals("PENDING_BUDGET_EXHAUSTED", record.reasonCode)
                    } else {
                        assertEquals("FAILED", runs().single { it["execution_identity"] == identity }["status"])
                        assertTrue(recovered.flatMap { it.events }.any { it.reasonCode == "QUEST_PROGRESS_FRESH_DECISION" })
                    }
                }
                val success = restored?.events?.single { it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED }

                returnDirect.countDown()
                oldWorker.get(10, TimeUnit.SECONDS)

                val after = journal.page(accountId, AutomationHistoryQuery()).cycles
                val finalSuccess = after.flatMap { it.events }.single {
                    it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED && it.actionKind == "QUEST_BATTLE"
                }
                if (receiptStoredBeforeRecovery) {
                    assertEquals(success, finalSuccess)
                    assertTrue(after.single { it.id == assertNotNull(restored).id }.events.any { it.id == finalSuccess.id },
                        "원래 worker는 복구 worker가 남긴 최초 성공의 판단 귀속을 옮기지 않는다.")
                } else assertTrue(after.single { finalSuccess in it.events }.id in originalCycles,
                    "늦은 원래 전투 응답의 성공 이력은 최초 제출 판단에 남긴다.")
                assertEquals(1, requests.count { it.method == HofHttpMethod.POST && it.url.contains("common=0003") })
            } finally {
                returnDirect.countDown()
                oldWorker.get(10, TimeUnit.SECONDS)
                otherTransport.delivered.forEach { jdbc.update("delete from automation_consumed_events where event_id = ?", it) }
            }
        }
    }
}
