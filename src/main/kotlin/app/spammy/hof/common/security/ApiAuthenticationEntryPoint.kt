package app.spammy.hof.common.security

import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.error.ErrorResponse
import app.spammy.hof.common.time.TimeProvider
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/** 인증 실패를 내부 예외 문자열 대신 사용자 친화적인 공통 API 오류 JSON으로 변환한다. */
@Component
class ApiAuthenticationEntryPoint(
    private val objectMapper: ObjectMapper,
    private val timeProvider: TimeProvider,
) : AuthenticationEntryPoint {
    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        response.status = ErrorCode.AUTH_TOKEN_INVALID.status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        objectMapper.writeValue(
            response.outputStream,
            ErrorResponse(
                code = ErrorCode.AUTH_TOKEN_INVALID.name,
                message = "로그인이 필요합니다.",
                timestamp = timeProvider.now(),
            ),
        )
    }
}
