package app.spammy.hof.common.security

import app.spammy.hof.auth.config.AuthProperties
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.mock.web.MockHttpServletRequest
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SecurityConfigTest {
    @Test
    fun `allows configured extension origins without opening ordinary web origins`() {
        val properties = AuthProperties(
            jwtSecret = "a".repeat(32),
            credentialEncryptionKey = "b".repeat(32),
            cookieEncryptionKey = "c".repeat(32),
            allowedOrigins = listOf("https://hof.example"),
            allowedOriginPatterns = listOf("chrome-extension://*"),
        )
        val source = SecurityConfig(
            properties,
            Mockito.mock(ApiAuthenticationEntryPoint::class.java),
        ).corsConfigurationSource()
        val request = MockHttpServletRequest("GET", "/api/status")
        val configuration = source.getCorsConfiguration(request)!!

        assertEquals("chrome-extension://abcdefghijklmnop", configuration.checkOrigin("chrome-extension://abcdefghijklmnop"))
        assertEquals("https://hof.example", configuration.checkOrigin("https://hof.example"))
        assertNull(configuration.checkOrigin("https://untrusted.example"))
    }
}
