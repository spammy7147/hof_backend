package app.spammy.hof.automation.service

import app.spammy.hof.automation.convergence.ActionConvergenceResult
import app.spammy.hof.automation.convergence.AutomationActionEvidence
import app.spammy.hof.automation.convergence.AutomationConvergenceMode
import app.spammy.hof.automation.convergence.AutomationConvergenceController
import app.spammy.hof.automation.convergence.ProductionEvidenceShapes
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
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.*

@SpringBootTest(properties = ["hof.automation-convergence.mode=LEGACY"])
class LegacyAutomationBattleMapDirectReceiptContinuityTest : AutomationBattleMapDirectReceiptContinuityTest() {
    override val mode = AutomationConvergenceMode.LEGACY
}

@SpringBootTest(properties = ["hof.automation-convergence.mode=SHADOW"])
class ShadowAutomationBattleMapDirectReceiptContinuityTest : AutomationBattleMapDirectReceiptContinuityTest() {
    override val mode = AutomationConvergenceMode.SHADOW
}

@SpringBootTest(properties = ["hof.automation-convergence.mode=ACTIVE"])
class ActiveAutomationBattleMapDirectReceiptContinuityTest : AutomationBattleMapDirectReceiptContinuityTest() {
    override val mode = AutomationConvergenceMode.ACTIVE
}

@ActiveProfiles("test")
@Import(AutomationRecoveryIntegrationTest.Config::class)
abstract class AutomationBattleMapDirectReceiptContinuityTest : AutomationRecoveryFixture() {
    @MockitoSpyBean private lateinit var results: AutomationResultCoordinator
    @MockitoSpyBean private lateinit var actionLifecycle: AutomationActionLifecycleModule
    @Autowired private lateinit var convergence: AutomationConvergenceController
    @Autowired private lateinit var mapper: tools.jackson.databind.ObjectMapper
    @Autowired private lateinit var consumedEvents: AutomationConsumedEventService
    @Autowired private lateinit var consumerLease: AccountAutomationLeaseService
    @Autowired private lateinit var publishedMarker: AutomationOutboxPublishMarker
    @Autowired private lateinit var runner: UnifiedAutomationRunner

    @Test
    fun `일반 전투맵 승리를 한 번 반영하고 독립 자택과 후속 판단을 이어간다`() =
        verifyBattleMapDirectResult()

    @Test
    fun `일반 전투맵 직접 승리 응답 뒤 로컬 완료가 실패해도 원래 결과와 후속 판단을 복구한다`() =
        verifyBattleMapDirectResult(failFirstApplication = true)

    @Test
    fun `이미 반영한 일반 전투맵 승리를 로컬 완료 재시도에서 중복하지 않는다`() =
        verifyBattleMapDirectResult(failFirstApplication = true, failAfterProjection = true)

    @ParameterizedTest
    @ValueSource(strings = ["DEFEAT", "DRAW"])
    fun `패배와 무승부를 복구해도 전투맵의 일일 승리는 늘리지 않는다`(outcome: String) =
        verifyBattleMapDirectResult(failFirstApplication = true,
            outcomes = listOf(BattleAutomationRoundOutcome.valueOf(outcome)), expectedVictories = 0)

    @Test
    fun `세 회차의 혼합 결과를 복원해 확인된 전투맵 승리만 한 번 반영한다`() =
        verifyBattleMapDirectResult(failFirstApplication = true, outcomes = listOf(
            BattleAutomationRoundOutcome.VICTORY, BattleAutomationRoundOutcome.DEFEAT, BattleAutomationRoundOutcome.DRAW,
        ), expectedVictories = 1)

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `한국 자정 뒤 복원해도 승리는 원래 행동 날짜에만 한 번 남는다`(afterProjection: Boolean) =
        verifyBattleMapDirectResult(failFirstApplication = true, failAfterProjection = afterProjection,
            recoverAfterMidnight = true)

    @ParameterizedTest
    @ValueSource(strings = ["FINGERPRINT", "KIND", "ROUNDS", "NONTERMINAL", "RESULT_ID"])
    fun `손상된 전투맵 응답은 원래 결과 범위를 보류하고 독립 자택과 명시 해제를 허용한다`(corruption: String) =
        verifyBattleMapDirectResult(failFirstApplication = true, corruption = corruption)

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `복구 판단과 늦은 원래 응답이 경합해도 전투맵 승리와 이력 귀속을 보존한다`(storedBeforeRecovery: Boolean) =
        verifyBattleMapDirectResult(receiptStoredBeforeRecovery = storedBeforeRecovery)

    private fun verifyBattleMapDirectResult(
        failFirstApplication: Boolean = false,
        failAfterProjection: Boolean = false,
        outcomes: List<BattleAutomationRoundOutcome> = listOf(BattleAutomationRoundOutcome.VICTORY),
        expectedVictories: Int = 1,
        recoverAfterMidnight: Boolean = false,
        corruption: String? = null,
        receiptStoredBeforeRecovery: Boolean? = null,
    ) {
        Mockito.doCallRealMethod().`when`(decisions).select(accountId)
        if (recoverAfterMidnight) clock.current = Instant.parse("2026-09-04T14:59:55Z")
        val availableTime = if (outcomes.size == 3) 300 else 100
        var battleCount = 0
        var homeAccepted = false
        val homeUrl = "https://hof.zerosic.com/index.php?menu=quest2"
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
        val home = HomePageParser().parse(HomeMode.HOME, homePage(), homeUrl,
            HofFormParser().parse(homePage(), homeUrl)).quests.single()
        TransactionTemplate(transactions).executeWithoutResult {
            val status = entityManager.createQuery(
                "select s from HofStatusSnapshotEntity s where s.account.id = :id",
                app.spammy.hof.status.entity.HofStatusSnapshotEntity::class.java,
            ).setParameter("id", accountId).singleResult
            status.timeCurrent = availableTime
            status.timeMax = availableTime
            val entry = entityManager.find(AutomationEntryEntity::class.java, entryId)
            entry.type = AutomationType.BATTLE_MAP
            entry.singletonTypeMarker = null
            entityManager.persist(BattleAutomationMapEntity(entry = entry, categoryId = "battle_map",
                mapCode = "0003", dailyTargetCount = outcomes.size, presetMode = PresetSelectionMode.PRIMARY, executionOrder = 0))
            val preset = PartyPresetEntity(account = entry.account, name = "전투맵 파티", isPrimary = true,
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
                    currentPatternForm() + savedPatternLoadForm(1)
                request.url.contains("menu=housing") || request.url.contains("menu=quest2") -> {
                    if (request.formFields["action"] == "get") {
                        assertEquals(HofHttpMethod.GET, request.method)
                        assertEquals("A", request.formFields["no"])
                        homeAccepted = true
                    }
                    homePage()
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
                        val rewards = if (outcome == BattleAutomationRoundOutcome.VICTORY)
                            "획득 경험치 : 1 획득 Funds : $ 1<br><b>전리품</b><br>Silver Ingot x 1<br>Bone x 1<br>" +
                                "소셜 신앙심 변동 :+1<fieldset>[ 퀘스트 정보 갱신 ] 조사 지원 - ( 1 / 10 )</fieldset>" else ""
                        """<h2>Show Detail( 1 turns. )</h2><h1>$title</h1>
                            <div>남은 HP : $enemyHp/100 생존자 : ${if (enemyHp == 0) 0 else 1}/1 총 데미지 : 0</div>
                            <div>남은 HP : $allyHp/100 생존자 : ${if (allyHp == 0) 0 else 1}/1 총 데미지 : 100 턴 : 1/100 $rewards</div>"""
                    }
                }
            }
            HofHttpResponse(200, request.url, body, emptyMap())
        }.`when`(gateway).execute(Mockito.eq(accountId), anyRequest(), Mockito.anyMap())

        var failureInjected = false
        if (failFirstApplication) {
            val placeholder = Mockito.mock(ManagedAutomationAction::class.java)
            Mockito.doAnswer { invocation ->
                val managed = invocation.getArgument<ManagedAutomationAction>(0)
                if (!failureInjected && managed.storedAction.payload is StoredTypedActionPayload.BattleMap) {
                    assertEquals(1, battleCount)
                    assertIs<AutomationActionEvidence.DirectApplied>(invocation.getArgument<AutomationActionEvidence?>(2))
                    if (failAfterProjection) invocation.callRealMethod()
                    val currentProgress = application.getTyped(accountId).entries.single { it.id == entryId }.battleMapProgress
                    assertEquals(if (failAfterProjection) expectedVictories else 0, currentProgress.sumOf { it.successfulRuns },
                        "승리 반영 전 실패와 반영 후 실패를 구분한다.")
                    failureInjected = true
                    throw IllegalStateException("수신한 일반 전투맵 결과의 첫 로컬 완료 실패")
                }
                invocation.callRealMethod()
            }.`when`(results).applyDirect(
                Mockito.any<ManagedAutomationAction>() ?: placeholder,
                Mockito.any<TypedAutomationExecution>() ?: TypedAutomationExecution.Completed,
                Mockito.nullable(AutomationActionEvidence::class.java),
                Mockito.nullable(Long::class.javaObjectType),
            )
        }

        wakeups.wake(accountId, "BATTLE_MAP_DIRECT_RESULT")
        if (receiptStoredBeforeRecovery != null) consumeRecoveryBeforeOriginalWorkerReturns(receiptStoredBeforeRecovery)
        else publisher.publishBatch()

        assertEquals(1, battleCount, "첫 판단에서 설정한 일반 전투맵을 실제 제출해야 한다.")
        assertFalse(homeAccepted, "첫 판단은 앞선 일반 전투맵 행동만 제출한다.")
        val identity = assertNotNull(jdbc.queryForObject(
            "select execution_identity from typed_automation_action_runs where account_id = ? and action_kind = 'BATTLE_MAP'",
            String::class.java, accountId))
        if (failFirstApplication) {
            val saved = mapper.readValue(assertNotNull(jdbc.queryForObject(
                "select direct_response_json from typed_automation_action_runs where account_id = ? and execution_identity = ?",
                String::class.java, accountId, identity)), StoredAutomationDirectResponse::class.java)
            val response = assertIs<AutomationDirectResponse.BattleMap>(saved.response)
            assertEquals(if (expectedVictories == 1) listOf("Silver Ingot x 1", "Bone x 1") else emptyList(), response.lootNames)
            assertEquals(if (expectedVictories == 1) listOf("[ 퀘스트 정보 갱신 ] 조사 지원 - ( 1 / 10 )") else emptyList(),
                response.questTexts)
        }
        if (corruption != null) {
            val mapper = jacksonObjectMapper()
            val receipt = mapper.readValue(assertNotNull(jdbc.queryForObject(
                "select direct_response_json from typed_automation_action_runs where account_id = ? and execution_identity = ?",
                String::class.java, accountId, identity)), StoredAutomationDirectResponse::class.java)
            val response = assertIs<AutomationDirectResponse.BattleMap>(receipt.response)
            assertEquals(identity, response.resultIdentity)
            assertEquals(listOf(BattleAutomationRoundOutcome.VICTORY), response.outcomes)
            if (corruption == "FINGERPRINT") {
                jdbc.update("update typed_automation_action_runs set direct_response_json = '{}' where account_id = ? and execution_identity = ?",
                    accountId, identity)
            } else {
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
                // 유효한 저장 지문을 만들어 종류·회차·결과 식별자의 의미 검증을 별도로 검사한다.
                val fingerprint = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest("$accountId\n$identity\n$actionFingerprint\n$invalidJson".toByteArray(Charsets.UTF_8)))
                jdbc.update("update typed_automation_action_runs set direct_response_json = ?, direct_response_fingerprint = ? where account_id = ? and execution_identity = ?",
                    invalidJson, fingerprint, accountId, identity)
            }
        }
        if (failFirstApplication) {
            assertTrue(failureInjected)
            clock.current = clock.now().plusSeconds(11)
            consumeNextWake()
        }
        fun originalEvents() = journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }
            .filter { it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED && it.actionKind == "BATTLE_MAP" }
        fun assertResultPreserved() {
            assertEquals(if (corruption == null) "SUCCEEDED" else "RESULT_HELD",
                runs().single { it["execution_identity"] == identity }["status"])
            val record = store.get(accountId, identity)
            if (mode == AutomationConvergenceMode.ACTIVE) {
                assertEquals(ActionConvergenceResult.APPLIED, assertNotNull(record).result)
                assertEquals("DIRECT_RESPONSE_APPLIED", record.reasonCode)
            } else assertNull(record)
            val progress = application.getTyped(accountId).entries.single { it.id == entryId }.battleMapProgress
            if (corruption != null) {
                assertTrue(progress.isEmpty(), "손상된 원래 응답으로 승리를 기록하지 않는다.")
            } else if (recoverAfterMidnight) {
                assertTrue(progress.isEmpty(), "이전 날짜의 직접 결과가 새 날짜의 일일 승리를 늘리지 않는다.")
            } else {
                assertEquals("battle_map", progress.single().categoryId)
                assertEquals("0003", progress.single().mapCode)
                assertEquals(expectedVictories, progress.single().successfulRuns)
            }
            assertEquals(if (corruption == null) expectedVictories else 0, jdbc.queryForObject(
                "select coalesce(sum(successful_runs), 0) from battle_automation_daily_progress where account_id = ? " +
                    "and progress_date = date '2026-09-04' and category_id = 'battle_map' and map_code = '0003' and source = 'battle_map'",
                Int::class.java, accountId))
            assertEquals(if (corruption == null) 1 else 0, jdbc.queryForObject(
                "select count(*) from battle_automation_processed_results where account_id = ? and execution_identity = ?",
                Int::class.java, accountId, identity))
            val shadows = jdbc.queryForList(
                "select new_result from automation_convergence_shadow_evaluations where account_id = ? and execution_identity_hash = ?",
                String::class.java, accountId, ProductionEvidenceShapes.fingerprint(identity))
            if (mode == AutomationConvergenceMode.SHADOW && receiptStoredBeforeRecovery == false) {
                assertEquals(listOf("APPLIED", "SUPERSEDED"), shadows.map { requireNotNull(it) }.sorted())
            } else assertEquals(if (mode == AutomationConvergenceMode.SHADOW && corruption == null) listOf("APPLIED") else emptyList(), shadows)
            assertEquals(if (corruption == null) 1 else 0, originalEvents().size)
        }
        assertResultPreserved()
        val result = originalEvents().singleOrNull()
        if (corruption == null) assertEquals("battle_map/0003", assertNotNull(result).targetKey)
        val initialCycles = journal.page(accountId, AutomationHistoryQuery()).cycles.map { it.id }.toSet()

        repeat(4) { consumeNextWake() }

        assertTrue(homeAccepted, "전투맵 종료 뒤 독립 자택의 실제 제출로 이어져야 한다.")
        assertEquals(1, requests.count { it.method == HofHttpMethod.GET && it.formFields["action"] == "get" && it.formFields["no"] == "A" })
        assertEquals(1, battleCount)
        assertResultPreserved()
        assertEquals(result, originalEvents().singleOrNull())
        assertTrue(journal.page(accountId, AutomationHistoryQuery()).cycles.count { it.id !in initialCycles } >= 2)
        assertEquals(0, runningWorkCount())
        assertNull(jdbc.queryForObject("select lease_token from typed_automation_runtime_states where account_id = ?", String::class.java, accountId))
        if (corruption != null) {
            val held = convergence.get(accountId).localResults.single()
            assertEquals(entryId, held.entryId)
            assertEquals("MAP_BATTLE", held.actionKind)
            assertEquals(TypedAutomationActionStatus.RESULT_HELD, held.status)
            assertEquals("LOCAL_RESULT_INTEGRITY_FAILED", held.reasonCode)
            assertTrue(held.canAllowFreshDecision)
            assertTrue(held.impactScope.contains("shared-battle-cooldown"), held.impactScope)
            val snippet = assertNotNull(jdbc.queryForObject(
                "select sanitized_snippet from automation_evidence_cases where id = ?",
                String::class.java, assertNotNull(held.evidenceCaseId)))
            assertTrue(snippet.contains("receiptValid=${corruption != "FINGERPRINT"}"), snippet)
            val beforeRelease = requests.size
            assertTrue(convergence.allowLocalFreshDecision(accountId, held.actionId).localResults.isEmpty())
            assertEquals(beforeRelease, requests.size, "보류 해제 자체는 원격 행동을 제출하지 않는다.")
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
            val placeholder = Mockito.mock(ManagedAutomationAction::class.java)
            Mockito.doAnswer { invocation ->
                val managed = invocation.getArgument<ManagedAutomationAction>(0)
                val result = invocation.callRealMethod()
                if (managed.storedAction.payload is StoredTypedActionPayload.BattleMap && firstDirect.compareAndSet(true, false)) {
                    directReady.countDown()
                    check(returnDirect.await(30, TimeUnit.SECONDS))
                }
                result
            }.`when`(results).applyDirect(
                Mockito.any<ManagedAutomationAction>() ?: placeholder,
                Mockito.any<TypedAutomationExecution>() ?: TypedAutomationExecution.Completed,
                Mockito.nullable(AutomationActionEvidence::class.java),
                Mockito.nullable(Long::class.javaObjectType),
            )
        } else {
            val preparedPlaceholder = battle()
            Mockito.doAnswer { invocation ->
                val managed = invocation.callRealMethod() as ManagedAutomationAction
                if (managed.storedAction.payload !is StoredTypedActionPayload.BattleMap) managed
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
                assertTrue(directReady.await(10, TimeUnit.SECONDS))
                val original = jdbc.queryForMap(
                    "select status, execution_identity, direct_response_json from typed_automation_action_runs " +
                        "where account_id = ? and action_kind = 'BATTLE_MAP'", accountId)
                val identity = original["execution_identity"] as String
                assertEquals("SUBMITTING", original["status"])
                assertEquals(receiptStoredBeforeRecovery, original["direct_response_json"] != null)
                val originalCycles = journal.page(accountId, AutomationHistoryQuery()).cycles.map { it.id }.toSet()
                // 동기 consumer fixture에서도 broker가 받은 최초 wake를 게시 완료로 확정한다.
                val dispatched = outbox.findUnpublished(clock.now()).single {
                    mapper.readValue(it.payload, AutomationWakeupEvent::class.java).reason == "BATTLE_MAP_DIRECT_RESULT"
                }
                publishedMarker.markPublished(dispatched.id)
                clock.current = clock.now().plusSeconds(301)
                wakeups.wake(accountId, "BATTLE_MAP_STORED_RECEIPT_RECOVERY")
                executor.submit { otherPublisher.publishBatch() }.get(10, TimeUnit.SECONDS)

                val recovered = journal.page(accountId, AutomationHistoryQuery()).cycles
                val restored = recovered.singleOrNull { cycle ->
                    cycle.events.any { it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED && it.actionKind == "BATTLE_MAP" }
                }
                val progress = application.getTyped(accountId).entries.single { it.id == entryId }.battleMapProgress
                if (receiptStoredBeforeRecovery) {
                    assertEquals("SUCCEEDED", runs().single { it["execution_identity"] == identity }["status"])
                    assertTrue(assertNotNull(restored).id !in originalCycles)
                    assertTrue(restored.events.any { it.reasonCode == "STORED_DIRECT_RESPONSE_RESTORE" })
                    assertEquals(1, progress.single().successfulRuns)
                } else {
                    assertNull(restored, "최신 전투맵 쿨다운만으로 원래 전투의 성공 이력을 만들지 않는다.")
                    assertTrue(progress.isEmpty(), "원래 직접 응답을 저장하기 전에는 승리를 귀속하지 않는다.")
                    if (mode == AutomationConvergenceMode.ACTIVE) {
                        assertEquals("AMBIGUOUS", runs().single { it["execution_identity"] == identity }["status"])
                        val record = assertNotNull(store.get(accountId, identity))
                        assertEquals(ActionConvergenceResult.HELD, record.result)
                        assertEquals("PENDING_BUDGET_EXHAUSTED", record.reasonCode)
                    } else {
                        assertEquals("FAILED", runs().single { it["execution_identity"] == identity }["status"])
                        assertTrue(recovered.flatMap { it.events }.any { it.reasonCode == "ACTION_SUPERSEDED_BY_FRESH_STATE" })
                    }
                }
                val success = restored?.events?.single { it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED }

                returnDirect.countDown()
                oldWorker.get(10, TimeUnit.SECONDS)

                val after = journal.page(accountId, AutomationHistoryQuery()).cycles
                val finalSuccess = after.flatMap { it.events }.single {
                    it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED && it.actionKind == "BATTLE_MAP"
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
