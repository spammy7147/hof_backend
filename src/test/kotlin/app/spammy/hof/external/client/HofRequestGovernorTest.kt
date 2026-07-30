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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HofRequestGovernorTest {
    @Test
    fun `waits one second by default between completed requests`() {
        val clock = MutableTimeProvider(NOW)
        val waiter = RecordingWaiter(clock)
        val governor = governor(clock, waiter)

        governor.execute(ACCOUNT_A, HofRequestOrigin.INTERACTIVE) { response(200) }
        governor.execute(ACCOUNT_A, HofRequestOrigin.AUTOMATION) { response(200) }

        assertEquals(listOf(Duration.ofSeconds(1)), waiter.waits)
    }

    @Test
    fun `interactive request overtakes queued automation after running request completes`() {
        val governor = governor(MutableTimeProvider(NOW), HofRequestWaiter { })
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val order = Collections.synchronizedList(mutableListOf<String>())

        val first = thread {
            governor.execute(ACCOUNT_A, HofRequestOrigin.AUTOMATION) {
                order += "automation-1"
                firstEntered.countDown()
                assertTrue(releaseFirst.await(2, TimeUnit.SECONDS))
                response(200)
            }
        }
        assertTrue(firstEntered.await(2, TimeUnit.SECONDS))

        val second = thread {
            governor.execute(ACCOUNT_A, HofRequestOrigin.AUTOMATION) {
                order += "automation-2"
                response(200)
            }
        }
        awaitQueued(second)

        val third = thread {
            governor.execute(ACCOUNT_A, HofRequestOrigin.INTERACTIVE) {
                order += "interactive"
                response(200)
            }
        }
        awaitQueued(third)
        assertEquals(listOf("automation-1"), order)

        releaseFirst.countDown()
        first.join(2_000)
        second.join(2_000)
        third.join(2_000)

        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
        assertFalse(third.isAlive)
        assertEquals(listOf("automation-1", "interactive", "automation-2"), order)
    }

    @Test
    fun `request spacing is isolated between accounts`() {
        val clock = MutableTimeProvider(NOW)
        val waiter = RecordingWaiter(clock)
        val governor = governor(clock, waiter)

        governor.execute(ACCOUNT_A, HofRequestOrigin.INTERACTIVE) { response(200) }
        governor.execute(ACCOUNT_B, HofRequestOrigin.INTERACTIVE) { response(200) }

        assertEquals(emptyList(), waiter.waits)

        governor.execute(ACCOUNT_A, HofRequestOrigin.INTERACTIVE) { response(200) }

        assertEquals(listOf(Duration.ofSeconds(1)), waiter.waits)
    }

    @Test
    fun `requests for different accounts execute concurrently`() {
        val governor = governor(MutableTimeProvider(NOW), HofRequestWaiter { })
        val accountAEntered = CountDownLatch(1)
        val releaseAccountA = CountDownLatch(1)
        val accountBCompleted = CountDownLatch(1)
        val childFailure = AtomicReference<Throwable>()

        val first = thread {
            try {
                governor.execute(ACCOUNT_A, HofRequestOrigin.AUTOMATION) {
                    accountAEntered.countDown()
                    releaseAccountA.await()
                    response(200)
                }
            } catch (error: Throwable) {
                childFailure.compareAndSet(null, error)
            }
        }
        assertTrue(accountAEntered.await(2, TimeUnit.SECONDS))

        val second = thread {
            try {
                governor.execute(ACCOUNT_B, HofRequestOrigin.AUTOMATION) { response(200) }
                accountBCompleted.countDown()
            } catch (error: Throwable) {
                childFailure.compareAndSet(null, error)
            }
        }

        val completedWhileAccountABlocked = try {
            accountBCompleted.await(500, TimeUnit.MILLISECONDS)
        } finally {
            releaseAccountA.countDown()
        }
        first.join(2_000)
        second.join(2_000)
        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
        childFailure.get()?.let { throw AssertionError("Child request failed", it) }
        assertTrue(completedWhileAccountABlocked)
    }

    @Test
    fun `same-priority waiters preserve FIFO while interactive waiters precede automation`() {
        val governor = governor(MutableTimeProvider(NOW), HofRequestWaiter { })
        val blockerEntered = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        val order = Collections.synchronizedList(mutableListOf<String>())

        val blocker = thread {
            governor.execute(ACCOUNT_A, HofRequestOrigin.AUTOMATION) {
                order += "blocker"
                blockerEntered.countDown()
                assertTrue(releaseBlocker.await(2, TimeUnit.SECONDS))
                response(200)
            }
        }
        assertTrue(blockerEntered.await(2, TimeUnit.SECONDS))

        fun queued(origin: HofRequestOrigin, label: String): Thread = thread {
            governor.execute(ACCOUNT_A, origin) {
                order += label
                response(200)
            }
        }.also(::awaitQueued)

        val automation1 = queued(HofRequestOrigin.AUTOMATION, "automation-1")
        val automation2 = queued(HofRequestOrigin.AUTOMATION, "automation-2")
        val interactive1 = queued(HofRequestOrigin.INTERACTIVE, "interactive-1")
        val interactive2 = queued(HofRequestOrigin.INTERACTIVE, "interactive-2")

        releaseBlocker.countDown()
        listOf(blocker, automation1, automation2, interactive1, interactive2).forEach {
            it.join(2_000)
            assertFalse(it.isAlive)
        }
        assertEquals(
            listOf("blocker", "interactive-1", "interactive-2", "automation-1", "automation-2"),
            order,
        )
    }

    @Test
    fun `service unavailable cooldown and failure count are isolated between accounts`() {
        val clock = MutableTimeProvider(NOW)
        val governor = governor(clock, RecordingWaiter(clock))
        var accountBCalls = 0

        val accountA = assertFailsWith<HofAutomationDeferredException> {
            governor.execute(ACCOUNT_A, HofRequestOrigin.AUTOMATION) { response(503) }
        }
        val accountB = assertFailsWith<HofAutomationDeferredException> {
            governor.execute(ACCOUNT_B, HofRequestOrigin.AUTOMATION) {
                accountBCalls += 1
                response(503)
            }
        }

        assertEquals(1, accountA.consecutiveFailures)
        assertEquals(1, accountB.consecutiveFailures)
        assertEquals(1, accountBCalls)
    }

    @Test
    fun `interrupted queued waiter is removed and next waiter remains usable`() {
        val governor = governor(MutableTimeProvider(NOW), HofRequestWaiter { })
        val blockerEntered = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        val interruptedFailure = AtomicReference<Throwable>()
        val interruptRestored = AtomicBoolean(false)
        val nextExecuted = CountDownLatch(1)

        val blocker = thread {
            governor.execute(ACCOUNT_A, HofRequestOrigin.AUTOMATION) {
                blockerEntered.countDown()
                assertTrue(releaseBlocker.await(2, TimeUnit.SECONDS))
                response(200)
            }
        }
        assertTrue(blockerEntered.await(2, TimeUnit.SECONDS))

        val interrupted = thread {
            try {
                governor.execute(ACCOUNT_A, HofRequestOrigin.INTERACTIVE) { response(200) }
            } catch (error: Throwable) {
                interruptedFailure.set(error)
                interruptRestored.set(Thread.currentThread().isInterrupted)
            }
        }
        awaitQueued(interrupted)

        val next = thread {
            governor.execute(ACCOUNT_A, HofRequestOrigin.AUTOMATION) {
                nextExecuted.countDown()
                response(200)
            }
        }
        awaitQueued(next)

        interrupted.interrupt()
        interrupted.join(2_000)
        assertFalse(interrupted.isAlive)
        assertTrue(interruptedFailure.get() is IllegalStateException)
        assertTrue(interruptRestored.get())

        releaseBlocker.countDown()
        assertTrue(nextExecuted.await(2, TimeUnit.SECONDS))
        blocker.join(2_000)
        next.join(2_000)
        assertFalse(blocker.isAlive)
        assertFalse(next.isAlive)
    }

    @Test
    fun `transport failure releases the queue and still spaces the next request`() {
        val clock = MutableTimeProvider(NOW)
        val waiter = RecordingWaiter(clock)
        val governor = governor(clock, waiter)

        assertFailsWith<IllegalStateException> {
            governor.execute(ACCOUNT_A, HofRequestOrigin.INTERACTIVE) {
                clock.current = clock.current.plusSeconds(2)
                throw IllegalStateException("network down")
            }
        }
        governor.execute(ACCOUNT_A, HofRequestOrigin.AUTOMATION) { response(200) }

        assertEquals(listOf(Duration.ofSeconds(1)), waiter.waits)
    }

    @Test
    fun `interrupted spacing restores interrupt status and leaves the queue usable`() {
        val clock = MutableTimeProvider(NOW)
        val governor = governor(clock, ThreadSleepHofRequestWaiter())
        governor.execute(ACCOUNT_A, HofRequestOrigin.INTERACTIVE) { response(200) }
        val failure = AtomicReference<Throwable>()
        val interruptRestored = AtomicBoolean(false)
        val requestBodyCalled = AtomicBoolean(false)

        val interrupted = thread {
            try {
                governor.execute(ACCOUNT_A, HofRequestOrigin.AUTOMATION) {
                    requestBodyCalled.set(true)
                    response(200)
                }
            } catch (error: Throwable) {
                failure.set(error)
                interruptRestored.set(Thread.currentThread().isInterrupted)
            }
        }
        awaitThreadState(interrupted, Thread.State.TIMED_WAITING)

        interrupted.interrupt()
        interrupted.join(2_000)
        assertFalse(interrupted.isAlive)
        assertTrue(failure.get() is IllegalStateException)
        assertTrue(interruptRestored.get())
        assertFalse(requestBodyCalled.get())

        clock.current = clock.current.plusSeconds(2)
        assertEquals(200, governor.execute(ACCOUNT_A, HofRequestOrigin.INTERACTIVE) { response(200) }.statusCode)
    }

    @Test
    fun `first and second 503 defer all automation requests for thirty seconds`() {
        val clock = MutableTimeProvider(NOW)
        val governor = governor(clock, RecordingWaiter(clock))
        var outboundCalls = 0

        val first = assertFailsWith<HofAutomationDeferredException> {
            governor.execute(ACCOUNT_A, HofRequestOrigin.AUTOMATION) {
                outboundCalls += 1
                response(503)
            }
        }
        assertEquals(NOW.plusSeconds(30), first.retryAt)
        assertEquals(1, first.consecutiveFailures)

        val whileCoolingDown = assertFailsWith<HofAutomationDeferredException> {
            governor.execute(ACCOUNT_A, HofRequestOrigin.AUTOMATION) {
                outboundCalls += 1
                response(200)
            }
        }
        assertEquals(first.retryAt, whileCoolingDown.retryAt)
        assertEquals(1, outboundCalls)

        clock.current = first.retryAt
        val second = assertFailsWith<HofAutomationDeferredException> {
            governor.execute(ACCOUNT_A, HofRequestOrigin.AUTOMATION) {
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
            governor.execute(ACCOUNT_A, HofRequestOrigin.AUTOMATION) {
                outboundCalls += 1
                response(503)
            }
        }

        val error = assertFailsWith<ApiException> {
            governor.execute(ACCOUNT_A, HofRequestOrigin.INTERACTIVE) {
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
                governor.execute(ACCOUNT_A, HofRequestOrigin.AUTOMATION) { response(503) }
            }
            clock.current = deferred.retryAt
        }

        val third = assertFailsWith<HofAutomationDeferredException> {
            governor.execute(ACCOUNT_A, HofRequestOrigin.AUTOMATION) { response(503) }
        }

        assertEquals(clock.current.plus(Duration.ofMinutes(3)), third.retryAt)
        assertEquals(3, third.consecutiveFailures)
    }

    @Test
    fun `a non-503 response resets the consecutive failure count`() {
        val clock = MutableTimeProvider(NOW)
        val governor = governor(clock, RecordingWaiter(clock))

        val first = assertFailsWith<HofAutomationDeferredException> {
            governor.execute(ACCOUNT_A, HofRequestOrigin.AUTOMATION) { response(503) }
        }
        clock.current = first.retryAt
        governor.execute(ACCOUNT_A, HofRequestOrigin.AUTOMATION) { response(200) }

        clock.current = clock.current.plusMillis(100)
        val afterSuccess = assertFailsWith<HofAutomationDeferredException> {
            governor.execute(ACCOUNT_A, HofRequestOrigin.AUTOMATION) { response(503) }
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

    private fun awaitQueued(candidate: Thread) {
        awaitThreadState(candidate, Thread.State.WAITING)
    }

    private fun awaitThreadState(candidate: Thread, expectedState: Thread.State) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (candidate.state != expectedState && System.nanoTime() < deadline) {
            Thread.yield()
        }
        assertEquals(expectedState, candidate.state)
    }

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
        private const val ACCOUNT_A = 11L
        private const val ACCOUNT_B = 22L
        private val NOW: Instant = Instant.parse("2026-07-23T00:00:00Z")
    }
}
