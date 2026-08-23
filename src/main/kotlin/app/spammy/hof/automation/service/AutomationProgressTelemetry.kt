package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.AutomationWorkType
import app.spammy.hof.common.time.TimeProvider
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

enum class AutomationOwnershipTransferReason {
    ACTION_PREPARE,
    DUE_RESUME,
    PRIORITY_YIELD,
    WAIT,
    COMPLETE,
}

data class AutomationProgressSnapshot(
    val observedAt: Instant,
    val staleEntryId: Long? = null,
    val staleWorkSessionId: Long? = null,
    val staleScope: String? = null,
    val staleNextAllowedAt: Instant? = null,
    val consecutiveStaleCount: Int = 0,
    val staleElapsedSeconds: Long = 0,
    val progressPressureSince: Instant? = null,
    val secondsWithoutTerminalAction: Long = 0,
    val stalled: Boolean = false,
    val pendingScopeReleaseSince: Instant? = null,
)

/**
 * 판단 기록의 의미를 바꾸지 않고 progress SLO용 수치와 경고를 남긴다.
 * 계정 ID는 로그 상관관계에만 사용하고 meter tag에는 넣지 않아 cardinality를 제한한다.
 */
@Service
class AutomationProgressTelemetry(
    private val meterRegistry: MeterRegistry,
    private val timeProvider: TimeProvider,
) {
    private val states = ConcurrentHashMap<Long, AccountProgress>()
    private val dueDelay = ConcurrentHashMap<AutomationWorkType, DistributionSummary>()
    private val ownershipTransfers = ConcurrentHashMap<OwnershipMetricKey, Counter>()

    fun recordDecision(accountId: Long, decision: AutomationCoordination) {
        val now = timeProvider.now()
        val staleTraces = decision.trace.filter { it.reasonCode == QUEST_PROGRESS_STALE_REASON }
        val stale = staleTraces.lastOrNull()
        val scopeRelease = decision.trace.lastOrNull { it.reasonCode in SCOPE_RELEASE_REASONS }
        if (staleTraces.isNotEmpty()) {
            meterRegistry.counter(QUEST_STALE_METER).increment(staleTraces.size.toDouble())
        }
        if (scopeRelease != null) meterRegistry.counter(SCOPE_BLOCKED_METER).increment()

        var stallWarning: ProgressWarning? = null
        var staleWarning: ProgressWarning? = null
        var releasedScopeDelay: Long? = null
        states.compute(accountId) { _, existing ->
            val current = existing ?: AccountProgress()
            staleTraces.forEach { trace -> current.updateStale(trace, now) }
            if (staleTraces.isEmpty()) current.clearStale()

            if (scopeRelease != null && current.scopeReleaseSince == null) {
                current.scopeReleaseSince = scopeRelease.observedAt ?: now
            }
            if (decision is AutomationCoordination.Runnable) {
                current.scopeReleaseSince?.let { releasedScopeDelay = elapsedSeconds(it, now) }
                current.scopeReleaseSince = null
            }

            val hasProgressPressure = decision is AutomationCoordination.Runnable ||
                stale != null || scopeRelease != null
            if (hasProgressPressure) {
                if (current.progressPressureSince == null) current.progressPressureSince = now
                val baseline = listOfNotNull(current.progressPressureSince, current.lastTerminalActionAt).maxOrNull()
                val withoutAction = baseline?.let { elapsedSeconds(it, now) } ?: 0
                if (withoutAction >= STALL_SECONDS && !current.stallWarned) {
                    current.stallWarned = true
                    stallWarning = current.warning(withoutAction, now)
                }
            } else {
                current.progressPressureSince = null
                current.stallWarned = false
            }

            if (
                stale != null && !current.staleWarned &&
                (current.staleCount > MAX_CONSECUTIVE_STALE || current.staleElapsed(now) > MAX_STALE_SECONDS)
            ) {
                current.staleWarned = true
                staleWarning = current.warning(0, now)
            }
            current
        }

        releasedScopeDelay?.let { seconds ->
            meterRegistry.summary(SCOPE_RELEASE_DELAY_METER).record(seconds.toDouble())
            log.info(
                "Automation progress scope released accountId={} selectionDelaySeconds={} reasonCode={}",
                accountId,
                seconds,
                SCOPE_RELEASED_REASON,
            )
        }
        staleWarning?.let { warning ->
            meterRegistry.counter(PROGRESS_STALL_METER, "reason", STALE_LIMIT_REASON).increment()
            log.warn(
                "Automation progress warning accountId={} reasonCode={} staleEntryId={} workSessionId={} " +
                    "scope={} nextAllowedAt={} consecutiveStaleCount={} staleElapsedSeconds={}",
                accountId,
                STALE_LIMIT_REASON,
                warning.staleEntryId,
                warning.staleWorkSessionId,
                warning.staleScope,
                warning.staleNextAllowedAt,
                warning.staleCount,
                warning.staleElapsedSeconds,
            )
        }
        stallWarning?.let { warning ->
            emitStallWarning(accountId, warning)
        }
    }

    /** 판단 파이프라인 자체가 멈춘 경우에도 wall-clock SLO를 독립적으로 감시한다. */
    @Scheduled(fixedDelayString = "\${hof.automation.progress-monitor-delay-ms:10000}")
    internal fun detectStalls() {
        val now = timeProvider.now()
        val warnings = mutableListOf<Pair<Long, ProgressWarning>>()
        states.keys.forEach { accountId ->
            states.computeIfPresent(accountId) { _, current ->
                val baseline = listOfNotNull(current.progressPressureSince, current.lastTerminalActionAt).maxOrNull()
                val withoutAction = baseline?.let { elapsedSeconds(it, now) } ?: 0
                if (withoutAction >= STALL_SECONDS && !current.stallWarned) {
                    current.stallWarned = true
                    warnings += accountId to current.warning(withoutAction, now)
                }
                current
            }
        }
        warnings.forEach { (accountId, warning) -> emitStallWarning(accountId, warning) }
    }

    fun recordTerminalAction(accountId: Long, type: AutomationType?) {
        val now = timeProvider.now()
        states.compute(accountId) { _, existing ->
            (existing ?: AccountProgress()).also {
                it.lastTerminalActionAt = now
                it.progressPressureSince = null
                it.stallWarned = false
            }
        }
        meterRegistry.counter(ACTION_TERMINAL_METER, "type", type?.name ?: "UNKNOWN").increment()
    }

    fun recordDueSession(type: AutomationWorkType, dueAt: Instant?) {
        if (dueAt == null) return
        val seconds = elapsedSeconds(dueAt, timeProvider.now())
        if (seconds < 0) return
        dueDelay.computeIfAbsent(type) {
            DistributionSummary.builder(DUE_DELAY_METER)
                .baseUnit("seconds")
                .tag("type", type.name)
                .register(meterRegistry)
        }.record(seconds.toDouble())
        if (seconds > MAX_DUE_DELAY_SECONDS) {
            log.warn(
                "Automation progress warning reasonCode={} workType={} dueDelaySeconds={}",
                DUE_DELAY_REASON,
                type,
                seconds,
            )
        }
    }

    fun recordOwnershipTransfer(reason: AutomationOwnershipTransferReason, duplicateRepair: Boolean) {
        val key = OwnershipMetricKey(reason, duplicateRepair)
        ownershipTransfers.computeIfAbsent(key) {
            Counter.builder(OWNERSHIP_TRANSFER_METER)
                .tag("reason", reason.name)
                .tag("duplicate_repair", duplicateRepair.toString())
                .register(meterRegistry)
        }.increment()
    }

    internal fun snapshot(accountId: Long): AutomationProgressSnapshot {
        val now = timeProvider.now()
        val state = states[accountId] ?: return AutomationProgressSnapshot(now)
        val baseline = listOfNotNull(state.progressPressureSince, state.lastTerminalActionAt).maxOrNull()
        val withoutAction = baseline?.let { elapsedSeconds(it, now) } ?: 0
        return AutomationProgressSnapshot(
            observedAt = now,
            staleEntryId = state.staleEntryId,
            staleWorkSessionId = state.staleWorkSessionId,
            staleScope = state.staleScope,
            staleNextAllowedAt = state.staleNextAllowedAt,
            consecutiveStaleCount = state.staleCount,
            staleElapsedSeconds = state.staleElapsed(now),
            progressPressureSince = state.progressPressureSince,
            secondsWithoutTerminalAction = withoutAction,
            stalled = state.stallWarned,
            pendingScopeReleaseSince = state.scopeReleaseSince,
        )
    }

    private fun elapsedSeconds(from: Instant, to: Instant): Long = Duration.between(from, to).seconds

    private fun emitStallWarning(accountId: Long, warning: ProgressWarning) {
        meterRegistry.counter(PROGRESS_STALL_METER, "reason", NO_TERMINAL_ACTION_REASON).increment()
        log.warn(
            "Automation progress warning accountId={} reasonCode={} secondsWithoutTerminalAction={} " +
                "staleEntryId={} workSessionId={} scope={} nextAllowedAt={} " +
                "consecutiveStaleCount={} staleElapsedSeconds={}",
            accountId,
            NO_TERMINAL_ACTION_REASON,
            warning.withoutActionSeconds,
            warning.staleEntryId,
            warning.staleWorkSessionId,
            warning.staleScope,
            warning.staleNextAllowedAt,
            warning.staleCount,
            warning.staleElapsedSeconds,
        )
    }

    private data class AccountProgress(
        var lastTerminalActionAt: Instant? = null,
        var progressPressureSince: Instant? = null,
        var stallWarned: Boolean = false,
        var staleEntryId: Long? = null,
        var staleWorkSessionId: Long? = null,
        var staleScope: String? = null,
        var staleNextAllowedAt: Instant? = null,
        var staleCount: Int = 0,
        var staleSince: Instant? = null,
        var staleWarned: Boolean = false,
        var scopeReleaseSince: Instant? = null,
    ) {
        fun updateStale(trace: AutomationEvaluationTrace, now: Instant) {
            if (staleEntryId == trace.entryId && staleWorkSessionId == trace.workSessionId) {
                staleCount += 1
                staleNextAllowedAt = trace.nextRunAt
                return
            }
            staleEntryId = trace.entryId
            staleWorkSessionId = trace.workSessionId
            staleScope = trace.scope ?: trace.targetKey?.let { "QUEST:$it" }
            staleNextAllowedAt = trace.nextRunAt
            staleCount = 1
            staleSince = now
            staleWarned = false
        }

        fun clearStale() {
            staleEntryId = null
            staleWorkSessionId = null
            staleScope = null
            staleNextAllowedAt = null
            staleCount = 0
            staleSince = null
            staleWarned = false
        }

        fun staleElapsed(now: Instant): Long = staleSince?.let { Duration.between(it, now).seconds } ?: 0

        fun warning(withoutActionSeconds: Long, now: Instant) = ProgressWarning(
            withoutActionSeconds = withoutActionSeconds,
            staleCount = staleCount,
            staleElapsedSeconds = staleElapsed(now),
            staleEntryId = staleEntryId,
            staleWorkSessionId = staleWorkSessionId,
            staleScope = staleScope,
            staleNextAllowedAt = staleNextAllowedAt,
        )
    }

    private data class OwnershipMetricKey(
        val reason: AutomationOwnershipTransferReason,
        val duplicateRepair: Boolean,
    )

    private data class ProgressWarning(
        val withoutActionSeconds: Long,
        val staleCount: Int,
        val staleElapsedSeconds: Long,
        val staleEntryId: Long?,
        val staleWorkSessionId: Long?,
        val staleScope: String?,
        val staleNextAllowedAt: Instant?,
    )

    private companion object {
        val log = LoggerFactory.getLogger(AutomationProgressTelemetry::class.java)
        const val QUEST_PROGRESS_STALE_REASON = "QUEST_PROGRESS_STALE"
        const val CONVERGENCE_SCOPE_BLOCKED_REASON = "CONVERGENCE_SCOPE_BLOCKED"
        const val OBSERVATION_GAP_HELD_REASON = "OBSERVATION_GAP_HELD"
        const val NO_TERMINAL_ACTION_REASON = "NO_TERMINAL_ACTION"
        const val STALE_LIMIT_REASON = "QUEST_STALE_LIMIT_EXCEEDED"
        const val DUE_DELAY_REASON = "DUE_SESSION_DELAY_EXCEEDED"
        const val SCOPE_RELEASED_REASON = "SCOPE_RELEASE_TO_SELECTION"
        const val QUEST_STALE_METER = "hof.automation.quest.stale"
        const val SCOPE_BLOCKED_METER = "hof.automation.scope.blocked"
        const val PROGRESS_STALL_METER = "hof.automation.progress.stall"
        const val ACTION_TERMINAL_METER = "hof.automation.action.terminal"
        const val DUE_DELAY_METER = "hof.automation.work.due.delay"
        const val SCOPE_RELEASE_DELAY_METER = "hof.automation.scope.release.selection.delay"
        const val OWNERSHIP_TRANSFER_METER = "hof.automation.work.ownership.transfer"
        const val STALL_SECONDS = 180
        const val MAX_CONSECUTIVE_STALE = 2
        const val MAX_STALE_SECONDS = 30
        const val MAX_DUE_DELAY_SECONDS = 120
        val SCOPE_RELEASE_REASONS = setOf(CONVERGENCE_SCOPE_BLOCKED_REASON, OBSERVATION_GAP_HELD_REASON)
    }
}
