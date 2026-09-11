package app.spammy.hof.automation.history

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.AutomationEntryEntity
import app.spammy.hof.automation.raid.RaidCycleOutcome
import app.spammy.hof.automation.raid.RaidCycleOutcomeKind
import app.spammy.hof.automation.raid.RaidCooldownSource
import app.spammy.hof.automation.service.*
import app.spammy.hof.common.time.TimeProvider
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import tools.jackson.module.kotlin.jacksonObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

@DataJpaTest
@ActiveProfiles("test")
open class AutomationDecisionJournalTest {
    @Autowired private lateinit var accounts: HofAccountRepository
    @Autowired private lateinit var entityManager: EntityManager
    @Autowired private lateinit var cycleCommands: AutomationDecisionCycleCommandRepository
    @Autowired private lateinit var eventCommands: AutomationDecisionEventCommandRepository
    @Autowired private lateinit var transactions: org.springframework.transaction.PlatformTransactionManager
    private var now = Instant.parse("2026-08-12T01:00:00Z")

    @ParameterizedTest
    @ValueSource(longs = [0, 1])
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `START 이력 재전달은 CATCH 뒤에 도착해도 원래 단계 순서를 보존한다`(secondsBetween: Long) {
        val journal = journal()
        val accountId = committed { account("history-delivery-order-$secondsBetween").id }
        val entryId = committed { entry(entityManager.find(HofAccountEntity::class.java, accountId), AutomationType.FISHING, 0).id }
        val cycleId = committed {
            journal.appendDecision(accountId, AutomationCoordination.Runnable(
                entryId, FishingTownAutomationAction(accountId,
                    app.spammy.hof.town.fishing.model.FishingAction.START,
                    app.spammy.hof.town.fishing.model.FishingPrimaryAction.START, 5), emptyList(),
                listOf(AutomationEvaluationTrace(0, entryId, AutomationType.FISHING,
                    AutomationDecisionOutcome.SELECTED, "ACTION_SELECTED", "낚시 선택")),
            ))
        }
        val startAt = now
        val start = committed { journal.deferActionResult(cycleId, "start-$cycleId", AutomationActionTrace(
            AutomationHistoryEventKind.ACTION_SUCCEEDED, "FISHING_START_APPLIED", "시작 적용",
            entryId, AutomationType.FISHING, "START",
        )) }
        committed { entityManager.createNativeQuery("alter table automation_decision_events add constraint history_start_failure check (decision_cycle_id <> $cycleId or reason_code <> 'FISHING_START_APPLIED')").executeUpdate() }
        try {
            journal.publishDeferredResult(start)
        } finally {
            committed { entityManager.createNativeQuery("alter table automation_decision_events drop constraint history_start_failure").executeUpdate() }
        }
        now = now.plusSeconds(secondsBetween)
        committed { journal.appendActionResult(cycleId, AutomationActionTrace(
            AutomationHistoryEventKind.ACTION_STARTED, "CATCH_STARTED", "잡기 전송", entryId, AutomationType.FISHING, "CATCH",
        )) }
        val catchAt = now
        val catch = committed { journal.deferActionResult(cycleId, "catch-$cycleId", AutomationActionTrace(
            AutomationHistoryEventKind.ACTION_SUCCEEDED, "FISHING_CATCH_APPLIED", "잡기 적용",
            entryId, AutomationType.FISHING, "CATCH",
        )) }
        journal.publishDeferredResult(catch)
        assertEquals(listOf("FISHING_CATCH_APPLIED"), committed {
            journal.page(accountId, AutomationHistoryQuery()).cycles.single().events
                .filter { it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED }.map { it.reasonCode }
        })
        now = now.plusSeconds(11)
        journal.publishDeferredResults()
        journal.publishDeferredResult(start)
        val cycle = committed { journal.page(accountId, AutomationHistoryQuery()).cycles.single() }
        assertEquals(listOf("ACTION_SELECTED", "FISHING_START_APPLIED", "CATCH_STARTED", "FISHING_CATCH_APPLIED"), cycle.events.map { it.reasonCode })
        assertEquals(listOf(0, 1, 2, 3), cycle.events.map { it.sequence })
        assertEquals(listOf("FISHING_START_APPLIED", "CATCH_STARTED", "FISHING_CATCH_APPLIED"), cycle.steps.single().executionEvents.map { it.reasonCode })
        assertEquals(listOf(startAt, catchAt), cycle.events.filter { it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED }.map { it.occurredAt })
    }

    private fun <T : Any> committed(block: () -> T): T = requireNotNull(TransactionTemplate(transactions).execute { block() })

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `Kafka 전달 실패 중에도 로컬 이력은 복원하고 상세 문맥은 전송하지 않는다`() {
        val journal = journal()
        val clock = TimeProvider { now }
        val accountId = committed { account("history-broker-unavailable").id }
        val cycleId = committed { journal.appendDecision(accountId, AutomationCoordination.Idle(emptyList())) }
        committed { journal.deferActionResult(cycleId, "broker-result-$cycleId", AutomationActionTrace(
            AutomationHistoryEventKind.ACTION_SUCCEEDED, "HOME_APPLIED", "로컬 결과 문맥", type = AutomationType.HOME_QUEST,
        )) }
        val topic = "hof.automation.wakeup"
        committed { entityManager.persist(app.spammy.hof.automation.outbox.AutomationOutboxEntity(
            eventId = "broker-wake-$cycleId", account = entityManager.find(HofAccountEntity::class.java, accountId),
            topic = topic, eventKey = accountId.toString(), payload = "wake", createdAt = now, availableAt = now,
        )) }
        try {
            val deliveredTopics = mutableListOf<String>()
            val query = app.spammy.hof.automation.outbox.AutomationOutboxQueryRepository(com.querydsl.jpa.impl.JPAQueryFactory(entityManager))
            val transport = object : app.spammy.hof.automation.outbox.AutomationOutboxTransport {
                override val supportedTopics: Set<String>? = null
                override fun publish(row: app.spammy.hof.automation.outbox.AutomationOutboxEntity) {
                    deliveredTopics += row.topic
                    throw IllegalStateException("broker unavailable")
                }
            }
            val publisher = app.spammy.hof.automation.outbox.AutomationOutboxPublisher(query,
                app.spammy.hof.automation.outbox.AutomationOutboxPublishMarker(query, clock), transport, clock, journal)
            repeat(2) { assertFailsWith<IllegalStateException> { committed { publisher.publishBatch() } } }
            assertEquals(listOf(topic, topic), deliveredTopics)
            val events = committed { journal.page(accountId, AutomationHistoryQuery()).cycles.single().events }
            assertEquals("로컬 결과 문맥", events.single().message)
        } finally {
            // PostgreSQL의 뒤 검사도 같은 DB를 사용하므로 실패 주입 행을 남기지 않는다.
            assertEquals(1, committed {
                entityManager.createQuery("delete from AutomationOutboxEntity o where o.account.id = :accountId and o.eventId = :eventId")
                    .setParameter("accountId", accountId)
                    .setParameter("eventId", "broker-wake-$cycleId")
                    .executeUpdate()
            })
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `보존 정리로 삭제한 판단의 미전달 문맥은 지우고 현재 이력은 계속 복원한다`(expired: Boolean) {
        val journal = journal()
        val accountId = committed { account("history-retention-pending-$expired").id }
        val oldCycle = committed { journal.appendPreparedActionAttempt(accountId, AutomationActionTrace(
            AutomationHistoryEventKind.ACTION_STARTED, "OLD_STARTED", "과거 행동",
        )) }
        val old = committed { journal.deferActionResult(oldCycle, "expired-$oldCycle", AutomationActionTrace(
            AutomationHistoryEventKind.ACTION_SUCCEEDED, "OLD_APPLIED", "과거 결과",
        )) }
        now = now.plusSeconds(if (expired) 31 * 24 * 60 * 60 else 1)
        val currentCycle = committed { journal.appendPreparedActionAttempt(accountId, AutomationActionTrace(
            AutomationHistoryEventKind.ACTION_STARTED, "CURRENT_STARTED", "현재 행동",
        )) }
        committed { journal.appendActionResult(currentCycle, AutomationActionTrace(
            AutomationHistoryEventKind.WAITING, "CURRENT_WAITING", "현재 결과 저장 대기",
        )) }
        val current = committed { journal.deferActionResult(currentCycle, "retained-$currentCycle", AutomationActionTrace(
            AutomationHistoryEventKind.ACTION_SUCCEEDED, "CURRENT_APPLIED", "현재 결과",
        )) }
        committed { AutomationHistoryRetentionScheduler(entityManager, TimeProvider { now }, 30, if (expired) 50000 else 1).cleanup() }
        fun payload(id: Long) = committed { entityManager.createQuery("select o.payload from AutomationOutboxEntity o where o.id = :id", String::class.java)
            .setParameter("id", id).singleResult }
        assertEquals("", payload(old))
        assertTrue(payload(current).isNotBlank())
        journal.publishDeferredResult(old)
        journal.publishDeferredResults()
        val cycles = committed { journal.page(accountId, AutomationHistoryQuery()).cycles }
        assertEquals(listOf(currentCycle), cycles.map { it.id })
        assertEquals("CURRENT_APPLIED", cycles.single().events.single { it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED }.reasonCode)
        assertEquals("", payload(current))
    }

    @ParameterizedTest
    @ValueSource(longs = [0, 1])
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `건수 제한은 늦게 전달한 과거 결과보다 최신 판단 이력을 보존한다`(secondsBetween: Long) {
        val journal = journal()
        val accountId = committed { account("history-retention-order-$secondsBetween").id }
        val oldCycle = committed { journal.appendPreparedActionAttempt(accountId, AutomationActionTrace(
            AutomationHistoryEventKind.ACTION_STARTED, "OLD_STARTED", "과거 행동",
        )) }
        val old = committed { journal.deferActionResult(oldCycle, "old-$oldCycle", AutomationActionTrace(
            AutomationHistoryEventKind.ACTION_SUCCEEDED, "OLD_APPLIED", "과거 결과",
        )) }
        now = now.plusSeconds(secondsBetween)
        val currentCycle = committed { journal.appendPreparedActionAttempt(accountId, AutomationActionTrace(
            AutomationHistoryEventKind.ACTION_STARTED, "CURRENT_STARTED", "현재 행동",
        )) }
        val current = committed { journal.deferActionResult(currentCycle, "current-$currentCycle", AutomationActionTrace(
            AutomationHistoryEventKind.ACTION_SUCCEEDED, "CURRENT_APPLIED", "현재 결과",
        )) }
        journal.publishDeferredResult(current)
        journal.publishDeferredResult(old)
        committed { AutomationHistoryRetentionScheduler(entityManager, TimeProvider { now }, 30, 2).cleanup() }
        val cycles = committed { journal.page(accountId, AutomationHistoryQuery()).cycles }
        assertEquals(listOf(currentCycle), cycles.map { it.id })
        assertEquals(listOf("CURRENT_STARTED", "CURRENT_APPLIED"), cycles.single().events.map { it.reasonCode })
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `같은 실행의 이력을 동시에 전달하고 새 판단에서 재요청해도 원래 이력 한 건만 남는다`() {
        val journal = journal()
        val accountId = committed { account("history-concurrent-delivery").id }
        val trace = AutomationActionTrace(AutomationHistoryEventKind.ACTION_SUCCEEDED, "HOME_APPLIED", "원래 자택 적용",
            type = AutomationType.HOME_QUEST, actionKind = "HOME_QUEST_ACCEPT")
        val originalCycle = committed { journal.appendDecision(accountId, AutomationCoordination.Idle(emptyList())) }
        val id = committed { journal.deferActionResult(originalCycle, "one-execution", trace) }
        val ready = java.util.concurrent.CountDownLatch(2)
        val start = java.util.concurrent.CountDownLatch(1)
        val workers = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val deliveries = List(2) { workers.submit {
                ready.countDown()
                check(start.await(10, java.util.concurrent.TimeUnit.SECONDS))
                journal.publishDeferredResult(id)
            } }
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS))
            start.countDown()
            deliveries.forEach { it.get(20, java.util.concurrent.TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            workers.shutdownNow()
        }
        val nextCycle = committed { journal.appendDecision(accountId, AutomationCoordination.Idle(emptyList())) }
        assertEquals(id, committed { journal.deferActionResult(nextCycle, "one-execution", trace.copy(message = "다음 판단의 다른 문맥")) })
        journal.publishDeferredResult(id)
        val cycles = committed { journal.page(accountId, AutomationHistoryQuery()).cycles }
        assertEquals("원래 자택 적용", cycles.single { it.id == originalCycle }.events.single().message)
        assertTrue(cycles.single { it.id == nextCycle }.events.isEmpty())
        val payload = committed { entityManager.createQuery("select o.payload from AutomationOutboxEntity o where o.id = :id", String::class.java)
            .setParameter("id", id).singleResult }
        assertEquals("", payload)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `지연 성공 이력은 현재 stall을 해소하거나 최신 진전 시각을 되돌리지 않는다`() {
        val registry = SimpleMeterRegistry()
        val telemetry = AutomationProgressTelemetry(registry, TimeProvider { now })
        val journal = journal(telemetry)
        val accountId = committed { account("history-late-telemetry").id }
        val entryId = committed { entry(entityManager.find(HofAccountEntity::class.java, accountId), AutomationType.HOME_QUEST, 0).id }
        val trace = AutomationActionTrace(AutomationHistoryEventKind.ACTION_SUCCEEDED, "HOME_APPLIED", "자택 적용",
            entryId, AutomationType.HOME_QUEST, "HOME_QUEST_ACCEPT")
        val oldCycle = committed { journal.appendPreparedActionAttempt(accountId, trace.copy(
            kind = AutomationHistoryEventKind.ACTION_STARTED, reasonCode = "ACTION_STARTED",
        )) }
        val oldAccept = committed { journal.deferActionResult(oldCycle, "old-accept-$oldCycle", trace) }
        val oldClaim = committed { journal.deferActionResult(oldCycle, "old-claim-$oldCycle", trace.copy(actionKind = "HOME_QUEST_CLAIM")) }
        now = now.plusSeconds(10)
        val currentCycle = committed { journal.appendDecision(accountId, AutomationCoordination.Runnable(
            entryId, HomeQuestAutomationAction(accountId, "home-next", "다음 자택", "accept", HomeQuestAutomationActionType.ACCEPT), emptyList(),
        )) }
        now = now.plusSeconds(181)
        telemetry.detectStalls()
        assertTrue(telemetry.snapshot(accountId).stalled)
        journal.publishDeferredResult(oldAccept)
        assertTrue(telemetry.snapshot(accountId).stalled)
        assertEquals(181, telemetry.snapshot(accountId).secondsWithoutTerminalAction)

        val current = committed { journal.deferActionResult(currentCycle, "current-accept-$currentCycle", trace) }
        journal.publishDeferredResult(current)
        assertFalse(telemetry.snapshot(accountId).stalled)
        assertEquals(0, telemetry.snapshot(accountId).secondsWithoutTerminalAction)
        now = now.plusSeconds(1)
        journal.publishDeferredResult(oldClaim)
        journal.publishDeferredResult(oldAccept)
        assertEquals(1, telemetry.snapshot(accountId).secondsWithoutTerminalAction)
        assertFalse(telemetry.snapshot(accountId).stalled)
        assertEquals(3.0, registry.counter("hof.automation.action.terminal", "type", "HOME_QUEST").count())
        assertEquals(1.0, registry.counter("hof.automation.progress.stall", "reason", "NO_TERMINAL_ACTION").count())

        // 새 성공이 있어도 후속 판단 자체가 끊겼다면 다시 정체를 감지한다.
        now = now.plusSeconds(181)
        telemetry.detectStalls()
        telemetry.detectStalls()
        assertEquals(2.0, registry.counter("hof.automation.progress.stall", "reason", "NO_TERMINAL_ACTION").count())
    }

    @ParameterizedTest
    @ValueSource(strings = ["IDLE", "COOLDOWN", "CAPTCHA"])
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `성공 뒤 정상 대기를 저장하면 정체 경고 없이 대기하고 새 실행 압력은 다시 감시한다`(waiting: String) {
        val registry = SimpleMeterRegistry()
        val telemetry = AutomationProgressTelemetry(registry, TimeProvider { now })
        val journal = journal(telemetry)
        val accountId = committed { account("history-idle-progress-$waiting").id }
        val entryId = committed { entry(entityManager.find(HofAccountEntity::class.java, accountId), AutomationType.HOME_QUEST, 0).id }
        val runnable = AutomationCoordination.Runnable(
            entryId, HomeQuestAutomationAction(accountId, "home", "자택", "accept", HomeQuestAutomationActionType.ACCEPT), emptyList(),
        )
        val cycleId = committed { journal.appendDecision(accountId, runnable) }
        committed { journal.appendActionResult(cycleId, AutomationActionTrace(
            AutomationHistoryEventKind.ACTION_SUCCEEDED, "HOME_APPLIED", "자택 적용",
            entryId, AutomationType.HOME_QUEST, "HOME_QUEST_ACCEPT",
        )) }
        val decision = when (waiting) {
            "COOLDOWN" -> AutomationCoordination.Unavailable(now.plusSeconds(600), emptyList())
            "CAPTCHA" -> {
                val battleEntryId = committed {
                    entry(entityManager.find(HofAccountEntity::class.java, accountId), AutomationType.BATTLE_MAP, 1).id
                }
                AutomationCoordination.Idle(emptyList(), listOf(AutomationEvaluationTrace(
                    0, battleEntryId, AutomationType.BATTLE_MAP, AutomationDecisionOutcome.WAITING,
                    "CAPTCHA_BATTLE_GATE_BLOCKED", "전투 관문 대기",
                )))
            }
            else -> AutomationCoordination.Idle(emptyList())
        }
        val idleCycleId = committed { journal.appendDecision(accountId, decision) }
        assertEquals(AutomationDecisionResult.IDLE, committed {
            journal.page(accountId, AutomationHistoryQuery()).cycles.single { it.id == idleCycleId }.result
        })
        assertEquals(1.0, registry.counter("hof.automation.action.terminal", "type", "HOME_QUEST").count())
        now = now.plusSeconds(181)

        telemetry.detectStalls()

        assertEquals(0.0, registry.counter("hof.automation.progress.stall", "reason", "NO_TERMINAL_ACTION").count())
        committed { journal.appendDecision(accountId, runnable) }
        telemetry.detectStalls()
        assertEquals(0.0, registry.counter("hof.automation.progress.stall", "reason", "NO_TERMINAL_ACTION").count())
        now = now.plusSeconds(181)
        telemetry.detectStalls()
        telemetry.detectStalls()
        assertEquals(1.0, registry.counter("hof.automation.progress.stall", "reason", "NO_TERMINAL_ACTION").count())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `복원 성공 이력 뒤 정상 유휴 확인 없이 판단이 끊긴 경우만 정체를 경고한다`(idleConfirmed: Boolean) {
        val registry = SimpleMeterRegistry()
        val telemetry = AutomationProgressTelemetry(registry, TimeProvider { now })
        val journal = journal(telemetry)
        val accountId = committed { account("history-idle-late-success-$idleConfirmed").id }
        val cycleId = committed { journal.appendPreparedActionAttempt(accountId, AutomationActionTrace(
            AutomationHistoryEventKind.ACTION_STARTED, "HOME_STARTED", "자택 제출", type = AutomationType.HOME_QUEST,
        )) }
        val deferred = committed { journal.deferActionResult(cycleId, "idle-late-$cycleId", AutomationActionTrace(
            AutomationHistoryEventKind.ACTION_SUCCEEDED, "HOME_APPLIED", "자택 적용", type = AutomationType.HOME_QUEST,
        )) }
        now = now.plusSeconds(1)
        if (idleConfirmed) committed { journal.appendDecision(accountId, AutomationCoordination.Idle(emptyList())) }
        now = now.plusSeconds(181)
        journal.publishDeferredResult(deferred)

        telemetry.detectStalls()

        assertEquals(1.0, registry.counter("hof.automation.action.terminal", "type", "HOME_QUEST").count())
        assertEquals(if (idleConfirmed) 0.0 else 1.0,
            registry.counter("hof.automation.progress.stall", "reason", "NO_TERMINAL_ACTION").count())
    }

    @ParameterizedTest
    @ValueSource(longs = [0, 1])
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `과거 CATCH 이력 재전달은 현재 낚시 복구 반복을 초기화하지 않는다`(secondsBetween: Long) {
        val journal = journal()
        val accountId = committed { account("history-late-catch-$secondsBetween").id }
        val entryId = committed { entry(entityManager.find(HofAccountEntity::class.java, accountId), AutomationType.FISHING, 0).id }
        val cycleId = committed { journal.appendPreparedActionAttempt(accountId, AutomationActionTrace(
            AutomationHistoryEventKind.ACTION_STARTED, "CATCH_STARTED", "이전 잡기", entryId, AutomationType.FISHING, "CATCH",
        )) }
        val deferred = committed { journal.deferActionResult(cycleId, "old-catch-$cycleId", AutomationActionTrace(
            AutomationHistoryEventKind.ACTION_SUCCEEDED, "FISHING_CATCH_APPLIED", "이전 잡기 완료", entryId, AutomationType.FISHING, "CATCH",
        )) }
        fun recovery() {
            now = now.plusSeconds(secondsBetween)
            committed { journal.appendResultObservation(accountId, AutomationActionTrace(
                AutomationHistoryEventKind.SKIPPED, "FISHING_BATTLE_RECOVERED_FROM_START", "현재 전투 재확인",
                entryId, AutomationType.FISHING, "START", diagnosticContext = """{
                    "version":1,"source":"DIRECT_RESPONSE","recheckRequired":true,
                    "fishing":{"primaryAction":"NONE","remainingCasts":4,"blockedByBattle":true,
                    "battleMapCode":"Fish03","battleObservationComplete":true}}""".trimIndent(),
            )) }
        }
        recovery()
        recovery()
        journal.publishDeferredResult(deferred)
        recovery()
        val events = committed { journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events } }
        val repeated = events.single { it.reasonCode == "FISHING_RECOVERY_REPEATED" }
        assertEquals(3, jacksonObjectMapper().readTree(repeated.diagnosticContext)["repetition"]["count"].asInt())
        assertEquals(1, events.count { it.reasonCode == "FISHING_CATCH_APPLIED" })
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `항목과 프리셋을 삭제한 뒤에도 지연 이력은 당시 이름과 결과를 보존한다`() {
        val journal = journal()
        val accountId = committed { account("history-deleted-entry").id }
        val entryId = committed {
            entry(entityManager.find(HofAccountEntity::class.java, accountId), AutomationType.BATTLE_MAP, 0)
                .apply { displayName = "원래 맵 묶음" }.id
        }
        val presetId = committed {
            app.spammy.hof.party.entity.PartyPresetEntity(
                account = entityManager.find(HofAccountEntity::class.java, accountId), name = "원래 프리셋",
                createdAt = now, updatedAt = now,
            ).also(entityManager::persist).id
        }
        val cycleId = committed { journal.appendPreparedActionAttempt(accountId, AutomationActionTrace(
            AutomationHistoryEventKind.ACTION_STARTED, "BATTLE_STARTED", "전투 전송", entryId, AutomationType.BATTLE_MAP, "BATTLE",
        )) }
        val occurredAt = now
        val context = """{"source":"DIRECT_RESPONSE","settingsRevision":7,"targetKey":"map-1"}"""
        val deferred = committed { journal.deferActionResult(cycleId, "deleted-entry-result", AutomationActionTrace(
            AutomationHistoryEventKind.ACTION_SUCCEEDED, "BATTLE_APPLIED", "전투 적용", entryId, AutomationType.BATTLE_MAP,
            "BATTLE", targetKey = "map-1", presetId = presetId, diagnosticContext = context,
        )) }
        committed {
            entityManager.remove(entityManager.find(AutomationEntryEntity::class.java, entryId))
            entityManager.remove(entityManager.find(app.spammy.hof.party.entity.PartyPresetEntity::class.java, presetId))
        }
        now = now.plusSeconds(20)
        journal.publishDeferredResult(deferred)
        journal.publishDeferredResults()
        val events = committed { journal.page(accountId, AutomationHistoryQuery()).cycles.single().events }
        val result = events.single { it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED }
        assertNull(result.entryId)
        assertEquals("원래 맵 묶음", result.entryDisplayName)
        assertEquals("원래 프리셋", result.presetName)
        assertEquals(occurredAt, result.occurredAt)
        assertEquals(context, result.diagnosticContext)
        assertEquals("map-1", result.targetKey)
        assertEquals(2, events.size)
    }

    @Test
    fun `낚시 반복은 다른 유형 성공과 재생성에 가려지지 않고 한 사건으로 보존된다`() {
        val account = account("fishing-repetition")
        val fishing = entry(account, AutomationType.FISHING, 0)
        val fishingId = fishing.id
        fun recovery(identity: String, remaining: Int = 5) {
            val journal = journal()
            val cycle = journal.appendDecision(account.id, AutomationCoordination.Idle(emptyList()))
            journal.appendActionResult(cycle, AutomationActionTrace(
                AutomationHistoryEventKind.SKIPPED, "FISHING_BATTLE_RECOVERED_FROM_START", "전투 재확인",
                fishingId, AutomationType.FISHING, "START", diagnosticContext = """{
                    "version":1,"source":"DIRECT_RESPONSE","executionIdentity":"$identity","recheckRequired":true,
                    "fishing":{"primaryAction":"NONE","remainingCasts":$remaining,"blockedByBattle":true,
                    "battleMapCode":"Fish03","battleObservationComplete":true}}
                """.trimIndent(),
            ))
            journal.appendActionResult(cycle, AutomationActionTrace(
                AutomationHistoryEventKind.ACTION_SUCCEEDED, "ACTION_SUCCEEDED", "유니온 성공", type = AutomationType.UNION,
            ))
            entityManager.flush()
            entityManager.clear()
        }
        fun warnings() = journal().page(account.id, AutomationHistoryQuery()).cycles.flatMap { it.events }
            .filter { it.reasonCode == "FISHING_RECOVERY_REPEATED" }
        recovery("first")
        recovery("second")
        assertTrue(warnings().isEmpty())
        recovery("third")
        val warning = warnings().single()
        assertEquals(AutomationHistoryEventKind.CONFIGURATION_WARNING, warning.kind)
        assertEquals("Fish03", warning.targetKey)
        val context = jacksonObjectMapper().readTree(assertNotNull(warning.diagnosticContext))
        assertEquals(3, context["repetition"]["count"].asInt())
        assertTrue(context["repetition"]["firstEventId"].asLong() > 0)
        recovery("fourth")
        assertEquals(listOf(warning.id), warnings().map { it.id })
        val latest = journal().page(account.id, AutomationHistoryQuery()).cycles.first().events.first()
        assertEquals(4, jacksonObjectMapper().readTree(assertNotNull(latest.diagnosticContext))["repetition"]["count"].asInt())

        // 확인된 낚시 진전과 설정 세대 변경은 각각 새 반복 사건의 경계다.
        val cycle = journal().appendDecision(account.id, AutomationCoordination.Idle(emptyList()))
        journal().appendActionResult(cycle, AutomationActionTrace(
            AutomationHistoryEventKind.ACTION_SUCCEEDED, "FISHING_CATCH_APPLIED", "잡기 완료", fishingId, AutomationType.FISHING, "CATCH",
        ))
        entityManager.flush(); entityManager.clear()
        recovery("after-catch-1"); recovery("after-catch-2")
        assertEquals(1, warnings().size)
        recovery("after-catch-3")
        assertEquals(2, warnings().size)
        entityManager.find(AutomationEntryEntity::class.java, fishingId).settingsRevision += 1
        entityManager.flush(); entityManager.clear()
        recovery("new-generation-1"); recovery("new-generation-2")
        assertEquals(2, warnings().size)
        recovery("new-generation-3")
        assertEquals(3, warnings().size)
        recovery("progress-1", remaining = 4); recovery("progress-2", remaining = 4)
        assertEquals(3, warnings().size)

        val stored = StoredTypedAutomationAction(fishingId, "external-recovery",
            StoredTypedActionPayload.FishingTown(app.spammy.hof.town.fishing.model.FishingAction.START,
                app.spammy.hof.town.fishing.model.FishingPrimaryAction.START, 4))
        val resolved = AutomationDecisionDiagnostics.fishingProbe(stored,
            """{"stage":"ACTION_RESULT","source":"LATEST_OBSERVATION","fishing":{"blockedByBattle":false,"battleObservationComplete":true}}""",
            app.spammy.hof.automation.convergence.AutomationActionEvidence.StateAdvanced(now, "external-battle-resolved"),
            app.spammy.hof.automation.convergence.ConvergenceDirective.ContinueSelection)
        assertEquals("FISHING_RECOVERY_RESOLVED", resolved.reasonCode)
        journal().appendResultObservation(account.id, resolved)
        entityManager.flush(); entityManager.clear()
        recovery("after-external-resolution-1", remaining = 4)
        recovery("after-external-resolution-2", remaining = 4)
        assertEquals(3, warnings().size)
        recovery("after-external-resolution-3", remaining = 4)
        assertEquals(4, warnings().size)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `낚시 미선택의 해소 관측은 같은 평가 아래 한 번 표시된다`(selectOther: Boolean) {
        val account = account("fishing-resolution-$selectOther")
        val fishing = entry(account, AutomationType.FISHING, 0)
        val other = entry(account, AutomationType.HOME_QUEST, 1)
        val journal = journal()
        val first = journal.appendDecision(account.id, AutomationCoordination.Idle(emptyList()))
        journal.appendActionResult(first, AutomationActionTrace(
            AutomationHistoryEventKind.SKIPPED, "FISHING_BATTLE_RECOVERED_FROM_START", "전투 재확인",
            fishing.id, AutomationType.FISHING, "START",
        ))
        val trace = listOf(AutomationEvaluationTrace(
            0, fishing.id, AutomationType.FISHING, AutomationDecisionOutcome.SKIPPED,
            "FISHING_STATE_INCOMPLETE", "낚시 상태 대기",
            diagnosticContext = """{"stage":"ENTRY_EVALUATION","snapshot":{"primaryAction":"NONE","battleObservationComplete":true,"blockedByBattle":false}}""",
        ))
        val decision = if (selectOther) AutomationCoordination.Runnable(
            other.id, HomeQuestAutomationAction(account.id, "A", "하위 자택", "accept-A", HomeQuestAutomationActionType.ACCEPT), emptyList(), trace + AutomationEvaluationTrace(
                1, other.id, AutomationType.HOME_QUEST, AutomationDecisionOutcome.SELECTED, "RUNNABLE", "수락",
            ),
        ) else AutomationCoordination.Idle(emptyList(), trace)
        val id = journal.appendDecision(account.id, decision)
        entityManager.flush(); entityManager.clear()
        val cycle = journal.page(account.id, AutomationHistoryQuery()).cycles.single { it.id == id }
        assertEquals(if (selectOther) 2 else 1, cycle.topLevelStepCount)
        assertEquals(cycle.steps.size, cycle.steps.map { it.event.entryId }.distinct().size)
        val step = cycle.steps.single { it.event.entryId == fishing.id }
        assertEquals("FISHING_STATE_INCOMPLETE", step.event.reasonCode)
        assertEquals("FISHING_RECOVERY_RESOLVED", step.executionEvents.single().reasonCode)
    }

    @Test
    fun `판단 불가 진단은 다음 판단 이후에도 같은 이력에서 조회된다`() {
        val account = account("history-diagnostic")
        val battle = entry(account, AutomationType.BATTLE_MAP, 0)
        val diagnostic = """{"stage":"ENTRY_EVALUATION","snapshot":{"keyMode":"UNKNOWN","attemptRemaining":null}}"""
        val journal = journal()
        val cycleId = journal.appendDecision(account.id, AutomationCoordination.Idle(emptyList(), listOf(
            AutomationEvaluationTrace(
                0, battle.id, AutomationType.BATTLE_MAP, AutomationDecisionOutcome.SKIPPED,
                "NOT_RUNNABLE", "실행 조건을 확정하지 못했습니다.", diagnosticContext = diagnostic,
            ),
        )))
        journal.appendDecision(account.id, AutomationCoordination.Idle(emptyList()))
        entityManager.flush()
        entityManager.clear()

        val event = journal.page(account.id, AutomationHistoryQuery()).cycles.first { it.id == cycleId }.events.single()
        assertEquals(AutomationHistoryEventKind.SKIPPED, event.kind)
        assertEquals(diagnostic, event.diagnosticContext)
    }

    @Test
    fun `all ineligible entries record no action while scheduling another check`() {
        val account = account("history-skipped-conditions")
        val battle = entry(account, AutomationType.BATTLE_MAP, 0)
        val journal = journal()
        val nextRunAt = now.plusSeconds(120)
        journal.appendDecision(account.id, AutomationCoordination.Unavailable(
            nextRunAt = nextRunAt,
            warnings = emptyList(),
            trace = listOf(AutomationEvaluationTrace(
                0, battle.id, AutomationType.BATTLE_MAP, AutomationDecisionOutcome.SKIPPED,
                "COOLDOWN", "쿨다운 중이므로 건너뜁니다.", nextRunAt,
            )),
        ))
        entityManager.flush()
        entityManager.clear()

        val cycle = journal.page(account.id, AutomationHistoryQuery()).cycles.single()
        assertEquals(AutomationDecisionResult.IDLE, cycle.result)
        assertNull(cycle.selectedEntryId)
        assertEquals(AutomationHistoryEventKind.SKIPPED, cycle.events.single().kind)
        assertEquals(nextRunAt, cycle.events.single().nextRunAt)
    }

    @Test
    fun `pending action transition still records a wait`() {
        val account = account("history-pending-transition")
        val journal = journal()
        journal.appendDecision(account.id, AutomationCoordination.Unavailable(
            nextRunAt = now.plusSeconds(5),
            warnings = emptyList(),
            waitScope = AutomationWaitScope.HOLD_CURRENT_WORK,
        ))
        assertEquals(AutomationDecisionResult.WAITING, journal.page(account.id, AutomationHistoryQuery()).cycles.single().result)
    }

    @Test
    fun `stores evaluated order and isolates account history`() {
        val first = account("history-first")
        val second = account("history-second")
        val quest = entry(first, AutomationType.QUEST, 0)
        val union = entry(first, AutomationType.UNION, 1)
        entityManager.flush()
        val journal = journal()
        val decision = AutomationCoordination.Idle(emptyList(), listOf(
            AutomationEvaluationTrace(0, quest.id, AutomationType.QUEST, AutomationDecisionOutcome.SKIPPED, "QUEST_DONE", "완료"),
            AutomationEvaluationTrace(
                1, union.id, AutomationType.UNION, AutomationDecisionOutcome.WAITING, "UNION_COOLDOWN", "대기",
                now.plusSeconds(60), "BATTLE", "union/0001", "도적 소탕", 99,
                diagnosticKind = AutomationDiagnosticKind.RAID_LOCAL_SAFETY_GATE,
                cooldownSource = RaidCooldownSource.LOCAL_FALLBACK,
                impactScope = AutomationImpactScope.RAID_ONLY,
                releaseCondition = "최신 상태 재확인",
            ),
        ))

        val cycleId = journal.appendDecision(first.id, decision)
        journal.appendActionResult(cycleId, AutomationActionTrace(
            AutomationHistoryEventKind.ACTION_SUCCEEDED, "DONE", "완료", union.id, AutomationType.UNION,
        ))
        entityManager.flush(); entityManager.clear()

        val page = journal.page(first.id, AutomationHistoryQuery())
        assertEquals(listOf(0, 1, 2), page.cycles.single().events.map { it.sequence })
        assertEquals(listOf("QUEST_DONE", "UNION_COOLDOWN", "DONE"), page.cycles.single().events.map { it.reasonCode })
        with(page.cycles.single().events[1]) {
            assertEquals("union/0001", targetKey)
            assertEquals("도적 소탕", targetName)
            assertEquals("BATTLE", actionKind)
            assertEquals(99, presetId)
            assertEquals(AutomationDiagnosticKind.RAID_LOCAL_SAFETY_GATE, diagnosticKind)
            assertEquals(RaidCooldownSource.LOCAL_FALLBACK, cooldownSource)
            assertEquals(AutomationImpactScope.RAID_ONLY, impactScope)
            assertEquals("최신 상태 재확인", releaseCondition)
        }
        assertNull(journal.page(second.id, AutomationHistoryQuery()).cycles.singleOrNull())
    }

    @Test
    fun `stores a resumed prepared action as a separately visible attempt`() {
        val account = account("history-resume")
        val raid = entry(account, AutomationType.RAID, 0)
        entityManager.flush()
        val journal = journal()

        journal.appendPreparedActionAttempt(account.id, AutomationActionTrace(
            kind = AutomationHistoryEventKind.WAITING,
            reasonCode = "AMBIGUOUS_RESULT_VERIFY",
            message = "전투 시작 단계의 적용 여부를 재확인합니다.",
            entryId = raid.id,
            type = AutomationType.RAID,
            actionKind = "START",
            targetKey = "Raid001",
            targetName = "고블린 전투 마차",
            nextRunAt = now.plusSeconds(10),
        ))
        entityManager.flush(); entityManager.clear()

        val cycle = journal.page(account.id, AutomationHistoryQuery()).cycles.single()
        assertEquals(AutomationDecisionResult.ACTION_SELECTED, cycle.result)
        assertEquals(raid.id, cycle.selectedEntryId)
        with(cycle.events.single()) {
            assertEquals("AMBIGUOUS_RESULT_VERIFY", reasonCode)
            assertEquals("START", actionKind)
            assertEquals("고블린 전투 마차", targetName)
            assertEquals(now.plusSeconds(10), nextRunAt)
        }
    }

    @Test
    fun `history snapshots the map group name used at decision time`() {
        val account = account("history-map-group")
        val group = entry(account, AutomationType.BATTLE_MAP, 0).also { it.displayName = "최우선 보스" }
        entityManager.flush()
        val journal = journal()

        journal.appendDecision(
            account.id,
            AutomationCoordination.Idle(
                emptyList(),
                listOf(
                    AutomationEvaluationTrace(
                        0,
                        group.id,
                        AutomationType.BATTLE_MAP,
                        AutomationDecisionOutcome.SKIPPED,
                        "DAILY_LIMIT",
                        "오늘 목표를 완료했습니다.",
                    ),
                ),
            ),
        )
        group.displayName = "나중 이름"
        entityManager.flush()
        entityManager.clear()

        val event = journal.page(account.id, AutomationHistoryQuery()).cycles.single().events.single()
        assertEquals("최우선 보스", event.entryDisplayName)
    }

    @Test
    fun `stores a manual raid handoff as a stable cycle outcome`() {
        val account = account("history-raid-handoff")
        val raid = entry(account, AutomationType.RAID, 0)
        entityManager.flush()
        val journal = journal()

        journal.appendRaidCycleOutcome(
            account.id,
            RaidCycleOutcome(raid.id, "Raid001", RaidCycleOutcomeKind.HANDED_OFF_MANUAL),
        )
        entityManager.flush()
        entityManager.clear()

        val event = journal.page(account.id, AutomationHistoryQuery()).cycles.single().events.single()
        assertEquals(AutomationHistoryEventKind.CYCLE_ABORTED, event.kind)
        assertEquals(RaidCycleOutcomeKind.HANDED_OFF_MANUAL.name, event.reasonCode)
        assertEquals("Raid001", event.targetKey)
    }

    @Test
    fun `maps a decision-time raid completion to cycle history instead of skipped`() {
        val account = account("history-raid-complete")
        val raid = entry(account, AutomationType.RAID, 0)
        entityManager.flush()
        val journal = journal()

        journal.appendDecision(
            account.id,
            AutomationCoordination.Idle(
                warnings = emptyList(),
                trace = listOf(
                    AutomationEvaluationTrace(
                        sequence = 0,
                        entryId = raid.id,
                        type = AutomationType.RAID,
                        outcome = AutomationDecisionOutcome.CYCLE_COMPLETED,
                        reasonCode = RaidCycleOutcomeKind.COMPLETED.name,
                        message = "레이드 사이클을 완료했습니다.",
                        targetKey = "Raid001",
                    ),
                ),
            ),
        )
        entityManager.clear()

        val event = journal.page(account.id, AutomationHistoryQuery()).cycles.single().events.single()
        assertEquals(AutomationHistoryEventKind.CYCLE_COMPLETED, event.kind)
        assertEquals(RaidCycleOutcomeKind.COMPLETED.name, event.reasonCode)
    }

    @Test
    fun `낚시 START CATCH와 방해 전투는 서로 다른 전역 판단 사이클에 기록된다`() {
        val account = account("history-fishing-cycle")
        val home = entry(account, AutomationType.HOME_QUEST, 0)
        val fishing = entry(account, AutomationType.FISHING, 1)
        entityManager.flush()
        val journal = journal()
        val castCycleId = journal.appendDecision(
            account.id,
            AutomationCoordination.Runnable(
                fishing.id,
                FishingTownAutomationAction(
                    account.id,
                    app.spammy.hof.town.fishing.model.FishingAction.START,
                    app.spammy.hof.town.fishing.model.FishingPrimaryAction.START,
                    18,
                ),
                emptyList(),
                listOf(
                    AutomationEvaluationTrace(
                        0,
                        home.id,
                        AutomationType.HOME_QUEST,
                        AutomationDecisionOutcome.SKIPPED,
                        "HOME_IDLE",
                        "실행할 자택 행동이 없습니다.",
                    ),
                    AutomationEvaluationTrace(
                        1,
                        fishing.id,
                        AutomationType.FISHING,
                        AutomationDecisionOutcome.SELECTED,
                        "ACTION_SELECTED",
                        "낚시를 선택했습니다.",
                    ),
                ),
            ),
        )
        journal.appendActionResult(castCycleId, AutomationActionTrace(
            AutomationHistoryEventKind.ACTION_SUCCEEDED,
            "FISHING_START_APPLIED",
            "START 적용",
            fishing.id,
            AutomationType.FISHING,
            actionKind = "START",
        ))
        journal.appendActionResult(castCycleId, AutomationActionTrace(
            AutomationHistoryEventKind.ACTION_SUCCEEDED,
            "FISHING_CATCH_APPLIED",
            "CATCH 적용",
            fishing.id,
            AutomationType.FISHING,
            actionKind = "CATCH",
        ))
        val battleCycleId = journal.appendDecision(
            account.id,
            AutomationCoordination.Runnable(
                fishing.id,
                BattleMapAutomationAction(
                    accountId = account.id,
                    progressDate = java.time.LocalDate.parse("2026-08-12"),
                    categoryId = "battle_map",
                    mapCode = "fish-monster",
                    presetMode = app.spammy.hof.automation.entity.PresetSelectionMode.PRIMARY,
                    presetId = 1,
                    battleCount = 1,
                    executionIdentity = "fishing-battle-1",
                    source = BattleAutomationActionSource.FISHING_AUTOMATION,
                ),
                emptyList(),
                listOf(
                    AutomationEvaluationTrace(
                        0,
                        home.id,
                        AutomationType.HOME_QUEST,
                        AutomationDecisionOutcome.SKIPPED,
                        "HOME_IDLE",
                        "실행할 자택 행동이 없습니다.",
                    ),
                    AutomationEvaluationTrace(
                        1,
                        fishing.id,
                        AutomationType.FISHING,
                        AutomationDecisionOutcome.SELECTED,
                        "ACTION_SELECTED",
                        "낚시 방해 전투를 선택했습니다.",
                    ),
                ),
            ),
        )
        journal.appendActionResult(battleCycleId, AutomationActionTrace(
            AutomationHistoryEventKind.ACTION_SUCCEEDED,
            "FISHING_OBSTRUCTION_BATTLE_APPLIED",
            "방해 전투 적용",
            fishing.id,
            AutomationType.FISHING,
            actionKind = "BATTLE",
        ))
        entityManager.flush()
        entityManager.clear()

        val cycles = journal.page(account.id, AutomationHistoryQuery()).cycles.sortedBy(AutomationHistoryCycle::id)

        assertEquals(2, cycles.size)
        assertEquals(listOf(2, 2), cycles.map(AutomationHistoryCycle::topLevelStepCount))
        assertEquals(listOf("HOME_IDLE", "ACTION_SELECTED"), cycles[0].steps.map { it.event.reasonCode })
        assertEquals(
            listOf("FISHING_START_APPLIED", "FISHING_CATCH_APPLIED"),
            cycles[0].steps.last().executionEvents.map(AutomationHistoryEvent::reasonCode),
        )
        assertEquals(listOf("HOME_IDLE", "ACTION_SELECTED"), cycles[1].steps.map { it.event.reasonCode })
        assertEquals(
            listOf("FISHING_OBSTRUCTION_BATTLE_APPLIED"),
            cycles[1].steps.last().executionEvents.map(AutomationHistoryEvent::reasonCode),
        )
    }

    private fun account(login: String) = accounts.save(HofAccountEntity(
        loginId = login, encryptedPassword = "encrypted", createdAt = now,
    ))

    private fun journal(telemetry: AutomationProgressTelemetry? = null) = JpaAutomationDecisionJournal(
        entityManager,
        TimeProvider { now },
        cycleCommands,
        eventCommands,
        transactions,
        telemetry,
    )

    private fun entry(account: HofAccountEntity, type: AutomationType, priority: Int): AutomationEntryEntity =
        AutomationEntryEntity(account = account, type = type, priority = priority, enabled = true, createdAt = now, updatedAt = now)
            .also(entityManager::persist)
}
