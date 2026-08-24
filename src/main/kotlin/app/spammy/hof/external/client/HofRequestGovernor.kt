package app.spammy.hof.external.client

import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.config.HofRequestProperties
import app.spammy.hof.external.model.HofBinaryResponse
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequestOrigin
import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

class HofAutomationDeferredException(
    val retryAt: Instant,
    val consecutiveFailures: Int,
    val requestAttempted: Boolean = false,
    val actionSubmissionAttempted: Boolean = requestAttempted,
    val reasonCode: String? = null,
) : RuntimeException(reasonCode ?: "HOF automation requests are deferred until $retryAt")

class HofCaptchaRetryException : RuntimeException("HOF CAPTCHA request returned 503 and must be retried")

fun interface HofRequestWaiter {
    fun waitFor(duration: Duration)
}

internal fun interface HofRequestQueueObserver {
    fun queued(accountId: Long, origin: HofRequestOrigin)
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
    private val accountStates = ConcurrentHashMap<Long, AccountRequestState>()
    @Volatile
    internal var queueObserver: HofRequestQueueObserver = HofRequestQueueObserver { _, _ -> }

    fun execute(
        accountId: Long,
        origin: HofRequestOrigin,
        request: () -> HofHttpResponse,
    ): HofHttpResponse = executeInternal(accountId, origin, request) { response -> response.statusCode }

    fun executeBinary(
        accountId: Long,
        origin: HofRequestOrigin,
        request: () -> HofBinaryResponse,
    ): HofBinaryResponse = executeInternal(accountId, origin, request) { response -> response.statusCode }

    private fun <T> executeInternal(
        accountId: Long,
        origin: HofRequestOrigin,
        request: () -> T,
        statusCode: (T) -> Int,
    ): T {
        val state = accountStates.computeIfAbsent(accountId) { AccountRequestState() }
        val queuedAtNanos = System.nanoTime()
        acquireExecutionSlot(accountId, state, origin)

        try {
            val requestStartedAtNanos = System.nanoTime()
            val response = try {
                request()
            } catch (error: Throwable) {
                log.warn(
                    "HOF QUEUE accountId={} origin={} queueWaitMs={} durationMs={} outcome=failed errorType={}",
                    accountId,
                    origin,
                    (requestStartedAtNanos - queuedAtNanos) / NANOS_PER_MILLISECOND,
                    (System.nanoTime() - requestStartedAtNanos) / NANOS_PER_MILLISECOND,
                    error.javaClass.simpleName,
                )
                throw error
            } finally {
                state.setNextAllowedAt(
                    origin,
                    timeProvider.now().plus(properties.minimumIntervalFor(origin)),
                )
            }

            val responseStatus = statusCode(response)
            log.info(
                "HOF QUEUE accountId={} origin={} queueWaitMs={} durationMs={} status={}",
                accountId,
                origin,
                (requestStartedAtNanos - queuedAtNanos) / NANOS_PER_MILLISECOND,
                (System.nanoTime() - requestStartedAtNanos) / NANOS_PER_MILLISECOND,
                responseStatus,
            )
            if (origin == HofRequestOrigin.CAPTCHA) {
                if (responseStatus == SERVICE_UNAVAILABLE) {
                    throw HofCaptchaRetryException()
                }
                return response
            }
            if (responseStatus != SERVICE_UNAVAILABLE) {
                state.consecutiveServiceUnavailable = 0
                state.cooldownUntil = null
                return response
            }

            state.consecutiveServiceUnavailable += 1
            val cooldown = if (state.consecutiveServiceUnavailable >= properties.longCooldownThreshold) {
                properties.longCooldown
            } else {
                properties.shortCooldown
            }
            val retryAt = timeProvider.now().plus(cooldown)
            state.cooldownUntil = retryAt
            // 503 재시도 시각은 일반 origin 간격보다 우선한다. 공유 cooldown이 정확한 재개 시각을 통제한다.
            state.setNextAllowedAt(origin, null)
            throw unavailable(state, origin, retryAt, requestAttempted = true)
        } finally {
            releaseExecutionSlot(state)
        }
    }

    private fun acquireExecutionSlot(
        accountId: Long,
        state: AccountRequestState,
        origin: HofRequestOrigin,
    ) {
        try {
            state.lock.lockInterruptibly()
        } catch (exception: InterruptedException) {
            throw interruptedQueueWait(exception)
        }

        val queuedRequest = QueuedRequest(state.lock.newCondition())
        val queue = state.queueFor(origin)
        queue.addLast(queuedRequest)
        try {
            queueObserver.queued(accountId, origin)
            while (true) {
                while (
                    state.executing ||
                    state.nextEligible() !== queuedRequest
                ) {
                    queuedRequest.condition.await()
                }

                rejectDuringCooldown(state, origin)
                val allowedAt = state.nextAllowedAt(origin)
                val remaining = allowedAt?.let { Duration.between(timeProvider.now(), it) }
                if (allowedAt == null || remaining == null || remaining.isNegative || remaining.isZero) {
                    queue.removeFirst()
                    state.executing = true
                    return
                }

                waitForRequestSpacing(state, origin, allowedAt, remaining)
            }
        } catch (exception: InterruptedException) {
            queue.remove(queuedRequest)
            state.signalNextEligible()
            throw interruptedQueueWait(exception)
        } catch (error: Throwable) {
            queue.remove(queuedRequest)
            state.signalNextEligible()
            throw error
        } finally {
            state.lock.unlock()
        }
    }

    private fun releaseExecutionSlot(state: AccountRequestState) {
        state.lock.lock()
        try {
            state.executing = false
            state.signalNextEligible()
        } finally {
            state.lock.unlock()
        }
    }

    private fun interruptedQueueWait(exception: InterruptedException): IllegalStateException {
        Thread.currentThread().interrupt()
        return IllegalStateException("Interrupted while waiting for the HOF request queue", exception)
    }

    private fun rejectDuringCooldown(state: AccountRequestState, origin: HofRequestOrigin) {
        if (origin == HofRequestOrigin.CAPTCHA) return
        val retryAt = state.cooldownUntil ?: return
        if (timeProvider.now().isBefore(retryAt)) {
            throw unavailable(state, origin, retryAt, requestAttempted = false)
        }
        state.cooldownUntil = null
    }

    private fun unavailable(
        state: AccountRequestState,
        origin: HofRequestOrigin,
        retryAt: Instant,
        requestAttempted: Boolean,
    ): RuntimeException =
        when (origin) {
            HofRequestOrigin.CAPTCHA -> error("CAPTCHA 503 responses must be retried before reaching cooldown handling")
            HofRequestOrigin.AUTOMATION -> HofAutomationDeferredException(
                retryAt = retryAt,
                consecutiveFailures = state.consecutiveServiceUnavailable,
                requestAttempted = requestAttempted,
            )
            HofRequestOrigin.INTERACTIVE -> ApiException(
                ErrorCode.HOF_TEMPORARILY_UNAVAILABLE,
                FRIENDLY_UNAVAILABLE_MESSAGE,
            )
        }

    private fun waitForRequestSpacing(
        state: AccountRequestState,
        origin: HofRequestOrigin,
        allowedAt: Instant,
        remaining: Duration,
    ) {
        state.lock.unlock()
        var waitFailure: Throwable? = null
        try {
            waiter.waitFor(remaining)
        } catch (error: Throwable) {
            waitFailure = error
        } finally {
            state.lock.lock()
        }

        waitFailure?.let { throw it }
        if (Thread.currentThread().isInterrupted) {
            throw interruptedQueueWait(InterruptedException("Interrupted after spacing HOF requests"))
        }
        if (state.nextAllowedAt(origin) == allowedAt) state.setNextAllowedAt(origin, null)
        state.signalNextEligible()
    }

    private class QueuedRequest(val condition: Condition)

    private class AccountRequestState {
        val lock = ReentrantLock()
        val captchaWaiters = ArrayDeque<QueuedRequest>()
        val interactiveWaiters = ArrayDeque<QueuedRequest>()
        val automationWaiters = ArrayDeque<QueuedRequest>()
        var executing = false
        private var captchaNextAllowedAt: Instant? = null
        private var interactiveNextAllowedAt: Instant? = null
        private var automationNextAllowedAt: Instant? = null
        var cooldownUntil: Instant? = null
        var consecutiveServiceUnavailable = 0

        fun queueFor(origin: HofRequestOrigin): ArrayDeque<QueuedRequest> =
            when (origin) {
                HofRequestOrigin.CAPTCHA -> captchaWaiters
                HofRequestOrigin.INTERACTIVE -> interactiveWaiters
                HofRequestOrigin.AUTOMATION -> automationWaiters
            }

        fun nextEligible(): QueuedRequest? =
            captchaWaiters.peekFirst() ?: interactiveWaiters.peekFirst() ?: automationWaiters.peekFirst()

        fun nextAllowedAt(origin: HofRequestOrigin): Instant? =
            when (origin) {
                HofRequestOrigin.CAPTCHA -> captchaNextAllowedAt
                HofRequestOrigin.INTERACTIVE -> interactiveNextAllowedAt
                HofRequestOrigin.AUTOMATION -> automationNextAllowedAt
            }

        fun setNextAllowedAt(origin: HofRequestOrigin, value: Instant?) {
            when (origin) {
                HofRequestOrigin.CAPTCHA -> captchaNextAllowedAt = value
                HofRequestOrigin.INTERACTIVE -> interactiveNextAllowedAt = value
                HofRequestOrigin.AUTOMATION -> automationNextAllowedAt = value
            }
        }

        fun signalNextEligible() {
            if (!executing) nextEligible()?.condition?.signal()
        }
    }

    private companion object {
        val CAPTCHA_INTERVAL: Duration = Duration.ofMillis(500)
        const val SERVICE_UNAVAILABLE = 503
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val FRIENDLY_UNAVAILABLE_MESSAGE =
            "HOF 서버 연결이 일시적으로 원활하지 않습니다. 잠시 후 다시 시도해 주세요."
    }

    private fun HofRequestProperties.minimumIntervalFor(origin: HofRequestOrigin): Duration =
        when (origin) {
            HofRequestOrigin.CAPTCHA -> CAPTCHA_INTERVAL
            HofRequestOrigin.INTERACTIVE -> interactiveMinimumInterval
            HofRequestOrigin.AUTOMATION -> automationMinimumInterval
        }
}
