package app.spammy.hof.automation.service

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

internal data class PersistedTypedRuntimeExecutionRight(
    val accountId: Long,
    val leaseToken: String,
    val actionId: Long?,
    override val checkpoint: TypedRuntimeCheckpoint?,
) : TypedRuntimeExecutionRight
