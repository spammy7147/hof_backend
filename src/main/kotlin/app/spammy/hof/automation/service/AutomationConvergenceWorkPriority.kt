package app.spammy.hof.automation.service

import app.spammy.hof.automation.repository.TypedAutomationQueryRepository
import org.springframework.stereotype.Component

/** due probe와 fresh action을 사용자가 저장한 entry 순서에서 비교한다. */
fun interface AutomationConvergenceWorkPriority {
    fun probeBeforeFresh(accountId: Long, probeEntryId: Long, freshEntryId: Long): Boolean
}

@Component
class StoredEntryAutomationConvergenceWorkPriority(
    private val typed: TypedAutomationQueryRepository,
) : AutomationConvergenceWorkPriority {
    override fun probeBeforeFresh(accountId: Long, probeEntryId: Long, freshEntryId: Long): Boolean {
        val ranks = typed.findEntries(accountId).mapIndexed { index, entry -> entry.id to index }.toMap()
        val probeRank = ranks[probeEntryId] ?: return true
        val freshRank = ranks[freshEntryId] ?: return true
        return probeRank <= freshRank
    }
}
