package app.spammy.hof.automation.service

import java.time.Instant

sealed interface AmbiguousActionResolution {
    data class Applied(
        val execution: TypedAutomationExecution = TypedAutomationExecution.Completed,
    ) : AmbiguousActionResolution

    data object Resubmit : AmbiguousActionResolution

    data class VerifyLater(
        val retryAt: Instant,
        val reason: String,
    ) : AmbiguousActionResolution
}

fun interface AutomationAmbiguousActionReconciler {
    fun reconcile(accountId: Long, action: StoredTypedAutomationActionV1): AmbiguousActionResolution
}
