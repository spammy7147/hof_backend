package app.spammy.hof.automation.history

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.AutomationEntryEntity
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
    private val now = Instant.parse("2026-08-12T01:00:00Z")

    @Test
    fun `stores evaluated order and isolates account history`() {
        val first = account("history-first")
        val second = account("history-second")
        val quest = entry(first, AutomationType.QUEST, 0)
        val union = entry(first, AutomationType.UNION, 1)
        entityManager.flush()
        val journal = JpaAutomationDecisionJournal(entityManager, TimeProvider { now })
        val decision = AutomationCoordination.Idle(emptyList(), listOf(
            AutomationEvaluationTrace(0, quest.id, AutomationType.QUEST, AutomationDecisionOutcome.SKIPPED, "QUEST_DONE", "완료"),
            AutomationEvaluationTrace(1, union.id, AutomationType.UNION, AutomationDecisionOutcome.WAITING, "UNION_COOLDOWN", "대기", now.plusSeconds(60)),
        ))

        val cycleId = journal.appendDecision(first.id, decision)
        journal.appendActionResult(cycleId, AutomationActionTrace(
            AutomationHistoryEventKind.ACTION_SUCCEEDED, "DONE", "완료", union.id, AutomationType.UNION,
        ))
        entityManager.flush(); entityManager.clear()

        val page = journal.page(first.id, AutomationHistoryQuery())
        assertEquals(listOf(0, 1, 2), page.cycles.single().events.map { it.sequence })
        assertEquals(listOf("QUEST_DONE", "UNION_COOLDOWN", "DONE"), page.cycles.single().events.map { it.reasonCode })
        assertNull(journal.page(second.id, AutomationHistoryQuery()).cycles.singleOrNull())
    }

    private fun account(login: String) = accounts.save(HofAccountEntity(
        loginId = login, encryptedPassword = "encrypted", createdAt = now,
    ))

    private fun entry(account: HofAccountEntity, type: AutomationType, priority: Int): AutomationEntryEntity =
        AutomationEntryEntity(account = account, type = type, priority = priority, enabled = true, createdAt = now, updatedAt = now)
            .also(entityManager::persist)
}
