package app.spammy.hof.common.security

import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import org.springframework.core.MethodParameter
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.context.request.NativeWebRequest
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.mockito.Mockito

class CurrentAccountIdArgumentResolverTest {
    private val resolver = CurrentAccountIdArgumentResolver()
    private val parameter = MethodParameter(
        Fixture::class.java.getDeclaredMethod("endpoint", Long::class.javaPrimitiveType),
        0,
    )

    @AfterTest
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun resolvesPositiveAccountIdOnlyFromJwtSubject() {
        SecurityContextHolder.getContext().authentication = TestingAuthenticationToken(jwt("42"), null)

        val resolved = resolver.resolveArgument(
            parameter,
            null,
            Mockito.mock(NativeWebRequest::class.java),
            null,
        )

        assertTrue(resolver.supportsParameter(parameter))
        assertEquals(42L, resolved)
    }

    @Test
    fun rejectsMissingNonNumericOrNonPositiveSubject() {
        listOf(null, "account", "0", "-1").forEach { subject ->
            SecurityContextHolder.getContext().authentication =
                subject?.let { TestingAuthenticationToken(jwt(it), null) }
            val error = assertFailsWith<ApiException> {
                resolver.resolveArgument(parameter, null, Mockito.mock(NativeWebRequest::class.java), null)
            }
            assertEquals(ErrorCode.AUTH_TOKEN_INVALID, error.errorCode)
        }
    }

    private fun jwt(subject: String): Jwt =
        Jwt.withTokenValue("token")
            .header("alg", "none")
            .subject(subject)
            .issuedAt(Instant.parse("2026-07-13T00:00:00Z"))
            .expiresAt(Instant.parse("2026-07-13T01:00:00Z"))
            .build()

    @Suppress("unused")
    private class Fixture {
        fun endpoint(@CurrentAccountId accountId: Long) = accountId
    }
}
