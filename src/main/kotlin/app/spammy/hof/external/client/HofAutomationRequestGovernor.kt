package app.spammy.hof.external.client

import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.config.HofAutomationRequestProperties
import app.spammy.hof.external.model.HofHttpResponse
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

class HofAutomationDeferredException(
    val retryAt: Instant,
    val consecutiveFailures: Int,
) : RuntimeException("HOF automation requests are deferred until $retryAt")

fun interface HofRequestWaiter {
    fun waitFor(duration: Duration)
}

@Component
class ThreadSleepHofRequestWaiter : HofRequestWaiter {
    override fun waitFor(duration: Duration) {
        try {
            Thread.sleep(duration)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while spacing HOF automation requests", exception)
        }
    }
}

@Component
class HofAutomationRequestGovernor(
    private val properties: HofAutomationRequestProperties,
    private val timeProvider: TimeProvider,
    private val waiter: HofRequestWaiter,
) {
    private var nextAllowedAt: Instant? = null
    private var cooldownUntil: Instant? = null
    private var consecutiveServiceUnavailable = 0

    @Synchronized
    fun execute(request: () -> HofHttpResponse): HofHttpResponse {
        rejectDuringCooldown()
        waitForRequestSpacing()

        val response = try {
            request()
        } finally {
            nextAllowedAt = timeProvider.now().plus(properties.minimumInterval)
        }

        if (response.statusCode != SERVICE_UNAVAILABLE) {
            consecutiveServiceUnavailable = 0
            cooldownUntil = null
            return response
        }

        consecutiveServiceUnavailable += 1
        val cooldown = if (consecutiveServiceUnavailable >= properties.longCooldownThreshold) {
            properties.longCooldown
        } else {
            properties.shortCooldown
        }
        val retryAt = timeProvider.now().plus(cooldown)
        cooldownUntil = retryAt
        throw HofAutomationDeferredException(retryAt, consecutiveServiceUnavailable)
    }

    private fun rejectDuringCooldown() {
        val retryAt = cooldownUntil ?: return
        if (timeProvider.now().isBefore(retryAt)) {
            throw HofAutomationDeferredException(retryAt, consecutiveServiceUnavailable)
        }
        cooldownUntil = null
    }

    private fun waitForRequestSpacing() {
        val allowedAt = nextAllowedAt ?: return
        val remaining = Duration.between(timeProvider.now(), allowedAt)
        if (!remaining.isNegative && !remaining.isZero) {
            waiter.waitFor(remaining)
        }
    }

    private companion object {
        const val SERVICE_UNAVAILABLE = 503
    }
}
