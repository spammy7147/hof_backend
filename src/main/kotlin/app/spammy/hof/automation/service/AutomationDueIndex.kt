package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.AutomationWorkType
import app.spammy.hof.automation.repository.AutomationWorkSessionCommandRepository
import app.spammy.hof.automation.repository.AutomationWorkSessionQueryRepository
import java.time.Instant
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

data class AutomationDueTarget(
    val accountId: Long,
    val sessionId: Long,
    val workType: AutomationWorkType,
    val targetKey: String,
)

interface AutomationDueIndex {
    fun schedule(target: AutomationDueTarget, dueAt: Instant)
    fun cancel(target: AutomationDueTarget)
    fun dueAtOrBefore(now: Instant, limit: Int = 100): List<AutomationDueTarget>
}

@Component
class DatabaseAutomationDueStore(
    private val queries: AutomationWorkSessionQueryRepository,
    private val commands: AutomationWorkSessionCommandRepository,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun schedule(target: AutomationDueTarget, dueAt: Instant) {
        val session = requireNotNull(queries.lockById(target.accountId, target.sessionId)) {
            "Automation work session ${target.sessionId} does not belong to account ${target.accountId}."
        }
        require(session.workType == target.workType && session.targetKey == target.targetKey) {
            "Automation due target does not match its persistent work session."
        }
        session.nextCheckAt = dueAt
        commands.save(session)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun cancel(target: AutomationDueTarget) {
        val session = queries.lockById(target.accountId, target.sessionId) ?: return
        session.nextCheckAt = null
        commands.save(session)
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    fun dueAtOrBefore(now: Instant, limit: Int = 100): List<AutomationDueTarget> =
        queries.findDue(now, limit).map { session ->
            AutomationDueTarget(session.accountId, session.id, session.workType, session.targetKey)
        }
}

@Component
@ConditionalOnProperty(
    prefix = "hof.automation-session",
    name = ["redis-enabled"],
    havingValue = "false",
    matchIfMissing = true,
)
class DatabaseAutomationDueIndex(
    private val store: DatabaseAutomationDueStore,
) : AutomationDueIndex {
    override fun schedule(target: AutomationDueTarget, dueAt: Instant) = store.schedule(target, dueAt)
    override fun cancel(target: AutomationDueTarget) = store.cancel(target)
    override fun dueAtOrBefore(now: Instant, limit: Int): List<AutomationDueTarget> =
        store.dueAtOrBefore(now, limit)
}
