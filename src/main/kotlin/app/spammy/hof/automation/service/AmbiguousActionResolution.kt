package app.spammy.hof.automation.service

import java.time.Instant

sealed interface AmbiguousActionResolution {
    data class Applied(
        val execution: TypedAutomationExecution = TypedAutomationExecution.Completed,
    ) : AmbiguousActionResolution

    data object Resubmit : AmbiguousActionResolution

    data class Superseded(
        val reason: String,
    ) : AmbiguousActionResolution

    data class VerifyLater(
        val retryAt: Instant,
        val reason: String,
    ) : AmbiguousActionResolution

    data class HandedOff(
        val retryAt: Instant,
        val reason: String,
    ) : AmbiguousActionResolution
}
