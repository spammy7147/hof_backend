package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.entity.HofCookieEntity
import app.spammy.hof.auth.service.AccountExecutionAuthorizationReader
import app.spammy.hof.automation.convergence.*
import app.spammy.hof.automation.entity.*
import app.spammy.hof.automation.history.*
import app.spammy.hof.automation.outbox.*
import app.spammy.hof.automation.port.AutomationWakeupPort
import app.spammy.hof.automation.raid.*
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.entity.CharacterPatternSlotEntity
import app.spammy.hof.external.client.HofGateway
import app.spammy.hof.external.model.*
import app.spammy.hof.party.entity.PartyPresetEntity
import app.spammy.hof.party.entity.PartyPresetMemberEntity
import app.spammy.hof.status.entity.HofStatusSnapshotEntity
import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.home.model.HomeMode
import app.spammy.hof.town.home.parser.HomePageParser
import jakarta.persistence.EntityManager
import java.time.Instant
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
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@SpringBootTest(properties = ["hof.automation-convergence.mode=LEGACY"])
class LegacyRaidRewardIntegrationTest : RaidRewardIntegrationTest() {
    @Test fun `불명확 보상의 최초 응답과 복구는 같은 실행 식별자를 유지한다`() = assertAmbiguousRewardRecovery()
}

@SpringBootTest(properties = ["hof.automation-convergence.mode=SHADOW"])
class ShadowRaidRewardIntegrationTest : RaidRewardIntegrationTest() {
    @Test fun `불명확 보상의 최초 응답과 복구는 같은 실행 식별자를 유지한다`() = assertAmbiguousRewardRecovery()
}

@SpringBootTest(properties = ["hof.automation-convergence.mode=ACTIVE"])
class ActiveRaidRewardIntegrationTest : RaidRewardIntegrationTest()

@ActiveProfiles("test")
@Import(AutomationRecoveryIntegrationTest.Config::class)
abstract class RaidRewardIntegrationTest {
    @Autowired private lateinit var entityManager: EntityManager
    @Autowired private lateinit var transactions: PlatformTransactionManager
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var clock: AutomationRecoveryIntegrationTest.RecoveryClock
    @Autowired private lateinit var publisher: AutomationOutboxPublisher
    @Autowired private lateinit var outbox: AutomationOutboxQueryRepository
    @Autowired private lateinit var transport: AutomationRecoveryIntegrationTest.ConsumerReplayTransport
    @Autowired private lateinit var wakeups: AutomationWakeupPort
    @Autowired private lateinit var journal: AutomationDecisionJournal
    @Autowired private lateinit var raidStore: RaidCycleStore
    @Autowired private lateinit var convergenceProperties: AutomationConvergenceProperties
    @Autowired private lateinit var lifecycle: TypedAutomationLifecycleBridge
    @MockitoBean private lateinit var gateway: HofGateway
    @MockitoBean private lateinit var preflight: AutomationDailyPreflight
    @MockitoBean private lateinit var authorization: AccountExecutionAuthorizationReader
    private var accountId = 0L
    private var entryId = 0L
    private val requests = mutableListOf<HofRequest>()

    @BeforeEach
    fun prepareAccount() {
        transport.delivered.clear()
        clock.current = Instant.parse("2026-09-08T04:42:00Z")
        TransactionTemplate(transactions).executeWithoutResult {
            val account = HofAccountEntity(loginId = "raid-reward-${UUID.randomUUID()}", encryptedPassword = "test", createdAt = clock.now())
            entityManager.persist(account)
            entityManager.flush()
            accountId = account.id
            val entry = AutomationEntryEntity(account = account, type = AutomationType.RAID, priority = 0,
                enabled = true, createdAt = clock.now(), updatedAt = clock.now())
            entityManager.persist(entry)
            entityManager.flush()
            entryId = entry.id
            entityManager.persist(HofCookieEntity(account = account, name = "PHPSESSID", value = "test-session", updatedAt = clock.now()))
            entityManager.persist(HofStatusSnapshotEntity(account = account, playerName = "테스트", funds = 1,
                timeCurrent = 100, timeMax = 100, work = "", auction = "", observedAt = clock.now()))
            entityManager.persist(TypedAutomationRuntimeStateEntity(accountId = accountId, account = account,
                lifecycleStatus = TypedAutomationLifecycle.RUNNING, createdAt = clock.now(), updatedAt = clock.now()))
            val character = CharacterEntity(account = account, hofCharacterId = "fixture-1", name = "참가자", job = "Knight", updatedAt = clock.now())
            entityManager.persist(character)
            val pattern = CharacterPatternSlotEntity(character = character, slotCode = "1", label = "기본", canLoad = true)
            entityManager.persist(pattern)
            val preset = PartyPresetEntity(account = account, name = "레이드 파티", createdAt = clock.now(), updatedAt = clock.now(), isPrimary = true)
            entityManager.persist(preset)
            entityManager.persist(PartyPresetMemberEntity(preset, 0, character, pattern))
            entityManager.persist(RaidAutomationTargetEntity(entry = entry, raidId = "RaidGoblin", displayName = "고블린 전투 마차",
                presetMode = PresetSelectionMode.PRIMARY, executionOrder = 0))
        }
        Mockito.`when`(preflight.ensureReady(accountId)).thenReturn(AutomationDailyPreflight.Result.Ready)
        Mockito.`when`(authorization.isExecutionAllowed(accountId)).thenReturn(true)
    }

    @AfterEach
    fun removeAccount() {
        transport.delivered.forEach { jdbc.update("delete from automation_consumed_events where event_id = ?", it) }
        if (accountId != 0L) jdbc.update("delete from hof_accounts where id = ?", accountId)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `보상 없음 직접 HTML은 갱신과 순환 후 다음 wakeup에서 하위 항목까지 진행한다`(resume: Boolean) {
        setupRewardRaid()
        wakeups.wake(accountId, "REWARD_RESULT_FIXTURE")
        publisher.publishBatch()

        assertEquals(1, rewardRequests().size)
        assertEquals(RaidAutomationCycleStatus.POST_REWARD_CHECK, raidStore.load(accountId).openCycle?.status)
        assertNull(raidStore.load(accountId).openCycle?.rewardRecovery)
        assertEquals(1, runningWorkCount())
        val rewardRun = runs().single()
        assertEquals("SUCCEEDED", rewardRun["status"])
        assertTrue(journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }.any {
            it.actionKind == "REWARD" && it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED &&
                it.message.contains("수령 가능한 보상이 없습니다.")
        })
        if (resume) {
            TransactionTemplate(transactions).executeWithoutResult { lifecycle.pause(accountId, "REWARD_PAUSE_FIXTURE") }
            TransactionTemplate(transactions).executeWithoutResult { lifecycle.resume(accountId, "REWARD_RESUME_FIXTURE") }
            assertEquals(RaidAutomationCycleStatus.POST_REWARD_CHECK, raidStore.load(accountId).openCycle?.status)
        }

        consumeNextWake() // 보상 후 REFRESH를 실제 제출하고 결과를 저장한다.
        assertEquals(1, requests.count { it.formFields.containsKey("refresh_nonce") })
        assertNull(raidStore.load(accountId).openCycle)
        assertEquals("RaidSiren", raidStore.load(accountId).configuration?.currentTargetKey)
        assertEquals(0, jdbc.queryForObject(
            "select count(*) from automation_work_sessions where account_id = ? and automation_entry_id = ? and status = 'RUNNING'",
            Int::class.java, accountId, entryId))
        assertTrue(runningWorkCount()!! <= 1)
        assertTrue(jdbc.queryForObject("select max(next_check_at) from automation_work_sessions where account_id = ?",
            java.time.OffsetDateTime::class.java, accountId)!!.toInstant().isAfter(clock.now().plusSeconds(10_000)))

        consumeNextWake() // 다음 전체 판단은 레이드를 스킵한 뒤 하위 자택 수락을 실행한다.
        assertEquals(1, requests.count { it.formFields["action"] == "get" && it.formFields["no"] == "B" },
            journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }.toString())
        assertEquals(1, rewardRequests().size)
        assertTrue(runs().none { it["status"] in setOf("RECONCILING", "AMBIGUOUS") })
        val histories = journal.page(accountId, AutomationHistoryQuery()).cycles
        assertTrue(histories.size >= 2)
        val last = histories.first()
        assertEquals(listOf(AutomationType.RAID, AutomationType.HOME_QUEST), last.steps.map { it.event.type })
        val events = histories.flatMap { it.events }
        assertTrue(events.any { it.actionKind == "REWARD" && it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED &&
            it.message.contains("수령 가능한 보상이 없습니다.") }, events.toString())
        assertTrue(events.none { it.reasonCode.startsWith("AMBIGUOUS_RESULT") })
        assertTrue(transport.delivered.all { outbox.consumed(it) })
        if (convergenceProperties.mode == AutomationConvergenceMode.ACTIVE) {
            assertEquals("APPLIED", jdbc.queryForObject("select c.result from automation_action_convergences c join automation_action_attempts a on a.id=c.attempt_id where a.account_id=? and a.action_kind='RAID_REWARD'",
                String::class.java, accountId))
        }
    }

    protected fun assertAmbiguousRewardRecovery() {
        setupRewardRaid(rewardMarkup = "")
        wakeups.wake(accountId, "AMBIGUOUS_REWARD_FIXTURE")
        publisher.publishBatch()
        val identity = runs().single()["execution_identity"]
        val initial = assertNotNull(raidStore.load(accountId).openCycle?.rewardRecovery)
        assertEquals(identity, initial.executionIdentity)
        assertEquals(1, initial.successfulObservationCount)
        consumeNextWake()
        val rechecked = assertNotNull(raidStore.load(accountId).openCycle?.rewardRecovery)
        assertEquals(identity, rechecked.executionIdentity)
        assertEquals(2, rechecked.successfulObservationCount)
        assertEquals(initial.firstAmbiguousAt, rechecked.firstAmbiguousAt)
        assertEquals(1, rewardRequests().size)
        assertTrue(journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }
            .none { it.message.contains("실행 식별자가 없습니다") })
    }

    private fun setupRewardRaid(rewardMarkup: String = "<font color=\"#88ee88\">수령 가능한 보상이 없습니다.</font><br>") {
        val homeUrl = "https://hof.zerosic.com/index.php?menu=quest2"
        var accepted = false
        fun homePage() = """<div id="menu2">Funds : $ 1 Time : 100/100</div><h4>수락 가능한 퀘스트</h4><table>
            <tr><td>[B] 하위 자택</td><td>미션 0/1</td><td>-</td><td>-</td><td>${if (accepted) "-" else "<a href='?menu=quest2&amp;action=get&amp;no=B'>수락</a>"}</td></tr></table>${if (accepted) "<div id='result'>수락했습니다.</div>" else ""}"""
        val quest = HomePageParser().parse(HomeMode.HOME, homePage(), homeUrl, HofFormParser().parse(homePage(), homeUrl)).quests.single()
        TransactionTemplate(transactions).executeWithoutResult {
            val entry = entityManager.find(AutomationEntryEntity::class.java, entryId)
            entityManager.persist(RaidAutomationTargetEntity(entry = entry, raidId = "RaidSiren", displayName = "세이렌",
                presetMode = PresetSelectionMode.PRIMARY, executionOrder = 1))
            entityManager.persist(AutomationRotationStateEntity(entry = entry, currentTargetKey = "RaidGoblin", updatedAt = clock.now()))
            val home = AutomationEntryEntity(account = entry.account, type = AutomationType.HOME_QUEST, priority = 1,
                enabled = true, createdAt = clock.now(), updatedAt = clock.now())
            entityManager.persist(home)
            entityManager.persist(HomeQuestAutomationSelectionEntity(entry = home,
                questId = quest.id, questName = quest.name, enabled = true, sourceOrder = 0))
        }
        val target = raidStore.load(accountId).configuration!!.targets.first()
        raidStore.open(accountId, entryId, target, clock.now(), RaidAutomationCycleStatus.REWARD_PENDING)
        var refreshed = false
        Mockito.doAnswer { invocation ->
            val request = invocation.arguments[1] as HofRequest
            requests += request
            when {
                request.url.contains("quest2") || request.formFields["menu"] == "quest2" -> {
                    if (request.formFields["action"] == "get") accepted = true
                    HofHttpResponse(200, homeUrl, homePage(), emptyMap())
                }
                request.url.contains("raidpub") || request.method == HofHttpMethod.POST -> {
                    if (request.formFields.containsKey("refresh_nonce")) refreshed = true
                    var page = raidHtml(!refreshed)
                        .replace("현재 상태 : 418초 후 출발", "현재 상태 : 보상 확인 시간 (남은 시간 앞으로 0시간 29분 56초)")
                    if (refreshed) page = page.replace("현재 상태는 신청 가능", "현재 상태는 신청 대기 (신청 가능까지 2시간 59분 50초)")
                    if (request.formFields.containsKey("reward_nonce")) page = page.replaceFirst("  <h4>", "  $rewardMarkup\n  <h4>")
                    HofHttpResponse(200, "https://hof.zerosic.com/index.php?menu=raidpub", page, emptyMap())
                }
                else -> HofHttpResponse(200, request.url,
                    "<div id='menu2'>Funds : $ 1 Time : 100/100</div>아무것도 없다", emptyMap())
            }
        }.`when`(gateway).execute(Mockito.eq(accountId), anyRequest(), Mockito.anyMap())
    }

    private fun rewardRequests() = requests.filter { it.formFields.containsKey("reward_nonce") }

    private fun raidHtml(joined: Boolean): String = requireNotNull(javaClass.getResource("/fixtures/town/raid/raidpub.html")).readText()
        .replace("Funds : $ 1,000", "Funds : $ 1,000 Time : 100/100")
        .replace("현재 상태는 신청 대기 (신청 가능까지 6분 58초)", "현재 상태는 신청 가능")
        .replace("현재 상태 : 모집 중", if (joined) "현재 상태 : 418초 후 출발" else "현재 상태 : 파티 모집 중 (신청 안됨)")
        .replace("[《테스트 길드》현재사용자]", if (joined) "[《테스트 길드》현재사용자]" else "[다른 신청자]")
        .replace("<input type=\"submit\" name=\"reward_nonce\"", "<input type=\"submit\" name=\"refresh_nonce\" value=\"상태 갱신\"><input type=\"submit\" name=\"reward_nonce\"")

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

    private fun runningWorkCount() = jdbc.queryForObject(
        "select count(*) from automation_work_sessions where account_id = ? and status = 'RUNNING'", Int::class.java, accountId)
    private fun runs() = jdbc.queryForList(
        "select status, submitted_at, execution_identity, last_error from typed_automation_action_runs where account_id = ? order by id", accountId)
    private fun anyRequest(): HofRequest = Mockito.any<HofRequest>() ?: HofRequest(HofHttpMethod.GET, "https://example.test")
}
