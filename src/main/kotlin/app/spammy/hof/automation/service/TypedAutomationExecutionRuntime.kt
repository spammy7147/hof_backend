package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.AutomationWaitReason
import java.time.Instant

/**
 * 자동화 한 회차의 저장 상태를 숨긴 실행권이다.
 *
 * caller는 lease token이나 action row를 조립하지 않고 검증된 domain checkpoint만 읽는다.
 */
interface TypedRuntimeExecutionRight {
    val checkpoint: TypedRuntimeCheckpoint?
}

data class TypedRuntimeCheckpoint(
    val storedAction: StoredTypedAutomationAction,
    val phase: TypedRuntimeCheckpointPhase,
    val submittedAt: Instant?,
    val diagnostic: String?,
)

enum class TypedRuntimeCheckpointPhase {
    PREPARED,
    RECONCILING,
}

sealed interface TypedRuntimeAcquisition {
    data object Inactive : TypedRuntimeAcquisition
    data object Busy : TypedRuntimeAcquisition
    data class RetryScheduled(val retryAt: Instant) : TypedRuntimeAcquisition
    data class Acquired(val execution: TypedRuntimeExecutionRight) : TypedRuntimeAcquisition
}

sealed interface TypedRuntimePreparation {
    data object Invalidated : TypedRuntimePreparation
    data class Ready(val execution: TypedRuntimeExecutionRight) : TypedRuntimePreparation
}

sealed interface TypedRuntimeSubmission {
    data object Invalidated : TypedRuntimeSubmission
    data class Started(val submittedAt: Instant) : TypedRuntimeSubmission
}

/** Runner가 해석한 domain 결과를 runtime 저장 상태로 원자적으로 투영하는 입력이다. */
sealed interface TypedRuntimeOutcome {
    data object Idle : TypedRuntimeOutcome
    data class SelectionChanged(val wakeReason: String) : TypedRuntimeOutcome
    data class ScheduledWait(
        val nextRunAt: Instant,
        val waitReason: AutomationWaitReason,
        val warnings: List<String> = emptyList(),
    ) : TypedRuntimeOutcome
    data class ConfigurationWait(val warnings: List<String>) : TypedRuntimeOutcome
    data class SafeRetry(val message: String) : TypedRuntimeOutcome
    data class RetryableFailure(
        val reason: AutomationStopReason,
        val message: String,
        val warnings: List<String>? = null,
    ) : TypedRuntimeOutcome
    data class ActionSucceeded(
        val wakeReason: String,
        val warnings: List<String>? = emptyList(),
    ) : TypedRuntimeOutcome
    data class SharedCooldownHandled(
        val wakeReason: String = "TYPED_SHARED_COOLDOWN_SKIPPED",
        val warnings: List<String>? = emptyList(),
    ) : TypedRuntimeOutcome
    data class SubmissionDeferred(val retryAt: Instant, val message: String) : TypedRuntimeOutcome
    data class SubmissionAmbiguous(val message: String) : TypedRuntimeOutcome
    data class ReconciliationApplied(val wakeReason: String) : TypedRuntimeOutcome
    data class ReconciliationResubmit(val wakeReason: String) : TypedRuntimeOutcome
    data class ReconciliationDeferred(val retryAt: Instant, val reason: String) : TypedRuntimeOutcome
    data class AmbiguousHandoff(
        val warning: String,
        val wakeReason: String,
    ) : TypedRuntimeOutcome
    data class IntegrityFailure(val message: String) : TypedRuntimeOutcome
}

data class TypedRuntimeProjection(
    val applied: Boolean,
    val nextAttemptAt: Instant? = null,
)
