package app.spammy.hof.automation.service

import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.beans.factory.annotation.Autowired
import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.automation.convergence.*
import app.spammy.hof.automation.raid.*
import app.spammy.hof.automation.history.AutomationHistoryQuery
import app.spammy.hof.automation.entity.*
import app.spammy.hof.automation.outbox.*
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.party.entity.PartyPresetEntity
import app.spammy.hof.party.entity.PartyPresetMemberEntity
import app.spammy.hof.character.entity.CharacterPatternSlotEntity
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.automation.history.AutomationHistoryEventKind
import org.junit.jupiter.params.provider.EnumSource
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.support.TransactionTemplate

@SpringBootTest(properties = ["hof.automation-convergence.mode=LEGACY"])
class LegacyAutomationRecoveryModeTest : AutomationRecoveryModesTest() {
    override val mode = AutomationConvergenceMode.LEGACY
}

@SpringBootTest(properties = ["hof.automation-convergence.mode=SHADOW"])
class ShadowAutomationRecoveryModeTest : AutomationRecoveryModesTest() {
    override val mode = AutomationConvergenceMode.SHADOW
}

@SpringBootTest(properties = ["hof.automation-convergence.mode=ACTIVE"])
class ActiveAutomationRecoveryModeTest : AutomationRecoveryModesTest() {
    override val mode = AutomationConvergenceMode.ACTIVE
}

@ActiveProfiles("test")
@Import(AutomationRecoveryIntegrationTest.Config::class)
abstract class AutomationRecoveryModesTest : AutomationRecoveryFixture() {
    @MockitoSpyBean private lateinit var wakeOutbox: AutomationOutboxService
    @Autowired private lateinit var captchaResumes: TypedCaptchaAutomationResumeService
    @Autowired private lateinit var captchaService: app.spammy.hof.captcha.service.CaptchaService
    @Autowired private lateinit var passMaintenance: app.spammy.hof.captcha.service.CaptchaPassMaintenanceService
    @MockitoSpyBean private lateinit var battleRuns: app.spammy.hof.battle.service.BattleRunService
    @ParameterizedTest
    @ValueSource(strings = ["REJECTED", "INCOMPLETE", "CAPTCHA"])
    fun `패턴 불러오기를 확인하지 못한 세 모드는 전투 없이 끝내고 새 판단에서 복구한다`(failure: String) {
        val state = setupFishing(initialBattle = true, hiddenBattle = true)
        state.preloadFailure = failure
        fun patternPosts() = requests.filter { it.method == HofHttpMethod.POST && it.url.contains("?char=") }
        fun fishingBattles() = requests.filter { it.method == HofHttpMethod.POST && it.url.contains("common=fishing_12") }

        wakeups.wake(accountId, "PATTERN_LOAD_UNCONFIRMED")
        publisher.publishBatch()

        assertEquals(1, patternPosts().size)
        assertTrue(fishingBattles().isEmpty(), "선로드의 거부·불완전·캡차 응답 뒤 실제 전투를 보내면 안 된다.")
        assertTrue(fishingPosts().isEmpty())
        val first = runs().single()
        assertEquals("FAILED", first["status"])
        assertNull(first["submitted_at"])
        assertEquals(0, jdbc.queryForObject(
            "select count(*) from automation_action_attempts where account_id = ? and submitted_at is not null", Int::class.java, accountId))
        assertTrue(store.findSuppressedBaselines(accountId).isEmpty(), "전투 미전송을 결과 미관측으로 보류하면 안 된다.")
        if (failure == "CAPTCHA") {
            assertNotNull(captchaService.findCurrent(accountId), "선로드 캡차도 실제 답안 요청으로 기록한다.")
            assertNotNull(store.activeBattleGate(accountId))
            consumeNextWake()
            assertTrue(fishingBattles().isEmpty(), "관문 해소 전의 후속 판단도 전투를 보내면 안 된다.")
            assertEquals(0, runningWorkCount(), "관문 판단에서 대기 작업의 작업권을 양보해야 한다.")
            val count = requests.size
            assertTrue(captchaService.resolveCurrentPassChallenge(accountId))
            assertNull(store.activeBattleGate(accountId))
            assertEquals(count, requests.size, "관문 해소는 저장 전투를 재전송하지 않는다.")
        } else {
            assertEquals(0, runningWorkCount())
            assertNull(store.activeBattleGate(accountId))
            val events = journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }
            assertTrue(events.any { it.reasonCode == "BATTLE_PATTERN_PRELOAD_FAILED" })
        }

        // 관문에 보류된 작업은 기존 30초 재확인 간격을 유지한다. 그동안의 유휴 wake도 실제로 소비한다.
        val recoveryDeadline = clock.now().plusSeconds(30)
        repeat(12) { if (fishingBattles().isEmpty()) consumeNextWake() }
        assertTrue(fishingBattles().isNotEmpty(), "기존 재확인 시각 안에 새 전투를 선택해야 한다.")
        assertTrue(clock.now() <= recoveryDeadline)

        assertEquals(2, patternPosts().size, "실패한 로드를 재사용하지 않고 최신 파티에서 다시 준비한다.")
        assertEquals(1, fishingBattles().size)
        assertEquals("SUCCEEDED", runs().last()["status"])
        assertNotEquals(first["execution_identity"], runs().last()["execution_identity"])
        val count = journal.page(accountId, AutomationHistoryQuery()).cycles.size
        consumeNextWake()
        assertTrue(journal.page(accountId, AutomationHistoryQuery()).cycles.size > count)
        assertEquals(1, fishingBattles().size)
        assertTrue(fishingPosts().isEmpty())
        assertEquals(0, runningWorkCount())
        assertNull(jdbc.queryForObject("select lease_token from typed_automation_runtime_states where account_id = ?", String::class.java, accountId))
        assertAppliedInMode(AutomationActionKind.FISHING_OBSTRUCTION_BATTLE)
    }

    @Test
    fun `빠른 답안 해소 뒤 늦게 생성된 관문도 유효 통행증 재관측과 다음 판단에서 복구한다`() {
        failedPattern = -1
        captchaBattleResponse = true
        var answerCompletedBeforeError = false
        var selections = 0
        Mockito.doAnswer {
            if (++selections <= 2) AutomationCoordination.Runnable(entryId, battle(), emptyList())
            else AutomationCoordination.Idle(emptyList())
        }.`when`(decisions).select(accountId)
        Mockito.doAnswer { invocation ->
            try { invocation.callRealMethod() }
            catch (error: app.spammy.hof.common.error.ApiException) {
                assertEquals(app.spammy.hof.common.error.ErrorCode.CAPTCHA_REQUIRED, error.errorCode)
                // 실제 detectAndRecord commit 뒤, 오류를 runner에 전달하기 전에 빠른 답안 처리가 완료된다.
                assertTrue(captchaService.resolveCurrentPassChallenge(accountId))
                assertTrue(captchaResumes.findPendingAccountIds().isEmpty())
                assertNull(store.activeBattleGate(accountId))
                answerCompletedBeforeError = true
                throw error
            }
        }.`when`(battleRuns).runBattle(Mockito.eq(accountId),
            Mockito.any(app.spammy.hof.battle.dto.RunBattleRequest::class.java)
                ?: app.spammy.hof.battle.dto.RunBattleRequest("union", "0003", characters),
            Mockito.eq(app.spammy.hof.external.model.HofRequestOrigin.AUTOMATION)
                ?: app.spammy.hof.external.model.HofRequestOrigin.AUTOMATION)
        wakeups.wake(accountId, "FAST_CAPTCHA_ANSWER")
        publisher.publishBatch()
        assertEquals(1, battleRequests().size)
        assertTrue(answerCompletedBeforeError, "답안 완료를 지연된 CAPTCHA_REQUIRED 전달보다 먼저 실행해야 한다.")
        assertNotNull(store.activeBattleGate(accountId), "답안보다 늦은 실제 runner의 관문 생성 순서를 재현한다.")
        val requestsBeforeObservation = requests.size
        captchaBattleResponse = false
        assertTrue(passMaintenance.observe(accountId, "<div id='menu'>인증 유효시간 0:30:00</div>", clock.now(), clock.now()))
        assertNull(store.activeBattleGate(accountId))
        assertEquals(requestsBeforeObservation, requests.size)
        consumeNextWake()
        assertEquals(2, battleRequests().size, "이전 캡차 응답 뒤 최신 실행에서 새 전투는 한 번만 제출한다.")
        val decisionCount = journal.page(accountId, AutomationHistoryQuery()).cycles.size
        repeat(3) {
            if (journal.page(accountId, AutomationHistoryQuery()).cycles.size <= decisionCount) consumeNextWake()
        }
        assertTrue(journal.page(accountId, AutomationHistoryQuery()).cycles.size > decisionCount)
        assertEquals(2, battleRequests().size)
        assertEquals(0, runningWorkCount())
        assertAppliedInMode(AutomationActionKind.UNION_BATTLE)
    }

    @ParameterizedTest
    @ValueSource(strings = ["NONE", "PAUSE", "STOP", "AUTH"])
    fun `세 모드에서 캡차 관문 중 비전투를 진행하고 재개 실패 복구 뒤 새 전투와 후속 판단을 소비한다`(control: String) {
        setupFishing()
        val challengeId = TransactionTemplate(transactions).execute {
            val account = entityManager.find(HofAccountEntity::class.java, accountId)
            entityManager.find(AutomationEntryEntity::class.java, entryId).priority = 1
            val battleEntry = AutomationEntryEntity(account = account, type = AutomationType.BATTLE_MAP,
                priority = 0, enabled = true, createdAt = clock.now(), updatedAt = clock.now())
            entityManager.persist(battleEntry)
            entityManager.persist(BattleAutomationMapEntity(entry = battleEntry, categoryId = "battle_map",
                mapCode = "0001", dailyTargetCount = 1, presetMode = PresetSelectionMode.PRIMARY, executionOrder = 0))
            val challenge = app.spammy.hof.captcha.entity.CaptchaChallengeEntity(
                account = account, status = "READY", prompt = "captcha", challengeKind = "VIGILANTE_PASS",
                imageUrl = null, sourceUrl = "https://example.test/captcha", answer = null,
                createdAt = clock.now(), answeredAt = null)
            entityManager.persist(challenge)
            store.openBattleGate(accountId, null, "CAPTCHA_REQUIRED", clock.now())
            challenge.id
        }
        fun battlePosts() = requests.filter {
            it.method == HofHttpMethod.POST && !it.url.contains("?char=") && !it.url.contains("menu=fishing")
        }
        wakeups.wake(accountId, "CAPTCHA_GATE_NON_BATTLE")
        publisher.publishBatch()
        assertEquals(listOf("FStart", "FCatch"), fishingPosts())
        assertTrue(battlePosts().isEmpty(), "상위 전투 관문이 하위 비전투를 막거나 전투를 제출하면 안 된다.")
        assertNotNull(store.activeBattleGate(accountId))
        val requestCount = requests.size
        Mockito.doThrow(org.springframework.dao.DataAccessResourceFailureException("controlled wake storage failure"))
            .doCallRealMethod().`when`(wakeOutbox).enqueue(accountId, "CAPTCHA_ANSWERED", null)
        assertTrue(captchaService.resolveCurrentPassChallenge(accountId))
        assertNotNull(store.activeBattleGate(accountId), "후속 예약 실패는 관문 해제도 rollback해야 한다.")
        assertTrue(jdbc.queryForObject("select automation_resume_pending from captcha_challenges where id = ?",
            Boolean::class.java, challengeId)!!)

        when (control) {
            "PAUSE" -> application.pauseTyped(accountId)
            "STOP" -> application.stopTyped(accountId)
            "AUTH" -> TransactionTemplate(transactions).executeWithoutResult {
                lifecycle.suspendForAuthentication(accountId, "CAPTCHA_RETRY_AUTH")
            }
        }
        val controlledState = jdbc.queryForMap("select * from typed_automation_runtime_states where account_id = ?", accountId)
        app.spammy.hof.captcha.service.CaptchaAutomationResumeScheduler(captchaResumes).recoverOnStartup()
        assertNull(store.activeBattleGate(accountId))
        assertFalse(jdbc.queryForObject("select automation_resume_pending from captcha_challenges where id = ?",
            Boolean::class.java, challengeId)!!)
        assertEquals(requestCount, requests.size, "재개 복구는 HOF 답안이나 관측을 재요청하지 않는다.")
        if (control != "NONE") {
            assertEquals(controlledState, jdbc.queryForMap("select * from typed_automation_runtime_states where account_id = ?", accountId))
            wakeups.wake(accountId, "STALE_CAPTCHA_WAKE")
            publisher.publishBatch()
            assertEquals(requestCount, requests.size, "사용자 제어 또는 인증 중단 중에는 지연 wake도 HOF를 호출하지 않는다.")
            when (control) {
                "PAUSE" -> application.resumeTyped(accountId)
                "STOP" -> application.startTyped(accountId)
                "AUTH" -> TransactionTemplate(transactions).executeWithoutResult {
                    assertTrue(lifecycle.resumeAfterAuthentication(accountId, "CAPTCHA_RETRY_LOGIN"))
                }
            }
        }
        consumeNextWake()
        assertEquals(1, battlePosts().size, "복구 뒤 최신 상태에서 전투를 한 번 제출해야 한다.")
        assertEquals(1, jdbc.queryForObject("select successful_runs from battle_automation_daily_progress where account_id = ?",
            Int::class.java, accountId))
        val decisionCount = journal.page(accountId, AutomationHistoryQuery()).cycles.size
        val nextDecisionDeadline = clock.now().plusSeconds(3)
        // 이전 즉시 wake가 남아 있으면 runtime의 다음 판단 시각 전에는 소비만 하고,
        // 이어서 예약된 유휴 판단 wake를 실제로 소비한다.
        repeat(2) {
            if (journal.page(accountId, AutomationHistoryQuery()).cycles.size <= decisionCount) consumeNextWake()
        }
        assertTrue(journal.page(accountId, AutomationHistoryQuery()).cycles.size > decisionCount)
        assertTrue(clock.now() <= nextDecisionDeadline)
        assertEquals(1, battlePosts().size)
        assertEquals(listOf("FStart", "FCatch"), fishingPosts())
        assertEquals(0, runningWorkCount())
        assertTrue(requests.none { it.url.contains("example.test/captcha") })
        assertAppliedInMode(AutomationActionKind.MAP_BATTLE)
    }

    @ParameterizedTest
    @EnumSource(value = AutomationType::class, names = ["BATTLE_MAP", "ADVENTURE_MAP", "UNION"])
    fun `실제 맵 선택은 쿨다운을 건너뛰고 영속 후속 판단에서 전투한 뒤 다시 판단한다`(type: AutomationType) {
        failedPattern = -1
        Mockito.doCallRealMethod().`when`(decisions).select(accountId)
        val category = when (type) {
            AutomationType.BATTLE_MAP -> "battle_map"
            AutomationType.ADVENTURE_MAP -> "adventure_map"
            else -> "union"
        }
        val query = when (type) {
            AutomationType.BATTLE_MAP -> "common"
            AutomationType.ADVENTURE_MAP -> "sp_common"
            else -> "union"
        }
        fun page(cooling: Boolean) = """<html><body><div id="menu2">Funds : $ 1 Time : 100/100</div>
            <div id="contents"><div>공유 지역 (2)</div><div id="mapgroup1">
            <p><a href='index.php?$query=0003'>도적소탕${if (cooling) " (1분) 남음" else ""}</a> 2 가능</p></div></div>
            <div id="foot"><h5>Copy Right sanitized fixture</h5><h6>H.O.F Korean Ver sanitized fixture</h6>
            <img src="image/zerohof.gif"></div></body></html>"""
        TransactionTemplate(transactions).executeWithoutResult {
            val entry = entityManager.find(AutomationEntryEntity::class.java, entryId)
            entry.type = type
            entry.singletonTypeMarker = type.takeIf { it == AutomationType.UNION }
            val preset = PartyPresetEntity(account = entry.account, name = "실제 선택 파티", createdAt = clock.now(),
                updatedAt = clock.now(), isPrimary = true)
            entityManager.persist(preset)
            entityManager.createQuery("select c from CharacterEntity c where c.account.id = :id order by c.id", CharacterEntity::class.java)
                .setParameter("id", accountId).resultList.forEachIndexed { index, character ->
                    val pattern = CharacterPatternSlotEntity(character = character, slotCode = "1", label = "기본", canLoad = true)
                    entityManager.persist(pattern)
                    entityManager.persist(PartyPresetMemberEntity(preset, index, character, pattern))
                }
            entityManager.persist(when (type) {
                AutomationType.BATTLE_MAP -> BattleAutomationMapEntity(entry = entry, categoryId = category,
                    mapCode = "0003", dailyTargetCount = 1, presetMode = PresetSelectionMode.PRIMARY, executionOrder = 0)
                AutomationType.ADVENTURE_MAP -> AdventureAutomationMapEntity(entry = entry, categoryId = category,
                    mapCode = "0003", presetMode = PresetSelectionMode.PRIMARY, executionOrder = 0)
                else -> UnionAutomationMapEntity(entry = entry, categoryId = category,
                    mapCode = "0003", presetMode = PresetSelectionMode.PRIMARY, executionOrder = 0)
            })
        }
        mapPage = page(cooling = true)
        wakeups.wake(accountId, "REAL_MAP_SELECTION_BASELINE")
        publisher.publishBatch()
        assertTrue(battleRequests().isEmpty(), "실제 선택기가 쿨다운 맵을 제출하면 안 된다.")
        assertEquals(1, journal.page(accountId, AutomationHistoryQuery()).cycles.size)

        clock.current = clock.now().plusSeconds(60)
        mapPage = page(cooling = false)
        consumeNextWake()
        val executionIdentity = assertIs<String>(runs().single()["execution_identity"])
        assertEquals("SUCCEEDED", runs().single()["status"], runs().toString())
        assertEquals(1, battleRequests().size)
        assertEquals(2, journal.page(accountId, AutomationHistoryQuery()).cycles.size)
        val actionKind = when (type) {
            AutomationType.BATTLE_MAP -> AutomationActionKind.MAP_BATTLE
            AutomationType.ADVENTURE_MAP -> AutomationActionKind.ADVENTURE_BATTLE
            else -> AutomationActionKind.UNION_BATTLE
        }
        assertAppliedInMode(actionKind)
        val resultEvent = journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }
            .single { it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED }
        assertEquals(type, resultEvent.type)

        mapPage = page(cooling = true)
        consumeNextWake()
        assertEquals(3, journal.page(accountId, AutomationHistoryQuery()).cycles.size)
        assertEquals(1, battleRequests().size, "새 관측의 쿨다운 또는 완료한 목표를 다시 제출하면 안 된다.")
        assertNull(jdbc.queryForObject("select lease_token from typed_automation_runtime_states where account_id = ?", String::class.java, accountId))
        assertAppliedInMode(actionKind)
        assertEquals("SUCCEEDED", runs().single { it["execution_identity"] == executionIdentity }["status"])
        assertEquals(resultEvent, journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }
            .single { it.id == resultEvent.id })
        val payloads = jdbc.queryForList(
            "select payload from automation_outbox where account_id = ? and topic = ? and available_at = ? and published_at is null",
            String::class.java, accountId, AutomationOutboxService.WAKEUP_TOPIC, java.sql.Timestamp.from(clock.now().plusSeconds(3)))
        assertTrue(payloads.any { jacksonObjectMapper().readTree(it)["reason"].asString() == "TYPED_NEXT_ROUND" })
    }


    private fun assertAppliedInMode(actionKind: AutomationActionKind) {
        val applied = jdbc.queryForObject(
            "select count(*) from automation_action_convergences c join automation_action_attempts a on a.id = c.attempt_id " +
                "where a.account_id = ? and a.action_kind = ? and c.result = 'APPLIED'",
            Int::class.java, accountId, actionKind.name,
        )
        assertEquals(if (mode == AutomationConvergenceMode.ACTIVE) 1 else 0, applied,
            "$mode 실행은 실제 조립된 모드에 맞는 생산 수렴을 저장해야 한다.")
        val shadow = jdbc.queryForObject(
            "select count(*) from automation_convergence_shadow_evaluations " +
                "where account_id = ? and action_kind = ? and new_result = 'APPLIED'",
            Int::class.java, accountId, actionKind.name,
        )
        assertEquals(if (mode == AutomationConvergenceMode.SHADOW) 1 else 0, shadow,
            "$mode 실행은 실제 조립된 모드에 맞는 SHADOW 비교를 저장해야 한다.")
    }

}
