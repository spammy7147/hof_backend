package app.spammy.hof.external.client

import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.config.HofRequestProperties
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
    private val accountStates = ConcurrentHashMap<Long, AccountRequestState>()

    fun execute(origin: HofRequestOrigin, request: () -> HofHttpResponse): HofHttpResponse =
        execute(LEGACY_ACCOUNT_ID, origin, request)

    fun execute(
        accountId: Long,
        origin: HofRequestOrigin,
        request: () -> HofHttpResponse,
    ): HofHttpResponse {
        val state = accountStates.computeIfAbsent(accountId) { AccountRequestState() }
        val queuedAtNanos = System.nanoTime()
        acquireExecutionSlot(state, origin)

        try {
            rejectDuringCooldown(state, origin)
            waitForRequestSpacing(state)
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
                state.nextAllowedAt = timeProvider.now().plus(properties.minimumInterval)
            }

            log.info(
                "HOF QUEUE accountId={} origin={} queueWaitMs={} durationMs={} status={}",
                accountId,
                origin,
                (requestStartedAtNanos - queuedAtNanos) / NANOS_PER_MILLISECOND,
                (System.nanoTime() - requestStartedAtNanos) / NANOS_PER_MILLISECOND,
                response.statusCode,
            )
            if (response.statusCode != SERVICE_UNAVAILABLE) {
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
            throw unavailable(state, origin, retryAt)
        } finally {
            releaseExecutionSlot(state)
        }
    }

    private fun acquireExecutionSlot(state: AccountRequestState, origin: HofRequestOrigin) {
        try {
            state.lock.lockInterruptibly()
        } catch (exception: InterruptedException) {
            throw interruptedQueueWait(exception)
        }

        val queuedRequest = QueuedRequest(state.lock.newCondition())
        val queue = state.queueFor(origin)
        queue.addLast(queuedRequest)
        try {
            while (state.executing || state.nextEligible() !== queuedRequest) {
                queuedRequest.condition.await()
            }
            queue.removeFirst()
            state.executing = true
        } catch (exception: InterruptedException) {
            queue.remove(queuedRequest)
            state.signalNextEligible()
            throw interruptedQueueWait(exception)
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
        val retryAt = state.cooldownUntil ?: return
        if (timeProvider.now().isBefore(retryAt)) throw unavailable(state, origin, retryAt)
        state.cooldownUntil = null
    }

    private fun unavailable(
        state: AccountRequestState,
        origin: HofRequestOrigin,
        retryAt: Instant,
    ): RuntimeException =
        when (origin) {
            HofRequestOrigin.AUTOMATION -> HofAutomationDeferredException(
                retryAt = retryAt,
                consecutiveFailures = state.consecutiveServiceUnavailable,
            )
            HofRequestOrigin.INTERACTIVE -> ApiException(
                ErrorCode.HOF_TEMPORARILY_UNAVAILABLE,
                FRIENDLY_UNAVAILABLE_MESSAGE,
            )
        }

    private fun waitForRequestSpacing(state: AccountRequestState) {
        val allowedAt = state.nextAllowedAt ?: return
        val remaining = Duration.between(timeProvider.now(), allowedAt)
        if (!remaining.isNegative && !remaining.isZero) waiter.waitFor(remaining)
    }

    private class QueuedRequest(val condition: Condition)

    private class AccountRequestState {
        val lock = ReentrantLock()
        val interactiveWaiters = ArrayDeque<QueuedRequest>()
        val automationWaiters = ArrayDeque<QueuedRequest>()
        var executing = false
        var nextAllowedAt: Instant? = null
        var cooldownUntil: Instant? = null
        var consecutiveServiceUnavailable = 0

        fun queueFor(origin: HofRequestOrigin): ArrayDeque<QueuedRequest> =
            when (origin) {
                HofRequestOrigin.INTERACTIVE -> interactiveWaiters
                HofRequestOrigin.AUTOMATION -> automationWaiters
            }

        fun nextEligible(): QueuedRequest? =
            interactiveWaiters.peekFirst() ?: automationWaiters.peekFirst()

        fun signalNextEligible() {
            if (!executing) nextEligible()?.condition?.signal()
        }
    }

    private companion object {
        const val LEGACY_ACCOUNT_ID = Long.MIN_VALUE
        const val SERVICE_UNAVAILABLE = 503
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val FRIENDLY_UNAVAILABLE_MESSAGE =
            "HOF 서버 연결이 일시적으로 원활하지 않습니다. 잠시 후 다시 시도해 주세요."
    }
}
