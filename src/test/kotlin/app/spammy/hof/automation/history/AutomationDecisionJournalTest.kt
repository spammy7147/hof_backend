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
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@DataJpaTest
@ActiveProfiles("test")
class AutomationDecisionJournalTest {
    @Autowired private lateinit var accounts: HofAccountRepository
    @Autowired private lateinit var entityManager: EntityManager
    @Autowired private lateinit var cycleCommands: AutomationDecisionCycleCommandRepository
    @Autowired private lateinit var eventCommands: AutomationDecisionEventCommandRepository
    private val now = Instant.parse("2026-08-12T01:00:00Z")

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

    private fun account(login: String) = accounts.save(HofAccountEntity(
        loginId = login, encryptedPassword = "encrypted", createdAt = now,
    ))

    private fun journal() = JpaAutomationDecisionJournal(
        entityManager,
        TimeProvider { now },
        cycleCommands,
        eventCommands,
    )

    private fun entry(account: HofAccountEntity, type: AutomationType, priority: Int): AutomationEntryEntity =
        AutomationEntryEntity(account = account, type = type, priority = priority, enabled = true, createdAt = now, updatedAt = now)
            .also(entityManager::persist)
}
