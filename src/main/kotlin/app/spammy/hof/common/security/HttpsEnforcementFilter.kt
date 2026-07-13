package app.spammy.hof.common.security

import app.spammy.hof.auth.config.AuthProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 운영 환경에서 앱과 백엔드 사이의 평문 HTTP API 호출을 차단한다.
 *
 * 리버스 프록시가 TLS를 종료하는 배포에서는 `server.forward-headers-strategy=framework`가
 * `X-Forwarded-Proto: https`를 반영해 [HttpServletRequest.isSecure]를 true로 만든다. 따라서
 * 프록시와 백엔드 사이가 내부 HTTP여도 외부 클라이언트가 HTTPS를 사용했다면 정상 처리된다.
 */
class HttpsEnforcementFilter(
    private val properties: AuthProperties,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !properties.requireHttps || !request.requestURI.startsWith("/api/")

    /**
     * HTTP 요청을 리다이렉트하지 않고 거부한다. 로그인 POST를 리다이렉트할 때 요청 본문이나
     * 메서드가 유실될 수 있고, 민감한 인증 정보를 불필요하게 재전송하게 되는 문제를 피하기 위함이다.
     */
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (request.isSecure) {
            filterChain.doFilter(request, response)
            return
        }

        response.status = HttpServletResponse.SC_UPGRADE_REQUIRED
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        response.writer.write(
            """{"code":"HTTPS_REQUIRED","message":"안전한 연결이 필요합니다. HTTPS로 다시 접속해 주세요."}""",
        )
    }
}
