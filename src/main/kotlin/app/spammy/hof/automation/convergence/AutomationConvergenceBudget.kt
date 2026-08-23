package app.spammy.hof.automation.convergence

import java.time.Duration
import java.time.Instant

internal object AutomationConvergenceBudget {
    const val MAX_SUCCESSFUL_OBSERVATIONS: Int = 5
    val MAX_PENDING_DURATION: Duration = Duration.ofMinutes(2)

    fun exhausted(
        successfulObservationCount: Int,
        firstPendingAt: Instant?,
        now: Instant,
    ): Boolean = successfulObservationCount >= MAX_SUCCESSFUL_OBSERVATIONS ||
        firstPendingAt?.plus(MAX_PENDING_DURATION)?.isAfter(now) == false
}
