package app.spammy.hof.external.client

import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HofControlSignalTest {
    @Test
    fun rethrowsOnlyGovernorControlSignalsUnchanged() {
        val unavailable = ApiException(ErrorCode.HOF_TEMPORARILY_UNAVAILABLE, "retry later")
        val deferred = HofAutomationDeferredException(Instant.parse("2026-07-30T03:00:00Z"), 2)

        assertTrue(unavailable.isHofControlSignal())
        assertTrue(deferred.isHofControlSignal())
        assertSame(unavailable, assertFailsWith<ApiException> { unavailable.rethrowIfHofControlSignal() })
        assertSame(
            deferred,
            assertFailsWith<HofAutomationDeferredException> { deferred.rethrowIfHofControlSignal() },
        )
    }

    @Test
    fun ignoresUnrelatedFailures() {
        val apiFailure = ApiException(ErrorCode.HOF_REQUEST_FAILED, "ordinary failure")
        val transportFailure = IllegalStateException("transport failure")

        assertFalse(apiFailure.isHofControlSignal())
        assertFalse(transportFailure.isHofControlSignal())
        apiFailure.rethrowIfHofControlSignal()
        transportFailure.rethrowIfHofControlSignal()
    }
}
