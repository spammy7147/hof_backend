package app.spammy.hof.external.client

import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.config.HofRequestProperties
import app.spammy.hof.external.model.HofHttpResponse
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HofAutomationRequestGovernorTest {
    @Test
    fun `waits at least the configured interval between completed requests`() {
        val clock = MutableTimeProvider(NOW)
        val waiter = RecordingWaiter(clock)
        val governor = governor(clock, waiter)

        governor.execute { response(200) }
        governor.execute { response(200) }

        assertEquals(listOf(Duration.ofMillis(100)), waiter.waits)
    }

    @Test
    fun `first and second 503 defer all automation requests for thirty seconds`() {
        val clock = MutableTimeProvider(NOW)
        val governor = governor(clock, RecordingWaiter(clock))
        var outboundCalls = 0

        val first = assertFailsWith<HofAutomationDeferredException> {
            governor.execute {
                outboundCalls += 1
                response(503)
            }
        }
        assertEquals(NOW.plusSeconds(30), first.retryAt)
        assertEquals(1, first.consecutiveFailures)

        val whileCoolingDown = assertFailsWith<HofAutomationDeferredException> {
            governor.execute {
                outboundCalls += 1
                response(200)
            }
        }
        assertEquals(first.retryAt, whileCoolingDown.retryAt)
        assertEquals(1, outboundCalls)

        clock.current = first.retryAt
        val second = assertFailsWith<HofAutomationDeferredException> {
            governor.execute {
                outboundCalls += 1
                response(503)
            }
        }
        assertEquals(clock.current.plusSeconds(30), second.retryAt)
        assertEquals(2, second.consecutiveFailures)
        assertEquals(2, outboundCalls)
    }

    @Test
    fun `third consecutive 503 defers all automation requests for three minutes`() {
        val clock = MutableTimeProvider(NOW)
        val governor = governor(clock, RecordingWaiter(clock))

        repeat(2) {
            val deferred = assertFailsWith<HofAutomationDeferredException> {
                governor.execute { response(503) }
            }
            clock.current = deferred.retryAt
        }

        val third = assertFailsWith<HofAutomationDeferredException> {
            governor.execute { response(503) }
        }

        assertEquals(clock.current.plus(Duration.ofMinutes(3)), third.retryAt)
        assertEquals(3, third.consecutiveFailures)
    }

    @Test
    fun `a non-503 response resets the consecutive failure count`() {
        val clock = MutableTimeProvider(NOW)
        val governor = governor(clock, RecordingWaiter(clock))

        val first = assertFailsWith<HofAutomationDeferredException> {
            governor.execute { response(503) }
        }
        clock.current = first.retryAt
        governor.execute { response(200) }

        clock.current = clock.current.plusMillis(500)
        val afterSuccess = assertFailsWith<HofAutomationDeferredException> {
            governor.execute { response(503) }
        }

        assertEquals(1, afterSuccess.consecutiveFailures)
        assertEquals(clock.current.plusSeconds(30), afterSuccess.retryAt)
    }

    private fun governor(
        clock: TimeProvider,
        waiter: HofRequestWaiter,
    ) = HofAutomationRequestGovernor(
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
