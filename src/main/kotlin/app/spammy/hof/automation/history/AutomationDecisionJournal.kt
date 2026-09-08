package app.spammy.hof.automation.history

import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.AutomationEntryEntity
import app.spammy.hof.automation.entity.automationEntryDisplayNames
import app.spammy.hof.automation.raid.RaidCycleOutcome
import app.spammy.hof.automation.raid.RaidCycleOutcomeKind
import app.spammy.hof.automation.raid.RaidCooldownSource
import app.spammy.hof.automation.service.*
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.party.entity.PartyPresetEntity
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.databind.node.ObjectNode

data class AutomationActionTrace(
    val kind: AutomationHistoryEventKind, val reasonCode: String, val message: String,
    val entryId: Long? = null, val type: AutomationType? = null, val actionKind: String? = null,
    val targetKey: String? = null, val targetName: String? = null,
    val presetId: Long? = null, val presetName: String? = null,
    val nextRunAt: Instant? = null,
    val diagnosticKind: AutomationDiagnosticKind? = null,
    val cooldownSource: RaidCooldownSource? = null,
    val impactScope: AutomationImpactScope? = null,
    val releaseCondition: String? = null,
    val diagnosticContext: String? = null,
)
data class AutomationHistoryQuery(
    val beforeCycleId: Long? = null, val limit: Int = 20, val type: AutomationType? = null,
    val kind: AutomationHistoryEventKind? = null, val from: Instant? = null, val to: Instant? = null,
)
data class AutomationHistoryEvent(
    val id: Long, val sequence: Int, val entryId: Long?, val type: AutomationType?,
    val entryDisplayName: String?,
    val kind: AutomationHistoryEventKind, val reasonCode: String, val message: String,
    val targetKey: String?, val targetName: String?, val actionKind: String?,
    val presetId: Long?, val presetName: String?, val nextRunAt: Instant?, val occurredAt: Instant,
    val diagnosticKind: AutomationDiagnosticKind?, val cooldownSource: RaidCooldownSource?,
    val impactScope: AutomationImpactScope?, val releaseCondition: String?,
    val diagnosticContext: String? = null,
)
data class AutomationHistoryCycle(
    val id: Long, val result: AutomationDecisionResult, val selectedEntryId: Long?,
    val startedAt: Instant, val finishedAt: Instant, val events: List<AutomationHistoryEvent>,
    val topLevelStepCount: Int = events.size,
    val steps: List<AutomationHistoryStep> = events.mapIndexed { index, event ->
        AutomationHistoryStep(index + 1, event)
    },
)
data class AutomationHistoryStep(
    val sequence: Int,
    val event: AutomationHistoryEvent,
    val executionEvents: List<AutomationHistoryEvent> = emptyList(),
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
    fun appendResultObservation(accountId: Long, result: AutomationActionTrace): Long
    fun appendRaidCycleOutcome(accountId: Long, outcome: RaidCycleOutcome): Long
    fun page(accountId: Long, query: AutomationHistoryQuery): AutomationHistoryPage
}

@Service
class JpaAutomationDecisionJournal(
    private val entityManager: EntityManager,
    private val timeProvider: TimeProvider,
    private val cycleCommands: AutomationDecisionCycleCommandRepository,
    private val eventCommands: AutomationDecisionEventCommandRepository,
    private val progressTelemetry: AutomationProgressTelemetry? = null,
) : AutomationDecisionJournal {
    private val diagnosticMapper = jacksonObjectMapper()
    @Transactional
    override fun appendDecision(accountId: Long, decision: AutomationCoordination): Long {
        val now = timeProvider.now()
        val entryDisplayNames = entryDisplayNames(accountId)
        val cycle = AutomationDecisionCycleEntity(accountId = accountId, result = when (decision) {
            is AutomationCoordination.Runnable -> AutomationDecisionResult.ACTION_SELECTED
            is AutomationCoordination.Unavailable -> if (
                decision.waitScope == AutomationWaitScope.HOLD_CURRENT_WORK ||
                decision.trace.any { it.outcome == AutomationDecisionOutcome.WAITING }
            ) AutomationDecisionResult.WAITING else AutomationDecisionResult.IDLE
            is AutomationCoordination.CycleBoundary -> AutomationDecisionResult.IDLE
            is AutomationCoordination.Idle -> AutomationDecisionResult.IDLE
            is AutomationCoordination.Fatal -> AutomationDecisionResult.FATAL
        }, selectedEntryId = (decision as? AutomationCoordination.Runnable)?.entryId, startedAt = now, finishedAt = now)
        cycleCommands.save(cycle)
        eventCommands.saveAll(decision.trace.map { item -> AutomationDecisionEventEntity(
            cycle = cycle, sequence = item.sequence, entryId = item.entryId, type = item.type,
            entryDisplayName = entryDisplayNames[item.entryId],
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
            diagnosticKind = item.diagnosticKind, cooldownSource = item.cooldownSource,
            impactScope = item.impactScope, releaseCondition = item.releaseCondition,
            diagnosticContext = item.diagnosticContext,
        ) })
        // 일반 다음 판단도 최신 전투 부재를 확인할 수 있다. probe를 거치지 않은 해소를 보존한다.
        decision.trace.filter { it.type == AutomationType.FISHING }.distinctBy { it.entryId }.forEach { item ->
            val observed = item.diagnosticContext?.let { diagnosticMapper.readTree(it).path("snapshot") }
            if (observed?.path("battleObservationComplete")?.asBoolean(false) == true &&
                observed.path("blockedByBattle").isBoolean && !observed.path("blockedByBattle").asBoolean() &&
                latestFishingProgress(accountId, item.entryId)?.reasonCode == FISHING_RECOVERY_REASON
            ) {
                appendActionResult(cycle.id, AutomationActionTrace(
                    kind = AutomationHistoryEventKind.SKIPPED, reasonCode = "FISHING_RECOVERY_RESOLVED",
                    message = "최신 목록에서 이전 낚시 전투의 부재를 확인하고 다시 판단했습니다.",
                    entryId = item.entryId, type = AutomationType.FISHING, actionKind = "OBSERVATION",
                    diagnosticContext = item.diagnosticContext,
                ))
            }
        }
        eventCommands.flush()
        afterCommitTelemetry { progressTelemetry?.recordDecision(accountId, decision) }
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
        eventCommands.save(fishingContext(cycle, result).toEntity(cycle, 0, accountId, now))
        eventCommands.flush()
        return cycle.id
    }

    @Transactional
    override fun appendResultObservation(accountId: Long, result: AutomationActionTrace): Long {
        val now = timeProvider.now()
        val cycle = cycleCommands.save(AutomationDecisionCycleEntity(
            accountId = accountId,
            result = if (result.nextRunAt != null) AutomationDecisionResult.WAITING else AutomationDecisionResult.IDLE,
            selectedEntryId = null, startedAt = now, finishedAt = now,
        ))
        appendActionResult(cycle.id, result)
        return cycle.id
    }

    @Transactional
    override fun appendActionResult(cycleId: Long, result: AutomationActionTrace) {
        val cycle = entityManager.find(AutomationDecisionCycleEntity::class.java, cycleId)
            ?: throw IllegalArgumentException("Automation decision cycle not found.")
        val next = entityManager.createQuery(
            "select coalesce(max(e.sequence), -1) + 1 from AutomationDecisionEventEntity e where e.cycle.id = :cycleId",
            Int::class.javaObjectType,
        ).setParameter("cycleId", cycleId).singleResult.toInt()
        val entryDisplayName = entityManager.createQuery(
            "select e.entryDisplayName from AutomationDecisionEventEntity e where e.cycle.id = :cycleId and e.entryId = :entryId and e.entryDisplayName is not null order by e.sequence asc",
            String::class.java,
        ).setParameter("cycleId", cycleId)
            .setParameter("entryId", result.entryId ?: -1L)
            .setMaxResults(1)
            .resultList
            .firstOrNull()
        val recorded = fishingContext(cycle, result)
        eventCommands.save(recorded.toEntity(cycle, next, cycle.accountId, timeProvider.now(), entryDisplayName))
        if (recorded.reasonCode == FISHING_RECOVERY_REASON &&
            recorded.diagnosticContext?.let { diagnosticMapper.readTree(it).path("repetition").path("count").asInt() } == 3
        ) {
            eventCommands.save(recorded.copy(
                kind = AutomationHistoryEventKind.CONFIGURATION_WARNING,
                reasonCode = "FISHING_RECOVERY_REPEATED",
                message = "같은 낚시 전투 복구가 진전 없이 세 번 반복됐습니다. 당시 상태와 전투 대상을 확인해 주세요.",
            ).toEntity(cycle, next + 1, cycle.accountId, timeProvider.now(), entryDisplayName))
            log.warn("Fishing recovery repeated accountId={} entryId={} decisionCycleId={} targetKey={}",
                cycle.accountId, recorded.entryId, cycle.id, recorded.targetKey)
        }
        if (result.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED) {
            afterCommitTelemetry { progressTelemetry?.recordTerminalAction(cycle.accountId, result.type) }
        }
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
            e.id, e.sequence, e.entryId, e.type, e.entryDisplayName, e.kind, e.reasonCode, e.message, e.targetKey,
            e.targetName, e.actionKind, e.presetId, e.presetName, e.nextRunAt, e.occurredAt,
            e.diagnosticKind, e.cooldownSource, e.impactScope, e.releaseCondition,
            e.diagnosticContext,
        ) }
        val steps = groupSteps(events, cycle.selectedEntryId)
        return AutomationHistoryCycle(
            cycle.id,
            cycle.result,
            cycle.selectedEntryId,
            cycle.startedAt,
            cycle.finishedAt,
            events,
            topLevelStepCount = steps.size,
            steps = steps,
        )
    }

    private fun groupSteps(
        events: List<AutomationHistoryEvent>,
        selectedEntryId: Long?,
    ): List<AutomationHistoryStep> {
        val attachedObservations = events.filter { observation ->
            observation.reasonCode == "FISHING_RECOVERY_RESOLVED" && observation.actionKind == "OBSERVATION" &&
                events.any { it.id < observation.id && it.entryId == observation.entryId &&
                    it.reasonCode != "FISHING_RECOVERY_RESOLVED" }
        }
        if (attachedObservations.isNotEmpty()) {
            return groupSteps(events - attachedObservations.toSet(), selectedEntryId).map { step ->
                step.copy(executionEvents = (step.executionEvents + attachedObservations.filter {
                    it.entryId == step.event.entryId
                }).sortedBy { it.sequence })
            }
        }
        val selectedIndex = events.indexOfFirst { event ->
            event.kind == AutomationHistoryEventKind.SELECTED &&
                (selectedEntryId == null || event.entryId == selectedEntryId)
        }
        if (selectedIndex < 0) {
            return events.mapIndexed { index, event -> AutomationHistoryStep(index + 1, event) }
        }
        val decisionEvents = events.take(selectedIndex + 1)
        val executionEvents = events.drop(selectedIndex + 1)
        val selectedEvent = decisionEvents.last()
        val selectedChildren = executionEvents.filter { event ->
            event.entryId == selectedEvent.entryId ||
                event.entryId == null && event.type == selectedEvent.type
        }
        val standalone = executionEvents.filterNot(selectedChildren::contains)
        return buildList {
            decisionEvents.forEachIndexed { index, event ->
                add(
                    AutomationHistoryStep(
                        sequence = index + 1,
                        event = event,
                        executionEvents = if (index == selectedIndex) selectedChildren else emptyList(),
                    ),
                )
            }
            standalone.forEach { event ->
                add(AutomationHistoryStep(size + 1, event))
            }
        }
    }

    private fun presetName(accountId: Long, presetId: Long?): String? = presetId?.let {
        entityManager.find(PartyPresetEntity::class.java, it)?.takeIf { preset -> preset.account.id == accountId }?.name
    }

    private fun fishingContext(cycle: AutomationDecisionCycleEntity, trace: AutomationActionTrace): AutomationActionTrace {
        if (trace.type != AutomationType.FISHING || trace.diagnosticContext == null) return trace
        val node = diagnosticMapper.readTree(trace.diagnosticContext) as? ObjectNode ?: return trace
        val entry = trace.entryId?.let { entityManager.find(AutomationEntryEntity::class.java, it) }
            ?.takeIf { it.account.id == cycle.accountId }
        val workId = entityManager.createQuery(
            "select w.id from AutomationWorkSessionEntity w where w.account.id = :accountId and w.entry.id = :entryId order by w.id desc",
            Long::class.javaObjectType,
        ).setParameter("accountId", cycle.accountId).setParameter("entryId", trace.entryId ?: -1L)
            .setMaxResults(1).resultList.firstOrNull()?.toLong()
        node.put("decisionCycleId", cycle.id)
        node.put("workSessionId", workId)
        node.put("settingsRevision", entry?.settingsRevision)
        if (trace.reasonCode == FISHING_RECOVERY_REASON && node.path("fishing").isObject) {
            annotateFishingRepetition(cycle, trace, node)
        }
        val target = node.path("fishing").path("battleMapCode").takeIf { it.isString }?.asString()
        return trace.copy(targetKey = trace.targetKey ?: target, diagnosticContext = AutomationDecisionDiagnostics.encode(node))
    }

    private fun latestFishingProgress(accountId: Long, entryId: Long?): AutomationDecisionEventEntity? {
        // 같은 낚시 항목의 마지막 복구 또는 진전만 읽는다. 다른 항목의 성공과 새 작업 ID는 경계가 아니다.
        return entityManager.createQuery(
            "select e from AutomationDecisionEventEntity e where e.cycle.accountId = :accountId " +
                "and e.entryId = :entryId and e.type = :type and (e.reasonCode in :reasons " +
                "or (e.kind = :success and e.actionKind <> 'START')) order by e.id desc",
            AutomationDecisionEventEntity::class.java,
        ).setParameter("accountId", accountId).setParameter("entryId", entryId)
            .setParameter("type", AutomationType.FISHING)
            .setParameter("reasons", listOf(FISHING_RECOVERY_REASON, "FISHING_DAILY_LIMIT", "FISHING_RECOVERY_RESOLVED"))
            .setParameter("success", AutomationHistoryEventKind.ACTION_SUCCEEDED)
            .setMaxResults(1).resultList.firstOrNull()
    }

    private fun annotateFishingRepetition(
        cycle: AutomationDecisionCycleEntity,
        trace: AutomationActionTrace,
        node: ObjectNode,
    ) {
        val previous = latestFishingProgress(cycle.accountId, trace.entryId)
        val prior = previous?.diagnosticContext?.let { runCatching { diagnosticMapper.readTree(it) }.getOrNull() }
        val continued = previous?.reasonCode == FISHING_RECOVERY_REASON && prior != null &&
            prior.path("settingsRevision").asLong() == node.path("settingsRevision").asLong() &&
            prior.path("fishing") == node.path("fishing")
        val old = prior?.path("repetition")
        val count = if (continued) (old?.path("count")?.asInt() ?: 1).coerceAtLeast(1) + 1 else 1
        val repetition = node.putObject("repetition")
        repetition.put("count", count)
        repetition.put("firstEventId", if (continued) old?.path("firstEventId")?.takeIf { it.isIntegralNumber }
            ?.asLong() ?: previous?.id else null)
        repetition.put("firstCycleId", if (continued) old?.path("firstCycleId")?.asLong() ?: previous?.cycle?.id else cycle.id)
        repetition.put("firstSeenAt", if (continued) old?.path("firstSeenAt")?.takeIf { it.isString }?.asString()
            ?: previous?.occurredAt?.toString() else timeProvider.now().toString())
        repetition.put("lastSeenAt", timeProvider.now().toString())
        repetition.put("lastCycleId", cycle.id)
    }

    private fun AutomationActionTrace.toEntity(
        cycle: AutomationDecisionCycleEntity,
        sequence: Int,
        accountId: Long,
        occurredAt: Instant,
        entryDisplayName: String? = null,
    ) = AutomationDecisionEventEntity(
        cycle = cycle,
        sequence = sequence,
        entryId = entryId,
        entryDisplayName = entryDisplayName ?: entryDisplayNames(accountId)[entryId],
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
        diagnosticKind = diagnosticKind,
        cooldownSource = cooldownSource,
        impactScope = impactScope,
        releaseCondition = releaseCondition,
        diagnosticContext = diagnosticContext,
    )

    private fun entryDisplayNames(accountId: Long): Map<Long, String> {
        val entries = entityManager.createQuery(
            "select e from AutomationEntryEntity e where e.account.id = :accountId order by e.priority asc, e.id asc",
            AutomationEntryEntity::class.java,
        ).setParameter("accountId", accountId).resultList
        return automationEntryDisplayNames(entries)
    }

    private fun afterCommitTelemetry(action: () -> Unit) {
        if (progressTelemetry == null) return
        val safeAction = {
            try {
                action()
            } catch (error: RuntimeException) {
                log.warn("Automation progress telemetry failed after journal commit.", error)
            }
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() = safeAction()
                },
            )
        } else {
            safeAction()
        }
    }

    private companion object {
        const val FISHING_RECOVERY_REASON = "FISHING_BATTLE_RECOVERED_FROM_START"
        val log = LoggerFactory.getLogger(JpaAutomationDecisionJournal::class.java)
    }
}
