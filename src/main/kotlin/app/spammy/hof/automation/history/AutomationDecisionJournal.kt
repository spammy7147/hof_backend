package app.spammy.hof.automation.history

import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.service.*
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.party.entity.PartyPresetEntity
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

data class AutomationActionTrace(
    val kind: AutomationHistoryEventKind, val reasonCode: String, val message: String,
    val entryId: Long? = null, val type: AutomationType? = null, val actionKind: String? = null,
    val targetKey: String? = null, val targetName: String? = null,
    val presetId: Long? = null, val presetName: String? = null,
)
data class AutomationHistoryQuery(
    val beforeCycleId: Long? = null, val limit: Int = 20, val type: AutomationType? = null,
    val kind: AutomationHistoryEventKind? = null, val from: Instant? = null, val to: Instant? = null,
)
data class AutomationHistoryEvent(
    val id: Long, val sequence: Int, val entryId: Long?, val type: AutomationType?,
    val kind: AutomationHistoryEventKind, val reasonCode: String, val message: String,
    val targetKey: String?, val targetName: String?, val actionKind: String?,
    val presetId: Long?, val presetName: String?, val nextRunAt: Instant?, val occurredAt: Instant,
)
data class AutomationHistoryCycle(
    val id: Long, val result: AutomationDecisionResult, val selectedEntryId: Long?,
    val startedAt: Instant, val finishedAt: Instant, val events: List<AutomationHistoryEvent>,
)
data class AutomationHistoryPage(val cycles: List<AutomationHistoryCycle>, val nextCursor: Long?)

interface AutomationDecisionJournal {
    fun appendDecision(accountId: Long, decision: AutomationCoordination): Long
    fun appendActionResult(cycleId: Long, result: AutomationActionTrace)
    fun page(accountId: Long, query: AutomationHistoryQuery): AutomationHistoryPage
}

@Service
class JpaAutomationDecisionJournal(private val entityManager: EntityManager, private val timeProvider: TimeProvider) : AutomationDecisionJournal {
    @Transactional
    override fun appendDecision(accountId: Long, decision: AutomationCoordination): Long {
        val now = timeProvider.now()
        val cycle = AutomationDecisionCycleEntity(accountId = accountId, result = when (decision) {
            is AutomationCoordination.Runnable -> AutomationDecisionResult.ACTION_SELECTED
            is AutomationCoordination.Unavailable -> AutomationDecisionResult.WAITING
            is AutomationCoordination.Idle -> AutomationDecisionResult.IDLE
            is AutomationCoordination.Fatal -> AutomationDecisionResult.FATAL
        }, selectedEntryId = (decision as? AutomationCoordination.Runnable)?.entryId, startedAt = now, finishedAt = now)
        entityManager.persist(cycle)
        decision.trace.forEach { item -> entityManager.persist(AutomationDecisionEventEntity(
            cycle = cycle, sequence = item.sequence, entryId = item.entryId, type = item.type,
            kind = when (item.outcome) {
                AutomationDecisionOutcome.SELECTED -> AutomationHistoryEventKind.SELECTED
                AutomationDecisionOutcome.SKIPPED -> AutomationHistoryEventKind.SKIPPED
                AutomationDecisionOutcome.WAITING -> AutomationHistoryEventKind.WAITING
                AutomationDecisionOutcome.CONFIGURATION_WARNING -> AutomationHistoryEventKind.CONFIGURATION_WARNING
                AutomationDecisionOutcome.FATAL -> AutomationHistoryEventKind.ACTION_FAILED
            }, reasonCode = item.reasonCode, message = item.message, nextRunAt = item.nextRunAt,
            actionKind = item.actionKind, targetKey = item.targetKey, targetName = item.targetName,
            presetId = item.presetId, presetName = presetName(accountId, item.presetId), occurredAt = now,
        )) }
        entityManager.flush()
        return cycle.id
    }

    @Transactional
    override fun appendActionResult(cycleId: Long, result: AutomationActionTrace) {
        val cycle = entityManager.find(AutomationDecisionCycleEntity::class.java, cycleId)
            ?: throw IllegalArgumentException("Automation decision cycle not found.")
        val next = entityManager.createQuery(
            "select coalesce(max(e.sequence), -1) + 1 from AutomationDecisionEventEntity e where e.cycle.id = :cycleId", java.lang.Integer::class.java,
        ).setParameter("cycleId", cycleId).singleResult.toInt()
        entityManager.persist(AutomationDecisionEventEntity(
            cycle = cycle, sequence = next, entryId = result.entryId, type = result.type, kind = result.kind,
            reasonCode = result.reasonCode, message = result.message, targetKey = result.targetKey,
            targetName = result.targetName, actionKind = result.actionKind, presetId = result.presetId,
            presetName = result.presetName ?: presetName(cycle.accountId, result.presetId), occurredAt = timeProvider.now(),
        ))
    }

    @Transactional(readOnly = true)
    override fun page(accountId: Long, query: AutomationHistoryQuery): AutomationHistoryPage {
        require(query.limit in 1..100)
        val jpql = buildString {
            append("select distinct c from AutomationDecisionCycleEntity c left join c.events e where c.accountId = :accountId ")
            if (query.beforeCycleId != null) append("and c.id < :cursor ")
            if (query.from != null) append("and c.startedAt >= :from ")
            if (query.to != null) append("and c.startedAt < :to ")
            if (query.type != null) append("and e.type = :type ")
            if (query.kind != null) append("and e.kind = :kind ")
            append("order by c.id desc")
        }
        val request = entityManager.createQuery(jpql, AutomationDecisionCycleEntity::class.java)
            .setParameter("accountId", accountId).setMaxResults(query.limit + 1)
        query.beforeCycleId?.let { request.setParameter("cursor", it) }
        query.from?.let { request.setParameter("from", it) }; query.to?.let { request.setParameter("to", it) }
        query.type?.let { request.setParameter("type", it) }; query.kind?.let { request.setParameter("kind", it) }
        val rows = request.resultList
        val current = rows.take(query.limit)
        return AutomationHistoryPage(current.map(::toHistory), if (rows.size > query.limit) current.last().id else null)
    }

    private fun toHistory(cycle: AutomationDecisionCycleEntity): AutomationHistoryCycle {
        val events = entityManager.createQuery(
            "select e from AutomationDecisionEventEntity e where e.cycle.id = :cycleId order by e.sequence asc, e.id asc",
            AutomationDecisionEventEntity::class.java,
        ).setParameter("cycleId", cycle.id).resultList.map { e -> AutomationHistoryEvent(
            e.id, e.sequence, e.entryId, e.type, e.kind, e.reasonCode, e.message, e.targetKey,
            e.targetName, e.actionKind, e.presetId, e.presetName, e.nextRunAt, e.occurredAt,
        ) }
        return AutomationHistoryCycle(cycle.id, cycle.result, cycle.selectedEntryId, cycle.startedAt, cycle.finishedAt, events)
    }

    private fun presetName(accountId: Long, presetId: Long?): String? = presetId?.let {
        entityManager.find(PartyPresetEntity::class.java, it)?.takeIf { preset -> preset.account.id == accountId }?.name
    }
}
