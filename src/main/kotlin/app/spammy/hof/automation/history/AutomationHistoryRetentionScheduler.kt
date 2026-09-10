package app.spammy.hof.automation.history

import app.spammy.hof.common.time.TimeProvider
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.temporal.ChronoUnit

@Service
class AutomationHistoryRetentionScheduler(
    private val entityManager: EntityManager,
    private val timeProvider: TimeProvider,
    @Value("\${hof.automation-history.retention-days:30}") private val retentionDays: Long,
    @Value("\${hof.automation-history.max-events-per-account:50000}") private val maxEventsPerAccount: Int,
) {
    @Scheduled(cron = "\${hof.automation-history.cleanup-cron:0 20 4 * * *}")
    @Transactional
    fun cleanup() {
        val cutoff = timeProvider.now().minus(retentionDays.coerceAtLeast(1), ChronoUnit.DAYS)
        entityManager.createQuery("delete from AutomationDecisionCycleEntity c where c.finishedAt < :cutoff")
            .setParameter("cutoff", cutoff).executeUpdate()
        val accounts = entityManager.createQuery(
            "select distinct c.accountId from AutomationDecisionCycleEntity c", java.lang.Long::class.java,
        ).resultList.map(Number::toLong)
        accounts.forEach { trimAccount(it) }
        entityManager.createQuery("""update AutomationOutboxEntity o set o.payload = '', o.publishedAt = :now
            where o.topic = :topic and o.payload <> '' and
              (o.publishedAt is not null or o.createdAt < :cutoff or not exists
                (select c.id from AutomationDecisionCycleEntity c where cast(c.id as string) = o.eventKey))""")
            .setParameter("now", timeProvider.now()).setParameter("topic", AUTOMATION_HISTORY_OUTBOX_TOPIC)
            .setParameter("cutoff", cutoff).executeUpdate()
    }

    private fun trimAccount(accountId: Long) {
        if (maxEventsPerAccount < 1) return
        val overflowIds = entityManager.createQuery(
            "select e.id from AutomationDecisionEventEntity e where e.cycle.accountId = :accountId order by e.occurredAt desc, e.cycle.id desc, e.sequence desc, e.id desc",
            java.lang.Long::class.java,
        ).setParameter("accountId", accountId).setFirstResult(maxEventsPerAccount).resultList
        if (overflowIds.isNotEmpty()) {
            entityManager.createQuery("delete from AutomationDecisionEventEntity e where e.id in :ids")
                .setParameter("ids", overflowIds).executeUpdate()
            entityManager.createQuery("delete from AutomationDecisionCycleEntity c where c.accountId = :accountId and c.events is empty")
                .setParameter("accountId", accountId).executeUpdate()
        }
    }
}
