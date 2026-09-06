package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.entity.HofCookieEntity
import app.spammy.hof.auth.service.AccountExecutionAuthorizationReader
import app.spammy.hof.automation.convergence.*
import app.spammy.hof.automation.raid.*
import app.spammy.hof.automation.history.AutomationHistoryEventKind
import app.spammy.hof.automation.history.AutomationDecisionJournal
import app.spammy.hof.automation.history.AutomationHistoryQuery
import app.spammy.hof.town.home.model.HomeMode
import app.spammy.hof.town.home.parser.HomePageParser
import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.quest.parser.QuestPageParser
import tools.jackson.module.kotlin.jacksonObjectMapper
import app.spammy.hof.party.entity.PartyPresetEntity
import app.spammy.hof.party.entity.PartyPresetMemberEntity
import app.spammy.hof.character.entity.CharacterPatternSlotEntity
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import app.spammy.hof.town.raid.model.RaidAction
import app.spammy.hof.automation.entity.*
import app.spammy.hof.automation.port.AutomationWakeupPort
import app.spammy.hof.automation.kafka.AutomationWakeupConsumer
import app.spammy.hof.automation.kafka.KafkaAutomationWakeupAdapter
import app.spammy.hof.automation.lease.AccountAutomationLeaseService
import app.spammy.hof.automation.outbox.*
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
import org.junit.jupiter.params.provider.EnumSource
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
import org.springframework.kafka.support.Acknowledgment
import tools.jackson.databind.ObjectMapper

@SpringBootTest(properties = ["hof.automation-convergence.mode=ACTIVE"])
@ActiveProfiles("test")
@Import(AutomationRecoveryIntegrationTest.Config::class)
class AutomationRecoveryIntegrationTest {
    @Autowired private lateinit var runner: UnifiedAutomationRunner
    @Autowired private lateinit var store: ConvergenceStore
    @Autowired private lateinit var journal: AutomationDecisionJournal
    @Autowired private lateinit var entityManager: EntityManager
    @Autowired private lateinit var transactions: PlatformTransactionManager
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var clock: RecoveryClock
    @Autowired private lateinit var publisher: AutomationOutboxPublisher
    @Autowired private lateinit var outbox: AutomationOutboxQueryRepository
    @Autowired private lateinit var transport: ConsumerReplayTransport
    @Autowired private lateinit var battleMaps: app.spammy.hof.battle.service.BattleMapService
    @Autowired private lateinit var application: UnifiedAutomationService
    @Autowired private lateinit var recoveryQuery: app.spammy.hof.automation.recovery.AutomationRecoveryDueAccountQuery
    @Autowired private lateinit var lifecycle: TypedAutomationLifecycleBridge
    @MockitoBean private lateinit var gateway: HofGateway
    @MockitoSpyBean private lateinit var decisions: AutomationDecisionSource
    @MockitoSpyBean private lateinit var raidModule: RaidCycleModule
    @MockitoBean private lateinit var preflight: AutomationDailyPreflight
    @MockitoBean private lateinit var authorization: AccountExecutionAuthorizationReader
    @Autowired private lateinit var wakeups: AutomationWakeupPort

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
        transport.delivered.forEach { jdbc.update("delete from automation_consumed_events where event_id = ?", it) }
        if (accountId != 0L) jdbc.update("delete from hof_accounts where id = ?", accountId)
    }

    @Test
    fun `패턴 IO 실패는 미전송으로 끝나고 새 판단에서 유니온 전투를 한 번만 제출한다`() {
        wakeups.wake(accountId, "CONTINUITY_BASELINE")
        publisher.publishBatch()

        assertEquals(2, patternCalls)
        assertEquals(0, battleRequests().size)
        val failed = runs().single()
        assertEquals("FAILED", failed["status"])
        assertNull(failed["submitted_at"])
        assertTrue(store.findSuppressedBaselines(accountId).isEmpty())
        assertTrue(store.findActiveScopes(accountId).isEmpty())
        val retryAt = jdbc.queryForObject("select next_attempt_at from typed_automation_runtime_states where account_id = ?",
            java.time.OffsetDateTime::class.java, accountId)!!.toInstant()
        assertEquals(clock.now().plusSeconds(10), retryAt)
        assertScheduledWake("HOF_503_COOLDOWN", retryAt)
        assertEquals("HOF_CONNECTION", jdbc.queryForObject(
            "select wait_reason from typed_automation_runtime_states where account_id = ?", String::class.java, accountId))

        failedPattern = -1
        clock.current = retryAt
        publisher.publishBatch()

        val completed = runs().last()
        assertEquals("SUCCEEDED", completed["status"], completed.toString())
        assertNotEquals(failed["execution_identity"], completed["execution_identity"])
        assertEquals(1, battleRequests().size)
        assertEquals(listOf(characters[0], characters[1], characters[1], characters[2]),
            requests.filter { it.url.contains("?char=") }.map { it.url.substringAfter("?char=") })
        assertTrue(store.findSuppressedBaselines(accountId).isEmpty())
        assertEquals(2, transport.delivered.size)
        assertTrue(transport.delivered.all { outbox.consumed(it) })
        assertEquals(0, jdbc.queryForObject("select count(*) from account_automation_leases where account_id = ?", Int::class.java, accountId))
        assertNull(jdbc.queryForObject("select lease_token from typed_automation_runtime_states where account_id = ?", String::class.java, accountId))
    }

    @Test
    fun `실제 전투 응답 유실은 미전송으로 바꾸거나 전투를 즉시 재제출하지 않는다`() {
        failedPattern = -1
        failBattle = true
        wakeups.wake(accountId, "LOST_BATTLE_BASELINE")
        publisher.publishBatch()
        assertEquals(1, battleRequests().size)
        assertEquals("AMBIGUOUS", runs().single()["status"])
        assertNotNull(runs().single()["submitted_at"])
        consumeNextWake()
        assertEquals(1, battleRequests().size)
    }

    @Test
    fun `정상 낚시는 START CATCH 뒤 작업권을 놓고 후속 유휴 판단을 계속 소비한다`() {
        setupFishing()
        wakeups.wake(accountId, "FISHING_CONTINUITY_BASELINE")
        publisher.publishBatch()

        assertEquals(listOf("FStart", "FCatch"), fishingPosts())
        assertEquals(listOf("SUCCEEDED", "SUCCEEDED"), runs().map { it["status"] }, runs().toString())
        assertEquals(2, runs().map { it["execution_identity"] }.distinct().size)
        assertEquals(0, runningWorkCount())
        consumeNextWake()
        val firstIdle = journal.page(accountId, AutomationHistoryQuery()).cycles
        assertEquals(2, firstIdle.size)
        val idleAt = clock.now()
        assertScheduledWake("TYPED_NEXT_ROUND", idleAt.plusSeconds(3))
        consumeNextWake()
        assertEquals(idleAt.plusSeconds(3), clock.now())
        assertEquals(3, journal.page(accountId, AutomationHistoryQuery()).cycles.size)
        assertEquals(listOf("FStart", "FCatch"), fishingPosts())
        assertEquals(0, runningWorkCount())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `CATCH 방해 전투는 새 판단에서 최신 맵을 확인하고 별도 사이클로 실행한다`(mapPreviouslyObserved: Boolean) {
        setupFishing(obstruction = true)
        if (mapPreviouslyObserved) {
            assertTrue(battleMaps.findMaps(accountId, "battle_map").any { it.mapCode == "fishing_12" })
            requests.clear()
        }
        wakeups.wake(accountId, "FISHING_OBSTRUCTION_BASELINE")
        publisher.publishBatch()

        assertEquals(listOf("FStart", "FCatch"), fishingPosts())
        assertEquals(listOf("SUCCEEDED", "SUCCEEDED"), runs().map { it["status"] }, runs().toString())
        assertEquals(2, runs().map { it["execution_identity"] }.distinct().size)
        assertEquals(0, runningWorkCount())
        assertEquals(0, requests.count { it.method == HofHttpMethod.POST && it.url.contains("common=fishing_12") })
        val fishingCycle = journal.page(accountId, AutomationHistoryQuery()).cycles.single()
        val requestBoundary = requests.size
        assertScheduledWake("TYPED_FISHING_CYCLE_COMPLETED", clock.now())

        consumeNextWake()

        val cycles = journal.page(accountId, AutomationHistoryQuery()).cycles
        assertEquals(2, cycles.size)
        assertEquals(1, cycles.count { it.id == fishingCycle.id })
        assertEquals(listOf("SUCCEEDED", "SUCCEEDED", "SUCCEEDED"), runs().map { it["status"] }, runs().toString())
        assertEquals(3, runs().map { it["execution_identity"] }.distinct().size)
        val battleRequests = requests.drop(requestBoundary)
        val battlePost = battleRequests.indexOfFirst { it.method == HofHttpMethod.POST && it.url.contains("common=fishing_12") }
        assertTrue(battlePost >= 0, "새 판단에서 전투를 한 번 제출해야 한다.")
        assertTrue(battleRequests.take(battlePost).any { it.method == HofHttpMethod.GET && it.url == "https://hof.zerosic.com/index.php?hunt" },
            "전투 제출 전에 최신 맵 관측을 거쳐야 한다.")
        assertEquals(1, requests.count { it.method == HofHttpMethod.POST && it.url.contains("common=fishing_12") })
        assertEquals(0, runningWorkCount())
        assertScheduledWake("TYPED_ACTION_COMPLETED", clock.now())

        consumeNextWake()
        assertEquals(3, journal.page(accountId, AutomationHistoryQuery()).cycles.size)
        assertEquals(listOf("FStart", "FCatch"), fishingPosts())
        assertEquals(1, requests.count { it.method == HofHttpMethod.POST && it.url.contains("common=fishing_12") })
        assertEquals(0, runningWorkCount())
    }

    @Test
    fun `START에서 발견한 이전 전투는 성공 귀속 없이 새 판단의 별도 전투로 실행한다`() {
        setupFishing(startObstruction = true)
        wakeups.wake(accountId, "FISHING_START_OBSTRUCTION")
        publisher.publishBatch()

        assertEquals(listOf("FStart"), fishingPosts())
        assertEquals(listOf("FAILED"), runs().map { it["status"] }, runs().toString())
        assertEquals(0, runningWorkCount())
        assertEquals(listOf("COMPLETED"), jdbc.queryForList(
            "select status from automation_work_sessions where account_id = ?", String::class.java, accountId))
        assertEquals(1, journal.page(accountId, AutomationHistoryQuery()).cycles.size)
        val selection = jdbc.queryForList(
            "select result from automation_action_convergences where account_id = ?", String::class.java, accountId)
        assertEquals(listOf("SUPERSEDED"), selection)
        assertEquals(0, requests.count { it.method == HofHttpMethod.POST && it.url.contains("common=fishing_12") })
        assertScheduledWake("ACTION_SUPERSEDED_BY_FRESH_STATE", clock.now())
        val boundary = requests.size

        consumeNextWake()

        assertEquals(listOf("FAILED", "SUCCEEDED"), runs().map { it["status"] }, runs().toString())
        assertEquals(2, runs().map { it["execution_identity"] }.distinct().size)
        assertEquals(2, journal.page(accountId, AutomationHistoryQuery()).cycles.size)
        val following = requests.drop(boundary)
        val postIndex = following.indexOfFirst { it.method == HofHttpMethod.POST && it.url.contains("common=fishing_12") }
        assertTrue(postIndex >= 0)
        assertTrue(following.take(postIndex).any { it.method == HofHttpMethod.GET && it.url == "https://hof.zerosic.com/index.php?hunt" })
        assertEquals(0, runningWorkCount())
        assertEquals(listOf("COMPLETED", "COMPLETED"), jdbc.queryForList(
            "select status from automation_work_sessions where account_id = ? order by id", String::class.java, accountId))
        assertScheduledWake("TYPED_ACTION_COMPLETED", clock.now())
        consumeNextWake()
        assertEquals(3, journal.page(accountId, AutomationHistoryQuery()).cycles.size)
        assertEquals(listOf("FStart"), fishingPosts())
        assertEquals(1, requests.count { it.method == HofHttpMethod.POST && it.url.contains("common=fishing_12") })
    }

    @ParameterizedTest
    @ValueSource(strings = ["FStart", "FCatch"])
    fun `유실된 낚시 응답의 최신 전투 관측은 이전 행동과 별도 작업으로 수렴한다`(lostAction: String) {
        setupFishing(obstruction = true, startObstruction = lostAction == "FStart", lostFishingResponse = lostAction)
        wakeups.wake(accountId, "FISHING_LOST_RESPONSE")
        publisher.publishBatch()
        val expectedPosts = if (lostAction == "FStart") listOf("FStart") else listOf("FStart", "FCatch")
        assertEquals(expectedPosts, fishingPosts())
        assertEquals("RECONCILING", runs().last()["status"], runs().toString())
        assertNotNull(runs().last()["submitted_at"])
        assertEquals(0, requests.count { it.method == HofHttpMethod.POST && it.url.contains("common=fishing_12") })

        nextRun()
        consumeNextWake()

        assertEquals(expectedPosts, fishingPosts())
        val results = jdbc.queryForList(
            "select result from automation_action_convergences where account_id = ? order by id", String::class.java, accountId)
        assertEquals(if (lostAction == "FStart") listOf("SUPERSEDED") else listOf("APPLIED", "SUPERSEDED"), results)
        val unresolvedRuns = if (lostAction == "FStart") listOf("AMBIGUOUS") else listOf("SUCCEEDED", "AMBIGUOUS")
        assertEquals(unresolvedRuns, runs().map { it["status"] })
        assertEquals(listOf("COMPLETED"), jdbc.queryForList(
            "select status from automation_work_sessions where account_id = ?", String::class.java, accountId))
        assertEquals(0, runningWorkCount())

        assertEquals(0, requests.count { it.method == HofHttpMethod.POST && it.url.contains("common=fishing_12") })
        assertScheduledWake("TYPED_CONVERGENCE_CONTINUE", clock.now())

        consumeNextWake()
        assertEquals(1, requests.count { it.method == HofHttpMethod.POST && it.url.contains("common=fishing_12") })
        assertEquals(expectedPosts, fishingPosts())
        assertEquals(listOf("COMPLETED", "COMPLETED"), jdbc.queryForList(
            "select status from automation_work_sessions where account_id = ? order by id", String::class.java, accountId))
        assertEquals(unresolvedRuns + "SUCCEEDED", runs().map { it["status"] })
        assertScheduledWake("TYPED_ACTION_COMPLETED", clock.now())
        val before = journal.page(accountId, AutomationHistoryQuery()).cycles.size
        val nextIdleAt = clock.now().plusSeconds(3)
        assertScheduledWake("TYPED_NEXT_ROUND", nextIdleAt)
        clock.current = nextIdleAt
        consumeNextWake()
        assertEquals(before + 1, journal.page(accountId, AutomationHistoryQuery()).cycles.size)
        assertEquals(expectedPosts, fishingPosts())
        assertEquals(1, requests.count { it.method == HofHttpMethod.POST && it.url.contains("common=fishing_12") })
    }

    @Test
    fun `CATCH 뒤 새로 실행 가능한 상위 항목이 방해 전투보다 먼저 선택된다`() {
        val url = "https://hof.zerosic.com/index.php?menu=quest2"
        var ready = false
        var accepted = false
        fun homePage(showQuest: Boolean = ready) = """<h4>수락 가능한 퀘스트</h4><table>
            ${if (showQuest) """<tr><td>[A] 상위 작업</td><td>미션 0/1</td><td>-</td><td>-</td><td>${if (accepted) "-" else "<a href='?menu=quest2&amp;action=get&amp;no=A'>수락</a>"}</td></tr>""" else ""}
            </table>${if (accepted) "<div id='result'>수락했습니다.</div>" else ""}"""
        val html = homePage(showQuest = true)
        val quest = HomePageParser().parse(HomeMode.HOME, html, url, HofFormParser().parse(html, url)).quests.single()
        setupFishing(obstruction = true, homeResponse = { request ->
            if (request.formFields["action"] == "get") accepted = true
            homePage()
        })
        val homeEntryId = TransactionTemplate(transactions).execute {
            val account = entityManager.find(HofAccountEntity::class.java, accountId)
            entityManager.find(AutomationEntryEntity::class.java, entryId).priority = 1
            entityManager.flush()
            val entry = AutomationEntryEntity(account = account, type = AutomationType.HOME_QUEST, priority = 0,
                enabled = true, createdAt = clock.now(), updatedAt = clock.now())
            entityManager.persist(entry)
            entityManager.persist(HomeQuestAutomationSelectionEntity(entry = entry, questId = quest.id,
                questName = quest.name, enabled = true, sourceOrder = 0))
            entityManager.flush()
            entry.id
        }
        wakeups.wake(accountId, "FISHING_PRIORITY_BOUNDARY")
        publisher.publishBatch()
        assertEquals(entryId, journal.page(accountId, AutomationHistoryQuery()).cycles.single().selectedEntryId)
        assertEquals(listOf("FStart", "FCatch"), fishingPosts())
        assertEquals(0, runningWorkCount())

        ready = true
        assertScheduledWake("TYPED_FISHING_CYCLE_COMPLETED", clock.now())
        consumeNextWake()
        val afterHome = journal.page(accountId, AutomationHistoryQuery()).cycles
        assertEquals(2, afterHome.size)
        assertEquals(homeEntryId, afterHome.first().selectedEntryId, afterHome.toString())
        val acceptance = requests.single { it.formFields["action"] == "get" }
        assertEquals(HofHttpMethod.GET, acceptance.method)
        assertEquals("A", acceptance.formFields["no"])
        assertEquals(0, requests.count { it.method == HofHttpMethod.POST && it.url.contains("common=fishing_12") })

        consumeNextWake()
        assertEquals(3, journal.page(accountId, AutomationHistoryQuery()).cycles.size)
        assertEquals(0, runningWorkCount())
        assertEquals(0, requests.count { it.method == HofHttpMethod.POST && it.url.contains("common=fishing_12") })
        assertEquals(listOf("WAITING_COOLDOWN"), jdbc.queryForList(
            "select status from automation_work_sessions where account_id = ? and automation_entry_id = ?",
            String::class.java, accountId, homeEntryId))

        consumeNextWake()
        assertEquals(4, journal.page(accountId, AutomationHistoryQuery()).cycles.size)
        assertEquals(entryId, journal.page(accountId, AutomationHistoryQuery()).cycles.first().selectedEntryId)
        assertEquals(1, requests.count { it.method == HofHttpMethod.POST && it.url.contains("common=fishing_12") })
        assertEquals(listOf("FStart", "FCatch"), fishingPosts())
        assertEquals(0, runningWorkCount())
        consumeNextWake()
        assertEquals(5, journal.page(accountId, AutomationHistoryQuery()).cycles.size)
        assertEquals(1, requests.count { it.formFields["action"] == "get" })
        assertEquals(1, requests.count { it.method == HofHttpMethod.POST && it.url.contains("common=fishing_12") })
    }

    @ParameterizedTest
    @ValueSource(strings = ["SETTINGS", "PAUSE", "AUTH"])
    fun `설정 정지와 사용자 일시정지와 인증 중단 뒤 최신 상태로 낚시 판단을 재개한다`(boundary: String) {
        setupFishing()
        wakeups.wake(accountId, "BEFORE_CONTROL_CHANGE")
        when (boundary) {
            "SETTINGS" -> application.updateFishing(accountId, app.spammy.hof.automation.dto.UpdateFishingAutomationRequest(false))
            "PAUSE" -> application.pauseTyped(accountId)
            "AUTH" -> {
                Mockito.`when`(authorization.isExecutionAllowed(accountId)).thenReturn(false)
                TransactionTemplate(transactions).executeWithoutResult {
                    lifecycle.suspendForAuthentication(accountId, "AUTH_SUSPEND_BASELINE")
                }
            }
        }
        consumeNextWake()
        assertTrue(fishingPosts().isEmpty())
        assertTrue(runs().isEmpty())
        val beforeResume = journal.page(accountId, AutomationHistoryQuery()).cycles.size
        when (boundary) {
            "SETTINGS" -> application.updateFishing(accountId, app.spammy.hof.automation.dto.UpdateFishingAutomationRequest(true))
            "PAUSE" -> application.resumeTyped(accountId)
            "AUTH" -> {
                Mockito.`when`(authorization.isExecutionAllowed(accountId)).thenReturn(true)
                TransactionTemplate(transactions).executeWithoutResult {
                    assertTrue(lifecycle.resumeAfterAuthentication(accountId, "AUTH_RESUME_BASELINE"))
                }
            }
        }
        consumeNextWake()
        assertEquals(listOf("FStart", "FCatch"), fishingPosts())
        assertEquals(listOf("SUCCEEDED", "SUCCEEDED"), runs().map { it["status"] })
        assertEquals(0, runningWorkCount())
        assertTrue(journal.page(accountId, AutomationHistoryQuery()).cycles.size > beforeResume)
        val beforeIdle = journal.page(accountId, AutomationHistoryQuery()).cycles.size
        consumeNextWake()
        assertEquals(beforeIdle + 1, journal.page(accountId, AutomationHistoryQuery()).cycles.size)
        assertEquals(listOf("FStart", "FCatch"), fishingPosts())
    }

    @Test
    fun `프로세스 시작 복구는 만료된 runtime lease의 계정을 깨워 행동과 후속 판단을 이어간다`() {
        setupFishing()
        jdbc.update("update typed_automation_runtime_states set lease_token = ?, lease_until = ?, next_attempt_at = ? where account_id = ?",
            UUID.randomUUID().toString(), java.sql.Timestamp.from(clock.now().minusSeconds(1)),
            java.sql.Timestamp.from(clock.now().minusSeconds(1)), accountId)
        assertTrue(recoveryQuery.findDueAccountIds(clock.now()).contains(accountId))
        app.spammy.hof.automation.recovery.AutomationRecoveryScheduler(recoveryQuery, wakeups, clock).recoverOnStartup()
        consumeNextWake()
        assertEquals(listOf("FStart", "FCatch"), fishingPosts())
        assertEquals(listOf("SUCCEEDED", "SUCCEEDED"), runs().map { it["status"] })
        consumeNextWake()
        assertEquals(2, journal.page(accountId, AutomationHistoryQuery()).cycles.size)
        assertEquals(listOf("FStart", "FCatch"), fishingPosts())
        assertNull(jdbc.queryForObject("select lease_token from typed_automation_runtime_states where account_id = ?", String::class.java, accountId))
    }

    private fun runningWorkCount() = jdbc.queryForObject(
        "select count(*) from automation_work_sessions where account_id = ? and status = 'RUNNING'", Int::class.java, accountId)

    private fun fishingPosts() = requests.mapNotNull { request ->
        val action = listOf("FStart", "FCatch").singleOrNull { it in request.formFields } ?: return@mapNotNull null
        assertEquals(HofHttpMethod.POST, request.method)
        assertEquals("https://hof.zerosic.com/index.php?menu=fishing", request.url)
        assertEquals(if (action == "FStart") "낚시를 시작한다" else "낚는다", request.formFields[action])
        action
    }

    private fun setupFishing(obstruction: Boolean = false, startObstruction: Boolean = false, lostFishingResponse: String? = null, homeResponse: ((HofRequest) -> String)? = null) {
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
        val header = "<div id='menu2'>Funds : $ 1 Time : 100/100</div>"
        fun fixture(name: String) = requireNotNull(javaClass.getResource("/fixtures/town/fishing/$name.html")).readText()
        var phase = "reset"
        Mockito.doAnswer { invocation ->
            val request = invocation.arguments[1] as HofRequest
            requests += request
            val body = when {
                request.url.contains("?char=") -> header
                homeResponse != null && request.url.contains("menu=quest2") -> homeResponse(request)
                "FStart" in request.formFields -> {
                    phase = if (startObstruction) "monster" else "waiting"
                    if (lostFishingResponse == "FStart") throw IOException("Fishing START response lost")
                    fixture(phase)
                }
                "FCatch" in request.formFields -> {
                    phase = if (obstruction) "monster" else "exhausted"
                    if (lostFishingResponse == "FCatch") throw IOException("Fishing CATCH response lost")
                    if (obstruction) fixture("caught").substringBefore("<form") + fixture("monster") + "</main>" else fixture("caught")
                }
                request.method == HofHttpMethod.POST -> {
                    phase = "exhausted"
                    """$header<h2>Show Detail( 1 turns. )</h2><h1>테스트은(는) 승리했다!</h1>
                    <div>남은 HP : 0/100 생존자 : 0/1 총 데미지 : 0</div>
                    <div>남은 HP : 100/100 생존자 : 1/1 총 데미지 : 100 턴 : 1/100 획득 경험치 : 1 획득 Funds : $ 1</div>"""
                }
                request.url.contains("menu=fishing") -> if (phase == "exhausted") fixture("reset").replace("18회", "0회") else fixture(phase)
                else -> "<a href='index.php?common=fishing_12'>낚시 전투</a>"
            }
            HofHttpResponse(200, request.url, header + body, emptyMap())
        }.`when`(gateway).execute(Mockito.eq(accountId), anyRequest(), Mockito.anyMap())
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

    @ParameterizedTest
    @EnumSource(value = ActionConvergenceResult::class, names = ["HELD", "PENDING"])
    fun `자택 A만 보류이면 실제 후보 B를 선택 제출하고 A 제외 이유를 이력에 남긴다`(result: ActionConvergenceResult) {
        Mockito.doCallRealMethod().`when`(decisions).select(accountId)
        val url = "https://hof.zerosic.com/index.php?menu=housing"
        var accepted = false
        fun page() = """<div id="menu2">Funds : $ 1 Time : 100/100</div>
            <h4>수락 가능한 퀘스트</h4><table>
            <tr><td>[A] 작업 A</td><td>미션 0/1</td><td>-</td><td>-</td><td><a href="?menu=housing&amp;action=get&amp;no=A">수락</a></td></tr>
            <tr><td>[B] 작업 B</td><td>미션 0/1</td><td>-</td><td>-</td><td>${if (accepted) "-" else "<a href='?menu=housing&amp;action=get&amp;no=B'>수락</a>"}</td></tr>
            </table>"""
        val quests = HomePageParser().parse(HomeMode.HOME, page(), url, HofFormParser().parse(page(), url)).quests
        assertEquals(2, quests.size)
        TransactionTemplate(transactions).executeWithoutResult {
            val entry = entityManager.find(AutomationEntryEntity::class.java, entryId)
            entry.type = AutomationType.HOME_QUEST
            entry.singletonTypeMarker = AutomationType.HOME_QUEST
            quests.forEachIndexed { order, quest ->
                entityManager.persist(HomeQuestAutomationSelectionEntity(entry = entry,
                    questId = quest.id, questName = quest.name, enabled = true, sourceOrder = order))
            }
        }
        Mockito.doAnswer { invocation ->
            val request = invocation.arguments[1] as HofRequest
            requests += request
            if (request.formFields["action"] == "get" && request.formFields["no"] == "B") accepted = true
            HofHttpResponse(200, url, page(), emptyMap())
        }.`when`(gateway).execute(Mockito.eq(accountId), anyRequest(), Mockito.anyMap())
        val a = quests.first()
        val action = HomeQuestAutomationAction(accountId, a.id, a.name, requireNotNull(a.actionId), HomeQuestAutomationActionType.ACCEPT)
        val preview = StoredActionConvergenceSelectionFactory().preview(entryId, action)
        val old = store.createOrGet(accountId, SelectedAutomationAction(entryId, UUID.randomUUID().toString(),
            preview.actionKind, preview.scope, "automation-action-convergence-v1", requireNotNull(preview.baselineFingerprint)), clock.now())
        old.result = result
        old.nextProbeAt = clock.now().plusSeconds(60)
        old.finishedAt = clock.now().takeIf { result == ActionConvergenceResult.HELD }
        store.save(old)

        val selected = assertIs<AutomationCoordination.Runnable>(decisions.select(accountId))
        assertEquals(quests[1].id, assertIs<HomeQuestAutomationAction>(selected.action).questId)
        wakeups.wake(accountId, "CANDIDATE_CONTINUITY_BASELINE")
        publisher.publishBatch()

        assertEquals(1, requests.count { it.formFields["action"] == "get" && it.formFields["no"] == "B" })
        assertEquals(0, requests.count { it.formFields["action"] == "get" && it.formFields["no"] == "A" })
        assertEquals(result, store.get(old.attemptId)?.result)
        val history = journal.page(accountId, AutomationHistoryQuery()).cycles.single()
        assertEquals(1, history.topLevelStepCount)
        val diagnostic = jacksonObjectMapper().readTree(requireNotNull(history.steps.first().event.diagnosticContext))
        assertEquals(a.id, diagnostic["excludedCandidates"][0]["targetKey"].asString())
        assertEquals(if (result == ActionConvergenceResult.HELD) "이전 행동 결과를 확정하지 못해 해당 범위를 보류했습니다. 최신 상태의 복구 조건을 확인하면 해제합니다."
            else "이전 행동 결과를 확인 중이라 해당 범위만 잠시 건너뜁니다.", diagnostic["excludedCandidates"][0]["reasonMessage"].asString())
        assertEquals(1, transport.delivered.size)
        consumeNextWake()
        assertEquals(2, journal.page(accountId, AutomationHistoryQuery()).cycles.size)
        assertEquals(1, requests.count { it.formFields["action"] == "get" && it.formFields["no"] == "B" })
        assertEquals(0, requests.count { it.formFields["action"] == "get" && it.formFields["no"] == "A" })
        assertEquals(0, jdbc.queryForObject("select count(*) from account_automation_leases where account_id = ?", Int::class.java, accountId))
    }

    @ParameterizedTest
    @EnumSource(value = ActionConvergenceResult::class, names = ["HELD", "PENDING"])
    fun `일반 퀘스트 A만 보류이면 실제 후보 B를 제출하고 B의 수락 사이클만 증가한다`(result: ActionConvergenceResult) {
        Mockito.doCallRealMethod().`when`(decisions).select(accountId)
        val url = "https://hof.zerosic.com/index.php?menu=quest"
        val header = "<tr><td>퀘스트명</td><td>타입</td><td>제한</td><td>보상</td><td>행동</td></tr>"
        var accepted = false
        fun row(id: String, active: Boolean = false) = """<tr><td class="td7s">[Q00$id] 작업 $id</td><td>미션 : 즉시 완료</td><td>-</td><td>-</td><td>${if (active) "-" else "<a href='?menu=quest&amp;action=get&amp;no=$id'>수락</a>"}</td></tr>"""
        val emptyPage = requireNotNull(javaClass.getResource("/fixtures/quest/quest-complete-empty.html")).readText()
        fun page() = emptyPage.replace(
            "<h4>진행중인 퀘스트 목록</h4>\n  <table>$header</table>",
            "<h4>진행중인 퀘스트 목록</h4><table>$header${if (accepted) row("B", true) else ""}</table>",
        ).replace(
            "<h4>수락 가능한 퀘스트 목록</h4>\n  <table>$header</table>",
            "<h4>수락 가능한 퀘스트 목록</h4><table>$header${row("A")}${if (accepted) "" else row("B")}</table>",
        )
        val observation = QuestPageParser().parseObservation(page(), url)
        assertTrue(observation.complete)
        val quests = observation.quests.filter { it.actionNo in listOf("A", "B") }
        assertEquals(2, quests.size)
        TransactionTemplate(transactions).executeWithoutResult {
            val entry = entityManager.find(AutomationEntryEntity::class.java, entryId)
            entry.type = AutomationType.QUEST
            entry.singletonTypeMarker = AutomationType.QUEST
            quests.forEachIndexed { order, quest ->
                entityManager.persist(QuestAutomationSelectionEntity(entry = entry,
                    questKey = quest.questKey, enabled = true, sourceOrder = order))
            }
        }
        Mockito.doAnswer { invocation ->
            val request = invocation.arguments[1] as HofRequest
            requests += request
            if (request.formFields["action"] == "get" && request.formFields["no"] == "B") accepted = true
            HofHttpResponse(200, url, page(), emptyMap())
        }.`when`(gateway).execute(Mockito.eq(accountId), anyRequest(), Mockito.anyMap())
        val a = quests.first()
        val preview = StoredActionConvergenceSelectionFactory().preview(entryId, QuestAction.Accept(a.questKey, "A", a.name, "0"))
        val old = store.createOrGet(accountId, SelectedAutomationAction(entryId, UUID.randomUUID().toString(),
            preview.actionKind, preview.scope, "automation-action-convergence-v1", requireNotNull(preview.baselineFingerprint)), clock.now())
        old.result = result
        old.nextProbeAt = clock.now().plusSeconds(60)
        old.finishedAt = clock.now().takeIf { result == ActionConvergenceResult.HELD }
        store.save(old)

        val selected = assertIs<AutomationCoordination.Runnable>(decisions.select(accountId))
        assertEquals(quests[1].questKey, assertIs<QuestAction.Accept>(selected.action).questKey)
        wakeups.wake(accountId, "WAITING_CONTINUITY_BASELINE")
        publisher.publishBatch()

        assertEquals(1, requests.count { it.formFields["action"] == "get" && it.formFields["no"] == "B" })
        assertEquals(0, requests.count { it.formFields["action"] == "get" && it.formFields["no"] == "A" })
        assertEquals(result, store.get(old.attemptId)?.result)
        assertEquals(listOf(quests[1].questKey), jdbc.queryForList(
            "select quest_code from quest_automation_cycles where account_id = ?", String::class.java, accountId))
        val history = journal.page(accountId, AutomationHistoryQuery()).cycles.single()
        assertEquals(1, history.topLevelStepCount)
        val diagnostic = jacksonObjectMapper().readTree(requireNotNull(history.steps.first().event.diagnosticContext))
        assertEquals(a.questKey, diagnostic["excludedCandidates"][0]["targetKey"].asString())
        assertEquals(if (result == ActionConvergenceResult.HELD) "이전 행동 결과를 확정하지 못해 해당 범위를 보류했습니다. 최신 상태의 복구 조건을 확인하면 해제합니다."
            else "이전 행동 결과를 확인 중이라 해당 범위만 잠시 건너뜁니다.", diagnostic["excludedCandidates"][0]["reasonMessage"].asString())
        consumeNextWake()
        assertEquals(2, journal.page(accountId, AutomationHistoryQuery()).cycles.size)
        assertEquals(1, requests.count { it.formFields["action"] == "get" && it.formFields["no"] == "B" })
        assertEquals(0, requests.count { it.formFields["action"] == "get" && it.formFields["no"] == "A" })
    }

    @ParameterizedTest
    @EnumSource(RaidWaitReason::class)
    fun `레이드 대기 사유를 저장하면서 하위 자택을 실행하고 재확인 시각을 보존한다`(reason: RaidWaitReason) {
        Mockito.doCallRealMethod().`when`(decisions).select(accountId)
        val retryAt = clock.now().plusSeconds(120)
        val directive = RaidDirective.WaitUntil(retryAt, reason, "레이드 상태 재확인", entryId, "RaidGoblin",
            impactScope = AutomationImpactScope.RAID_ONLY, releaseCondition = "최신 레이드 상태 재확인")
        Mockito.doReturn(RaidDecision(directive)).`when`(raidModule).decide(accountId)
        val url = "https://hof.zerosic.com/index.php?menu=housing"
        var accepted = false
        fun page() = """<div id="menu2">Funds : $ 1 Time : 100/100</div><h4>수락 가능한 퀘스트</h4><table>
            <tr><td>[B] 하위 자택</td><td>미션 0/1</td><td>-</td><td>-</td><td>${if (accepted) "-" else "<a href='?menu=housing&amp;action=get&amp;no=B'>수락</a>"}</td></tr></table>"""
        val quest = HomePageParser().parse(HomeMode.HOME, page(), url, HofFormParser().parse(page(), url)).quests.single()
        TransactionTemplate(transactions).executeWithoutResult {
            val entry = entityManager.find(AutomationEntryEntity::class.java, entryId)
            entry.type = AutomationType.RAID
            entry.singletonTypeMarker = AutomationType.RAID
            val home = AutomationEntryEntity(account = entry.account, type = AutomationType.HOME_QUEST, priority = 1,
                enabled = true, createdAt = clock.now(), updatedAt = clock.now())
            entityManager.persist(home)
            entityManager.persist(HomeQuestAutomationSelectionEntity(entry = home,
                questId = quest.id, questName = quest.name, enabled = true, sourceOrder = 0))
        }
        Mockito.doAnswer { invocation ->
            val request = invocation.arguments[1] as HofRequest
            requests += request
            if (request.formFields["action"] == "get" && request.formFields["no"] == "B") accepted = true
            HofHttpResponse(200, url, page(), emptyMap())
        }.`when`(gateway).execute(Mockito.eq(accountId), anyRequest(), Mockito.anyMap())

        wakeups.wake(accountId, "WAITING_CONTINUITY_BASELINE")
        publisher.publishBatch()

        assertEquals(1, requests.count { it.formFields["action"] == "get" && it.formFields["no"] == "B" })
        val history = journal.page(accountId, AutomationHistoryQuery()).cycles.single()
        val raidEvent = history.steps.first().event
        val expectedKind = if (reason in setOf(RaidWaitReason.BATTLE_RECOVERY_RECHECK,
                RaidWaitReason.REWARD_CONFIRMATION, RaidWaitReason.POST_REWARD_CHECK)) {
            AutomationHistoryEventKind.WAITING
        } else AutomationHistoryEventKind.SKIPPED
        assertEquals(2, history.topLevelStepCount)
        assertEquals(expectedKind, raidEvent.kind)
        assertEquals(reason.name, raidEvent.reasonCode)
        assertEquals("RaidGoblin", raidEvent.targetKey)
        assertEquals(retryAt, raidEvent.nextRunAt)
        assertNotNull(raidEvent.diagnosticContext)
        assertEquals(retryAt, jdbc.queryForObject(
            "select next_check_at from automation_work_sessions where account_id = ? and work_type = 'RAID'",
            java.sql.Timestamp::class.java, accountId)?.toInstant())
        consumeNextWake()
        assertEquals(2, journal.page(accountId, AutomationHistoryQuery()).cycles.size)
        assertEquals(1, requests.count { it.formFields["action"] == "get" && it.formFields["no"] == "B" })
        assertEquals(raidEvent, journal.page(accountId, AutomationHistoryQuery()).cycles.first { it.id == history.id }.steps.first().event)
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
        assertEquals(1, registerRequests().size)
        assertTrue(store.findActiveScopes(accountId).isNotEmpty(), "로그아웃은 신청 결과가 미확정인 경계에서 발생해야 한다.")
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
        consumeNextWake()
    }

    private fun consumeNextWake() {
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

    private fun assertScheduledWake(reason: String, at: Instant) {
        val payloads = jdbc.queryForList(
            "select payload from automation_outbox where account_id = ? and topic = ? and available_at = ? and published_at is null",
            String::class.java, accountId, AutomationOutboxService.WAKEUP_TOPIC, java.sql.Timestamp.from(at))
        assertTrue(payloads.any { jacksonObjectMapper().readTree(it)["reason"].asString() == reason },
            "$reason wake가 $at 에 예약되어 있어야 한다.")
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

    /** Only broker delivery is replaced; production consumer, lease and runtime are real. */
    class ConsumerReplayTransport(private val consumer: AutomationWakeupConsumer) : AutomationOutboxTransport {
        override val supportedTopics = setOf(AutomationOutboxService.WAKEUP_TOPIC)
        val delivered = mutableListOf<String>()
        override fun publish(row: AutomationOutboxEntity) {
            var acknowledged = false
            consumer.consume(row.payload, Acknowledgment { acknowledged = true })
            check(acknowledged) { "Wake was not consumed: ${row.eventId}" }
            delivered += row.eventId
        }
    }

    @TestConfiguration
    class Config {
        @Bean @Primary fun recoveryClock() = RecoveryClock()
        @Bean @Primary
        fun durableWakeups(outbox: AutomationOutboxService): AutomationWakeupPort = KafkaAutomationWakeupAdapter(outbox)
        @Bean @Primary
        fun consumerReplayTransport(
            mapper: ObjectMapper,
            consumed: AutomationConsumedEventService,
            lease: AccountAutomationLeaseService,
            runner: UnifiedAutomationRunner,
            clock: TimeProvider,
        ) = ConsumerReplayTransport(AutomationWakeupConsumer(mapper, consumed, lease, runner, clock))
    }
}
