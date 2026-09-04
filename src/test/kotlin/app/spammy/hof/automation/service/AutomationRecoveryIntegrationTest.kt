package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.entity.HofCookieEntity
import app.spammy.hof.auth.service.AccountExecutionAuthorizationReader
import app.spammy.hof.automation.convergence.*
import app.spammy.hof.party.entity.PartyPresetEntity
import app.spammy.hof.party.entity.PartyPresetMemberEntity
import app.spammy.hof.character.entity.CharacterPatternSlotEntity
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import app.spammy.hof.town.raid.model.RaidAction
import app.spammy.hof.automation.entity.*
import app.spammy.hof.automation.port.AutomationWakeupPort
import app.spammy.hof.battle.dto.BattlePatternLoadRequest
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.status.entity.HofStatusSnapshotEntity
import app.spammy.hof.external.client.HofGateway
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import jakarta.persistence.EntityManager
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@SpringBootTest(properties = ["hof.automation-convergence.mode=ACTIVE"])
@ActiveProfiles("test")
@Import(AutomationRecoveryIntegrationTest.Config::class)
class AutomationRecoveryIntegrationTest {
    @Autowired private lateinit var runner: UnifiedAutomationRunner
    @Autowired private lateinit var store: ConvergenceStore
    @Autowired private lateinit var entityManager: EntityManager
    @Autowired private lateinit var transactions: PlatformTransactionManager
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var clock: RecoveryClock
    @MockitoBean private lateinit var gateway: HofGateway
    @MockitoSpyBean private lateinit var decisions: AutomationDecisionSource
    @MockitoBean private lateinit var preflight: AutomationDailyPreflight
    @MockitoBean private lateinit var authorization: AccountExecutionAuthorizationReader
    @MockitoBean private lateinit var wakeups: AutomationWakeupPort

    private var accountId = 0L
    private var entryId = 0L
    private val requests = mutableListOf<HofRequest>()
    private var failedPattern = 2
    private var failBattle = false
    private var patternCalls = 0
    private val characters = listOf("recovery-1", "recovery-2", "recovery-3")
    private var selectedCharacters = characters
    private var raidPageTransform: (String) -> String = { it }
    private var incompleteRefreshPost = false

    @BeforeEach
    fun prepareAccount() {
        clock.current = Instant.parse("2026-09-04T00:00:00Z")
        TransactionTemplate(transactions).executeWithoutResult {
            val account = HofAccountEntity(loginId = "recovery-${UUID.randomUUID()}", encryptedPassword = "test", createdAt = clock.now())
            entityManager.persist(account)
            entityManager.flush()
            accountId = account.id
            val entry = AutomationEntryEntity(account = account, type = AutomationType.UNION, priority = 0,
                enabled = true, createdAt = clock.now(), updatedAt = clock.now())
            entityManager.persist(entry)
            entityManager.flush()
            entryId = entry.id
            entityManager.persist(HofCookieEntity(account = account, name = "PHPSESSID", value = "test-session", updatedAt = clock.now()))
            entityManager.persist(HofStatusSnapshotEntity(account = account, playerName = "테스트", funds = 1, timeCurrent = 100, timeMax = 100, work = "", auction = "", observedAt = clock.now()))
            characters.forEach { id ->
                entityManager.persist(CharacterEntity(account = account, hofCharacterId = id, name = id, job = "Knight", updatedAt = clock.now()))
            }
            entityManager.persist(TypedAutomationRuntimeStateEntity(accountId = accountId, account = account,
                lifecycleStatus = TypedAutomationLifecycle.RUNNING, createdAt = clock.now(), updatedAt = clock.now()))
        }
        Mockito.`when`(preflight.ensureReady(accountId)).thenReturn(AutomationDailyPreflight.Result.Ready)
        Mockito.`when`(authorization.isExecutionAllowed(accountId)).thenReturn(true)
        Mockito.doAnswer {
            AutomationCoordination.Runnable(entryId, battle(), emptyList())
        }.`when`(decisions).select(accountId)
        Mockito.doAnswer { invocation ->
            val request = invocation.arguments[1] as HofRequest
            requests += request
            val body = when {
                request.url.contains("?char=") -> {
                    patternCalls++
                    if (patternCalls == failedPattern) throw IOException("HTTP/1.1 header parser received no bytes")
                    "<div>Funds : $ 1 Time : 100/100</div>"
                }
                request.method == HofHttpMethod.GET -> "<a href='index.php?union=0003'>도적소탕</a>"
                failBattle -> throw IOException("battle response lost")
                else -> """<div id="menu2">Funds : $ 1 Time : 100/100</div><h2>Show Detail( 1 turns. )</h2><h1>테스트은(는) 승리했다!</h1>
                    <div>남은 HP : 0/100 생존자 : 0/1 총 데미지 : 0</div>
                    <div>남은 HP : 100/100 생존자 : 1/1 총 데미지 : 100 턴 : 1/100 획득 경험치 : 1 획득 Funds : $ 1</div>"""
            }
            HofHttpResponse(200, request.url, body, emptyMap())
        }.`when`(gateway).execute(Mockito.eq(accountId), anyRequest(), Mockito.anyMap())
    }

    @AfterEach
    fun removeAccount() {
        if (accountId != 0L) jdbc.update("delete from hof_accounts where id = ?", accountId)
    }

    @Test
    fun `패턴 IO 실패는 미전송으로 끝나고 새 판단에서 유니온 전투를 한 번만 제출한다`() {
        runner.runOne(accountId)

        assertEquals(2, patternCalls)
        assertEquals(0, battleRequests().size)
        val failed = runs().single()
        assertEquals("FAILED", failed["status"])
        assertNull(failed["submitted_at"])
        assertTrue(store.findSuppressedBaselines(accountId).isEmpty())
        assertTrue(store.findActiveScopes(accountId).isEmpty())
        val retryAt = jdbc.queryForObject("select next_attempt_at from typed_automation_runtime_states where account_id = ?",
            java.time.OffsetDateTime::class.java, accountId)!!.toInstant()
        assertTrue(retryAt.isAfter(clock.now()))

        failedPattern = -1
        clock.current = retryAt
        runner.runOne(accountId)

        val completed = runs().last()
        assertEquals("SUCCEEDED", completed["status"], completed.toString())
        assertNotEquals(failed["execution_identity"], completed["execution_identity"])
        assertEquals(1, battleRequests().size)
        assertEquals(listOf(characters[0], characters[1], characters[1], characters[2]),
            requests.filter { it.url.contains("?char=") }.map { it.url.substringAfter("?char=") })
        assertTrue(store.findSuppressedBaselines(accountId).isEmpty())
    }

    @Test
    fun `실제 전투 응답 유실은 미전송으로 바꾸거나 전투를 즉시 재제출하지 않는다`() {
        failedPattern = -1
        failBattle = true
        runner.runOne(accountId)
        assertEquals(1, battleRequests().size)
        assertEquals("AMBIGUOUS", runs().single()["status"])
        assertNotNull(runs().single()["submitted_at"])
        runner.runOne(accountId)
        assertEquals(1, battleRequests().size)
    }

    @Test
    fun `첫 패턴 실패 뒤 맵이 사라지면 전투를 보내지 않는다`() {
        failedPattern = 1
        runner.runOne(accountId)
        assertEquals("FAILED", runs().single()["status"])
        assertEquals(0, battleRequests().size)
        clock.current = nextRetryAt()
        Mockito.doAnswer { invocation ->
            val request = invocation.arguments[1] as HofRequest
            requests += request
            HofHttpResponse(200, request.url, "<div id='menu2'>Funds : $ 1 Time : 100/100</div>아무것도 없다", emptyMap())
        }.`when`(gateway).execute(Mockito.eq(accountId), anyRequest(), Mockito.anyMap())
        runner.runOne(accountId)
        assertEquals(0, battleRequests().size)
        assertTrue(store.findSuppressedBaselines(accountId).isEmpty())
    }

    @Test
    fun `과거 신청 보류는 실제 갱신 뒤 해제되고 새 신청은 한 번만 제출된다`() {
        setupRaid()
        runner.runOne(accountId) // 먼저 사이클을 연다.
        val old = holdRegistration()
        requests.clear()
        nextRun() // 기존 사이클도 신청 전에 REFRESH
        assertTrue(requests.any { it.formEntries.any { field -> field.name == "refresh_nonce" } })
        assertFalse(store.findSuppressedBaselines(accountId).values.any { old.selection.baselineFingerprint in it })
        assertEquals(ActionConvergenceResult.HELD, store.get(old.attemptId)?.result)
        assertEquals("RAID_REGISTRATION_FRESH_DECISION_RELEASED", store.get(old.attemptId)?.reasonCode)
        nextRun()
        assertEquals(1, registerRequests().size)
        assertTrue(runs().none { it["execution_identity"] == old.selection.executionIdentity })
        nextRun()
        assertEquals(1, registerRequests().size)
        assertFalse(store.findSuppressedBaselines(accountId).values.any { old.selection.baselineFingerprint in it })
    }

    @Test
    fun `신청 응답 유실은 갱신 후 새 실행으로 신청하며 동일 상태 보류에 갇히지 않는다`() {
        setupRaid(loseFirstRegistration = true)
        runner.runOne(accountId) // REFRESH
        nextRun() // REGISTER 응답 유실
        assertEquals(1, registerRequests().size)
        nextRun() // 미확정 범위의 작업권을 양보한다.
        nextRun() // 실제 REFRESH로 결과 재판단
        assertTrue(store.findSuppressedBaselines(accountId).isEmpty())
        assertTrue(jdbc.queryForList("select result from automation_action_convergences where account_id = ?", accountId)
            .any { it["result"] == "RESULT_UNOBSERVED" }, runs().toString() + jdbc.queryForList("select result, reason_code from automation_action_convergences where account_id = ?", accountId).toString())
        clock.current = jdbc.queryForObject("select min(next_check_at) from automation_work_sessions where account_id = ?",
            java.time.OffsetDateTime::class.java, accountId)?.toInstant() ?: clock.now()
        nextRun() // 기존 작업 재확인 예약에서 새 REGISTER
        assertEquals(2, registerRequests().size)
        val registrationRuns = jdbc.queryForList(
            "select execution_identity from automation_action_attempts where account_id = ? and action_kind = 'RAID_REGISTER'", accountId)
        assertEquals(2, registrationRuns.map { it["execution_identity"] }.distinct().size)
        nextRun()
        assertEquals(2, registerRequests().size)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `신청 응답 유실 뒤 참가 대기와 전역 쿨다운은 과거 신청 성공으로 귀속하지 않는다`(alreadyJoined: Boolean) {
        setupRaid(loseFirstRegistration = true, joinOnLost = alreadyJoined)
        runner.runOne(accountId)
        nextRun()
        val attemptId = jdbc.queryForObject(
            "select id from automation_action_attempts where account_id = ? and action_kind = 'RAID_REGISTER'",
            Long::class.java, accountId)!!
        if (!alreadyJoined) {
            raidPageTransform = { it.replace("현재 상태는 신청 가능", "현재 상태는 신청 대기 (신청 가능까지 6분 58초)") }
        }
        repeat(2) { nextRun() }
        assertEquals(ActionConvergenceResult.SUPERSEDED, store.get(attemptId)?.result)
        assertEquals(1, registerRequests().size)
        assertNotNull(jdbc.queryForObject("select next_check_at from automation_work_sessions where account_id = ?",
            java.time.OffsetDateTime::class.java, accountId))
    }

    @Test
    fun `갱신 POST만 불완전하면 완전한 GET이 있어도 신청 보류를 해제하지 않는다`() {
        setupRaid()
        runner.runOne(accountId)
        val old = holdRegistration()
        incompleteRefreshPost = true
        repeat(3) { nextRun() }
        assertEquals(0, registerRequests().size)
        assertTrue(old.selection.baselineFingerprint in store.findSuppressedBaselines(accountId)[old.selection.scope].orEmpty())
    }

    @Test
    fun `신청 결과 확인 중 로그아웃하면 갱신 POST도 보내지 않는다`() {
        setupRaid(loseFirstRegistration = true)
        runner.runOne(accountId)
        nextRun()
        nextRun() // 기존 작업권 양보
        val postsBeforeLogout = requests.count { it.method == HofHttpMethod.POST }
        Mockito.`when`(authorization.isExecutionAllowed(accountId)).thenReturn(false)
        nextRun()
        assertEquals(postsBeforeLogout, requests.count { it.method == HofHttpMethod.POST })
        assertEquals(1, registerRequests().size)
        assertTrue(store.findActiveScopes(accountId).isNotEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["cooldown", "incomplete", "different-target"])
    fun `쿨다운 불완전 화면 다른 대상은 과거 신청 보류를 해제하지 않는다`(state: String) {
        setupRaid()
        val old = holdRegistration()
        raidPageTransform = { page -> when (state) {
            "cooldown" -> page.replace("현재 상태는 신청 가능", "현재 상태는 신청 대기 (신청 가능까지 6분 58초)")
            "incomplete" -> page.substringBefore("<div id=\"foot\"")
            else -> page.replace("RaidGoblin", "RaidOther")
        } }
        runner.runOne(accountId)
        assertEquals(0, registerRequests().size)
        assertTrue(old.selection.baselineFingerprint in store.findSuppressedBaselines(accountId)[old.selection.scope].orEmpty())
    }

    @Test
    fun `패턴 실패 뒤 새 판단의 파티를 사용하고 기존 작업권은 대기 상태로 양보한다`() {
        runner.runOne(accountId)
        assertEquals("WAITING_COOLDOWN", jdbc.queryForObject(
            "select status from automation_work_sessions where account_id = ?", String::class.java, accountId))
        selectedCharacters = listOf(characters.last())
        failedPattern = -1
        clock.current = nextRetryAt()
        runner.runOne(accountId)
        assertEquals("SUCCEEDED", runs().last()["status"])
        assertEquals(characters.last(), requests.last { it.url.contains("?char=") }.url.substringAfter("?char="))
        val submittedCharacters = battleRequests().single().formEntries.map { it.name.removePrefix("char_") }.filter { it in characters }
        assertEquals(listOf(characters.last()), submittedCharacters)
    }

    private fun setupRaid(loseFirstRegistration: Boolean = false, joinOnLost: Boolean = false) {
        Mockito.doCallRealMethod().`when`(decisions).select(accountId)
        TransactionTemplate(transactions).executeWithoutResult {
            val account = entityManager.find(HofAccountEntity::class.java, accountId)
            val entry = entityManager.find(AutomationEntryEntity::class.java, entryId)
            entry.type = AutomationType.RAID
            entry.singletonTypeMarker = AutomationType.RAID
            val preset = PartyPresetEntity(account = account, name = "복구 파티", createdAt = clock.now(), updatedAt = clock.now(), isPrimary = true)
            entityManager.persist(preset)
            val character = entityManager.createQuery("select c from CharacterEntity c where c.account.id = :id", CharacterEntity::class.java)
                .setParameter("id", accountId).resultList.first()
            val pattern = CharacterPatternSlotEntity(character = character, slotCode = "1", label = "기본", canLoad = true)
            entityManager.persist(pattern)
            entityManager.persist(PartyPresetMemberEntity(preset, 0, character, pattern))
            entityManager.persist(RaidAutomationTargetEntity(entry = entry, raidId = "RaidGoblin", displayName = "고블린 전투 마차",
                presetMode = PresetSelectionMode.PRIMARY, executionOrder = 0))
        }
        var joined = false
        var lost = false
        Mockito.doAnswer { invocation ->
            val request = invocation.arguments[1] as HofRequest
            requests += request
            if (request.formEntries.any { it.name == "register_goblin" }) {
                if (loseFirstRegistration && !lost) {
                    lost = true
                    joined = joinOnLost
                    throw IOException("registration response lost")
                }
                joined = true
            }
            val html = if (request.url.contains("raidpub") || request.method == HofHttpMethod.POST) {
                raidPageTransform(raidHtml(joined)).let { page ->
                    if (incompleteRefreshPost && request.formEntries.any { it.name == "refresh_nonce" }) {
                        page.substringBefore("<div id=\"foot\"")
                    } else page
                }
            } else "<div id='menu2'>Funds : $ 1 Time : 100/100</div>아무것도 없다"
            HofHttpResponse(200, request.url.takeIf { it.contains("raidpub") } ?: "https://hof.zerosic.com/index.php?menu=raidpub", html, emptyMap())
        }.`when`(gateway).execute(Mockito.eq(accountId), anyRequest(), Mockito.anyMap())
    }

    private fun raidHtml(joined: Boolean): String = requireNotNull(javaClass.getResource("/fixtures/town/raid/raidpub.html")).readText()
        .replace("Funds : $ 1,000", "Funds : $ 1,000 Time : 100/100")
        .replace("현재 상태는 신청 대기 (신청 가능까지 6분 58초)", "현재 상태는 신청 가능")
        .replace("현재 상태 : 모집 중", if (joined) "현재 상태 : 418초 후 출발" else "현재 상태 : 파티 모집 중 (신청 안됨)")
        .replace("[《테스트 길드》현재사용자]", if (joined) "[《테스트 길드》현재사용자]" else "[다른 신청자]")
        .replace("<input type=\"submit\" name=\"reward_nonce\"", "<input type=\"submit\" name=\"refresh_nonce\" value=\"상태 갱신\"><input type=\"submit\" name=\"reward_nonce\"")

    private fun holdRegistration(): ActionConvergenceRecord {
        val action = RaidTownAutomationAction(accountId, RaidAction.REGISTER, "RaidGoblin", targetRaidId = "RaidGoblin")
        val preview = StoredActionConvergenceSelectionFactory().preview(entryId, action)
        val old = store.createOrGet(accountId, SelectedAutomationAction(entryId, UUID.randomUUID().toString(),
            AutomationActionKind.RAID_REGISTER, preview.scope, "automation-action-convergence-v1", requireNotNull(preview.baselineFingerprint)), clock.now())
        old.result = ActionConvergenceResult.HELD
        old.reasonCode = "PENDING_BUDGET_EXHAUSTED"
        old.finishedAt = clock.now()
        store.save(old)
        return old
    }

    private fun registerRequests() = requests.filter { it.formEntries.any { field -> field.name == "register_goblin" } }
    private fun nextRetryAt() = jdbc.queryForObject("select next_attempt_at from typed_automation_runtime_states where account_id = ?",
        java.time.OffsetDateTime::class.java, accountId)?.toInstant() ?: clock.now().plusSeconds(1)
    private fun nextRun() {
        val probeAt = jdbc.queryForObject("select min(next_probe_at) from automation_action_convergences where account_id = ? and active_marker = 1",
            java.time.OffsetDateTime::class.java, accountId)?.toInstant() ?: clock.now()
        clock.current = maxOf(nextRetryAt(), probeAt)
        runner.runOne(accountId)
    }

    private fun battle() = BattleMapAutomationAction(accountId, LocalDate.of(2026, 9, 4), "union", "0003",
        PresetSelectionMode.EXPLICIT, 1L, 1, UUID.randomUUID().toString(),
        source = BattleAutomationActionSource.UNION_AUTOMATION,
        resolvedParty = ResolvedAutomationParty(selectedCharacters, selectedCharacters.map { BattlePatternLoadRequest(it, 1) }),
        mapName = "도적소탕")

    private fun runs() = jdbc.queryForList(
        "select status, submitted_at, execution_identity, last_error from typed_automation_action_runs where account_id = ? order by id", accountId)
    private fun battleRequests() = requests.filter { it.method == HofHttpMethod.POST && !it.url.contains("?char=") }
    private fun anyRequest(): HofRequest = Mockito.any<HofRequest>()
        ?: HofRequest(HofHttpMethod.GET, "https://example.test")

    class RecoveryClock(var current: Instant = Instant.parse("2026-09-04T00:00:00Z")) : TimeProvider {
        override fun now(): Instant = current
    }

    @TestConfiguration
    class Config {
        @Bean @Primary fun recoveryClock() = RecoveryClock()
    }
}
