package app.spammy.hof.automation.history

import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.raid.RaidCycleOutcome
import app.spammy.hof.automation.raid.RaidCycleOutcomeKind
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
    val nextRunAt: Instant? = null,
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

fun RaidCycleOutcome.toAutomationActionTrace(): AutomationActionTrace = AutomationActionTrace(
    kind = if (kind == RaidCycleOutcomeKind.COMPLETED) {
        AutomationHistoryEventKind.CYCLE_COMPLETED
    } else {
        AutomationHistoryEventKind.CYCLE_ABORTED
    },
    reasonCode = kind.name,
    message = when (kind) {
        RaidCycleOutcomeKind.COMPLETED -> "레이드 사이클을 완료했습니다."
        RaidCycleOutcomeKind.ABORTED_CLOSED -> "닫힌 레이드의 자동화 사이클을 종료했습니다."
        RaidCycleOutcomeKind.ABORTED_REGISTRATION_LOST -> "등록 상태가 유실된 레이드 사이클을 종료했습니다."
        RaidCycleOutcomeKind.HANDED_OFF_MANUAL -> "진행 중인 레이드를 수동 제어로 인계했습니다."
        RaidCycleOutcomeKind.SUPERSEDED_BY_OBSERVED_RAID -> "원본 서버에서 관측된 다른 레이드로 사이클을 교체했습니다."
    },
    entryId = entryId,
    type = AutomationType.RAID,
    actionKind = "CYCLE",
    targetKey = raidId,
)

interface AutomationDecisionJournal {
    fun appendDecision(accountId: Long, decision: AutomationCoordination): Long
    fun appendPreparedActionAttempt(accountId: Long, result: AutomationActionTrace): Long
    fun appendActionResult(cycleId: Long, result: AutomationActionTrace)
    fun appendRaidCycleOutcome(accountId: Long, outcome: RaidCycleOutcome): Long
    fun page(accountId: Long, query: AutomationHistoryQuery): AutomationHistoryPage
}

@Service
class JpaAutomationDecisionJournal(
    private val entityManager: EntityManager,
    private val timeProvider: TimeProvider,
    private val cycleCommands: AutomationDecisionCycleCommandRepository,
    private val eventCommands: AutomationDecisionEventCommandRepository,
) : AutomationDecisionJournal {
    @Transactional
    override fun appendDecision(accountId: Long, decision: AutomationCoordination): Long {
        val now = timeProvider.now()
        val cycle = AutomationDecisionCycleEntity(accountId = accountId, result = when (decision) {
            is AutomationCoordination.Runnable -> AutomationDecisionResult.ACTION_SELECTED
            is AutomationCoordination.Unavailable -> AutomationDecisionResult.WAITING
            is AutomationCoordination.Idle -> AutomationDecisionResult.IDLE
            is AutomationCoordination.Fatal -> AutomationDecisionResult.FATAL
        }, selectedEntryId = (decision as? AutomationCoordination.Runnable)?.entryId, startedAt = now, finishedAt = now)
        cycleCommands.save(cycle)
        eventCommands.saveAll(decision.trace.map { item -> AutomationDecisionEventEntity(
            cycle = cycle, sequence = item.sequence, entryId = item.entryId, type = item.type,
            kind = when (item.outcome) {
                AutomationDecisionOutcome.SELECTED -> AutomationHistoryEventKind.SELECTED
                AutomationDecisionOutcome.SKIPPED -> AutomationHistoryEventKind.SKIPPED
                AutomationDecisionOutcome.WAITING -> AutomationHistoryEventKind.WAITING
                AutomationDecisionOutcome.CONFIGURATION_WARNING -> AutomationHistoryEventKind.CONFIGURATION_WARNING
                AutomationDecisionOutcome.CYCLE_COMPLETED -> AutomationHistoryEventKind.CYCLE_COMPLETED
                AutomationDecisionOutcome.CYCLE_ABORTED -> AutomationHistoryEventKind.CYCLE_ABORTED
                AutomationDecisionOutcome.FATAL -> AutomationHistoryEventKind.ACTION_FAILED
            }, reasonCode = item.reasonCode, message = item.message, nextRunAt = item.nextRunAt,
            actionKind = item.actionKind, targetKey = item.targetKey, targetName = item.targetName,
            presetId = item.presetId, presetName = presetName(accountId, item.presetId), occurredAt = now,
        ) })
        eventCommands.flush()
        return cycle.id
    }

    @Transactional
    override fun appendPreparedActionAttempt(accountId: Long, result: AutomationActionTrace): Long {
        val now = timeProvider.now()
        val cycle = AutomationDecisionCycleEntity(
            accountId = accountId,
            result = AutomationDecisionResult.ACTION_SELECTED,
            selectedEntryId = result.entryId,
            startedAt = now,
            finishedAt = now,
        )
        cycleCommands.save(cycle)
        eventCommands.save(result.toEntity(cycle, 0, accountId, now))
        eventCommands.flush()
        return cycle.id
    }

    @Transactional
    override fun appendActionResult(cycleId: Long, result: AutomationActionTrace) {
        val cycle = entityManager.find(AutomationDecisionCycleEntity::class.java, cycleId)
            ?: throw IllegalArgumentException("Automation decision cycle not found.")
        val next = entityManager.createQuery(
            "select coalesce(max(e.sequence), -1) + 1 from AutomationDecisionEventEntity e where e.cycle.id = :cycleId", java.lang.Integer::class.java,
        ).setParameter("cycleId", cycleId).singleResult.toInt()
        eventCommands.save(result.toEntity(cycle, next, cycle.accountId, timeProvider.now()))
    }

    @Transactional
    override fun appendRaidCycleOutcome(accountId: Long, outcome: RaidCycleOutcome): Long {
        val now = timeProvider.now()
        val trace = outcome.toAutomationActionTrace()
        val cycle = AutomationDecisionCycleEntity(
            accountId = accountId,
            result = AutomationDecisionResult.IDLE,
            selectedEntryId = outcome.entryId,
            startedAt = now,
            finishedAt = now,
        )
        cycleCommands.save(cycle)
        eventCommands.save(trace.toEntity(cycle, 0, accountId, now))
        eventCommands.flush()
        return cycle.id
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

    private fun AutomationActionTrace.toEntity(
        cycle: AutomationDecisionCycleEntity,
        sequence: Int,
        accountId: Long,
        occurredAt: Instant,
    ) = AutomationDecisionEventEntity(
        cycle = cycle,
        sequence = sequence,
        entryId = entryId,
        type = type,
        kind = kind,
        reasonCode = reasonCode,
        message = message,
        targetKey = targetKey,
        targetName = targetName,
        actionKind = actionKind,
        presetId = presetId,
        presetName = presetName ?: presetName(accountId, presetId),
        nextRunAt = nextRunAt,
        occurredAt = occurredAt,
    )
}
