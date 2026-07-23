package app.spammy.hof.external.client

import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.config.HofRequestProperties
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequestOrigin
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HofRequestGovernorTest {
    @Test
    fun `waits at least the configured interval between completed requests`() {
        val clock = MutableTimeProvider(NOW)
        val waiter = RecordingWaiter(clock)
        val governor = governor(clock, waiter)

        governor.execute(HofRequestOrigin.INTERACTIVE) { response(200) }
        governor.execute(HofRequestOrigin.AUTOMATION) { response(200) }

        assertEquals(listOf(Duration.ofMillis(100)), waiter.waits)
    }

    @Test
    fun `interactive and automation requests execute one at a time in FIFO lock order`() {
        val governor = governor(MutableTimeProvider(NOW), HofRequestWaiter { })
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val order = Collections.synchronizedList(mutableListOf<String>())
        fun awaitQueued(candidate: Thread) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (candidate.state != Thread.State.WAITING && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertEquals(Thread.State.WAITING, candidate.state)
        }

        val first = thread {
            governor.execute(HofRequestOrigin.INTERACTIVE) {
                order += "first"
                firstEntered.countDown()
                assertTrue(releaseFirst.await(2, TimeUnit.SECONDS))
                response(200)
            }
        }
        assertTrue(firstEntered.await(2, TimeUnit.SECONDS))

        val second = thread {
            governor.execute(HofRequestOrigin.AUTOMATION) {
                order += "second"
                response(200)
            }
        }
        awaitQueued(second)

        val third = thread {
            governor.execute(HofRequestOrigin.INTERACTIVE) {
                order += "third"
                response(200)
            }
        }
        awaitQueued(third)
        assertEquals(listOf("first"), order)

        releaseFirst.countDown()
        first.join(2_000)
        second.join(2_000)
        third.join(2_000)

        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
        assertFalse(third.isAlive)
        assertEquals(listOf("first", "second", "third"), order)
    }

    @Test
    fun `transport failure releases the queue and still spaces the next request`() {
        val clock = MutableTimeProvider(NOW)
        val waiter = RecordingWaiter(clock)
        val governor = governor(clock, waiter)

        assertFailsWith<IllegalStateException> {
            governor.execute(HofRequestOrigin.INTERACTIVE) {
                clock.current = clock.current.plusSeconds(2)
                throw IllegalStateException("network down")
            }
        }
        governor.execute(HofRequestOrigin.AUTOMATION) { response(200) }

        assertEquals(listOf(Duration.ofMillis(100)), waiter.waits)
    }

    @Test
    fun `interrupted spacing restores interrupt status and leaves the queue usable`() {
        val clock = MutableTimeProvider(NOW)
        val governor = governor(clock, ThreadSleepHofRequestWaiter())
        governor.execute(HofRequestOrigin.INTERACTIVE) { response(200) }

        Thread.currentThread().interrupt()
        try {
            assertFailsWith<IllegalStateException> {
                governor.execute(HofRequestOrigin.AUTOMATION) { response(200) }
            }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }

        clock.current = clock.current.plusMillis(100)
        assertEquals(200, governor.execute(HofRequestOrigin.INTERACTIVE) { response(200) }.statusCode)
    }

    @Test
    fun `first and second 503 defer all automation requests for thirty seconds`() {
        val clock = MutableTimeProvider(NOW)
        val governor = governor(clock, RecordingWaiter(clock))
        var outboundCalls = 0

        val first = assertFailsWith<HofAutomationDeferredException> {
            governor.execute(HofRequestOrigin.AUTOMATION) {
                outboundCalls += 1
                response(503)
            }
        }
        assertEquals(NOW.plusSeconds(30), first.retryAt)
        assertEquals(1, first.consecutiveFailures)

        val whileCoolingDown = assertFailsWith<HofAutomationDeferredException> {
            governor.execute(HofRequestOrigin.AUTOMATION) {
                outboundCalls += 1
                response(200)
            }
        }
        assertEquals(first.retryAt, whileCoolingDown.retryAt)
        assertEquals(1, outboundCalls)

        clock.current = first.retryAt
        val second = assertFailsWith<HofAutomationDeferredException> {
            governor.execute(HofRequestOrigin.AUTOMATION) {
                outboundCalls += 1
                response(503)
            }
        }
        assertEquals(clock.current.plusSeconds(30), second.retryAt)
        assertEquals(2, second.consecutiveFailures)
        assertEquals(2, outboundCalls)
    }

    @Test
    fun `interactive request receives friendly error during shared cooldown without outbound call`() {
        val clock = MutableTimeProvider(NOW)
        val governor = governor(clock, RecordingWaiter(clock))
        var outboundCalls = 0

        assertFailsWith<HofAutomationDeferredException> {
            governor.execute(HofRequestOrigin.AUTOMATION) {
                outboundCalls += 1
                response(503)
            }
        }

        val error = assertFailsWith<ApiException> {
            governor.execute(HofRequestOrigin.INTERACTIVE) {
                outboundCalls += 1
                response(200)
            }
        }

        assertEquals(ErrorCode.HOF_TEMPORARILY_UNAVAILABLE, error.errorCode)
        assertEquals(1, outboundCalls)
    }

    @Test
    fun `third consecutive 503 defers all automation requests for three minutes`() {
        val clock = MutableTimeProvider(NOW)
        val governor = governor(clock, RecordingWaiter(clock))

        repeat(2) {
            val deferred = assertFailsWith<HofAutomationDeferredException> {
                governor.execute(HofRequestOrigin.AUTOMATION) { response(503) }
            }
            clock.current = deferred.retryAt
        }

        val third = assertFailsWith<HofAutomationDeferredException> {
            governor.execute(HofRequestOrigin.AUTOMATION) { response(503) }
        }

        assertEquals(clock.current.plus(Duration.ofMinutes(3)), third.retryAt)
        assertEquals(3, third.consecutiveFailures)
    }

    @Test
    fun `a non-503 response resets the consecutive failure count`() {
        val clock = MutableTimeProvider(NOW)
        val governor = governor(clock, RecordingWaiter(clock))

        val first = assertFailsWith<HofAutomationDeferredException> {
            governor.execute(HofRequestOrigin.AUTOMATION) { response(503) }
        }
        clock.current = first.retryAt
        governor.execute(HofRequestOrigin.AUTOMATION) { response(200) }

        clock.current = clock.current.plusMillis(100)
        val afterSuccess = assertFailsWith<HofAutomationDeferredException> {
            governor.execute(HofRequestOrigin.AUTOMATION) { response(503) }
        }

        assertEquals(1, afterSuccess.consecutiveFailures)
        assertEquals(clock.current.plusSeconds(30), afterSuccess.retryAt)
    }

    private fun governor(
        clock: TimeProvider,
        waiter: HofRequestWaiter,
    ) = HofRequestGovernor(
        properties = HofRequestProperties(),
        timeProvider = clock,
        waiter = waiter,
    )

    private fun response(statusCode: Int) = HofHttpResponse(
        statusCode = statusCode,
        finalUrl = "https://sic.zerosic.com/test",
        body = "",
        setCookies = emptyMap(),
    )

    private class MutableTimeProvider(var current: Instant) : TimeProvider {
        override fun now(): Instant = current
    }

    private class RecordingWaiter(
        private val clock: MutableTimeProvider,
    ) : HofRequestWaiter {
        val waits = mutableListOf<Duration>()

        override fun waitFor(duration: Duration) {
            waits += duration
            clock.current = clock.current.plus(duration)
        }
    }

    companion object {
        private val NOW: Instant = Instant.parse("2026-07-23T00:00:00Z")
    }
}
