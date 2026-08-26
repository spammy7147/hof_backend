package app.spammy.hof.auth.dto

import jakarta.validation.constraints.NotBlank
import java.time.Instant

/** refresh token 전달 방식이 다른 네이티브 앱과 브라우저를 구분한다. */
enum class AuthClientType {
    NATIVE,
    WEB,
}

/** HOF 원본 서버 인증과 로컬 토큰 발급을 한 번에 요청한다. */
data class LoginRequest(
    @field:NotBlank val loginId: String,
    @field:NotBlank val password: String,
    val clientType: AuthClientType,
)

/** 네이티브는 body로 refresh token을 보내고 웹은 HttpOnly 쿠키를 사용하므로 값이 선택 사항이다. */
data class RefreshRequest(
    val refreshToken: String? = null,
)

/** 로그아웃도 플랫폼별 refresh token 전달 방식을 동일하게 사용한다. */
data class LogoutRequest(
    val refreshToken: String? = null,
    val pushTargetId: Long? = null,
    val pushInstallationId: String? = null,
)

/**
 * Access Token과 두 토큰의 만료 시각을 반환한다.
 *
 * [refreshToken]은 네이티브 응답에만 포함되며 웹에서는 JavaScript가 읽을 수 없는 HttpOnly 쿠키로 전달된다.
 */
data class TokenResponse(
    val accessToken: String,
    val tokenType: String = "Bearer",
    val accessTokenExpiresAt: Instant,
    val refreshToken: String? = null,
    val refreshTokenExpiresAt: Instant,
)
