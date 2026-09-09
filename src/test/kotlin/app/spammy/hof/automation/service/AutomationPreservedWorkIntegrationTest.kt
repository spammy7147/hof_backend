package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.entity.HofCookieEntity
import app.spammy.hof.auth.service.AccountExecutionAuthorizationReader
import app.spammy.hof.automation.dto.QuestSelectionRequest
import app.spammy.hof.automation.dto.UpdateQuestAutomationRequest
import app.spammy.hof.automation.entity.*
import app.spammy.hof.automation.history.AutomationDecisionJournal
import app.spammy.hof.automation.history.AutomationHistoryEventKind
import app.spammy.hof.automation.history.AutomationHistoryQuery
import app.spammy.hof.automation.outbox.AutomationOutboxPublisher
import app.spammy.hof.automation.outbox.AutomationOutboxQueryRepository
import app.spammy.hof.automation.outbox.AutomationOutboxService
import app.spammy.hof.automation.repository.AutomationWorkSessionQueryRepository
import app.spammy.hof.external.client.HofGateway
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.quest.parser.QuestPageParser
import app.spammy.hof.status.entity.HofStatusSnapshotEntity
import jakarta.persistence.EntityManager
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@ActiveProfiles("test")
@Import(AutomationRecoveryIntegrationTest.Config::class)
abstract class AutomationPreservedWorkIntegrationTest {
    @Autowired private lateinit var entityManager: EntityManager
    @Autowired private lateinit var transactions: PlatformTransactionManager
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var clock: AutomationRecoveryIntegrationTest.RecoveryClock
    @Autowired private lateinit var publisher: AutomationOutboxPublisher
    @Autowired private lateinit var outbox: AutomationOutboxQueryRepository
    @Autowired private lateinit var transport: AutomationRecoveryIntegrationTest.ConsumerReplayTransport
    @Autowired private lateinit var application: UnifiedAutomationService
    @Autowired private lateinit var journal: AutomationDecisionJournal
    @Autowired private lateinit var work: AutomationWorkSessionQueryRepository
    @MockitoBean private lateinit var gateway: HofGateway
    @MockitoBean private lateinit var preflight: AutomationDailyPreflight
    @MockitoBean private lateinit var authorization: AccountExecutionAuthorizationReader
    private var accountId = 0L
    private var entryId = 0L
    private var workId = 0L
    @MockitoSpyBean private lateinit var selector: AutomationTargetSelector
    @MockitoSpyBean private lateinit var actionLifecycle: UnifiedAutomationActionLifecycleModule
    @MockitoSpyBean private lateinit var ownership: AutomationWorkSessionService
    private var includeB = false
    private var claimedB = false
    private var claimed = false
    private val requests = mutableListOf<HofRequest>()
    private lateinit var selection: QuestSelectionRequest
    private val questUrl = "https://hof.zerosic.com/index.php?menu=quest"

    @BeforeEach
    fun prepareAccount() {
        transport.delivered.clear()
        clock.current = Instant.parse("2026-09-09T03:10:00Z")
        val observed = QuestPageParser().parseObservation(page(), questUrl)
        assertTrue(observed.complete)
        val quest = observed.quests.single { it.actionNo == "claim-a" }
        selection = QuestSelectionRequest(quest.questKey, quest.displayCode, quest.name, true, 0, emptyList())
        TransactionTemplate(transactions).executeWithoutResult {
            val account = HofAccountEntity(loginId = "preserved-work-${UUID.randomUUID()}", encryptedPassword = "fixture", createdAt = clock.now())
            entityManager.persist(account)
            entityManager.flush()
            accountId = account.id
            val old = clock.now().minusSeconds(3600)
            val entry = AutomationEntryEntity(account = account, type = AutomationType.QUEST, priority = 0,
                enabled = true, createdAt = old, updatedAt = old)
            entityManager.persist(entry)
            entityManager.flush()
            entryId = entry.id
            entityManager.persist(QuestAutomationSelectionEntity(entry = entry, questKey = quest.questKey,
                enabled = true, sourceOrder = 0, displayCode = quest.displayCode, questName = quest.name))
            val session = AutomationWorkSessionEntity(account = account, entry = entry,
                workType = AutomationWorkType.QUEST, targetKey = quest.questKey,
                status = AutomationWorkStatus.WAITING_RESOURCE, configVersion = old.toString(),
                confirmedCount = 3, nextCheckAt = old.plusSeconds(60), createdAt = old, updatedAt = old)
            entityManager.persist(session)
            entityManager.flush()
            workId = session.id
            entityManager.persist(HofCookieEntity(account = account, name = "PHPSESSID", value = "fixture", updatedAt = clock.now()))
            entityManager.persist(HofStatusSnapshotEntity(account = account, playerName = "테스트", funds = 1,
                timeCurrent = 100, timeMax = 200, work = "", auction = "", observedAt = clock.now()))
            entityManager.persist(TypedAutomationRuntimeStateEntity(accountId = accountId, account = account,
                lifecycleStatus = TypedAutomationLifecycle.RUNNING, createdAt = clock.now(), updatedAt = clock.now()))
        }
        Mockito.`when`(preflight.ensureReady(accountId)).thenReturn(AutomationDailyPreflight.Result.Ready)
        Mockito.`when`(authorization.isExecutionAllowed(accountId)).thenReturn(true)
        Mockito.doAnswer { invocation ->
            val request = invocation.getArgument<HofRequest>(1)
            requests += request
            assertTrue(request.url.startsWith(questUrl), "설정 대상의 퀘스트 관측과 행동만 실행한다.")
            if (request.formFields["action"] == "complete") {
                if (request.formFields["no"] == "claim-a") claimed = true
                if (request.formFields["no"] == "claim-b") claimedB = true
            }
            HofHttpResponse(200, request.url, page(), emptyMap())
        }.`when`(gateway).execute(Mockito.eq(accountId), anyRequest(), Mockito.anyMap())
    }

    @AfterEach
    fun removeAccount() {
        transport.delivered.forEach { jdbc.update("delete from automation_consumed_events where event_id = ?", it) }
        if (accountId != 0L) jdbc.update("delete from hof_accounts where id = ?", accountId)
    }

    @ParameterizedTest
    @ValueSource(strings = ["same", "other-target", "reorder"])
    fun `관계없는 설정 변경 뒤 대기 퀘스트의 보상을 한 번 수령하고 다음 판단까지 진행한다`(change: String) {
        when (change) {
            "reorder" -> application.reorderEntries(accountId, app.spammy.hof.automation.dto.ReorderAutomationEntriesRequest(listOf(entryId)))
            "other-target" -> {
                includeB = true
                application.updateQuest(accountId, UpdateQuestAutomationRequest(true, listOf(selection, otherSelection().copy(enabled = false))))
            }
            else -> application.updateQuest(accountId, UpdateQuestAutomationRequest(true, listOf(selection)))
        }
        assertEquals(AutomationWorkStatus.WAITING_RESOURCE, savedWork().first)

        consumeUntil { claimRequests().isNotEmpty() }

        assertEquals(1, claimRequests().size, "보존한 대기 작업이 최신 보상 수령 행동을 실제로 제출해야 한다.")
        assertNull(application.getTyped(accountId).runtime.lastError)
        assertTrue(journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }
            .any { it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED && it.actionKind == "QUEST_CLAIM" })
        val previousCycles = journal.page(accountId, AutomationHistoryQuery()).cycles.map { it.id }.toSet()

        consumeUntil { savedWork().first == AutomationWorkStatus.COMPLETED }

        assertTrue(journal.page(accountId, AutomationHistoryQuery()).cycles.any { it.id !in previousCycles })
        assertEquals(1, claimRequests().size)
        assertEquals(AutomationWorkStatus.COMPLETED to 3, savedWork())
        assertNull(work.findRunning(accountId))
    }

    @ParameterizedTest
    @ValueSource(strings = ["BUILD", "VALIDATE", "QUEST_BATTLE"])
    fun `준비 오류는 해당 후보만 보류하고 다른 후보와 후속 판단을 진행한다`(stage: String) {
        includeB = true
        application.updateQuest(accountId, UpdateQuestAutomationRequest(true, listOf(selection, otherSelection())))
        var failA = true
        Mockito.doAnswer { invocation ->
            val decision = invocation.callRealMethod() as AutomationCoordination
            if (stage == "QUEST_BATTLE" && failA && decision is AutomationCoordination.Runnable &&
                (decision.action as? QuestAction)?.questKey == selection.questKey) {
                decision.copy(action = QuestAction.Battle(selection.questKey, "cycle", "mission",
                    app.spammy.hof.quest.model.QuestMissionType.MONSTER_KILL, "battle_map", "failed-map", "표시용 맵",
                    QuestPresetSelection(PresetSelectionMode.PRIMARY)))
            } else decision
        }.`when`(selector).select(accountId)
        Mockito.doAnswer { invocation ->
            val assignment = invocation.getArgument<AutomationWorkAssignment>(2)
            if (stage == "BUILD" && assignment.targetKey == selection.questKey && failA) error("synthetic preparation failure")
            invocation.callRealMethod()
        }.`when`(ownership).ensure(Mockito.eq(accountId), Mockito.eq(entryId), anyAssignment())

        Mockito.doAnswer { invocation ->
            val managed = invocation.callRealMethod() as ManagedAutomationAction
            val action = invocation.getArgument<PreparedAutomationAction>(2)
            object : ManagedAutomationAction by managed {
                override fun validateBeforeSubmission() {
                    if (stage == "VALIDATE" && action is QuestAction && action.questKey == selection.questKey && failA) {
                        error("synthetic validation failure")
                    }
                    managed.validateBeforeSubmission()
                }
            }
        }.`when`(actionLifecycle).prepare(Mockito.eq(accountId), Mockito.eq(entryId), anyAction())
        consumeNextWake()
        assertTrue(claimRequests().isEmpty(), "준비 실패 전에 원격 보상 수령 요청을 보내면 안 된다.")
        val failure = journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }
            .single { it.reasonCode == "ACTION_PREPARATION_FAILED" }
        assertEquals(AutomationHistoryEventKind.ACTION_FAILED, failure.kind)
        assertEquals(selection.questKey, failure.targetKey)
        assertTrue(failure.message.contains("전송하지 않았습니다"))
        assertNotNull(failure.nextRunAt)
        assertNull(application.getTyped(accountId).runtime.lastError)
        consumeUntil { claimedB }
        assertFalse(claimed)
        assertTrue(clock.now().isBefore(failure.nextRunAt))
        assertEquals(1, claimRequests().size)

        clock.current = failure.nextRunAt!!.plusSeconds(1)
        assertEquals(1, application.getTyped(accountId).runtime.preparationFailures.size,
            "재판단 시각만 지났고 아직 준비하지 않았다면 현재 실패를 유지해야 한다.")
        failA = false
        consumeUntil { claimed }
        assertEquals(2, claimRequests().size)
        consumeUntil { savedWork().first == AutomationWorkStatus.COMPLETED }
        assertTrue(journal.page(accountId, AutomationHistoryQuery(limit = 100)).cycles.flatMap { it.events }
            .any { it.id == failure.id })
        assertTrue(application.getTyped(accountId).runtime.preparationFailures.isEmpty())
        assertNull(application.getTyped(accountId).runtime.lastError)
    }

    @Test
    fun `모든 후보 보류 이력 뒤에도 현재 오류 조회와 다음 재판단이 유지된다`() {
        application.updateQuest(accountId, UpdateQuestAutomationRequest(true, listOf(selection)))
        var fail = true
        Mockito.doAnswer { invocation ->
            if (fail) error("synthetic preparation failure")
            invocation.callRealMethod()
        }.`when`(ownership).ensure(Mockito.eq(accountId), Mockito.eq(entryId), anyAssignment())

        consumeNextWake()
        val failure = application.getTyped(accountId).runtime.preparationFailures.single()
        consumeUntil {
            journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }.any {
                it.reasonCode == "ACTION_PREPARATION_FAILED" &&
                    it.kind in setOf(AutomationHistoryEventKind.SKIPPED, AutomationHistoryEventKind.WAITING)
            }
        }
        assertEquals(listOf(failure), application.getTyped(accountId).runtime.preparationFailures)
        assertTrue(claimRequests().isEmpty())

        fail = false
        clock.current = failure.retryAt.plusSeconds(1)
        consumeUntil { claimed }
        assertEquals(1, claimRequests().size)
        assertTrue(application.getTyped(accountId).runtime.preparationFailures.isEmpty())
        consumeUntil { savedWork().first == AutomationWorkStatus.COMPLETED }
    }

    @ParameterizedTest
    @ValueSource(strings = ["remove", "disable"])
    fun `변경한 대상은 종료하고 이전 보상을 제출하지 않는다`(change: String) {
        application.updateQuest(accountId, UpdateQuestAutomationRequest(true,
            if (change == "remove") emptyList() else listOf(selection.copy(enabled = false))))
        assertEquals(AutomationWorkStatus.STOPPED, savedWork().first)
        consumeNextWake()
        assertTrue(claimRequests().isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["prepare", "submit"])
    fun `준비나 제출 직전 비활성화하면 이전 행동은 전송하지 않는다`(stage: String) {
        application.updateQuest(accountId, UpdateQuestAutomationRequest(true, listOf(selection)))
        Mockito.doAnswer { invocation ->
            val managed = invocation.callRealMethod() as ManagedAutomationAction
            fun disable() { application.updateQuest(accountId, UpdateQuestAutomationRequest(false, listOf(selection))) }
            if (stage == "prepare") disable()
            object : ManagedAutomationAction by managed {
                override fun validateBeforeSubmission() {
                    managed.validateBeforeSubmission()
                    if (stage == "submit") disable()
                }
            }
        }.`when`(actionLifecycle).prepare(Mockito.eq(accountId), Mockito.eq(entryId), anyAction())
        consumeNextWake()
        assertTrue(claimRequests().isEmpty(), "설정 저장 뒤 지난 행동을 제출하면 안 된다.")
        assertEquals(AutomationWorkStatus.STOPPED, savedWork().first)
        assertNull(application.getTyped(accountId).runtime.lastError)
        consumeNextWake()
        assertTrue(claimRequests().isEmpty())
        assertNull(application.getTyped(accountId).runtime.currentAction)
    }

    private fun anyAction(): PreparedAutomationAction = Mockito.any(PreparedAutomationAction::class.java)
        ?: QuestAction.Claim("fixture", "fixture", "fixture", "0")

    private fun otherSelection(): QuestSelectionRequest {
        val quest = QuestPageParser().parseObservation(page(), questUrl).quests.single { it.actionNo == "claim-b" }
        return QuestSelectionRequest(quest.questKey, quest.displayCode, quest.name, true, 1, emptyList())
    }

    private fun savedWork(): Pair<AutomationWorkStatus, Int> = TransactionTemplate(transactions).execute {
        val session = requireNotNull(work.lockById(accountId, workId))
        session.status to session.confirmedCount
    }!!

    private fun claimRequests() = requests.filter { it.formFields["action"] == "complete" }

    private fun consumeUntil(done: () -> Boolean) {
        val deadline = clock.now().plusSeconds(120)
        repeat(64) {
            if (done()) return
            consumeNextWake()
            assertTrue(clock.now() <= deadline, "후속 판단이 120초를 넘으면 안 된다.")
        }
        assertTrue(done(), "120초 이내의 실제 wakeup 소비로 진행해야 한다. runtime=${application.getTyped(accountId).runtime}, requests=$requests, work=${savedWork()}, history=${journal.page(accountId, AutomationHistoryQuery()).cycles}")
    }

    private fun consumeNextWake() {
        val next = jdbc.queryForObject(
            "select min(available_at) from automation_outbox where account_id = ? and topic = ? and published_at is null",
            OffsetDateTime::class.java, accountId, AutomationOutboxService.WAKEUP_TOPIC)?.toInstant()
        assertNotNull(next, "후속 판단을 실제로 소비할 영속 wakeup이 있어야 한다.")
        clock.current = maxOf(clock.now(), next)
        val previous = transport.delivered.size
        publisher.publishBatch()
        assertTrue(transport.delivered.size > previous)
        assertTrue(transport.delivered.all { outbox.consumed(it) })
    }

    private fun page(): String {
        val empty = requireNotNull(javaClass.getResource("/fixtures/quest/quest-complete-empty.html")).readText()
        val rowA = if (claimed) "" else """<tr><td class="td7s">[0777] 보존할 퀘스트</td>
            <td>미션 : 즉시 완료</td><td>-</td><td>Time x10</td>
            <td><a href="?menu=quest&amp;action=complete&amp;no=claim-a">완료</a></td></tr>"""
        val rowB = if (!includeB || claimedB) "" else """<tr><td class="td7s">[0778] 독립 퀘스트</td>
            <td>미션 : 즉시 완료</td><td>-</td><td>Time x10</td>
            <td><a href="?menu=quest&amp;action=complete&amp;no=claim-b">완료</a></td></tr>"""
        return "<div id='menu2'>Funds : $ 1 Time : 100/200</div>" + empty.replaceFirst("</table>", "$rowA$rowB</table>")
    }

    private fun anyAssignment(): AutomationWorkAssignment = Mockito.any(AutomationWorkAssignment::class.java)
        ?: AutomationWorkAssignment(AutomationWorkType.QUEST, "fixture")

    private fun anyRequest(): HofRequest = Mockito.any<HofRequest>()
        ?: HofRequest(app.spammy.hof.external.model.HofHttpMethod.GET, "https://example.test")
}

@SpringBootTest(properties = ["hof.automation-convergence.mode=ACTIVE"])
class ActiveAutomationPreservedWorkIntegrationTest : AutomationPreservedWorkIntegrationTest()

@SpringBootTest(properties = ["hof.automation-convergence.mode=SHADOW"])
class ShadowAutomationPreservedWorkIntegrationTest : AutomationPreservedWorkIntegrationTest()

@SpringBootTest(properties = ["hof.automation-convergence.mode=LEGACY"])
class LegacyAutomationPreservedWorkIntegrationTest : AutomationPreservedWorkIntegrationTest()
