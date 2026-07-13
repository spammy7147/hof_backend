package app.spammy.hof.auth.service

import app.spammy.hof.auth.config.AuthProperties
import app.spammy.hof.auth.config.JwtCryptoConfig
import app.spammy.hof.common.time.TimeProvider
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class JwtTokenServiceTest {
    private val properties = AuthProperties(
        jwtSecret = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=",
        credentialEncryptionKey = "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8=",
        accessTokenTtl = Duration.ofMinutes(30),
        issuer = "hof-backend",
        audience = "hof-api",
    )
    private val cryptoConfig = JwtCryptoConfig(properties)
    private val service = JwtTokenService(cryptoConfig.jwtEncoder(), properties, TimeProvider { NOW })

    @Test
    fun issuesHs256AccessTokenWithAccountSubjectAndThirtyMinuteLifetime() {
        val issued = service.issue(42L)
        val jwt = cryptoConfig.jwtDecoder().decode(issued.value)

        assertEquals("42", jwt.subject)
        assertEquals("hof-backend", jwt.getClaimAsString("iss"))
        assertEquals(listOf("hof-api"), jwt.audience)
        assertEquals(NOW.epochSecond, requireNotNull(jwt.issuedAt).epochSecond)
        assertEquals(NOW.plus(Duration.ofMinutes(30)).epochSecond, requireNotNull(jwt.expiresAt).epochSecond)
        assertEquals(requireNotNull(jwt.expiresAt).epochSecond, issued.expiresAt.epochSecond)
        assertEquals("HS256", jwt.headers["alg"])
    }

    private companion object {
        val NOW: Instant = Instant.now()
    }
}
