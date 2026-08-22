package app.spammy.hof.auth.service

import app.spammy.hof.auth.config.AuthProperties
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AuthRateLimiterTest {
    private var now = Instant.parse("2026-07-13T00:00:00Z")
    private val limiter = AuthRateLimiter(
        properties = properties(),
        timeProvider = TimeProvider { now },
    )

    @Test
    fun limitsLoginByNormalizedHofIdWithoutCouplingDifferentIds() {
        limiter.checkLogin(" HOF-User ")
        limiter.checkLogin("hof-user")

        val error = assertFailsWith<ApiException> {
            limiter.checkLogin("HOF-USER")
        }

        assertEquals(ErrorCode.RATE_LIMITED, error.errorCode)
        assertEquals(60L, error.retryAfterSeconds)
        limiter.checkLogin("another-user")
    }

    @Test
    fun limitsRefreshByFamilyAndAllowsDifferentFamiliesForSameAccount() {
        limiter.checkRefresh("family-a", 42L)
        limiter.checkRefresh("family-a", 42L)

        assertFailsWith<ApiException> { limiter.checkRefresh("family-a", 42L) }

        limiter.checkRefresh("family-b", 42L)
        limiter.checkRefresh("family-b", 42L)
    }

    @Test
    fun limitsRefreshAcrossFamiliesByAccountWithoutCouplingDifferentAccounts() {
        repeat(4) { index -> limiter.checkRefresh("family-$index", 42L) }

        val error = assertFailsWith<ApiException> { limiter.checkRefresh("family-extra", 42L) }

        assertEquals(ErrorCode.RATE_LIMITED, error.errorCode)
        limiter.checkRefresh("other-account-family", 84L)
    }

    @Test
    fun rejectedMultiScopeRefreshDoesNotPartiallyConsumeFamilyAllowance() {
        repeat(4) { index -> limiter.checkRefresh("account-full-$index", 42L) }

        assertFailsWith<ApiException> { limiter.checkRefresh("target-family", 42L) }

        limiter.checkRefresh("target-family", 84L)
        limiter.checkRefresh("target-family", 84L)
        assertFailsWith<ApiException> { limiter.checkRefresh("target-family", 84L) }
    }

    @Test
    fun allowsRequestsAfterWindowAndReportsRemainingRetryAfter() {
        limiter.checkRefresh("family", 42L)
        limiter.checkRefresh("family", 42L)
        now = now.plusSeconds(15).plusMillis(1)

        val error = assertFailsWith<ApiException> { limiter.checkRefresh("family", 42L) }

        assertEquals(45L, error.retryAfterSeconds)
        now = now.plusSeconds(46)
        limiter.checkRefresh("family", 42L)
    }

    private fun properties() = AuthProperties(
        jwtSecret = SECRET,
        credentialEncryptionKey = SECRET,
        cookieEncryptionKey = SECRET,
        rateLimitWindow = Duration.ofMinutes(1),
        loginRateLimitPerId = 2,
        refreshRateLimitPerFamily = 2,
        refreshRateLimitPerAccount = 4,
    )

    private companion object {
        const val SECRET = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA="
    }
}
