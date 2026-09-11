package app.spammy.hof.automation.service

import app.spammy.hof.automation.convergence.*
import app.spammy.hof.automation.entity.*
import app.spammy.hof.automation.lease.AccountAutomationLeaseService
import app.spammy.hof.automation.outbox.AutomationConsumedEventService
import app.spammy.hof.automation.outbox.AutomationOutboxPublishMarker
import app.spammy.hof.automation.outbox.AutomationOutboxPublisher
import app.spammy.hof.automation.outbox.AutomationOutboxService
import app.spammy.hof.automation.outbox.AutomationWakeupEvent
import app.spammy.hof.automation.history.AutomationHistoryEventKind
import app.spammy.hof.automation.history.AutomationHistoryQuery
import app.spammy.hof.automation.raid.RaidCooldownSource
import app.spammy.hof.automation.raid.RaidCycleStore
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.entity.CharacterPatternSlotEntity
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
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

@SpringBootTest(properties = ["hof.automation-convergence.mode=LEGACY"])
class LegacyAutomationRaidBattleDirectReceiptContinuityTest : AutomationRaidBattleDirectReceiptContinuityTest() {
    override val mode = AutomationConvergenceMode.LEGACY
}

@SpringBootTest(properties = ["hof.automation-convergence.mode=SHADOW"])
class ShadowAutomationRaidBattleDirectReceiptContinuityTest : AutomationRaidBattleDirectReceiptContinuityTest() {
    override val mode = AutomationConvergenceMode.SHADOW
}

@SpringBootTest(properties = ["hof.automation-convergence.mode=ACTIVE"])
class ActiveAutomationRaidBattleDirectReceiptContinuityTest : AutomationRaidBattleDirectReceiptContinuityTest() {
    override val mode = AutomationConvergenceMode.ACTIVE
}

@ActiveProfiles("test")
@Import(AutomationRecoveryIntegrationTest.Config::class)
abstract class AutomationRaidBattleDirectReceiptContinuityTest : AutomationRecoveryFixture() {
    @Autowired private lateinit var raidStore: RaidCycleStore
    @Autowired private lateinit var convergence: AutomationConvergenceController
    @Autowired private lateinit var mapper: tools.jackson.databind.ObjectMapper
    @MockitoSpyBean private lateinit var results: AutomationResultCoordinator
    @MockitoSpyBean private lateinit var actionLifecycle: AutomationActionLifecycleModule
    @Autowired private lateinit var consumedEvents: AutomationConsumedEventService
    @Autowired private lateinit var consumerLease: AccountAutomationLeaseService
    @Autowired private lateinit var publishedMarker: AutomationOutboxPublishMarker
    @Autowired private lateinit var runner: UnifiedAutomationRunner

    @Test
    fun `레이드 단말 응답의 첫 로컬 반영이 실패해도 재전송 없이 원래 결과와 다음 판단을 복원한다`() = verifyRaidDirectResponse()

    @Test
    fun `레이드 로컬 후처리 대기는 작업권을 놓고 독립 자택과 원래 종료 시각의 안전 대기를 보존한다`() =
        verifyRaidDirectResponse(hofCooldown = false, verifyWaitingOwnership = true)

    @Test
    fun `레이드 안전 대기를 이미 반영한 뒤 실패해도 원래 결과와 종료 시각을 유지한다`() =
        verifyRaidDirectResponse(hofCooldown = false, failAfterProjection = true)

    @ParameterizedTest
    @ValueSource(strings = ["FINGERPRINT", "KIND", "ROUNDS", "NONTERMINAL", "RESULT_ID", "FINISHED_AT"])
    fun `손상된 레이드 응답은 항목만 보류하고 명시 해제 뒤 새 전투를 판단한다`(corruption: String) =
        verifyRaidDirectResponse(hofCooldown = false, corruption = corruption)

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `늦은 레이드 직접 응답 반영은 새 전투의 복구 연쇄와 원래 성공 이력을 보존한다`(receiptStoredBeforeRecovery: Boolean) =
        verifyRaidDirectResponse(hofCooldown = false, lateWorker = receiptStoredBeforeRecovery)

    @Test
    fun `늦은 원래 레이드 결과는 이미 완료한 새 전투의 안전 대기를 되돌리지 않는다`() =
        verifyRaidDirectResponse(hofCooldown = false, lateWorker = true, newBattleCompletes = true)

    @ParameterizedTest
    @ValueSource(strings = ["DEFEAT", "DRAW"])
    fun `레이드 패배와 무승부도 원래 단말 결과로 복원하고 독립 판단을 보존한다`(outcome: String) =
        verifyRaidDirectResponse(hofCooldown = false, outcome = BattleAutomationRoundOutcome.valueOf(outcome))

    @Test
    fun `복원한 레이드 전투 뒤 보상 확인과 상태 갱신을 완료하고 중복 제출 없이 다음 판단을 이어간다`() =
        verifyRaidDirectResponse(hofCooldown = false, finishWithReward = true)

    private fun verifyRaidDirectResponse(
        hofCooldown: Boolean = true,
        verifyWaitingOwnership: Boolean = false,
        failAfterProjection: Boolean = false,
        corruption: String? = null,
        lateWorker: Boolean? = null,
        newBattleCompletes: Boolean = false,
        outcome: BattleAutomationRoundOutcome = BattleAutomationRoundOutcome.VICTORY,
        finishWithReward: Boolean = false,
    ) {
        Mockito.doCallRealMethod().`when`(decisions).select(accountId)
        val homeUrl = "https://hof.zerosic.com/index.php?menu=quest2"
        var homeAccepted = false
        var battles = 0
        var rewardWindow = false
        var rewardChecks = 0
        var postRewardRefreshes = 0
        fun homePage(): String {
            val heading = if (homeAccepted) "진행중인 작업 목록" else "수락 가능한 작업 목록"
            val action = if (homeAccepted) "-" else "<a href='?menu=quest2&amp;action=get&amp;no=A'>수락</a>"
            return """<div id='menu2'>Funds : $ 1 Time : 100/100</div><h4>$heading</h4><table>
                <tr><td>[A] 독립 자택</td><td>미션 0/1</td><td>-</td><td>-</td><td>$action</td></tr></table>"""
        }
        val home = HomePageParser().parse(HomeMode.HOME, homePage(), homeUrl, HofFormParser().parse(homePage(), homeUrl)).quests.single()
        val raidPage = requireNotNull(javaClass.getResource("/fixtures/town/raid/raidpub.html")).readText()
            .replace("현재사용자", "테스트")
            .replace("Funds : $ 1,000", "Funds : $ 1,000 Time : 100/100")
            .replace("현재 상태 : 모집 중", "현재 상태 : 전투 중")
        fun currentRaidPage(): String {
            if (!rewardWindow) return raidPage
            var page = raidPage
                .replace("현재 상태 : 전투 중", "현재 상태 : 보상 확인 시간 (남은 시간 앞으로 0시간 29분 56초)")
                .replace("현재 상태는 신청 대기 (신청 가능까지 6분 58초)", "현재 상태는 신청 가능")
                .replace("<input type=\"submit\" name=\"reward_nonce\"",
                    "<input type=\"submit\" name=\"refresh_nonce\" value=\"상태 갱신\"><input type=\"submit\" name=\"reward_nonce\"")
            if (postRewardRefreshes > 0) page = page
                .replace("현재 상태 : 보상 확인 시간 (남은 시간 앞으로 0시간 29분 56초)", "현재 상태 : 파티 모집 중 (신청 안됨)")
                .replace("[《테스트 길드》테스트]", "[다른 신청자]")
                .replace("현재 상태는 신청 가능", "현재 상태는 신청 대기 (신청 가능까지 2시간 59분 50초)")
            return page
        }
        val mapFixture = requireNotNull(javaClass.getResource("/fixtures/raid/raid-complete-absent.html")).readText()
        fun raidMaps() = if (rewardWindow) mapFixture else mapFixture.replace("<p>진행 중인 전투가 없습니다.</p>", """<div id='mapgroup1'><div>
            ${if (battles > 0 && hofCooldown) "<span>다음 전투까지 99초 남음</span>" else ""}
            <a href='index.php?raid_common=RaidGoblin'>고블린 전투 마차</a></div></div>""")
        TransactionTemplate(transactions).executeWithoutResult {
            val entry = entityManager.find(AutomationEntryEntity::class.java, entryId)
            entry.type = AutomationType.RAID
            entry.singletonTypeMarker = AutomationType.RAID
            entityManager.persist(RaidAutomationTargetEntity(entry = entry, raidId = "RaidGoblin", displayName = "고블린 전투 마차",
                presetMode = PresetSelectionMode.PRIMARY, executionOrder = 0))
            entityManager.persist(RaidAutomationCycleEntity(account = entry.account, entry = entry, raidId = "RaidGoblin",
                raidName = "고블린 전투 마차", status = RaidAutomationCycleStatus.IN_BATTLE,
                lastObservedStatus = "전투 중", startedAt = clock.now(), updatedAt = clock.now()))
            val preset = PartyPresetEntity(account = entry.account, name = "레이드 전투 파티", isPrimary = true,
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
                request.url.contains("?char=") -> "<div>Funds : $ 1 Time : 100/100</div>" +
                    app.spammy.hof.character.service.currentPatternForm() + app.spammy.hof.character.service.savedPatternLoadForm(1)
                request.url.contains("menu=quest2") -> {
                    if (request.formFields["action"] == "get") {
                        assertEquals(HofHttpMethod.GET, request.method)
                        assertEquals("A", request.formFields["no"])
                        homeAccepted = true
                    }
                    homePage()
                }
                request.url.contains("menu=raidpub") -> {
                    assertEquals(HofHttpMethod.GET, request.method)
                    currentRaidPage()
                }
                request.url.endsWith("?raid_hunt") -> raidMaps()
                request.formFields.containsKey("reward_nonce") -> {
                    assertTrue(rewardWindow)
                    assertEquals(HofHttpMethod.POST, request.method)
                    assertEquals(0, postRewardRefreshes)
                    assertEquals(1, ++rewardChecks)
                    currentRaidPage().replaceFirst("  <h4>", "  <font color=\"#88ee88\">수령 가능한 보상이 없습니다.</font><br>\n  <h4>")
                }
                request.formFields.containsKey("refresh_nonce") -> {
                    assertTrue(rewardWindow)
                    assertEquals(HofHttpMethod.POST, request.method)
                    assertEquals(1, rewardChecks)
                    assertEquals(1, ++postRewardRefreshes)
                    currentRaidPage()
                }
                else -> {
                    assertEquals(HofHttpMethod.POST, request.method)
                    assertEquals("https://hof.zerosic.com/index.php?raid_common=RaidGoblin", request.url)
                    battles++
                    if (lateWorker != null && battles == 2 && !newBattleCompletes) throw java.io.IOException("새 레이드 전투 응답 유실")
                    val title = when (outcome) {
                        BattleAutomationRoundOutcome.VICTORY -> "테스트은(는) 승리했다!"
                        BattleAutomationRoundOutcome.DEFEAT -> "고블린은(는) 승리했다!"
                        BattleAutomationRoundOutcome.DRAW -> "무승부!"
                        else -> error("이 검사는 직접 단말 전투 결과만 사용한다.")
                    }
                    """<div id="menu2">Funds : $ 1 Time : 100/100</div><h2>Show Detail( 1 turns. )</h2><h1>$title</h1>
                        <div>남은 HP : 0/100 생존자 : 0/1 총 데미지 : 0</div>
                        <div>남은 HP : 100/100 생존자 : 1/1 총 데미지 : 100 턴 : 1/100 획득 경험치 : 1 획득 Funds : $ 1</div>"""
                }
            }
            val responseUrl = if (request.formFields.keys.any { it in setOf("reward_nonce", "refresh_nonce") })
                "https://hof.zerosic.com/index.php?menu=raidpub" else request.url
            HofHttpResponse(200, responseUrl, body, emptyMap())
        }.`when`(gateway).execute(Mockito.eq(accountId), anyRequest(), Mockito.anyMap())

        var failureInjected = false
        val placeholder = Mockito.mock(ManagedAutomationAction::class.java)
        Mockito.doAnswer { invocation ->
            val managed = invocation.getArgument<ManagedAutomationAction>(0)
            val payload = managed.storedAction.payload
            if (!failureInjected && payload is StoredTypedActionPayload.BattleMap &&
                payload.source == BattleAutomationActionSource.RAID_AUTOMATION
            ) {
                assertEquals(1, battles, "원격 전투는 이미 단말 응답을 반환했다.")
                if (failAfterProjection) {
                    invocation.callRealMethod()
                    val projected = assertNotNull(raidStore.load(accountId).openCycle?.battleSafetyGate)
                    assertEquals(RaidCooldownSource.LOCAL_FALLBACK, projected.source)
                    assertEquals(clock.now(), projected.startedAt)
                    assertEquals(clock.now().plusSeconds(120), projected.notBefore)
                }
                failureInjected = true
                throw IllegalStateException("수신한 레이드 전투 결과의 첫 로컬 반영 실패")
            }
            invocation.callRealMethod()
        }.`when`(results).applyDirect(
            Mockito.any<ManagedAutomationAction>() ?: placeholder,
            Mockito.any<TypedAutomationExecution>() ?: TypedAutomationExecution.Completed,
            Mockito.nullable(AutomationActionEvidence::class.java),
            Mockito.nullable(Long::class.javaObjectType),
        )

        if (lateWorker != null) {
            verifyLateWorkerPreservesNewRecovery(lateWorker, newBattleCompletes, { battles }, { homeAccepted })
            return
        }
        val beganAt = clock.now()
        wakeups.wake(accountId, "RAID_BATTLE_DIRECT_RESULT")
        publisher.publishBatch()

        assertEquals(1, battles, journal.page(accountId, AutomationHistoryQuery()).cycles.toString())
        assertFalse(homeAccepted)
        val identity = assertIs<String>(runs().single()["execution_identity"])
        val payload = jacksonObjectMapper().readTree(assertNotNull(jdbc.queryForObject(
            "select payload_json from typed_automation_action_runs where account_id = ? and execution_identity = ?",
            String::class.java, accountId, identity)))["payload"]
        assertEquals("RAID_AUTOMATION", payload["source"].asString())
        assertEquals("RaidGoblin", payload["sourceTargetKey"].asString())
        assertEquals("raid", payload["categoryId"].asString())
        assertTrue(failureInjected)
        assertEquals("RESULT_PENDING", runs().single { it["execution_identity"] == identity }["status"])
        val receiptJson = assertNotNull(jdbc.queryForObject(
            "select direct_response_json from typed_automation_action_runs where account_id = ? and execution_identity = ?",
            String::class.java, accountId, identity))
        val receipt = jacksonObjectMapper().readValue(receiptJson, StoredAutomationDirectResponse::class.java)
        val response = assertIs<AutomationDirectResponse.BattleMap>(receipt.response)
        assertEquals(beganAt, response.finishedAt)
        assertEquals(listOf(outcome), response.outcomes)
        assertEquals(AutomationActionKind.RAID_BATTLE, assertNotNull(receipt.policyContext).actionKind)
        assertEquals(entryId, assertNotNull(receipt.isolation).entryId)
        assertEquals(AutomationIsolationScope(AutomationIsolationScopeKind.RAID_ENTRY, "RaidGoblin"),
            assertNotNull(receipt.isolation).scope)
        assertTrue(journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }
            .none { it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED && it.type == AutomationType.RAID })
        if (verifyWaitingOwnership) {
            assertEquals(0, runningWorkCount(), "로컬 결과 재시도를 기다리는 레이드는 작업권을 소유하지 않는다.")
            val waiting = jdbc.queryForMap(
                "select target_key, status, next_check_at from automation_work_sessions where account_id = ? and work_type = 'RAID'",
                accountId)
            assertEquals("RaidGoblin", waiting["target_key"])
            assertEquals("WAITING_COOLDOWN", waiting["status"])
            assertEquals(beganAt.plusSeconds(10), (waiting["next_check_at"] as java.time.OffsetDateTime).toInstant())
            consumeNextWake()
            assertTrue(homeAccepted, "레이드 결과 재시도 전에 독립 자택을 수락한다.")
            consumeNextWake()
            assertTrue(clock.now() < beganAt.plusSeconds(10))
            assertEquals("RESULT_PENDING", runs().single { it["execution_identity"] == identity }["status"])
            assertEquals(1, battles)
            assertEquals(0, runningWorkCount())
        }
        if (corruption != null) {
            val invalidResponse = when (corruption) {
                "KIND" -> AutomationDirectResponse.QuestBattle(response.outcomes)
                "ROUNDS" -> response.copy(outcomes = emptyList())
                "NONTERMINAL" -> response.copy(outcomes = listOf(BattleAutomationRoundOutcome.UNKNOWN))
                "RESULT_ID" -> response.copy(resultIdentity = "")
                "FINISHED_AT" -> response.copy(finishedAt = null)
                "FINGERPRINT" -> response
                else -> error("지원하지 않는 손상 fixture")
            }
            val invalid = mapper.writeValueAsString(receipt.copy(response = invalidResponse))
            val actionFingerprint = assertNotNull(jdbc.queryForObject(
                "select action_fingerprint from typed_automation_action_runs where account_id = ? and execution_identity = ?",
                String::class.java, accountId, identity))
            val fingerprint = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest("$accountId\n$identity\n$actionFingerprint\n$invalid".toByteArray(Charsets.UTF_8)))
            jdbc.update("update typed_automation_action_runs set direct_response_json = ?, direct_response_fingerprint = ? where account_id = ? and execution_identity = ?",
                if (corruption == "FINGERPRINT") "{}" else invalid, fingerprint, accountId, identity)
        }
        clock.current = receipt.capturedAt.plusSeconds(11)
        repeat(8) {
            if (runs().single { it["execution_identity"] == identity }["status"] !in setOf("SUCCEEDED", "RESULT_HELD")) consumeNextWake()
        }
        if (corruption != null) {
            verifyCorruptReceiptHeld(identity, corruption, { battles }, { homeAccepted })
            return
        }
        assertEquals(receiptJson, jdbc.queryForObject(
            "select direct_response_json from typed_automation_action_runs where account_id = ? and execution_identity = ?",
            String::class.java, accountId, identity))

        fun assertResultPreserved() {
            assertEquals("SUCCEEDED", runs().single { it["execution_identity"] == identity }["status"])
            val record = store.get(accountId, identity)
            if (mode == AutomationConvergenceMode.ACTIVE) {
                assertNotNull(record)
                assertEquals(AutomationActionKind.RAID_BATTLE, record.selection.actionKind)
                assertEquals(ActionConvergenceResult.APPLIED, record.result)
                assertEquals("DIRECT_RESPONSE_APPLIED", record.reasonCode)
            } else assertNull(record)
            assertEquals(if (mode == AutomationConvergenceMode.SHADOW) listOf("APPLIED") else emptyList(),
                jdbc.queryForList("select new_result from automation_convergence_shadow_evaluations where account_id = ? and execution_identity_hash = ?",
                    String::class.java, accountId, ProductionEvidenceShapes.fingerprint(identity)))
        }
        assertResultPreserved()
        val result = journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }
            .single { it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED && it.type == AutomationType.RAID }
        assertEquals("BATTLE_MAP", result.actionKind)
        assertEquals("TYPED_ACTION_COMPLETED", result.reasonCode)
        assertEquals("RaidGoblin", result.targetKey)
        repeat(4) { consumeNextWake() }

        assertTrue(homeAccepted, journal.page(accountId, AutomationHistoryQuery()).cycles.toString())
        assertEquals(1, requests.count { it.formFields["action"] == "get" && it.formFields["no"] == "A" })
        assertEquals(1, battles)
        assertResultPreserved()
        if (!hofCooldown && journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }.none {
                it.type == AutomationType.RAID && it.cooldownSource == RaidCooldownSource.LOCAL_FALLBACK
            }) {
            val recheckAt = assertNotNull(jdbc.queryForObject(
                "select next_check_at from automation_work_sessions where account_id = ? and work_type = 'RAID' and status = 'WAITING_COOLDOWN'",
                java.time.OffsetDateTime::class.java, accountId)).toInstant()
            assertTrue(recheckAt.isBefore(beganAt.plusSeconds(120)), "대기 재확인은 전투 재제출 허가 시각보다 먼저 수행한다.")
            clock.current = maxOf(clock.now(), recheckAt)
            consumeNextWake()
            assertEquals(1, battles, "안전 대기 중 실제 후속 재확인도 같은 전투를 다시 제출하지 않는다.")
        }
        val histories = journal.page(accountId, AutomationHistoryQuery()).cycles
        val expectedSource = if (hofCooldown) RaidCooldownSource.HOF_DIRECT else RaidCooldownSource.LOCAL_FALLBACK
        val cooldowns = histories.flatMap { it.events }.filter {
            it.type == AutomationType.RAID && it.cooldownSource == expectedSource
        }
        assertTrue(cooldowns.isNotEmpty(), histories.toString())
        cooldowns.forEach {
            assertEquals(expectedSource, it.cooldownSource)
            assertEquals(if (hofCooldown) it.occurredAt.plusSeconds(99) else beganAt.plusSeconds(120), it.nextRunAt)
        }
        assertEquals(result, histories.flatMap { it.events }.single { it.id == result.id })
        val cycle = assertNotNull(raidStore.load(accountId).openCycle)
        assertEquals(RaidAutomationCycleStatus.IN_BATTLE, cycle.status)
        assertNull(cycle.battleRecovery)
        val gate = assertNotNull(cycle.battleSafetyGate)
        assertEquals(expectedSource, gate.source)
        if (!hofCooldown) assertEquals(beganAt, gate.startedAt)
        assertEquals(cooldowns.first().nextRunAt, gate.notBefore)
        assertEquals(0, runningWorkCount())
        assertNull(jdbc.queryForObject("select lease_token from typed_automation_runtime_states where account_id = ?", String::class.java, accountId))
        if (finishWithReward) {
            rewardWindow = true
            clock.current = gate.notBefore.plusSeconds(1)
            repeat(5) { if (rewardChecks == 0) consumeNextWake() }
            assertEquals(1, rewardChecks)
            assertEquals(0, postRewardRefreshes, "보상 응답의 종결 뒤 별도 후속 판단이 상태를 갱신한다.")
            assertEquals(RaidAutomationCycleStatus.POST_REWARD_CHECK, raidStore.load(accountId).openCycle?.status)
            assertEquals(1, runningWorkCount())
            consumeNextWake()
            assertEquals(1, postRewardRefreshes)
            assertNull(raidStore.load(accountId).openCycle)
            assertEquals(0, runningWorkCount())
            val nextRaidCheck = assertNotNull(jdbc.queryForObject(
                "select max(next_check_at) from automation_work_sessions where account_id = ? and work_type = 'RAID'",
                java.time.OffsetDateTime::class.java, accountId)).toInstant()
            assertTrue(nextRaidCheck.isAfter(clock.now().plusSeconds(10_000)))
            repeat(4) { consumeNextWake() }
            assertEquals(1, battles)
            assertEquals(1, rewardChecks)
            assertEquals(1, postRewardRefreshes)
            assertResultPreserved()
            assertEquals(4, runs().size, "전투·독립 자택·보상·상태 갱신을 각각 한 번만 실행한다.")
            assertTrue(runs().all { it["status"] == "SUCCEEDED" }, runs().toString())
            val events = journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }
            assertEquals(result, events.single { it.id == result.id })
            val successes = events.filter { it.type == AutomationType.RAID && it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED }
            assertEquals(listOf("BATTLE_MAP", "REWARD"), successes.mapNotNull { it.actionKind }.sorted())
            assertEquals(1, events.count { it.type == AutomationType.RAID && it.actionKind == "REFRESH" &&
                it.kind == AutomationHistoryEventKind.CYCLE_COMPLETED }, "보상 후 갱신은 레이드 사이클 완료로 기록한다.")
            assertTrue(successes.single { it.actionKind == "REWARD" }.message.contains("수령 가능한 보상이 없습니다."))
            assertEquals(1, requests.count { it.formFields["action"] == "get" && it.formFields["no"] == "A" })
            assertEquals(0, runningWorkCount())
            assertNull(jdbc.queryForObject("select lease_token from typed_automation_runtime_states where account_id = ?", String::class.java, accountId))
        }
    }
    private fun verifyCorruptReceiptHeld(identity: String, corruption: String, battleCount: () -> Int, homeAccepted: () -> Boolean) {
        fun raidSuccesses() = journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }
            .filter { it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED && it.type == AutomationType.RAID }
        fun assertOriginalHeld(afterNewSelection: Boolean = false) {
            assertEquals("RESULT_HELD", runs().single { it["execution_identity"] == identity }["status"])
            if (mode == AutomationConvergenceMode.ACTIVE) {
                assertEquals(ActionConvergenceResult.APPLIED, assertNotNull(store.get(accountId, identity)).result)
            } else assertNull(store.get(accountId, identity))
            val expectedShadow = if (mode == AutomationConvergenceMode.SHADOW && afterNewSelection) listOf("SUPERSEDED") else emptyList()
            assertEquals(expectedShadow, jdbc.queryForList(
                "select new_result from automation_convergence_shadow_evaluations where account_id = ? and execution_identity_hash = ?",
                String::class.java, accountId, ProductionEvidenceShapes.fingerprint(identity)))
        }
        assertEquals(1, battleCount(), "손상 응답의 로컬 결과 보류는 명시 해제 전 같은 레이드의 새 전투를 막는다.")
        assertOriginalHeld()
        assertTrue(raidSuccesses().isEmpty())
        repeat(4) { consumeNextWake() }
        assertTrue(homeAccepted(), "레이드 결과 보류 중에도 독립 자택을 수락한다.")
        assertEquals(1, battleCount(), "같은 레이드의 새 전투도 명시 해제 전에는 선택하지 않는다.")
        assertTrue(raidSuccesses().isEmpty())
        assertOriginalHeld()
        val held = convergence.get(accountId).localResults.single()
        assertEquals(entryId, held.entryId)
        assertEquals("RAID_BATTLE", held.actionKind)
        assertEquals(TypedAutomationActionStatus.RESULT_HELD, held.status)
        assertEquals("LOCAL_RESULT_INTEGRITY_FAILED", held.reasonCode)
        assertEquals("레이드 사이클 RaidGoblin", held.impactScope)
        assertTrue(held.canAllowFreshDecision)
        val snippet = assertNotNull(jdbc.queryForObject("select sanitized_snippet from automation_evidence_cases where id = ?",
            String::class.java, assertNotNull(held.evidenceCaseId)))
        assertTrue(snippet.contains("receiptValid=" + (corruption != "FINGERPRINT")), snippet)
        val releasedAt = clock.now()
        val raidCheckAt = assertNotNull(jdbc.queryForObject(
            "select next_check_at from automation_work_sessions where account_id = ? and work_type = 'RAID'",
            java.time.OffsetDateTime::class.java, accountId)).toInstant()
        assertTrue(raidCheckAt <= releasedAt.plusSeconds(30), "보류 재확인은 기존 유한 주기 안에 있어야 한다.")
        val homeCheckAt = jdbc.queryForObject(
            "select next_check_at from automation_work_sessions where account_id = ? and work_type = 'HOME_QUEST'",
            java.time.OffsetDateTime::class.java, accountId)
        val beforeRelease = requests.size
        assertTrue(convergence.allowLocalFreshDecision(accountId, held.actionId).localResults.isEmpty())
        assertEquals(beforeRelease, requests.size, "명시 해제 자체는 원래 전투를 재전송하지 않는다.")
        assertEquals(homeCheckAt, jdbc.queryForObject(
            "select next_check_at from automation_work_sessions where account_id = ? and work_type = 'HOME_QUEST'",
            java.time.OffsetDateTime::class.java, accountId), "레이드 해제는 독립 자택의 다음 확인 시각을 바꾸지 않는다.")
        clock.current = maxOf(clock.now(), raidCheckAt)
        repeat(5) { if (battleCount() < 2) consumeNextWake() }
        assertEquals(2, battleCount(), "time=" + clock.now() + "; works=" + jdbc.queryForList(
            "select work_type, status, next_check_at from automation_work_sessions where account_id = ?", accountId) +
            "; events=" + journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }.map { it.reasonCode to it.nextRunAt })
        assertTrue(clock.now() <= releasedAt.plusSeconds(35), "명시 해제 뒤 기존 재확인 시각에 새 판단을 실행한다.")
        val newIdentity = assertNotNull(jdbc.queryForObject(
            "select execution_identity from typed_automation_action_runs where account_id = ? and action_kind = 'BATTLE_MAP' and execution_identity <> ?",
            String::class.java, accountId, identity))
        assertNotEquals(identity, newIdentity)
        assertEquals("SUCCEEDED", runs().single { it["execution_identity"] == newIdentity }["status"])
        if (mode == AutomationConvergenceMode.ACTIVE) {
            assertEquals(ActionConvergenceResult.APPLIED, assertNotNull(store.get(accountId, newIdentity)).result)
        } else assertNull(store.get(accountId, newIdentity))
        val newResult = raidSuccesses().single()
        assertEquals("RaidGoblin", newResult.targetKey)
        repeat(3) { consumeNextWake() }
        assertOriginalHeld(afterNewSelection = true)
        assertEquals(listOf(newResult), raidSuccesses(), "새 전투 성공만 보존하고 손상된 원래 전투의 성공 이력은 만들지 않는다.")
        assertEquals(2, battleCount())
        assertTrue(convergence.get(accountId).localResults.isEmpty())
        assertEquals(1, requests.count { it.formFields["action"] == "get" && it.formFields["no"] == "A" })
        assertEquals(0, runningWorkCount())
        assertNull(jdbc.queryForObject("select lease_token from typed_automation_runtime_states where account_id = ?",
            String::class.java, accountId))
    }

    private fun verifyLateWorkerPreservesNewRecovery(
        receiptStoredBeforeRecovery: Boolean,
        newBattleCompletes: Boolean,
        battleCount: () -> Int,
        homeAccepted: () -> Boolean,
    ) {
        val directReady = CountDownLatch(1)
        val returnDirect = CountDownLatch(1)
        val firstDirect = AtomicBoolean(true)
        Mockito.doAnswer { invocation ->
            val managed = invocation.callRealMethod() as ManagedAutomationAction
            val payload = managed.storedAction.payload
            if (payload !is StoredTypedActionPayload.BattleMap || payload.source != BattleAutomationActionSource.RAID_AUTOMATION) {
                return@doAnswer managed
            }
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
            val payload = managed.storedAction.payload
            if (payload is StoredTypedActionPayload.BattleMap &&
                payload.source == BattleAutomationActionSource.RAID_AUTOMATION && receiptStoredBeforeRecovery && firstDirect.compareAndSet(true, false)
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
        fun raidSuccesses() = journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }
            .filter { it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED && it.type == AutomationType.RAID }
        wakeups.wake(accountId, "RAID_BATTLE_LATE_DIRECT")
        try {
            Executors.newSingleThreadExecutor().use { executor ->
                val oldWorker = executor.submit { publisher.publishBatch() }
                try {
                    assertTrue(directReady.await(10, TimeUnit.SECONDS))
                    assertEquals(1, battleCount())
                    val original = jdbc.queryForMap(
                        "select status, execution_identity, direct_response_json, submitted_at from typed_automation_action_runs where account_id = ? and action_kind = 'BATTLE_MAP'",
                        accountId)
                    val identity = assertIs<String>(original["execution_identity"])
                    assertEquals("SUBMITTING", original["status"])
                    assertEquals(receiptStoredBeforeRecovery, original["direct_response_json"] != null)
                    assertTrue(raidSuccesses().isEmpty())
                    val originalCycles = journal.page(accountId, AutomationHistoryQuery()).cycles.map { it.id }.toSet()
                    val dispatched = outbox.findUnpublished(clock.now()).single {
                        it.topic == AutomationOutboxService.WAKEUP_TOPIC &&
                            mapper.readValue(it.payload, AutomationWakeupEvent::class.java).reason == "RAID_BATTLE_LATE_DIRECT"
                    }
                    publishedMarker.markPublished(dispatched.id)
                    clock.current = clock.now().plusSeconds(301)
                    wakeups.wake(accountId, "RAID_STORED_RECEIPT_RECOVERY")
                    consumeOtherWake()
                    assertEquals(if (receiptStoredBeforeRecovery) "SUCCEEDED" else "AMBIGUOUS",
                        runs().single { it["execution_identity"] == identity }["status"])
                    val originalSuccess = raidSuccesses().singleOrNull()
                    if (receiptStoredBeforeRecovery) assertNotNull(originalSuccess) else {
                        assertNull(originalSuccess, "원래 응답을 아직 받지 못한 복구가 성공 이력을 만들면 안 된다.")
                        val firstRecovery = assertNotNull(raidStore.load(accountId).openCycle)
                        if (mode == AutomationConvergenceMode.ACTIVE) {
                            // ACTIVE는 원래 제출 시각의 확인 예산을 보존해 유한 보류한다.
                            val held = assertNotNull(store.get(accountId, identity))
                            assertEquals(ActionConvergenceResult.HELD, held.result,
                                "원래 응답 없이 확인 예산이 끝난 ACTIVE는 새 전투를 자동 허가하지 않는다: " + firstRecovery)
                            assertNull(firstRecovery.battleRecovery)
                            assertEquals(1, battleCount())
                            val beforeRelease = requests.size
                            convergence.allowFreshDecision(accountId, held.attemptId)
                            assertEquals(beforeRelease, requests.size, "사용자 해제는 POST가 아닌 새 판단의 허가다.")
                        } else {
                            // 기존 모드는 별도 canonical 시도를 만들지 않고 레이드 전용 복구로 인계한다.
                            assertNull(store.get(accountId, identity))
                            assertEquals(identity, assertNotNull(firstRecovery.battleRecovery).latestExecutionIdentity)
                            clock.current = maxOf(clock.now(), firstRecovery.battleRecovery.nextCheckAt,
                                assertNotNull(firstRecovery.battleSafetyGate).notBefore).plusSeconds(1)
                        }
                    }
                    repeat(8) { if (battleCount() < 2) consumeOtherWake() }
                    assertEquals(2, battleCount())
                    val newIdentity = assertNotNull(jdbc.queryForObject(
                        "select execution_identity from typed_automation_action_runs where account_id = ? and action_kind = 'BATTLE_MAP' and execution_identity <> ?",
                        String::class.java, accountId, identity))
                    assertNotEquals(identity, newIdentity)
                    if (newBattleCompletes) repeat(2) { consumeOtherWake() }
                    val before = assertNotNull(raidStore.load(accountId).openCycle)
                    val recovery = before.battleRecovery
                    if (newBattleCompletes) assertNull(recovery) else {
                        assertEquals(newIdentity, assertNotNull(recovery).latestExecutionIdentity)
                        assertEquals(if (receiptStoredBeforeRecovery || mode == AutomationConvergenceMode.ACTIVE) newIdentity else identity,
                            recovery.originalExecutionIdentity)
                    }
                    val successesBeforeLateResult = raidSuccesses()
                    assertEquals(if (newBattleCompletes) 2 else if (receiptStoredBeforeRecovery) 1 else 0,
                        successesBeforeLateResult.size)
                    val gate = assertNotNull(before.battleSafetyGate)
                    assertEquals(newIdentity, gate.executionIdentity)
                    val newWork = jdbc.queryForMap(
                        "select id, status, next_check_at, finished_at from automation_work_sessions where account_id = ? and work_type = 'RAID' and status not in ('COMPLETED', 'STOPPED')",
                        accountId)
                    assertEquals("WAITING_COOLDOWN", newWork["status"])
                    val newStatus = runs().single { it["execution_identity"] == newIdentity }["status"]
                    assertEquals(if (newBattleCompletes) "SUCCEEDED" else "AMBIGUOUS", newStatus)
                    if (mode == AutomationConvergenceMode.SHADOW) {
                        // 같은 레이드의 새 선택은 이전 비교를 대체하며 아직 원래 응답의 성공은 아니다.
                        assertEquals(listOf(if (receiptStoredBeforeRecovery) "APPLIED" else "SUPERSEDED"),
                            jdbc.queryForList("select new_result from automation_convergence_shadow_evaluations where account_id = ? and execution_identity_hash = ?",
                                String::class.java, accountId, ProductionEvidenceShapes.fingerprint(identity)))
                    }

                    returnDirect.countDown()
                    oldWorker.get(10, TimeUnit.SECONDS)

                    val after = assertNotNull(raidStore.load(accountId).openCycle)
                    assertEquals(recovery, after.battleRecovery, "늦은 원래 전투의 완료가 새 전투의 복구 연쇄를 지우면 안 된다.")
                    assertEquals(gate, after.battleSafetyGate, "원래 종료 시각의 안전 대기가 새 전투의 대기를 덮어쓰면 안 된다.")
                    assertEquals(newWork, jdbc.queryForMap(
                        "select id, status, next_check_at, finished_at from automation_work_sessions where id = ?", newWork["id"]))
                    assertEquals(newStatus, runs().single { it["execution_identity"] == newIdentity }["status"])
                    val lateSuccess = if (originalSuccess != null) raidSuccesses().single { it == originalSuccess } else raidSuccesses().single()
                    if (originalSuccess != null) assertEquals(originalSuccess, lateSuccess)
                    else assertTrue(journal.page(accountId, AutomationHistoryQuery()).cycles.any {
                        it.id in originalCycles && lateSuccess in it.events
                    }, "늦게 확보한 직접 성공도 원래 제출 판단에 귀속한다.")
                    assertEquals("SUCCEEDED", runs().single { it["execution_identity"] == identity }["status"])
                    assertEquals(original["submitted_at"], runs().single { it["execution_identity"] == identity }["submitted_at"])
                    repeat(6) { if (!homeAccepted()) consumeOtherWake() }
                    assertTrue(homeAccepted(), "새 레이드 복구 대기 중에도 독립 자택을 실행한다.")
                    repeat(3) { consumeOtherWake() }
                    assertEquals(2, battleCount())
                    assertEquals(if (newBattleCompletes) successesBeforeLateResult else listOf(lateSuccess), raidSuccesses())
                    if (newBattleCompletes) {
                        assertNull(raidStore.load(accountId).openCycle?.battleRecovery)
                        assertEquals(gate, raidStore.load(accountId).openCycle?.battleSafetyGate)
                    } else {
                        assertEquals(newIdentity, assertNotNull(raidStore.load(accountId).openCycle?.battleRecovery).latestExecutionIdentity)
                    }
                    if (mode == AutomationConvergenceMode.ACTIVE) {
                        assertEquals(ActionConvergenceResult.APPLIED, assertNotNull(store.get(accountId, identity)).result)
                    } else assertNull(store.get(accountId, identity))
                    val expectedShadow = if (mode != AutomationConvergenceMode.SHADOW) emptyList()
                        else if (receiptStoredBeforeRecovery) listOf("APPLIED") else listOf("APPLIED", "SUPERSEDED")
                    assertEquals(expectedShadow,
                        jdbc.queryForList("select new_result from automation_convergence_shadow_evaluations where account_id = ? and execution_identity_hash = ?",
                            String::class.java, accountId, ProductionEvidenceShapes.fingerprint(identity)).map { requireNotNull(it) }.sorted())
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

}
