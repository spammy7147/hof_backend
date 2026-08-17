package app.spammy.hof.status.web

import app.spammy.hof.status.dto.HofObservedStatusResponse
import app.spammy.hof.status.service.HofStatusSnapshotService
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.slf4j.LoggerFactory
import org.springframework.core.MethodParameter
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice
import tools.jackson.databind.ObjectMapper

/** 인증 API 응답에 계정별 최신 HOF 상단 상태를 메타데이터로 함께 전달한다. */
@RestControllerAdvice
class HofStatusResponseHeaderAdvice(
    private val snapshots: HofStatusSnapshotService,
    private val objectMapper: ObjectMapper,
) : ResponseBodyAdvice<Any> {
    override fun supports(
        returnType: MethodParameter,
        converterType: Class<out HttpMessageConverter<*>>,
    ): Boolean = true

    override fun beforeBodyWrite(
        body: Any?,
        returnType: MethodParameter,
        selectedContentType: MediaType,
        selectedConverterType: Class<out HttpMessageConverter<*>>,
        request: ServerHttpRequest,
        response: ServerHttpResponse,
    ): Any? {
        val accountId = currentAccountId() ?: return body
        runCatching { snapshots.findLatest(accountId) }
            .onFailure { error ->
                log.warn("HOF status response header failed accountId={}", accountId, error)
            }
            .getOrNull()
            ?.let(::encode)
            ?.let { encoded -> response.headers.set(HEADER_NAME, encoded) }
        return body
    }

    private fun currentAccountId(): Long? {
        val principal = SecurityContextHolder.getContext().authentication?.principal
        return (principal as? Jwt)?.subject?.toLongOrNull()?.takeIf { it > 0 }
    }

    private fun encode(status: HofObservedStatusResponse): String =
        URLEncoder.encode(objectMapper.writeValueAsString(status), StandardCharsets.UTF_8)
            .replace("+", "%20")

    companion object {
        const val HEADER_NAME = "X-HOF-Observed-Status"
        private val log = LoggerFactory.getLogger(HofStatusResponseHeaderAdvice::class.java)
    }
}
