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
import jakarta.persistence.EntityManager
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.Test
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
