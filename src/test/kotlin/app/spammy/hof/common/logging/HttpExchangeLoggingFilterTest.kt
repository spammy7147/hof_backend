package app.spammy.hof.common.logging

import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletRequest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
