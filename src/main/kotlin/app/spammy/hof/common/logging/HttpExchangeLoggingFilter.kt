package app.spammy.hof.common.logging

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.MediaType
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingRequestWrapper
import org.springframework.web.util.ContentCachingResponseWrapper
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.UUID

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
/**
 * 들어오고 나가는 API 요청/응답을 requestId와 함께 로깅한다.
 */
class HttpExchangeLoggingFilter : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(HttpExchangeLoggingFilter::class.java)

    /**
     * health check와 SSE 스트림은 로깅 필터에서 제외한다.
     */
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.requestURI == "/actuator/health" || request.isSseRequest()

    /**
     * 요청/응답 body를 캐싱해 처리 후 로그로 남긴다.
     */
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val requestId = UUID.randomUUID().toString().take(8)
        val cachedRequest = ContentCachingRequestWrapper(request, REQUEST_CACHE_LIMIT)
        val cachedResponse = ContentCachingResponseWrapper(response)
        val startedAt = System.nanoTime()

        MDC.put("requestId", requestId)
        log.info(
            "HTTP IN requestId={} method={} uri={} remote={} headers={}",
            requestId,
            request.method,
            requestUriWithQuery(request),
            request.remoteAddr,
            requestHeaders(request),
        )

        try {
            filterChain.doFilter(cachedRequest, cachedResponse)
        } catch (error: Exception) {
            log.error(
                "HTTP ERROR requestId={} method={} uri={} durationMs={} error={}",
                requestId,
                request.method,
                requestUriWithQuery(request),
                elapsedMs(startedAt),
                error.message,
                error,
            )
            throw error
        } finally {
            if (request.isAuthenticationRequest()) {
                cachedResponse.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
            }
            log.info(
                "HTTP OUT requestId={} method={} uri={} status={} durationMs={} requestBody={} responseBody={}",
                requestId,
                request.method,
                requestUriWithQuery(request),
                cachedResponse.status,
                elapsedMs(startedAt),
                requestBody(cachedRequest),
                responseBody(cachedRequest, cachedResponse),
            )
            cachedResponse.copyBodyToResponse()
            MDC.remove("requestId")
        }
    }

    /**
     * query string이 있으면 URI에 붙여 로그용 경로를 만든다.
     */
    private fun requestUriWithQuery(request: HttpServletRequest): String =
        if (request.queryString.isNullOrBlank()) {
            request.requestURI
        } else {
            "${request.requestURI}?${request.queryString}"
        }

    /**
     * 요청 header를 로그 문자열로 만들고 민감한 값은 마스킹한다.
     */
    private fun requestHeaders(request: HttpServletRequest): String =
        Collections.list(request.headerNames)
            .joinToString(prefix = "[", postfix = "]") { name ->
                val value = Collections.list(request.getHeaders(name)).joinToString("|")
                LogSanitizer.sanitizeHeader(name, LogSanitizer.preview(value, maxLength = 160))
            }

    /**
     * 요청 body bytes를 문자열로 디코딩하고 민감한 값을 마스킹한다.
     */
    private fun requestBody(request: ContentCachingRequestWrapper): String =
        if (request.isAuthenticationRequest()) {
            REDACTED_BODY
        } else {
            decodeAndSanitize(request.contentAsByteArray, request.characterEncoding)
        }

    /**
     * 응답 body bytes를 문자열로 디코딩하고 민감한 값을 마스킹한다.
     */
    private fun responseBody(
        request: HttpServletRequest,
        response: ContentCachingResponseWrapper,
    ): String =
        if (request.isAuthenticationRequest()) {
            REDACTED_BODY
        } else {
            decodeAndSanitize(response.contentAsByteArray, response.characterEncoding)
        }

    /**
     * bytes를 문자셋 기준으로 문자열화하고 긴 body는 preview로 줄인다.
     */
    private fun decodeAndSanitize(bytes: ByteArray, encoding: String?): String {
        if (bytes.isEmpty()) return "<empty>"
        val charset = encoding
            ?.let { runCatching { Charset.forName(it) }.getOrDefault(StandardCharsets.UTF_8) }
            ?: StandardCharsets.UTF_8
        val body = bytes.toString(charset)

        return LogSanitizer.preview(LogSanitizer.sanitizeBody(body), maxLength = 2_000)
    }

    /**
     * 시작 시각부터 현재까지 걸린 시간을 ms로 계산한다.
     */
    private fun elapsedMs(startedAt: Long): Long =
        (System.nanoTime() - startedAt) / 1_000_000

    /**
     * SSE 요청인지 확인한다.
     */
    private fun HttpServletRequest.isSseRequest(): Boolean {
        val accept = getHeader("Accept").orEmpty()

        // ContentCachingResponseWrapper는 응답 본문을 끝까지 버퍼링하므로 SSE 스트림에는 쓰면 안 된다.
        return requestURI.endsWith("/events") || accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE)
    }

    /** 로그인·갱신·로그아웃 payload는 필드 추가 여부와 무관하게 로그에서 완전히 제외한다. */
    private fun HttpServletRequest.isAuthenticationRequest(): Boolean =
        requestURI == AUTH_API_PREFIX || requestURI.startsWith("$AUTH_API_PREFIX/")

    private companion object {
        const val AUTH_API_PREFIX = "/api/auth"
        const val REDACTED_BODY = "<redacted>"
        const val REQUEST_CACHE_LIMIT = 16 * 1024
    }
}
