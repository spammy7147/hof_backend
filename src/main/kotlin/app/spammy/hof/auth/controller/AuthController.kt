package app.spammy.hof.auth.controller

import app.spammy.hof.auth.config.AuthProperties
import app.spammy.hof.auth.dto.AuthClientType
import app.spammy.hof.auth.dto.LoginRequest
import app.spammy.hof.auth.dto.LogoutRequest
import app.spammy.hof.auth.dto.RefreshRequest
import app.spammy.hof.auth.dto.TokenResponse
import app.spammy.hof.auth.service.AuthService
import app.spammy.hof.auth.service.AuthTokenPair
import app.spammy.hof.auth.service.AuthRateLimiter
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.CacheControl
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration

/** 공개 로그인·갱신·로그아웃 엔드포인트와 플랫폼별 refresh token 전달 방식을 제공한다. */
@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authService: AuthService,
    private val rateLimiter: AuthRateLimiter,
    private val properties: AuthProperties,
) {
    /** HOF ID/PW를 검증한 뒤 네이티브는 JSON, 웹은 HttpOnly 쿠키로 refresh token을 전달한다. */
    @PostMapping("/login")
    fun login(
        @Valid @RequestBody request: LoginRequest,
    ): ResponseEntity<TokenResponse> {
        rateLimiter.checkLogin(request.loginId)
        return response(authService.login(request.loginId, request.password, request.clientType))
    }

    /** 네이티브 body 또는 웹 쿠키의 기존 refresh token을 회전하고 새 토큰 쌍을 반환한다. */
    @PostMapping("/refresh")
    fun refresh(
        @RequestBody(required = false) request: RefreshRequest?,
        @CookieValue(name = REFRESH_COOKIE, required = false) cookieToken: String?,
    ): ResponseEntity<TokenResponse> {
        return response(authService.refresh(selectRefreshToken(request?.refreshToken, cookieToken)))
    }

    /** 제출된 refresh token 패밀리를 폐기하고 브라우저 쿠키도 즉시 만료시킨다. */
    @PostMapping("/logout")
    fun logout(
        @RequestBody(required = false) request: LogoutRequest?,
        @CookieValue(name = REFRESH_COOKIE, required = false) cookieToken: String?,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<Void> {
        authService.logout(
            request?.refreshToken ?: cookieToken,
            request?.pushTargetId,
            request?.pushInstallationId,
        )
        val builder = ResponseEntity.noContent().cacheControl(CacheControl.noStore())
        if (cookieToken != null || servletRequest.getHeader(HttpHeaders.COOKIE) != null) {
            builder.header(HttpHeaders.SET_COOKIE, expiredCookie().toString())
        }
        return builder.build()
    }

    private fun response(tokens: AuthTokenPair): ResponseEntity<TokenResponse> {
        val web = tokens.clientType == AuthClientType.WEB
        val body = TokenResponse(
            accessToken = tokens.accessToken.value,
            accessTokenExpiresAt = tokens.accessToken.expiresAt,
            refreshToken = tokens.refreshToken.value.takeUnless { web },
            refreshTokenExpiresAt = tokens.refreshToken.expiresAt,
        )
        val builder = ResponseEntity.ok().cacheControl(CacheControl.noStore())
        if (web) builder.header(HttpHeaders.SET_COOKIE, refreshCookie(tokens.refreshToken.value).toString())
        return builder.body(body)
    }

    private fun selectRefreshToken(bodyToken: String?, cookieToken: String?): String =
        bodyToken?.takeIf(String::isNotBlank)
            ?: cookieToken?.takeIf(String::isNotBlank)
            ?: throw ApiException(ErrorCode.AUTH_TOKEN_INVALID, "로그인 정보가 만료되었거나 유효하지 않습니다.")

    private fun refreshCookie(value: String): ResponseCookie =
        ResponseCookie.from(REFRESH_COOKIE, value)
            .httpOnly(true)
            .secure(properties.refreshCookieSecure)
            .sameSite("Strict")
            .path("/api/auth")
            .maxAge(properties.refreshTokenTtl)
            .build()

    private fun expiredCookie(): ResponseCookie =
        ResponseCookie.from(REFRESH_COOKIE, "")
            .httpOnly(true)
            .secure(properties.refreshCookieSecure)
            .sameSite("Strict")
            .path("/api/auth")
            .maxAge(Duration.ZERO)
            .build()

    private companion object {
        const val REFRESH_COOKIE = "hof_refresh_token"
    }
}
