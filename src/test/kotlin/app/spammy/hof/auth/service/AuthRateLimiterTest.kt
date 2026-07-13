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
    fun limitsLoginByNormalizedHofIdWithinWindow() {
        limiter.checkLogin("203.0.113.1", " HOF-User ")
        limiter.checkLogin("203.0.113.2", "hof-user")

        val error = assertFailsWith<ApiException> {
            limiter.checkLogin("203.0.113.3", "HOF-USER")
        }

        assertEquals(ErrorCode.RATE_LIMITED, error.errorCode)
        assertEquals(60L, error.retryAfterSeconds)
    }

    @Test
    fun limitsRefreshByClientAddressAndAllowsRequestsAfterWindow() {
        limiter.checkRefresh("203.0.113.10")
        limiter.checkRefresh("203.0.113.10")

        assertFailsWith<ApiException> { limiter.checkRefresh("203.0.113.10") }

        now = now.plusSeconds(61)
        limiter.checkRefresh("203.0.113.10")
    }

    private fun properties() = AuthProperties(
        jwtSecret = SECRET,
        credentialEncryptionKey = SECRET,
        cookieEncryptionKey = SECRET,
        rateLimitWindow = Duration.ofMinutes(1),
        loginRateLimitPerIp = 10,
        loginRateLimitPerId = 2,
        refreshRateLimitPerIp = 2,
    )

    private companion object {
        const val SECRET = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA="
    }
}
