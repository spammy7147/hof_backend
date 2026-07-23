package app.spammy.hof.external.client

import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.config.HofRequestProperties
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequestOrigin
import java.time.Duration
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

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
            throw IllegalStateException("Interrupted while spacing HOF requests", exception)
        }
    }
}

@Component
class HofRequestGovernor(
    private val properties: HofRequestProperties,
    private val timeProvider: TimeProvider,
    private val waiter: HofRequestWaiter,
) {
    private val log = LoggerFactory.getLogger(HofRequestGovernor::class.java)
    private val executionLock = ReentrantLock(true)
    private var nextAllowedAt: Instant? = null
    private var cooldownUntil: Instant? = null
    private var consecutiveServiceUnavailable = 0

    fun execute(request: () -> HofHttpResponse): HofHttpResponse =
        execute(HofRequestOrigin.AUTOMATION, request)

    fun execute(origin: HofRequestOrigin, request: () -> HofHttpResponse): HofHttpResponse {
        val queuedAtNanos = System.nanoTime()
        try {
            executionLock.lockInterruptibly()
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while waiting for the HOF request queue", exception)
        }

        try {
            rejectDuringCooldown(origin)
            waitForRequestSpacing()
            val requestStartedAtNanos = System.nanoTime()
            val response = try {
                request()
            } catch (error: Throwable) {
                log.warn(
                    "HOF QUEUE origin={} queueWaitMs={} durationMs={} outcome=failed errorType={}",
                    origin,
                    (requestStartedAtNanos - queuedAtNanos) / NANOS_PER_MILLISECOND,
                    (System.nanoTime() - requestStartedAtNanos) / NANOS_PER_MILLISECOND,
                    error.javaClass.simpleName,
                )
                throw error
            } finally {
                nextAllowedAt = timeProvider.now().plus(properties.minimumInterval)
            }

            log.info(
                "HOF QUEUE origin={} queueWaitMs={} durationMs={} status={}",
                origin,
                (requestStartedAtNanos - queuedAtNanos) / NANOS_PER_MILLISECOND,
                (System.nanoTime() - requestStartedAtNanos) / NANOS_PER_MILLISECOND,
                response.statusCode,
            )
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
            throw unavailable(origin, retryAt)
        } finally {
            executionLock.unlock()
        }
    }

    private fun rejectDuringCooldown(origin: HofRequestOrigin) {
        val retryAt = cooldownUntil ?: return
        if (timeProvider.now().isBefore(retryAt)) throw unavailable(origin, retryAt)
        cooldownUntil = null
    }

    private fun unavailable(origin: HofRequestOrigin, retryAt: Instant): RuntimeException =
        when (origin) {
            HofRequestOrigin.AUTOMATION -> HofAutomationDeferredException(
                retryAt = retryAt,
                consecutiveFailures = consecutiveServiceUnavailable,
            )
            HofRequestOrigin.INTERACTIVE -> ApiException(
                ErrorCode.HOF_TEMPORARILY_UNAVAILABLE,
                FRIENDLY_UNAVAILABLE_MESSAGE,
            )
        }

    private fun waitForRequestSpacing() {
        val allowedAt = nextAllowedAt ?: return
        val remaining = Duration.between(timeProvider.now(), allowedAt)
        if (!remaining.isNegative && !remaining.isZero) waiter.waitFor(remaining)
    }

    private companion object {
        const val SERVICE_UNAVAILABLE = 503
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val FRIENDLY_UNAVAILABLE_MESSAGE =
            "HOF 서버 연결이 일시적으로 원활하지 않습니다. 잠시 후 다시 시도해 주세요."
    }
}

typealias HofAutomationRequestGovernor = HofRequestGovernor
