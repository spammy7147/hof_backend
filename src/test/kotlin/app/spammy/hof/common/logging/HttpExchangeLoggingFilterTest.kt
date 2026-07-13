package app.spammy.hof.common.logging

import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.junit.jupiter.api.extension.ExtendWith
import jakarta.servlet.FilterChain
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@ExtendWith(OutputCaptureExtension::class)
class HttpExchangeLoggingFilterTest {
    private val filter = TestableHttpExchangeLoggingFilter()

    @Test
    fun shouldSkipSseEventStreamsSoResponsesAreNotBuffered() {
        assertTrue(
            filter.shouldSkip(
                requestUri = "/api/accounts/1/characters/sync-jobs/12/events",
                accept = MediaType.TEXT_EVENT_STREAM_VALUE,
            ),
        )
    }

    @Test
    fun shouldStillLogRegularApiRequests() {
        assertFalse(
            filter.shouldSkip(
                requestUri = "/api/accounts/1/characters",
                accept = MediaType.APPLICATION_JSON_VALUE,
            ),
        )
    }

    @Test
    fun authRequestAndResponseBodiesAreNeverWrittenToLogs(output: CapturedOutput) {
        val request = MockHttpServletRequest("POST", "/api/auth/login").apply {
            contentType = MediaType.APPLICATION_JSON_VALUE
            setContent("""{"loginId":"hof-user","password":"plain-password"}""".toByteArray())
        }
        val response = MockHttpServletResponse()
        val chain = FilterChain { _, servletResponse ->
            servletResponse.contentType = MediaType.APPLICATION_JSON_VALUE
            servletResponse.writer.write(
                """{"accessToken":"access-secret","refreshToken":"refresh-secret"}""",
            )
        }

        filter.doFilter(request, response, chain)

        assertFalse(output.out.contains("plain-password"))
        assertFalse(output.out.contains("access-secret"))
        assertFalse(output.out.contains("refresh-secret"))
        assertTrue(output.out.contains("requestBody=<redacted>"))
        assertTrue(output.out.contains("responseBody=<redacted>"))
    }

    private class TestableHttpExchangeLoggingFilter : HttpExchangeLoggingFilter() {
        fun shouldSkip(
            requestUri: String,
            accept: String,
        ): Boolean {
            val request = MockHttpServletRequest("GET", requestUri)
            request.addHeader("Accept", accept)

            return shouldNotFilter(request)
        }
    }
}
