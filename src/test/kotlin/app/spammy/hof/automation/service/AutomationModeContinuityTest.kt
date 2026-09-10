package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.entity.HofCookieEntity
import app.spammy.hof.auth.service.AccountExecutionAuthorizationReader
import app.spammy.hof.automation.convergence.AutomationConvergenceMode
import app.spammy.hof.automation.convergence.AutomationConvergenceProperties
import app.spammy.hof.automation.entity.*
import app.spammy.hof.automation.history.AutomationDecisionJournal
import app.spammy.hof.automation.history.AutomationHistoryQuery
import app.spammy.hof.automation.outbox.AutomationOutboxPublisher
import app.spammy.hof.automation.outbox.AutomationOutboxQueryRepository
import app.spammy.hof.automation.port.AutomationWakeupPort
import app.spammy.hof.external.client.HofGateway
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.status.entity.HofStatusSnapshotEntity
import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.home.model.HomeMode
import app.spammy.hof.town.home.parser.HomePageParser
import app.spammy.hof.character.transfer.CharacterTransferFixture
import app.spammy.hof.character.transfer.CharacterTransferRequest
import app.spammy.hof.character.transfer.CharacterTransferOutcome
import app.spammy.hof.character.transfer.CharacterSavedPatternMapping
import app.spammy.hof.character.dto.CharacterTransferExecuteRequest
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.pattern.CharacterPatternSetting
import app.spammy.hof.character.pattern.CharacterPatternRowValue
import app.spammy.hof.character.service.CharacterOperationJobService
import app.spammy.hof.character.service.CharacterSnapshotSynchronizer
import app.spammy.hof.character.service.CharacterSnapshotArchiveWriter
import app.spammy.hof.external.parser.CharacterDetailParser
import jakarta.persistence.EntityManager
import java.time.Instant
import java.util.UUID
import kotlin.test.*
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
class LegacyAutomationContinuityTest : AutomationModeContinuityTest() {
    override val mode = AutomationConvergenceMode.LEGACY
}

@SpringBootTest(properties = ["hof.automation-convergence.mode=SHADOW"])
class ShadowAutomationContinuityTest : AutomationModeContinuityTest() {
    override val mode = AutomationConvergenceMode.SHADOW
}

@SpringBootTest(properties = ["hof.automation-convergence.mode=ACTIVE"])
class ActiveAutomationContinuityTest : AutomationModeContinuityTest() {
    override val mode = AutomationConvergenceMode.ACTIVE
}

/** Each supported mode is assembled from its real startup property, without mocking rollout. */
@ActiveProfiles("test")
@Import(AutomationRecoveryIntegrationTest.Config::class)
abstract class AutomationModeContinuityTest {
    protected abstract val mode: AutomationConvergenceMode
    @Autowired private lateinit var properties: AutomationConvergenceProperties
    @Autowired private lateinit var entityManager: EntityManager
    @Autowired private lateinit var transactions: PlatformTransactionManager
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var clock: AutomationRecoveryIntegrationTest.RecoveryClock
    @Autowired private lateinit var transport: AutomationRecoveryIntegrationTest.ConsumerReplayTransport
    @Autowired private lateinit var publisher: AutomationOutboxPublisher
    @Autowired private lateinit var outbox: AutomationOutboxQueryRepository
    @Autowired private lateinit var wakeups: AutomationWakeupPort
    @Autowired private lateinit var journal: AutomationDecisionJournal
    @MockitoBean private lateinit var gateway: HofGateway
    @MockitoBean private lateinit var preflight: AutomationDailyPreflight
    @MockitoBean private lateinit var authorization: AccountExecutionAuthorizationReader
    @Autowired private lateinit var characterGate: app.spammy.hof.character.command.CharacterAutomationGate
    @Autowired private lateinit var characterRecovery: app.spammy.hof.character.service.CharacterDeepSyncRecovery
    @Autowired private lateinit var characterJobs: CharacterOperationJobService
    @Autowired private lateinit var snapshots: CharacterSnapshotSynchronizer
    @Autowired private lateinit var archive: CharacterSnapshotArchiveWriter
    @Autowired private lateinit var characterParser: CharacterDetailParser
    @Autowired private lateinit var automation: UnifiedAutomationService
    @MockitoBean(name = "characterSyncTaskExecutor") private lateinit var characterTasks: org.springframework.core.task.TaskExecutor

    @Test
    fun `설정 가져오기의 최종 상태를 보존하고 영속 복귀 깨우기에서 낚시와 후속 판단을 이어간다`() {
        assertEquals(mode, properties.mode)
        clock.current = Instant.parse("2026-09-09T00:00:00Z")
        transport.delivered.clear()
        val requests = mutableListOf<HofRequest>()
        val tasks = ArrayDeque<Runnable>()
        lateinit var source: CharacterEntity
        lateinit var target: CharacterEntity
        val accountId = TransactionTemplate(transactions).execute {
            val account = HofAccountEntity(loginId = "transfer-mode-${UUID.randomUUID()}", encryptedPassword = "test", createdAt = clock.now())
            entityManager.persist(account)
            entityManager.persist(AutomationEntryEntity(account = account, type = AutomationType.FISHING, priority = 0,
                enabled = true, createdAt = clock.now(), updatedAt = clock.now()))
            entityManager.persist(HofCookieEntity(account = account, name = "PHPSESSID", value = "test-session", updatedAt = clock.now()))
            entityManager.persist(TypedAutomationRuntimeStateEntity(accountId = account.id, account = account,
                lifecycleStatus = TypedAutomationLifecycle.RUNNING, createdAt = clock.now(), updatedAt = clock.now()))
            source = CharacterEntity(account = account, hofCharacterId = "transfer-source", name = "원본", job = "Knight", updatedAt = clock.now())
            target = CharacterEntity(account = account, hofCharacterId = "transfer-target", name = "대상", job = "Knight", updatedAt = clock.now())
            entityManager.persist(source)
            entityManager.persist(target)
            account.id
        }
        var current = CharacterTransferFixture.setting("0")
        val slots = mutableMapOf<String, CharacterPatternSetting?>("0" to null)
        val sourceCurrent = CharacterTransferFixture.setting("1")
        val sourceSaved = CharacterTransferFixture.setting("2")
        var fishingBattle = false
        fun fishingFixture(name: String) = requireNotNull(javaClass.getResource("/fixtures/town/fishing/$name.html")).readText()
        try {
            snapshots.writeParsed(accountId, source.hofCharacterId, characterParser.parsePage(source.hofCharacterId,
                CharacterTransferFixture.page(source.hofCharacterId, sourceCurrent, mapOf("0" to sourceSaved))))
            archive.savePatternSlot(source, "0", characterParser.parsePage(source.hofCharacterId,
                CharacterTransferFixture.page(source.hofCharacterId, sourceSaved, mapOf("0" to sourceSaved))))
            snapshots.writeParsed(accountId, target.hofCharacterId, characterParser.parsePage(target.hofCharacterId,
                CharacterTransferFixture.page(target.hofCharacterId, current, slots)))
            Mockito.doAnswer { tasks.addLast(it.getArgument(0)); null }
                .`when`(characterTasks).execute(Mockito.any(Runnable::class.java))
            Mockito.`when`(preflight.ensureReady(accountId)).thenReturn(AutomationDailyPreflight.Result.Ready)
            Mockito.`when`(authorization.isExecutionAllowed(accountId)).thenReturn(true)
            Mockito.doAnswer { invocation ->
                val request = invocation.getArgument<HofRequest>(1)
                requests += request
                val fields = request.formFields
                val body = if (request.url.contains("?char=")) {
                    check(request.url.substringAfter("char=") == target.hofCharacterId)
                    when {
                        "ChangePattern" in fields -> current = current.copy(rows = listOf(CharacterPatternRowValue(
                            fields.getValue("judge0"), fields.getValue("quantity0"), fields.getValue("skill0"))))
                        "ChangePosition" in fields -> current = current.copy(position = fields.getValue("position"), guard = fields.getValue("guard"))
                        "savepattern" in fields -> slots[fields.getValue("patternno")] = current
                    }
                    CharacterTransferFixture.page(target.hofCharacterId, current, slots)
                } else {
                    "<div id='menu2'>Funds : $ 1 Time : 100/100</div>" + when {
                        "FStart" in fields -> fishingFixture("waiting")
                        "FCatch" in fields -> {
                            fishingBattle = true
                            fishingFixture("caught").substringBefore("<form") + fishingFixture("monster")
                        }
                        request.url.contains("menu=fishing") -> fishingFixture("reset")
                        else -> """<div id='contents'><a href='?common=0001'>일반 맵</a>
                            ${if (fishingBattle) "<a href='?common=Fish03'>Fishing- 악어</a>" else ""}</div>
                            <div id='foot'><h5>Copy Right sanitized</h5><h6>H.O.F Korean Ver sanitized</h6><img src='zerohof.gif'></div>"""
                    }
                }
                HofHttpResponse(200, request.url, body, emptyMap())
            }.`when`(gateway).execute(Mockito.eq(accountId), Mockito.any<HofRequest>()
                ?: HofRequest(HofHttpMethod.GET, "https://example.test"), Mockito.anyMap())

            val started = characterJobs.startTransfer(accountId, CharacterTransferExecuteRequest(source.id, target.id,
                CharacterTransferRequest(includeCurrentPattern = true,
                    savedPatternMappings = listOf(CharacterSavedPatternMapping("0", "0")))))
            requests.clear()
            wakeups.wake(accountId, "TRANSFER_PENDING")
            publisher.publishBatch()
            assertTrue(requests.isEmpty(), "가져오기 대기 중 새 자동화 행동을 실행하지 않는다.")
            tasks.removeFirst().run()
            assertEquals(CharacterTransferOutcome.COMPLETED, characterJobs.find(accountId, started.id).transfer?.outcome)
            assertEquals(sourceCurrent, current)
            assertEquals(sourceSaved, slots["0"])
            assertEquals(TypedAutomationLifecycle.RUNNING, automation.getTyped(accountId).runtime.lifecycle)
            publisher.publishBatch()
            val firstCycles = journal.page(accountId, AutomationHistoryQuery()).cycles.map { it.id }.toSet()
            assertEquals(1, requests.count { "FStart" in it.formFields })
            assertEquals(1, requests.count { "FCatch" in it.formFields })
            clock.current = assertNotNull(jdbc.queryForObject(
                "select min(available_at) from automation_outbox where account_id = ? and published_at is null",
                java.time.OffsetDateTime::class.java, accountId)).toInstant()
            publisher.publishBatch()
            val cycles = journal.page(accountId, AutomationHistoryQuery()).cycles
            assertTrue(cycles.any { it.id !in firstCycles }, "복귀 행동 이후 새 판단을 실제 소비해야 한다.")
            assertEquals(1, requests.count { "FStart" in it.formFields })
            assertEquals(1, requests.count { "FCatch" in it.formFields })
            assertEquals(sourceCurrent, current)
            assertTrue(transport.delivered.all { outbox.consumed(it) })
        } finally {
            transport.delivered.forEach { jdbc.update("delete from automation_consumed_events where event_id = ?", it) }
            jdbc.update("delete from hof_accounts where id = ?", accountId)
        }
    }

    @Test
    fun `각 모드의 낚시는 CATCH 뒤 숨은 전투를 확인하고 새 START를 반복하지 않는다`() {
        assertEquals(mode, properties.mode)
        clock.current = Instant.parse("2026-09-08T00:00:00Z")
        transport.delivered.clear()
        val requests = mutableListOf<HofRequest>()
        val accountId = TransactionTemplate(transactions).execute {
            val account = HofAccountEntity(loginId = "fishing-mode-${UUID.randomUUID()}", encryptedPassword = "test", createdAt = clock.now())
            entityManager.persist(account)
            entityManager.persist(AutomationEntryEntity(account = account, type = AutomationType.FISHING, priority = 0,
                enabled = true, createdAt = clock.now(), updatedAt = clock.now()))
            entityManager.persist(HofCookieEntity(account = account, name = "PHPSESSID", value = "test-session", updatedAt = clock.now()))
            entityManager.persist(HofStatusSnapshotEntity(account = account, playerName = "테스트", funds = 1,
                timeCurrent = 100, timeMax = 100, work = "", auction = "", observedAt = clock.now()))
            entityManager.persist(TypedAutomationRuntimeStateEntity(accountId = account.id, account = account,
                lifecycleStatus = TypedAutomationLifecycle.RUNNING, createdAt = clock.now(), updatedAt = clock.now()))
            account.id
        }
        fun fixture(name: String) = requireNotNull(javaClass.getResource("/fixtures/town/fishing/$name.html")).readText()
        var battle = false
        try {
            Mockito.`when`(preflight.ensureReady(accountId)).thenReturn(AutomationDailyPreflight.Result.Ready)
            Mockito.`when`(authorization.isExecutionAllowed(accountId)).thenReturn(true)
            Mockito.doAnswer { invocation ->
                val request = invocation.arguments[1] as HofRequest
                requests += request
                val body = when {
                    "FStart" in request.formFields -> fixture("waiting")
                    "FCatch" in request.formFields -> {
                        battle = true
                        fixture("caught").substringBefore("<form") + fixture("monster")
                    }
                    request.url.contains("menu=fishing") -> fixture("reset")
                    else -> """<div id='contents'><a href='?common=0001'>일반 맵</a>
                        ${if (battle) "<a href='?common=Fish03'>Fishing- 악어</a>" else ""}</div>
                        <div id='foot'><h5>Copy Right sanitized</h5><h6>H.O.F Korean Ver sanitized</h6><img src='zerohof.gif'></div>"""
                }
                HofHttpResponse(200, request.url, "<div id='menu2'>Funds : $ 1 Time : 100/100</div>" + body, emptyMap())
            }.`when`(gateway).execute(Mockito.eq(accountId), Mockito.any<HofRequest>()
                ?: HofRequest(HofHttpMethod.GET, "https://example.test"), Mockito.anyMap())
            wakeups.wake(accountId, "FISHING_MODE_CONTINUITY")
            publisher.publishBatch()
            assertEquals(listOf(HofHttpMethod.GET, HofHttpMethod.GET, HofHttpMethod.POST, HofHttpMethod.POST), requests.map { it.method })
            repeat(2) {
                clock.current = assertNotNull(jdbc.queryForObject(
                    "select min(available_at) from automation_outbox where account_id = ? and published_at is null",
                    java.time.OffsetDateTime::class.java, accountId)).toInstant()
                publisher.publishBatch()
            }
            assertEquals(1, requests.count { "FStart" in it.formFields })
            assertEquals(1, requests.count { "FCatch" in it.formFields })
            assertEquals(2, requests.count { it.method == HofHttpMethod.POST })
            val history = journal.page(accountId, AutomationHistoryQuery())
            assertTrue(history.cycles.any { cycle -> cycle.events.any { it.reasonCode == "FISHING_PRESET_MISSING" } }, history.toString())
            assertEquals(0, jdbc.queryForObject("select count(*) from automation_work_sessions where account_id = ? and status = 'RUNNING'", Int::class.java, accountId))
            assertTrue(transport.delivered.all { outbox.consumed(it) })
        } finally {
            transport.delivered.forEach { jdbc.update("delete from automation_consumed_events where event_id = ?", it) }
            jdbc.update("delete from hof_accounts where id = ?", accountId)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `레이드 시작의 같은 READY는 유한하게 확인하고 다른 항목과 다음 판단을 이어간다`(externalAdvance: Boolean) {
        assertEquals(mode, properties.mode)
        clock.current = Instant.parse("2026-09-10T01:00:00Z")
        transport.delivered.clear()
        val requests = mutableListOf<HofRequest>()
        val raidUrl = "https://hof.zerosic.com/index.php?menu=raidpub"
        val homeUrl = "https://hof.zerosic.com/index.php?menu=housing"
        val ready = requireNotNull(javaClass.getResource("/fixtures/town/raid/raidpub.html")).readText()
            .replace("Funds : $ 1,000", "Funds : $ 1,000 Time : 100/100")
            .replace("현재 상태는 신청 대기 (신청 가능까지 6분 58초)", "현재 상태는 신청 가능")
            .replace("현재 상태 : 모집 중", "현재 상태 : 출발 가능")
            .replace("name=\"register_goblin\" value=\"등록한다\"", "name=\"start_goblin\" value=\"전투를 시작한다\"")
        val noBattle = requireNotNull(javaClass.getResource("/fixtures/raid/raid-complete-absent.html")).readText()
        var homeAccepted = false
        var externallyStarted = false
        fun homePage(): String {
            val heading = if (homeAccepted) "진행중인 작업 목록" else "수락 가능한 작업 목록"
            val action = if (homeAccepted) "-" else "<a href='?menu=housing&amp;action=get&amp;no=A'>수락</a>"
            return """<div id='menu2'>Funds : $ 1 Time : 100/100</div><h4>$heading</h4><table>
                <tr><td>[A] 독립 자택</td><td>미션 0/1</td><td>-</td><td>-</td><td>$action</td></tr></table>"""
        }
        val homeQuest = HomePageParser().parse(HomeMode.HOME, homePage(), homeUrl, HofFormParser().parse(homePage(), homeUrl)).quests.single()
        val accountId = TransactionTemplate(transactions).execute {
            val account = HofAccountEntity(loginId = "raid-ready-mode-${UUID.randomUUID()}", encryptedPassword = "test", createdAt = clock.now())
            entityManager.persist(account)
            val raidEntry = AutomationEntryEntity(account = account, type = AutomationType.RAID, priority = 0,
                enabled = true, createdAt = clock.now(), updatedAt = clock.now())
            entityManager.persist(raidEntry)
            entityManager.persist(RaidAutomationTargetEntity(entry = raidEntry, raidId = "RaidGoblin", displayName = "고블린 전투 마차",
                presetMode = PresetSelectionMode.PRIMARY, executionOrder = 0))
            entityManager.persist(RaidAutomationCycleEntity(account = account, entry = raidEntry, raidId = "RaidGoblin",
                raidName = "고블린 전투 마차", status = RaidAutomationCycleStatus.REGISTERED_WAITING,
                lastObservedStatus = "출발 가능", startedAt = clock.now(), updatedAt = clock.now()))
            val homeEntry = AutomationEntryEntity(account = account, type = AutomationType.HOME_QUEST, priority = 1,
                enabled = true, createdAt = clock.now(), updatedAt = clock.now())
            entityManager.persist(homeEntry)
            entityManager.persist(HomeQuestAutomationSelectionEntity(entry = homeEntry, questId = homeQuest.id,
                questName = homeQuest.name, enabled = true, sourceOrder = 0))
            entityManager.persist(HofCookieEntity(account = account, name = "PHPSESSID", value = "fixture", updatedAt = clock.now()))
            entityManager.persist(HofStatusSnapshotEntity(account = account, playerName = "테스트", funds = 1,
                timeCurrent = 100, timeMax = 100, work = "", auction = "", observedAt = clock.now()))
            entityManager.persist(TypedAutomationRuntimeStateEntity(accountId = account.id, account = account,
                lifecycleStatus = TypedAutomationLifecycle.RUNNING, createdAt = clock.now(), updatedAt = clock.now()))
            account.id
        }
        try {
            Mockito.`when`(preflight.ensureReady(accountId)).thenReturn(AutomationDailyPreflight.Result.Ready)
            Mockito.`when`(authorization.isExecutionAllowed(accountId)).thenReturn(true)
            Mockito.doAnswer { invocation ->
                val request = invocation.getArgument<HofRequest>(1)
                requests += request
                if (request.formFields["action"] == "get") homeAccepted = true
                when {
                    request.url.contains("raid_hunt") -> HofHttpResponse(200, "https://hof.zerosic.com/index.php?raid_hunt", noBattle, emptyMap())
                    request.url.contains("raidpub") || request.formFields.containsKey("start_goblin") -> HofHttpResponse(200, raidUrl,
                        if (externallyStarted) ready.replace("현재 상태 : 출발 가능", "현재 상태 : 전투 중") else ready, emptyMap())
                    else -> HofHttpResponse(200, homeUrl, homePage(), emptyMap())
                }
            }.`when`(gateway).execute(Mockito.eq(accountId), Mockito.any<HofRequest>()
                ?: HofRequest(HofHttpMethod.GET, "https://example.test"), Mockito.anyMap())
            fun startCount() = requests.count { it.method == HofHttpMethod.POST && it.formFields.containsKey("start_goblin") }
            fun convergenceResults() = jdbc.queryForList("select c.result from automation_action_convergences c join automation_action_attempts a on a.id = c.attempt_id where a.account_id = ? and a.action_kind = 'RAID_START' order by c.id", String::class.java, accountId)

            wakeups.wake(accountId, "RAID_SAME_READY")
            publisher.publishBatch()
            assertEquals(1, startCount(), requests.map { it.url to it.formFields.keys }.toString())
            assertEquals(listOf(if (mode == AutomationConvergenceMode.ACTIVE) "AMBIGUOUS" else "RECONCILING"),
                jdbc.queryForList("select status from typed_automation_action_runs where account_id = ? order by id", String::class.java, accountId))
            assertEquals(if (mode == AutomationConvergenceMode.ACTIVE) listOf("PENDING") else emptyList(), convergenceResults())
            if (mode == AutomationConvergenceMode.SHADOW) assertEquals(listOf("PENDING"), jdbc.queryForList(
                "select new_result from automation_convergence_shadow_evaluations where account_id = ? and action_kind = 'RAID_START' order by created_at", String::class.java, accountId))

            if (externalAdvance) externallyStarted = true
            clock.current = clock.now().plusSeconds(if (externalAdvance) 31 else 121)
            repeat(12) {
                clock.current = maxOf(clock.now(), assertNotNull(jdbc.queryForObject(
                    "select min(available_at) from automation_outbox where account_id = ? and published_at is null",
                    java.time.OffsetDateTime::class.java, accountId)).toInstant())
                publisher.publishBatch()
            }
            assertEquals(1, startCount(), "$mode 같은 READY를 다시 시작하면 안 된다.")
            assertEquals(when {
                !externalAdvance -> listOf("HELD")
                mode == AutomationConvergenceMode.ACTIVE -> listOf("SUPERSEDED")
                else -> emptyList()
            }, convergenceResults(), "$mode 동일 상태의 예산 종료와 외부 진전을 구분해야 한다.")
            if (!externalAdvance) assertEquals(1, jdbc.queryForObject(
                "select count(*) from automation_action_convergences where account_id = ? and result = 'HELD' and suppression_released_at is null",
                Int::class.java, accountId))
            if (externalAdvance) {
                assertEquals("IN_BATTLE", jdbc.queryForObject(
                    "select status from raid_automation_cycles where account_id = ? and open_marker = 1", String::class.java, accountId))
                if (mode == AutomationConvergenceMode.SHADOW) assertEquals("SUPERSEDED", jdbc.queryForList(
                    "select new_result from automation_convergence_shadow_evaluations where account_id = ? and action_kind = 'RAID_START' order by created_at",
                    String::class.java, accountId).last())
            }
            assertTrue(homeAccepted, "$mode 결과 확인 중에도 독립 자택을 실행해야 한다.")
            assertTrue(journal.page(accountId, AutomationHistoryQuery()).cycles.size >= 3)
            assertTrue(transport.delivered.all { outbox.consumed(it) })
            assertEquals(0, jdbc.queryForObject("select count(*) from automation_work_sessions where account_id = ? and running_slot is not null", Int::class.java, accountId))
            assertEquals(0, jdbc.queryForObject("select count(*) from account_automation_leases where account_id = ?", Int::class.java, accountId))
        } finally {
            transport.delivered.forEach { jdbc.update("delete from automation_consumed_events where event_id = ?", it) }
            jdbc.update("delete from hof_accounts where id = ?", accountId)
        }
    }

    @Test
    fun `자택의 UNKNOWN 직접 응답도 사후 상태로 수락과 보상을 닫고 다음 판단을 소비한다`() {
        assertEquals(mode, properties.mode)
        clock.current = Instant.parse("2026-09-10T00:00:00Z")
        transport.delivered.clear()
        val requests = mutableListOf<HofRequest>()
        val url = "https://hof.zerosic.com/index.php?menu=housing"
        var stage = 0
        fun page(): String {
            val heading = when (stage) { 0 -> "수락 가능한 퀘스트"; 1 -> "완료 가능한 퀘스트"; else -> "대기 중인 퀘스트" }
            val link = when (stage) {
                0 -> "<a href='?menu=housing&amp;action=get&amp;no=A'>수락</a>"
                1 -> "<a href='?menu=housing&amp;action=complete&amp;no=A'>보상 수령</a>"
                else -> "-"
            }
            return """<div id="menu2">Funds : $ 1 Time : 100/100</div><h4>$heading</h4><table>
                <tr><td>[A] 전환 검증</td><td>미션 1/1</td><td>-</td><td>-</td><td>$link</td></tr></table>"""
        }
        val quest = HomePageParser().parse(HomeMode.HOME, page(), url, HofFormParser().parse(page(), url)).quests.single()
        val accountId = TransactionTemplate(transactions).execute {
            val account = HofAccountEntity(loginId = "home-mode-${UUID.randomUUID()}", encryptedPassword = "test", createdAt = clock.now())
            entityManager.persist(account)
            val entry = AutomationEntryEntity(account = account, type = AutomationType.HOME_QUEST, priority = 0,
                enabled = true, createdAt = clock.now(), updatedAt = clock.now())
            entityManager.persist(entry)
            entityManager.persist(HomeQuestAutomationSelectionEntity(entry = entry, questId = quest.id,
                questName = quest.name, enabled = true, sourceOrder = 0))
            entityManager.persist(HofCookieEntity(account = account, name = "PHPSESSID", value = "fixture", updatedAt = clock.now()))
            entityManager.persist(HofStatusSnapshotEntity(account = account, playerName = "테스트", funds = 1,
                timeCurrent = 100, timeMax = 100, work = "", auction = "", observedAt = clock.now()))
            entityManager.persist(TypedAutomationRuntimeStateEntity(accountId = account.id, account = account,
                lifecycleStatus = TypedAutomationLifecycle.RUNNING, createdAt = clock.now(), updatedAt = clock.now()))
            account.id
        }
        try {
            Mockito.`when`(preflight.ensureReady(accountId)).thenReturn(AutomationDailyPreflight.Result.Ready)
            Mockito.`when`(authorization.isExecutionAllowed(accountId)).thenReturn(true)
            Mockito.doAnswer { invocation ->
                val request = invocation.getArgument<HofRequest>(1)
                requests += request
                when (request.formFields["action"]) { "get" -> stage = 1; "complete" -> stage = 2 }
                val body = page()
                assertEquals("UNKNOWN", app.spammy.hof.town.fishing.dto.TownActionResultResponse.from(
                    app.spammy.hof.town.common.parser.HofResultParser().parse(body)).status)
                HofHttpResponse(200, url, body, emptyMap())
            }.`when`(gateway).execute(Mockito.eq(accountId), Mockito.any<HofRequest>()
                ?: HofRequest(HofHttpMethod.GET, "https://example.test"), Mockito.anyMap())

            wakeups.wake(accountId, "HOME_UNKNOWN_POSTSTATE")
            publisher.publishBatch()
            assertEquals(listOf("SUCCEEDED"), jdbc.queryForList(
                "select status from typed_automation_action_runs where account_id = ? order by id", String::class.java, accountId))
            repeat(2) {
                clock.current = assertNotNull(jdbc.queryForObject(
                    "select min(available_at) from automation_outbox where account_id = ? and published_at is null",
                    java.time.OffsetDateTime::class.java, accountId)).toInstant()
                publisher.publishBatch()
            }

            assertEquals(listOf("get", "complete"), requests.mapNotNull { it.formFields["action"] })
            assertEquals(listOf("SUCCEEDED", "SUCCEEDED"), jdbc.queryForList(
                "select status from typed_automation_action_runs where account_id = ? order by id", String::class.java, accountId))
            assertTrue(journal.page(accountId, AutomationHistoryQuery()).cycles.size >= 3)
            assertTrue(transport.delivered.all { outbox.consumed(it) })
            assertEquals(0, jdbc.queryForObject("select count(*) from automation_work_sessions where account_id = ? and running_slot is not null", Int::class.java, accountId))
            assertEquals(0, jdbc.queryForObject("select count(*) from account_automation_leases where account_id = ?", Int::class.java, accountId))
            val applied = jdbc.queryForList("select result from automation_action_convergences where account_id = ? order by id", String::class.java, accountId)
            val shadow = jdbc.queryForList("select new_result from automation_convergence_shadow_evaluations where account_id = ? order by created_at", String::class.java, accountId)
            assertEquals(if (mode == AutomationConvergenceMode.ACTIVE) listOf("APPLIED", "APPLIED") else emptyList(), applied)
            assertEquals(if (mode == AutomationConvergenceMode.SHADOW) listOf("APPLIED", "APPLIED") else emptyList(), shadow)
        } finally {
            transport.delivered.forEach { jdbc.update("delete from automation_consumed_events where event_id = ?", it) }
            jdbc.update("delete from hof_accounts where id = ?", accountId)
        }
    }

    @Test
    fun `동기화 복구 뒤 같은 자택 응답은 한 번 제출하고 후속 판단에서 진행 대기로 양보한다`() {
        assertEquals(mode, properties.mode)
        clock.current = Instant.parse("2026-09-06T00:00:00Z")
        transport.delivered.clear()
        val requests = mutableListOf<HofRequest>()
        val url = "https://hof.zerosic.com/index.php?menu=housing"
        var accepted = false
        fun page() = """<div id="menu2">Funds : $ 1 Time : 100/100</div><h4>수락 가능한 퀘스트</h4><table>
            <tr><td>[A] 모드 기준</td><td>미션 0/1</td><td>-</td><td>-</td><td>${if (accepted) "-" else "<a href='?menu=housing&amp;action=get&amp;no=A'>수락</a>"}</td></tr></table>"""
        val quest = HomePageParser().parse(HomeMode.HOME, page(), url, HofFormParser().parse(page(), url)).quests.single()
        lateinit var characterJob: app.spammy.hof.character.entity.CharacterOperationJobEntity
        val accountId = TransactionTemplate(transactions).execute {
            val account = HofAccountEntity(loginId = "mode-${UUID.randomUUID()}", encryptedPassword = "test", createdAt = clock.now())
            entityManager.persist(account)
            entityManager.flush()
            val entry = AutomationEntryEntity(account = account, type = AutomationType.HOME_QUEST, priority = 0,
                enabled = true, createdAt = clock.now(), updatedAt = clock.now())
            entityManager.persist(entry)
            entityManager.persist(HomeQuestAutomationSelectionEntity(entry = entry, questId = quest.id,
                questName = quest.name, enabled = true, sourceOrder = 0))
            entityManager.persist(HofCookieEntity(account = account, name = "PHPSESSID", value = "test-session", updatedAt = clock.now()))
            entityManager.persist(HofStatusSnapshotEntity(account = account, playerName = "테스트", funds = 1,
                timeCurrent = 100, timeMax = 100, work = "", auction = "", observedAt = clock.now()))
            entityManager.persist(TypedAutomationRuntimeStateEntity(accountId = account.id, account = account,
                lifecycleStatus = TypedAutomationLifecycle.RUNNING, createdAt = clock.now(), updatedAt = clock.now()))
            val character = app.spammy.hof.character.entity.CharacterEntity(account = account, hofCharacterId = "mode-character",
                name = "fixture", job = "Knight", updatedAt = clock.now())
            entityManager.persist(character)
            characterJob = app.spammy.hof.character.entity.CharacterOperationJobEntity(account = account,
                operationType = app.spammy.hof.character.entity.CharacterOperationType.DEEP_SYNC, targetCharacterId = character.id,
                recoveryStatus = app.spammy.hof.character.entity.CharacterRecoveryStatus.NOT_STARTED,
                startedAt = clock.now(), updatedAt = clock.now())
            entityManager.persist(characterJob)
            account.id
        }
        try {
            Mockito.`when`(preflight.ensureReady(accountId)).thenReturn(AutomationDailyPreflight.Result.Ready)
            Mockito.`when`(authorization.isExecutionAllowed(accountId)).thenReturn(true)
            Mockito.doAnswer { invocation ->
                val request = invocation.arguments[1] as HofRequest
                requests += request
                if (request.formFields["action"] == "get") accepted = true
                HofHttpResponse(200, url, page() + if (accepted) "<div id='result'>수락했습니다.</div>" else "", emptyMap())
            }.`when`(gateway).execute(Mockito.eq(accountId), Mockito.any<HofRequest>()
                ?: HofRequest(HofHttpMethod.GET, "https://example.test"), Mockito.anyMap())

            val original = app.spammy.hof.character.service.CharacterRestoreState("mode-character",
                listOf(app.spammy.hof.external.model.HofActionPatternRow(0, judge = "0", quantity = "0", skill = "0")), emptyList(), "front", "0")
            characterGate.executeJob(accountId, characterJob.id, { error("동기화 일시정지 실패") }) {
                characterRecovery.save(characterJob.id, accountId, characterJob.targetCharacterId,
                    app.spammy.hof.character.service.CharacterDeepSyncCheckpoint(original))
                wakeups.wake(accountId, "MODE_CHARACTER_RECOVERY_PENDING")
                publisher.publishBatch()
                assertTrue(requests.isEmpty(), "$mode 미복원 중 새 HOF 행동을 실행하면 안 된다.")
                characterRecovery.save(characterJob.id, accountId, characterJob.targetCharacterId,
                    app.spammy.hof.character.service.CharacterDeepSyncCheckpoint(original,
                        status = app.spammy.hof.character.entity.CharacterRecoveryStatus.RESTORED, collectionComplete = true))
            }
            publisher.publishBatch()
            val first = journal.page(accountId, AutomationHistoryQuery()).cycles.single()
            assertEquals(1, requests.count { it.formFields["action"] == "get" },
                first.toString() + requests.map { it.method to it.url })
            val next = assertNotNull(jdbc.queryForObject(
                "select min(available_at) from automation_outbox where account_id = ? and published_at is null",
                java.time.OffsetDateTime::class.java, accountId)).toInstant()
            clock.current = next
            publisher.publishBatch()

            assertTrue(transport.delivered.size >= 2)
            assertTrue(transport.delivered.all { outbox.consumed(it) })
            val cycles = journal.page(accountId, AutomationHistoryQuery()).cycles
            assertEquals(2, cycles.size)
            assertNotEquals(first.id, cycles.first().id)
            assertEquals(1, requests.count { it.formFields["action"] == "get" })
            // Home acceptance is a mutating GET; count the exact action query, not POST.
            assertEquals(HofHttpMethod.GET, requests.single { it.formFields["action"] == "get" }.method)
            assertEquals("A", requests.single { it.formFields["action"] == "get" }.formFields["no"])
            assertEquals(listOf("SUCCEEDED"), jdbc.queryForList(
                "select status from typed_automation_action_runs where account_id = ?", String::class.java, accountId))
            assertEquals(listOf("WAITING_COOLDOWN"), jdbc.queryForList(
                "select status from automation_work_sessions where account_id = ?", String::class.java, accountId))
            assertEquals(0, jdbc.queryForObject("select count(*) from account_automation_leases where account_id = ?", Int::class.java, accountId))
        } finally {
            transport.delivered.forEach { jdbc.update("delete from automation_consumed_events where event_id = ?", it) }
            jdbc.update("delete from hof_accounts where id = ?", accountId)
        }
    }
}
