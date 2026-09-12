package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.entity.HofCookieEntity
import app.spammy.hof.auth.service.AccountExecutionAuthorizationReader
import app.spammy.hof.automation.convergence.*
import app.spammy.hof.automation.raid.*
import app.spammy.hof.automation.history.AutomationDecisionJournal
import app.spammy.hof.party.entity.PartyPresetEntity
import app.spammy.hof.party.entity.PartyPresetMemberEntity
import app.spammy.hof.character.entity.CharacterPatternSlotEntity
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import app.spammy.hof.automation.entity.*
import app.spammy.hof.automation.port.AutomationWakeupPort
import app.spammy.hof.automation.outbox.*
import app.spammy.hof.battle.dto.BattlePatternLoadRequest
import app.spammy.hof.character.entity.CharacterEntity
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
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

import app.spammy.hof.automation.service.AutomationRecoveryIntegrationTest.RecoveryClock
import app.spammy.hof.automation.service.AutomationRecoveryIntegrationTest.ConsumerReplayTransport

/** 기존 복구 시나리오와 실제 모드 검사가 공유하는 계정·HOF·후속 wake fixture다. */
abstract class AutomationRecoveryFixture {
    protected open val mode = AutomationConvergenceMode.ACTIVE
    @MockitoBean protected lateinit var captchaSolver: app.spammy.hof.captcha.service.CaptchaAutoSolveCoordinator
    @Autowired protected lateinit var convergenceProperties: AutomationConvergenceProperties
    @Autowired protected lateinit var store: ConvergenceStore
    @MockitoSpyBean protected lateinit var journal: AutomationDecisionJournal
    @Autowired protected lateinit var entityManager: EntityManager
    @Autowired protected lateinit var transactions: PlatformTransactionManager
    @Autowired protected lateinit var jdbc: JdbcTemplate
    @Autowired protected lateinit var clock: RecoveryClock
    @Autowired protected lateinit var publisher: AutomationOutboxPublisher
    @Autowired protected lateinit var outbox: AutomationOutboxQueryRepository
    @Autowired protected lateinit var transport: ConsumerReplayTransport
    @Autowired protected lateinit var application: UnifiedAutomationService
    @Autowired protected lateinit var lifecycle: TypedAutomationLifecycleBridge
    @MockitoBean protected lateinit var gateway: HofGateway
    @MockitoSpyBean protected lateinit var decisions: AutomationDecisionSource
    @MockitoBean protected lateinit var preflight: AutomationDailyPreflight
    @MockitoSpyBean protected lateinit var authorization: AccountExecutionAuthorizationReader
    @Autowired protected lateinit var auth: app.spammy.hof.auth.service.AuthService
    @Autowired protected lateinit var wakeups: AutomationWakeupPort

    protected var accountId = 0L
    protected var entryId = 0L
    protected val requests = mutableListOf<HofRequest>()
    protected var failedPattern = 2
    protected var failBattle = false
    protected var captchaBattleResponse = false
    protected var patternCalls = 0
    protected val characters = listOf("recovery-1", "recovery-2", "recovery-3")
    protected var selectedCharacters = characters
    protected var rotatePatternCookies = false
    protected val requestCookies = mutableListOf<Map<String, String>>()
    protected var mapPage = "<a href='index.php?union=0003'>도적소탕</a>"

    @BeforeEach
    fun prepareAccount() {
        assertEquals(mode, convergenceProperties.mode)
        transport.delivered.clear()
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
            requestCookies += invocation.getArgument<Map<String, String>>(2)
            val body = when {
                request.url.contains("?char=") -> {
                    patternCalls++
                    if (patternCalls == failedPattern) throw IOException("HTTP/1.1 header parser received no bytes")
                    "<div>Funds : $ 1 Time : 100/100</div>" + app.spammy.hof.character.service.currentPatternForm() +
                        app.spammy.hof.character.service.savedPatternLoadForm(request.formFields.getValue("patternno").toInt())
                }
                request.method == HofHttpMethod.GET -> mapPage
                captchaBattleResponse -> "<div id='menu2'>Funds : $ 1 Time : 100/100</div><div>자경단에서 통행증을 발급받아주세요.</div>"
                failBattle -> throw IOException("battle response lost")
                else -> """<div id="menu2">Funds : $ 1 Time : 100/100</div><h2>Show Detail( 1 turns. )</h2><h1>테스트은(는) 승리했다!</h1>
                    <div>남은 HP : 0/100 생존자 : 0/1 총 데미지 : 0</div>
                    <div>남은 HP : 100/100 생존자 : 1/1 총 데미지 : 100 턴 : 1/100 획득 경험치 : 1 획득 Funds : $ 1</div>"""
            }
            val updatedCookies = if (rotatePatternCookies && request.url.contains("?char=")) {
                mapOf("PHPSESSID" to "rotated-$patternCalls")
            } else emptyMap()
            HofHttpResponse(200, request.url, body, updatedCookies)
        }.`when`(gateway).execute(Mockito.eq(accountId), anyRequest(), Mockito.anyMap())
    }

    @AfterEach
    fun removeAccount() {
        transport.delivered.forEach { jdbc.update("delete from automation_consumed_events where event_id = ?", it) }
        if (accountId != 0L) jdbc.update("delete from hof_accounts where id = ?", accountId)
    }

    protected fun completeFishingMapPage(extra: String = "") = """<div id='contents'>
        <div id='mapgroup1'><a href='index.php?common=0001'>일반 맵</a></div>$extra</div>
        <div id='foot'><h5>Copy Right sanitized fixture</h5><h6>H.O.F Korean Ver sanitized fixture</h6>
        <img src='image/zerohof.gif'></div>"""

    protected fun runningWorkCount() = jdbc.queryForObject(
        "select count(*) from automation_work_sessions where account_id = ? and status = 'RUNNING'", Int::class.java, accountId)

    protected fun fishingPosts() = requests.mapNotNull { request ->
        val action = listOf("FStart", "FCatch").singleOrNull { it in request.formFields } ?: return@mapNotNull null
        assertEquals(HofHttpMethod.POST, request.method)
        assertEquals("https://hof.zerosic.com/index.php?menu=fishing", request.url)
        assertEquals(if (action == "FStart") "낚시를 시작한다" else "낚는다", request.formFields[action])
        action
    }

    protected class FishingFixtureState(var phase: String = "reset", var battle: Boolean = false, var mapFailure: String? = null) {
        var beforeCatch: (() -> Unit)? = null
        var battleOutcome = BattleAutomationRoundOutcome.VICTORY
        var revealOnStart = false
        var preloadFailure: String? = null
        var patternPageExtra = ""
        var currentTime = 100
        val requestCookies = mutableListOf<Map<String, String>>()
    }

    protected fun setupFishing(
        obstruction: Boolean = false,
        startObstruction: Boolean = false,
        lostFishingResponse: String? = null,
        homeResponse: ((HofRequest) -> String)? = null,
        initialBattle: Boolean = false,
        hiddenBattle: Boolean = false,
        castsAfterBattle: Int = 0,
        mapFailure: String? = null,
        rotatingCookies: Boolean = false,
        unconfirmedResponse: String? = null,
    ): FishingFixtureState {
        Mockito.doCallRealMethod().`when`(decisions).select(accountId)
        TransactionTemplate(transactions).executeWithoutResult {
            val entry = entityManager.find(AutomationEntryEntity::class.java, entryId)
            entry.type = AutomationType.FISHING
            entry.singletonTypeMarker = AutomationType.FISHING
            val preset = PartyPresetEntity(account = entry.account, name = "낚시 파티", createdAt = clock.now(), updatedAt = clock.now(), isPrimary = true)
            entityManager.persist(preset)
            val character = entityManager.createQuery("select c from CharacterEntity c where c.account.id = :id", CharacterEntity::class.java)
                .setParameter("id", accountId).resultList.first()
            val pattern = CharacterPatternSlotEntity(character = character, slotCode = "1", label = "기본", canLoad = true)
            entityManager.persist(pattern)
            entityManager.persist(PartyPresetMemberEntity(preset, 0, character, pattern))
        }
        fun fixture(name: String) = requireNotNull(javaClass.getResource("/fixtures/town/fishing/$name.html")).readText()
        val state = FishingFixtureState(battle = initialBattle, mapFailure = mapFailure)
        state.revealOnStart = startObstruction
        Mockito.doAnswer { invocation ->
            val request = invocation.arguments[1] as HofRequest
            @Suppress("UNCHECKED_CAST")
            val requestCookies = invocation.arguments[2] as Map<String, String>
            val header = """<table id='menu2'><tr><td>《테스트》테스트</td>
                <td>Funds : $ 1<br>Work : Nothing</td><td>Time : ${state.currentTime}/100<br>Auction : Nothing</td></tr></table>"""
            state.requestCookies += requestCookies.toMap()
            if ("FCatch" in request.formFields) state.beforeCatch?.invoke()
            requests += request
            val body = when {
                unconfirmedResponse != null && unconfirmedResponse in request.formFields -> fixture(state.phase)
                request.url.contains("?char=") -> {
                    val failure = state.preloadFailure
                    state.preloadFailure = null
                    header + when (failure) {
                        "REJECTED" -> app.spammy.hof.character.service.currentPatternForm() + "<div class='error'>패턴 로드가 거부되었습니다.</div>"
                        "INCOMPLETE" -> "<div>현재 설정을 읽지 못했습니다.</div>"
                        "CAPTCHA" -> "<div>자경단에서 통행증을 발급받아주세요.</div>"
                        else -> app.spammy.hof.character.service.currentPatternForm() + app.spammy.hof.character.service.savedPatternLoadForm(1)
                    } + state.patternPageExtra
                }
                homeResponse != null && request.url.contains("menu=quest2") -> homeResponse(request)
                "FStart" in request.formFields -> {
                    state.battle = state.battle || state.revealOnStart
                    state.revealOnStart = false
                    state.phase = if (state.battle) "monster" else "waiting"
                    if (lostFishingResponse == "FStart") throw IOException("Fishing START response lost")
                    fixture(state.phase)
                }
                "FCatch" in request.formFields -> {
                    state.battle = obstruction
                    state.phase = if (obstruction) "monster" else "exhausted"
                    if (lostFishingResponse == "FCatch") throw IOException("Fishing CATCH response lost")
                    if (obstruction) fixture("caught").substringBefore("<form") + fixture("monster") + "</main>" else fixture("caught")
                }
                request.method == HofHttpMethod.POST -> {
                    state.battle = false
                    state.phase = if (castsAfterBattle > 0) "reset" else "exhausted"
                    val title = when (state.battleOutcome) {
                        BattleAutomationRoundOutcome.VICTORY -> "《테스트》테스트은(는) 승리했다!"
                        BattleAutomationRoundOutcome.DEFEAT -> "Fishing- 악어은(는) 승리했다!"
                        BattleAutomationRoundOutcome.DRAW -> "무승부!"
                        else -> error("단말 전투 결과만 사용하는 fixture다.")
                    }
                    val enemyHp = if (state.battleOutcome == BattleAutomationRoundOutcome.VICTORY) 0 else 100
                    val allyHp = if (state.battleOutcome == BattleAutomationRoundOutcome.DEFEAT) 0 else 100
                    """$header<h2>Show Detail( 1 turns. )</h2><h1>$title</h1>
                    <div>남은 HP : $enemyHp/100 생존자 : ${if (enemyHp == 0) 0 else 1}/1 총 데미지 : 0</div>
                    <div>남은 HP : $allyHp/100 생존자 : ${if (allyHp == 0) 0 else 1}/1 총 데미지 : 100 턴 : 1/100 획득 경험치 : 1 획득 Funds : $ 1</div>"""
                }
                request.url.contains("menu=fishing") -> when {
                    state.phase == "exhausted" -> fixture("reset").replace("18회", "0회")
                    hiddenBattle && state.battle -> fixture("reset")
                    else -> fixture(state.phase)
                }
                state.mapFailure == "IO" -> throw IOException("map unavailable")
                state.mapFailure == "INCOMPLETE" -> "<div>목록 일부만 도착했습니다.</div>"
                else -> completeFishingMapPage(if (state.battle) "<a href='index.php?common=fishing_12'>Fishing- 악어</a>" else "")
            }
            val responseCookies = if (rotatingCookies) mapOf("PHPSESSID" to when {
                "FStart" in request.formFields -> "start-cookie"
                "FCatch" in request.formFields -> "catch-cookie"
                request.url.endsWith("?hunt") -> "hunt-cookie"
                else -> "fishing-cookie"
            }) else emptyMap()
            HofHttpResponse(200, request.url, header + body, responseCookies)
        }.`when`(gateway).execute(Mockito.eq(accountId), anyRequest(), Mockito.anyMap())
        return state
    }

    protected fun consumeNextWake() {
        val next = jdbc.queryForObject(
            "select min(available_at) from automation_outbox where account_id = ? and topic = ? and published_at is null",
            java.time.OffsetDateTime::class.java, accountId, AutomationOutboxService.WAKEUP_TOPIC)?.toInstant()
        assertNotNull(next, "행동 수렴 뒤 실제 소비할 후속 wakeup이 있어야 한다.")
        clock.current = maxOf(clock.now(), next)
        val before = transport.delivered.size
        publisher.publishBatch()
        assertTrue(transport.delivered.size > before)
        assertTrue(transport.delivered.all { outbox.consumed(it) })
        assertEquals(0, jdbc.queryForObject("select count(*) from account_automation_leases where account_id = ?", Int::class.java, accountId))
    }

    protected fun battle() = BattleMapAutomationAction(accountId, LocalDate.of(2026, 9, 4), "union", "0003",
        PresetSelectionMode.EXPLICIT, 1L, 1, UUID.randomUUID().toString(),
        source = BattleAutomationActionSource.UNION_AUTOMATION,
        resolvedParty = ResolvedAutomationParty(selectedCharacters, selectedCharacters.map { BattlePatternLoadRequest(it, 1) }),
        mapName = "도적소탕")

    protected fun runs() = jdbc.queryForList(
        "select status, submitted_at, execution_identity, last_error from typed_automation_action_runs where account_id = ? order by id", accountId)
    protected fun battleRequests() = requests.filter { it.method == HofHttpMethod.POST && !it.url.contains("?char=") }
    protected fun anyRequest(): HofRequest = Mockito.any<HofRequest>()
        ?: HofRequest(HofHttpMethod.GET, "https://example.test")

}
