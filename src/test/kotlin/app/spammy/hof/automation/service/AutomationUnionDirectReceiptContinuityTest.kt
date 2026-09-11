package app.spammy.hof.automation.service

import app.spammy.hof.automation.convergence.ActionConvergenceResult
import app.spammy.hof.automation.convergence.AutomationActionEvidence
import app.spammy.hof.automation.convergence.AutomationConvergenceMode
import app.spammy.hof.automation.convergence.AutomationConvergenceController
import app.spammy.hof.automation.convergence.ProductionEvidenceShapes
import app.spammy.hof.automation.entity.*
import app.spammy.hof.automation.dto.UnionMapSettingRequest
import app.spammy.hof.automation.dto.UpdateUnionAutomationRequest
import app.spammy.hof.automation.history.AutomationHistoryEventKind
import app.spammy.hof.automation.history.AutomationHistoryQuery
import app.spammy.hof.automation.lease.AccountAutomationLeaseService
import app.spammy.hof.automation.outbox.AutomationConsumedEventService
import app.spammy.hof.automation.outbox.AutomationOutboxPublishMarker
import app.spammy.hof.automation.outbox.AutomationOutboxPublisher
import app.spammy.hof.automation.outbox.AutomationOutboxService
import app.spammy.hof.automation.outbox.AutomationWakeupEvent
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

@SpringBootTest(properties = ["hof.automation-convergence.mode=LEGACY"])
class LegacyAutomationUnionDirectReceiptContinuityTest : AutomationUnionDirectReceiptContinuityTest() {
    override val mode = AutomationConvergenceMode.LEGACY
}

@SpringBootTest(properties = ["hof.automation-convergence.mode=SHADOW"])
class ShadowAutomationUnionDirectReceiptContinuityTest : AutomationUnionDirectReceiptContinuityTest() {
    override val mode = AutomationConvergenceMode.SHADOW
}

@SpringBootTest(properties = ["hof.automation-convergence.mode=ACTIVE"])
class ActiveAutomationUnionDirectReceiptContinuityTest : AutomationUnionDirectReceiptContinuityTest() {
    override val mode = AutomationConvergenceMode.ACTIVE
}

@ActiveProfiles("test")
@Import(AutomationRecoveryIntegrationTest.Config::class)
abstract class AutomationUnionDirectReceiptContinuityTest : AutomationRecoveryFixture() {
    @MockitoSpyBean private lateinit var results: AutomationResultCoordinator
    @MockitoSpyBean private lateinit var actionLifecycle: AutomationActionLifecycleModule
    @Autowired private lateinit var convergence: AutomationConvergenceController
    @Autowired private lateinit var mapper: tools.jackson.databind.ObjectMapper
    @Autowired private lateinit var consumedEvents: AutomationConsumedEventService
    @Autowired private lateinit var consumerLease: AccountAutomationLeaseService
    @Autowired private lateinit var publishedMarker: AutomationOutboxPublishMarker
    @Autowired private lateinit var runner: UnifiedAutomationRunner

    @Test
    fun `유니온 전투 뒤 순환 위치를 보존하고 독립 자택과 다음 유니온 맵을 실행한다`() = verifyUnionResult()

    @Test
    fun `유니온 직접 응답 뒤 로컬 완료가 실패해도 원래 결과와 다음 순환을 복구한다`() =
        verifyUnionResult(failFirstApplication = true)

    @Test
    fun `유니온 순환을 이미 반영한 뒤 실패해도 원래 결과를 한 번 완료한다`() =
        verifyUnionResult(failFirstApplication = true, failAfterProjection = true)

    @ParameterizedTest
    @ValueSource(strings = ["DEFEAT", "DRAW"])
    fun `패배와 무승부의 원래 응답을 복구해도 다음 유니온 맵으로 진행한다`(outcome: String) =
        verifyUnionResult(failFirstApplication = true, outcome = BattleAutomationRoundOutcome.valueOf(outcome))

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `응답 저장 전후 복구가 다음 유니온 맵을 완료해도 늦은 worker는 순환 차례를 되돌리지 않는다`(storedBeforeRecovery: Boolean) =
        verifyUnionResult(lateWorker = storedBeforeRecovery)

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `유니온 순서를 바꾸고 응답을 복원할 때 이미 반영한 순환은 유지하고 미반영 결과만 새 순서를 따른다`(alreadyApplied: Boolean) =
        verifyUnionResult(failFirstApplication = true, failAfterProjection = alreadyApplied, reorderBeforeRestore = true)

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `완료 맵을 설정에서 삭제해도 원래 유니온 응답과 독립 작업을 복구하고 남은 맵을 실행한다`(alreadyApplied: Boolean) =
        verifyUnionResult(failFirstApplication = true, failAfterProjection = alreadyApplied, removeBeforeRestore = true)

    @ParameterizedTest
    @ValueSource(strings = ["FINGERPRINT", "KIND", "ROUNDS", "NONTERMINAL", "RESULT_ID"])
    fun `손상된 응답은 유니온 항목만 보류하고 자택과 명시 해제 뒤 새 맵의 실행을 보존한다`(corruption: String) =
        verifyUnionResult(failFirstApplication = true, corruption = corruption)

    @Test
    fun `완료 맵을 삭제했다 다시 추가해도 이미 복구한 늦은 응답은 새 순환을 만들지 않는다`() =
        verifyUnionResult(lateWorker = true, readdRemovedMap = true)

    private fun verifyUnionResult(
        failFirstApplication: Boolean = false,
        failAfterProjection: Boolean = false,
        outcome: BattleAutomationRoundOutcome = BattleAutomationRoundOutcome.VICTORY,
        lateWorker: Boolean? = null,
        reorderBeforeRestore: Boolean = false,
        removeBeforeRestore: Boolean = false,
        corruption: String? = null,
        readdRemovedMap: Boolean = false,
    ) {
        // A·B·C를 A·C·B로 바꾼다. 이미 반영한 A는 B를 유지하고, 미반영 A는 C로 진행한다.
        val nextMap = if (reorderBeforeRestore && !failAfterProjection) "0005" else "0004"
        val afterNextMap = if (!reorderBeforeRestore) "0005" else if (failAfterProjection) "0003" else "0004"
        val restoredRotation = if (removeBeforeRestore && !failAfterProjection) null else "union:$nextMap"
        Mockito.doCallRealMethod().`when`(decisions).select(accountId)
        val battleTargets = mutableListOf<String>()
        var homeAccepted = false
        var allowNextUnion = false
        val homeUrl = "https://hof.zerosic.com/index.php?menu=quest2"
        fun homePage(): String {
            val heading = if (homeAccepted) "진행중인 작업 목록" else "수락 가능한 작업 목록"
            val action = if (homeAccepted) "-" else "<a href='?menu=quest2&amp;action=get&amp;no=A'>수락</a>"
            return """<div id='menu2'>Funds : $ 1 Time : 100/100</div><h4>$heading</h4><table>
                <tr><td>[A] 독립 자택</td><td>미션 0/1</td><td>-</td><td>-</td><td>$action</td></tr></table>"""
        }
        fun maps(): String {
            fun cooldown(mapCode: String) = if (battleTargets.isNotEmpty() &&
                (!allowNextUnion || (lateWorker != null || corruption != null) && mapCode == "0003")) " (1분) 남음" else ""
            return """<html><body><div id='menu2'>Funds : $ 1 Time : 100/100</div>
                <div id='contents'><div>공유 지역 (2)</div><div id='mapgroup1'>
                <p><a href='index.php?union=0003'>유니온 A${cooldown("0003")}</a> 3 가능</p>
                <p><a href='index.php?union=0004'>유니온 B${cooldown("0004")}</a> 3 가능</p>
                <p><a href='index.php?union=0005'>유니온 C${cooldown("0005")}</a> 3 가능</p></div></div>
                <div id='foot'><h5>Copy Right sanitized fixture</h5><h6>H.O.F Korean Ver sanitized fixture</h6>
                <img src='image/zerohof.gif'></div></body></html>"""
        }
        val home = HomePageParser().parse(HomeMode.HOME, homePage(), homeUrl,
            HofFormParser().parse(homePage(), homeUrl)).quests.single()
        TransactionTemplate(transactions).executeWithoutResult {
            val entry = entityManager.find(AutomationEntryEntity::class.java, entryId)
            entry.singletonTypeMarker = AutomationType.UNION
            val preset = PartyPresetEntity(account = entry.account, name = "유니온 파티", isPrimary = true,
                createdAt = clock.now(), updatedAt = clock.now())
            entityManager.persist(preset)
            entityManager.createQuery("select c from CharacterEntity c where c.account.id = :id order by c.id", CharacterEntity::class.java)
                .setParameter("id", accountId).resultList.forEachIndexed { index, character ->
                    val pattern = CharacterPatternSlotEntity(character = character, slotCode = "1", label = "기본", canLoad = true)
                    entityManager.persist(pattern)
                    entityManager.persist(PartyPresetMemberEntity(preset, index, character, pattern))
                }
            listOf("0003", "0004", "0005").forEachIndexed { index, map ->
                entityManager.persist(UnionAutomationMapEntity(entry = entry, categoryId = "union", mapCode = map,
                    presetMode = PresetSelectionMode.PRIMARY, executionOrder = index))
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
                request.url.contains("?char=") -> "<div>Funds : $ 1 Time : 100/100</div>" +
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
                    assertTrue(request.url.startsWith("https://hof.zerosic.com/index.php?union="), request.url)
                    battleTargets += request.url.substringAfter("?union=")
                    allowNextUnion = false
                    val title = when (outcome) {
                        BattleAutomationRoundOutcome.VICTORY -> "테스트은(는) 승리했다!"
                        BattleAutomationRoundOutcome.DEFEAT -> "Frosty Mountain- 대충산(마도사의 은신처)은(는) 승리했다!"
                        BattleAutomationRoundOutcome.DRAW -> "무승부!"
                        else -> error("단말 결과 fixture만 사용한다.")
                    }
                    val enemyHp = if (outcome == BattleAutomationRoundOutcome.VICTORY) 0 else 100
                    val allyHp = if (outcome == BattleAutomationRoundOutcome.DEFEAT) 0 else 100
                    """<div id='menu2'>Funds : $ 1 Time : 100/100</div><h2>Show Detail( 1 turns. )</h2>
                        <h1>$title</h1>
                        <div>남은 HP : $enemyHp/100 생존자 : ${if (enemyHp == 0) 0 else 1}/1 총 데미지 : 0</div>
                        <div>남은 HP : $allyHp/100 생존자 : ${if (allyHp == 0) 0 else 1}/1 총 데미지 : 100 턴 : 1/100 획득 경험치 : 1 획득 Funds : $ 1</div>"""
                }
            }
            HofHttpResponse(200, request.url, body, emptyMap())
        }.`when`(gateway).execute(Mockito.eq(accountId), anyRequest(), Mockito.anyMap())

        var failureInjected = false
        if (failFirstApplication) {
            val placeholder = Mockito.mock(ManagedAutomationAction::class.java)
            Mockito.doAnswer { invocation ->
                val managed = invocation.getArgument<ManagedAutomationAction>(0)
                val payload = managed.storedAction.payload
                if (!failureInjected && payload is StoredTypedActionPayload.BattleMap &&
                    payload.source == BattleAutomationActionSource.UNION_AUTOMATION
                ) {
                    assertEquals(listOf("0003"), battleTargets)
                    assertIs<AutomationActionEvidence.DirectApplied>(invocation.getArgument<AutomationActionEvidence?>(2))
                    if (failAfterProjection) invocation.callRealMethod()
                    assertEquals(if (failAfterProjection) listOf("union:0004") else emptyList(), jdbc.queryForList(
                        "select current_target_key from automation_rotation_states where automation_entry_id = ?",
                        String::class.java, entryId), "순환 반영 전 실패와 반영 후 실패를 구분한다.")
                    failureInjected = true
                    throw IllegalStateException("수신한 유니온 결과의 첫 로컬 완료 실패")
                }
                invocation.callRealMethod()
            }.`when`(results).applyDirect(
                Mockito.any<ManagedAutomationAction>() ?: placeholder,
                Mockito.any<TypedAutomationExecution>() ?: TypedAutomationExecution.Completed,
                Mockito.nullable(AutomationActionEvidence::class.java),
                Mockito.nullable(Long::class.javaObjectType),
            )
        }

        wakeups.wake(accountId, "UNION_DIRECT_RESULT")
        if (lateWorker != null) {
            verifyLateWorkerAfterNextMap(lateWorker, battleTargets, { allowNextUnion = true }, { homeAccepted }, readdRemovedMap)
            return
        }
        publisher.publishBatch()

        assertEquals(listOf("0003"), battleTargets)
        assertFalse(homeAccepted)
        val identity = assertNotNull(jdbc.queryForObject(
            "select execution_identity from typed_automation_action_runs where account_id = ? and action_kind = 'BATTLE_MAP'",
            String::class.java, accountId))
        if (failFirstApplication) {
            assertTrue(failureInjected)
            val receipt = mapper.readValue(assertNotNull(jdbc.queryForObject(
                "select direct_response_json from typed_automation_action_runs where account_id = ? and execution_identity = ?",
                String::class.java, accountId, identity)), StoredAutomationDirectResponse::class.java)
            val response = assertIs<AutomationDirectResponse.BattleMap>(receipt.response)
            assertEquals(identity, response.resultIdentity)
            assertEquals(listOf(outcome), response.outcomes)
            assertEquals("UNION_BATTLE", receipt.policyContext?.actionKind?.name)
            assertEquals(entryId, receipt.isolation?.entryId)
            assertEquals("UNION_ENTRY", receipt.isolation?.scope?.kind?.name)
            assertEquals(entryId.toString(), receipt.isolation?.scope?.key)
            if (corruption != null) corruptReceipt(identity, receipt, corruption)
            if (reorderBeforeRestore || removeBeforeRestore) {
                val changedMaps = if (removeBeforeRestore) listOf("0004", "0005") else listOf("0003", "0005", "0004")
                application.updateUnion(accountId, UpdateUnionAutomationRequest(true,
                    changedMaps.mapIndexed { index, mapCode ->
                        UnionMapSettingRequest("union", mapCode, PresetSelectionMode.PRIMARY, null, index)
                    }))
            }
            clock.current = clock.now().plusSeconds(11)
            consumeNextWake()
            if (corruption != null) {
                verifyCorruptReceiptHeld(identity, corruption, battleTargets, { homeAccepted }, { allowNextUnion = true })
                return
            }
            assertTrue(journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }.any {
                it.reasonCode == "STORED_DIRECT_RESPONSE_RESTORE" && it.actionKind == "BATTLE_MAP"
            })
        }
        fun successEvents() = journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }
            .filter { it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED && it.actionKind == "BATTLE_MAP" }
        fun rotation() = jdbc.queryForList(
            "select current_target_key from automation_rotation_states where automation_entry_id = ?", String::class.java, entryId).singleOrNull()
        fun assertOriginalResult() {
            assertEquals("SUCCEEDED", runs().single { it["execution_identity"] == identity }["status"])
            val record = store.get(accountId, identity)
            if (mode == AutomationConvergenceMode.ACTIVE) {
                assertEquals(ActionConvergenceResult.APPLIED, assertNotNull(record).result)
                assertEquals("DIRECT_RESPONSE_APPLIED", record.reasonCode)
            } else assertNull(record)
            val shadowResults = jdbc.queryForList(
                "select new_result from automation_convergence_shadow_evaluations where account_id = ? and execution_identity_hash = ?",
                String::class.java, accountId, ProductionEvidenceShapes.fingerprint(identity))
            assertEquals(if (mode == AutomationConvergenceMode.SHADOW) listOf("APPLIED") else emptyList(), shadowResults)
        }
        assertOriginalResult()
        assertEquals(restoredRotation, rotation())
        val firstSuccess = successEvents().single()
        val firstCycles = journal.page(accountId, AutomationHistoryQuery()).cycles.map { it.id }.toSet()

        repeat(4) { consumeNextWake() }

        assertTrue(homeAccepted)
        assertEquals(1, requests.count { it.method == HofHttpMethod.GET && it.formFields["action"] == "get" && it.formFields["no"] == "A" })
        assertEquals(listOf("0003"), battleTargets)
        assertEquals(firstSuccess, successEvents().single())
        assertEquals(restoredRotation, rotation())
        assertOriginalResult()
        assertTrue(journal.page(accountId, AutomationHistoryQuery()).cycles.count { it.id !in firstCycles } >= 2)
        assertEquals(0, runningWorkCount())

        allowNextUnion = true
        repeat(5) { if (battleTargets.size < 2) consumeNextWake() }

        assertEquals(listOf("0003", nextMap), battleTargets, "저장된 순환 위치에서 실제 다음 맵을 선택해야 한다.")
        assertEquals("union:$afterNextMap", rotation())
        assertEquals(2, successEvents().size)
        assertTrue(firstSuccess in successEvents())
        assertOriginalResult()
        assertEquals(0, runningWorkCount())
        assertNull(jdbc.queryForObject("select lease_token from typed_automation_runtime_states where account_id = ?", String::class.java, accountId))
    }

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
        battleTargets: List<String>,
        homeAccepted: () -> Boolean,
        enableNextUnion: () -> Unit,
    ) {
        fun successEvents() = journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }
            .filter { it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED && it.actionKind == "BATTLE_MAP" }
        fun assertOriginalHeld(afterNewSelection: Boolean = false) {
            assertEquals("RESULT_HELD", runs().single { it["execution_identity"] == identity }["status"])
            if (mode == AutomationConvergenceMode.ACTIVE) {
                assertEquals(ActionConvergenceResult.APPLIED, assertNotNull(store.get(accountId, identity)).result)
            } else assertNull(store.get(accountId, identity))
            assertTrue(successEvents().none { it.targetKey == "union/0003" })
            val expectedShadow = if (mode == AutomationConvergenceMode.SHADOW && afterNewSelection) listOf("SUPERSEDED") else emptyList()
            assertEquals(expectedShadow, jdbc.queryForList(
                "select new_result from automation_convergence_shadow_evaluations where account_id = ? and execution_identity_hash = ?",
                String::class.java, accountId, ProductionEvidenceShapes.fingerprint(identity)), "원래 손상 응답의 SHADOW 비교 결과")
        }
        assertOriginalHeld()
        assertEquals(0, jdbc.queryForObject("select count(*) from automation_rotation_states where automation_entry_id = ?", Int::class.java, entryId))
        enableNextUnion()
        repeat(4) { consumeNextWake() }
        assertTrue(homeAccepted(), "유니온의 결과 보류가 독립 자택 수락을 막지 않는다.")
        assertEquals(listOf("0003"), battleTargets, "B가 실행 가능해도 유니온 항목의 보류 범위를 지킨다.")
        assertOriginalHeld()
        val held = convergence.get(accountId).localResults.single()
        assertEquals(entryId, held.entryId)
        assertEquals("UNION_BATTLE", held.actionKind)
        assertEquals(TypedAutomationActionStatus.RESULT_HELD, held.status)
        assertEquals("LOCAL_RESULT_INTEGRITY_FAILED", held.reasonCode)
        assertEquals("유니온 자동화 $entryId", held.impactScope)
        assertTrue(held.canAllowFreshDecision)
        val snippet = assertNotNull(jdbc.queryForObject("select sanitized_snippet from automation_evidence_cases where id = ?",
            String::class.java, assertNotNull(held.evidenceCaseId)))
        assertTrue(snippet.contains("receiptValid=${corruption != "FINGERPRINT"}"), snippet)
        val beforeRelease = requests.size
        assertTrue(convergence.allowLocalFreshDecision(accountId, held.actionId).localResults.isEmpty())
        assertEquals(beforeRelease, requests.size, "명시 해제 자체는 HOF에 행동을 제출하지 않는다.")
        repeat(5) { if (battleTargets.size < 2) consumeNextWake() }
        assertEquals(listOf("0003", "0004"), battleTargets)
        assertEquals("union:0005", jdbc.queryForObject(
            "select current_target_key from automation_rotation_states where automation_entry_id = ?", String::class.java, entryId))
        assertEquals("union/0004", successEvents().single().targetKey)
        repeat(2) { consumeNextWake() }
        assertOriginalHeld(afterNewSelection = true)
        assertTrue(convergence.get(accountId).localResults.isEmpty())
        assertEquals(listOf("0003", "0004"), battleTargets)
        assertEquals(1, requests.count { it.formFields["action"] == "get" && it.formFields["no"] == "A" })
        assertEquals(0, runningWorkCount())
        assertNull(jdbc.queryForObject("select lease_token from typed_automation_runtime_states where account_id = ?", String::class.java, accountId))
    }

    private fun verifyLateWorkerAfterNextMap(
        receiptStoredBeforeRecovery: Boolean,
        battleTargets: List<String>,
        enableNextUnion: () -> Unit,
        homeAccepted: () -> Boolean,
        readdRemovedMap: Boolean,
    ) {
        val directReady = CountDownLatch(1)
        val returnDirect = CountDownLatch(1)
        val firstDirect = AtomicBoolean(true)
        if (receiptStoredBeforeRecovery) {
            val placeholder = Mockito.mock(ManagedAutomationAction::class.java)
            Mockito.doAnswer { invocation ->
                val managed = invocation.getArgument<ManagedAutomationAction>(0)
                val payload = managed.storedAction.payload
                if (payload is StoredTypedActionPayload.BattleMap &&
                    payload.source == BattleAutomationActionSource.UNION_AUTOMATION && firstDirect.compareAndSet(true, false)
                ) {
                    // 원래 응답은 이미 저장됐지만 순환 차례는 아직 반영하지 않은 지점이다.
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
        } else {
            val preparedPlaceholder = battle()
            Mockito.doAnswer { invocation ->
                val managed = invocation.callRealMethod() as ManagedAutomationAction
                object : ManagedAutomationAction by managed {
                    override fun execute(): TypedAutomationExecution {
                        val execution = managed.execute()
                        if (firstDirect.compareAndSet(true, false)) {
                            directReady.countDown()
                            check(returnDirect.await(30, TimeUnit.SECONDS))
                        }
                        return execution
                    }
                }
            }.`when`(actionLifecycle).prepare(Mockito.eq(accountId), Mockito.eq(entryId),
                Mockito.any<PreparedAutomationAction>() ?: preparedPlaceholder)
        }
        val otherTransport = AutomationRecoveryIntegrationTest.Config()
            .consumerReplayTransport(mapper, consumedEvents, consumerLease, runner, clock)
        val otherPublisher = AutomationOutboxPublisher(outbox, publishedMarker, otherTransport, clock)
        fun rotation() = jdbc.queryForList(
            "select current_target_key from automation_rotation_states where automation_entry_id = ?", String::class.java, entryId).singleOrNull()
        fun successes() = journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }
            .filter { it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED && it.actionKind == "BATTLE_MAP" }
        Executors.newFixedThreadPool(2).use { executor ->
            fun consumeOtherWake() {
                val next = assertNotNull(jdbc.queryForObject(
                    "select min(available_at) from automation_outbox where account_id = ? and topic = ? and published_at is null",
                    java.time.OffsetDateTime::class.java, accountId, AutomationOutboxService.WAKEUP_TOPIC)).toInstant()
                clock.current = maxOf(clock.now(), next)
                val before = otherTransport.delivered.size
                executor.submit { otherPublisher.publishBatch() }.get(10, TimeUnit.SECONDS)
                assertTrue(otherTransport.delivered.size > before)
                assertTrue(otherTransport.delivered.all { outbox.consumed(it) })
                assertEquals(0, jdbc.queryForObject("select count(*) from account_automation_leases where account_id = ?", Int::class.java, accountId))
            }
            val oldWorker = executor.submit { publisher.publishBatch() }
            try {
                assertTrue(directReady.await(10, TimeUnit.SECONDS))
                assertEquals(listOf("0003"), battleTargets)
                val original = jdbc.queryForMap(
                    "select status, execution_identity, direct_response_json from typed_automation_action_runs where account_id = ? and action_kind = 'BATTLE_MAP'",
                    accountId)
                val identity = original["execution_identity"] as String
                assertEquals("SUBMITTING", original["status"])
                assertEquals(receiptStoredBeforeRecovery, original["direct_response_json"] != null)
                val originalCycles = journal.page(accountId, AutomationHistoryQuery()).cycles.map { it.id }.toSet()
                assertEquals(0, jdbc.queryForObject("select count(*) from automation_rotation_states where automation_entry_id = ?", Int::class.java, entryId))
                val dispatched = outbox.findUnpublished(clock.now()).single {
                    it.topic == AutomationOutboxService.WAKEUP_TOPIC &&
                        mapper.readValue(it.payload, AutomationWakeupEvent::class.java).reason == "UNION_DIRECT_RESULT"
                }
                publishedMarker.markPublished(dispatched.id)
                if (readdRemovedMap) application.updateUnion(accountId, UpdateUnionAutomationRequest(true,
                    listOf("0005", "0004").mapIndexed { index, mapCode ->
                        UnionMapSettingRequest("union", mapCode, PresetSelectionMode.PRIMARY, null, index)
                    }))
                clock.current = clock.now().plusSeconds(301)
                wakeups.wake(accountId, "UNION_STORED_RECEIPT_RECOVERY")
                consumeOtherWake()
                if (receiptStoredBeforeRecovery) {
                    assertEquals("SUCCEEDED", runs().single { it["execution_identity"] == identity }["status"])
                    assertEquals(if (readdRemovedMap) null else "union:0004", rotation())
                } else {
                    assertTrue(successes().isEmpty(), "최신 공유 맵 관측만으로 A 성공을 귀속하지 않는다.")
                    assertEquals(0, jdbc.queryForObject("select count(*) from automation_rotation_states where automation_entry_id = ?", Int::class.java, entryId))
                    if (mode == AutomationConvergenceMode.ACTIVE) {
                        val held = assertNotNull(store.get(accountId, identity))
                        assertEquals(ActionConvergenceResult.HELD, held.result)
                        assertEquals("AMBIGUOUS", runs().single { it["execution_identity"] == identity }["status"])
                        val requestCount = requests.size
                        convergence.allowFreshDecision(accountId, held.attemptId)
                        assertEquals(requestCount, requests.size, "명시적 새 판단 허용 자체는 HOF에 제출하지 않는다.")
                    } else assertEquals("FAILED", runs().single { it["execution_identity"] == identity }["status"])
                }
                val firstSuccess = successes().singleOrNull()
                assertEquals(listOf("0003"), battleTargets, "복구는 A 전투를 다시 제출하지 않는다.")

                if (readdRemovedMap) {
                    assertNotNull(firstSuccess)
                    application.updateUnion(accountId, UpdateUnionAutomationRequest(true,
                        listOf("0005", "0003", "0004").mapIndexed { index, mapCode ->
                            UnionMapSettingRequest("union", mapCode, PresetSelectionMode.PRIMARY, null, index)
                        }))
                    returnDirect.countDown()
                    oldWorker.get(10, TimeUnit.SECONDS)
                    assertNull(rotation(), "삭제된 A의 완료는 이미 처리됐다. 늦은 동일 결과가 새 C·A·B 설정에 순환 차례를 만들면 안 된다.")
                    assertEquals(firstSuccess, successes().single())
                    assertEquals("SUCCEEDED", runs().single { it["execution_identity"] == identity }["status"])
                    enableNextUnion()
                    repeat(5) { if (battleTargets.size < 2) consumeOtherWake() }
                    assertEquals(listOf("0003", "0005"), battleTargets, "새 설정의 첫 실행 가능 맵 C를 선택한다.")
                    assertEquals("union:0003", rotation())
                    repeat(4) { consumeOtherWake() }
                    assertTrue(homeAccepted())
                    assertEquals(listOf("0003", "0005"), battleTargets)
                    assertEquals(2, successes().size)
                    assertTrue(firstSuccess in successes())
                    assertEquals(0, runningWorkCount())
                    return@use
                }

                enableNextUnion()
                repeat(10) { if (battleTargets.size < 2) consumeOtherWake() }
                assertEquals(listOf("0003", "0004"), battleTargets, "새 식별자로 실제 B 전투를 제출한다.")
                assertEquals("union:0005", rotation())
                val completedBeforeLate = successes().toSet()
                assertEquals(if (receiptStoredBeforeRecovery) 2 else 1, completedBeforeLate.size)
                if (firstSuccess != null) assertTrue(firstSuccess in completedBeforeLate)
                repeat(5) { if (!homeAccepted()) consumeOtherWake() }
                assertTrue(homeAccepted(), "B 완료 뒤 독립 자택을 실제 소비한다.")
                val independentWork = jdbc.queryForMap(
                    "select id, work_type, target_key, status, next_check_at, finished_at from automation_work_sessions where account_id = ? and work_type = 'HOME_QUEST' order by id desc limit 1", accountId)
                assertEquals("HOME_QUEST", independentWork["work_type"])
                assertTrue(independentWork["status"] in setOf("RUNNING", "WAITING_COOLDOWN"),
                    "수락한 자택은 실행 중이거나 진행도 재확인을 기다린다: $independentWork")
                assertNull(independentWork["finished_at"])
                if (independentWork["status"] == "WAITING_COOLDOWN") assertNotNull(independentWork["next_check_at"])

                returnDirect.countDown()
                oldWorker.get(10, TimeUnit.SECONDS)

                assertEquals("union:0005", rotation(), "늦은 A 후처리는 B 완료 뒤의 C 순환 차례를 되돌리지 않는다.")
                val finalSuccesses = successes().toSet()
                assertEquals(2, finalSuccesses.size)
                assertTrue(finalSuccesses.containsAll(completedBeforeLate), "먼저 확인한 성공 이력의 귀속을 유지한다.")
                if (receiptStoredBeforeRecovery) assertEquals(completedBeforeLate, finalSuccesses)
                else {
                    val originalSuccess = (finalSuccesses - completedBeforeLate).single()
                    assertEquals("union/0003", originalSuccess.targetKey)
                    assertTrue(journal.page(accountId, AutomationHistoryQuery()).cycles.any {
                        it.id in originalCycles && originalSuccess in it.events
                    }, "원래 A의 늦은 성공은 첫 제출 판단에 귀속한다.")
                }
                assertEquals(listOf("0003", "0004"), battleTargets)
                assertEquals("SUCCEEDED", runs().single { it["execution_identity"] == identity }["status"])
                if (mode == AutomationConvergenceMode.ACTIVE) {
                    assertEquals(ActionConvergenceResult.APPLIED, assertNotNull(store.get(accountId, identity)).result)
                } else assertNull(store.get(accountId, identity))
                val expectedShadow = if (mode != AutomationConvergenceMode.SHADOW) emptyList()
                    else if (receiptStoredBeforeRecovery) listOf("APPLIED") else listOf("APPLIED", "SUPERSEDED")
                assertEquals(expectedShadow, jdbc.queryForList(
                    "select new_result from automation_convergence_shadow_evaluations where account_id = ? and execution_identity_hash = ?",
                    String::class.java, accountId, ProductionEvidenceShapes.fingerprint(identity)).map { requireNotNull(it) }.sorted())
                assertEquals(independentWork, jdbc.queryForMap(
                    "select id, work_type, target_key, status, next_check_at, finished_at from automation_work_sessions where account_id = ? and work_type = 'HOME_QUEST' order by id desc limit 1", accountId),
                    "늦은 유니온 완료는 현재 자택 작업권을 종료하거나 교체하지 않는다.")
                repeat(3) { consumeOtherWake() }
                assertEquals(0, runningWorkCount())
                assertEquals("union:0005", rotation())
                assertEquals(listOf("0003", "0004"), battleTargets)
            } finally {
                returnDirect.countDown()
                oldWorker.get(10, TimeUnit.SECONDS)
                otherTransport.delivered.forEach { jdbc.update("delete from automation_consumed_events where event_id = ?", it) }
            }
        }
    }
}
