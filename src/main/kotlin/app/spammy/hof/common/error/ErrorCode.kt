package app.spammy.hof.common.error

import org.springframework.http.HttpStatus

/**
 * API 에러 코드와 HTTP status 매핑이다.
 */
enum class ErrorCode(val status: HttpStatus) {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST),
    HOF_LOGIN_FAILED(HttpStatus.UNAUTHORIZED),
    HOF_SESSION_EXPIRED(HttpStatus.UNAUTHORIZED),
    HOF_REQUEST_FAILED(HttpStatus.BAD_GATEWAY),
    HOF_TEMPORARILY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND),
    CAPTCHA_REQUIRED(HttpStatus.CONFLICT),
    CAPTCHA_PREPARATION_FAILED(HttpStatus.BAD_GATEWAY),
    CAPTCHA_STALE(HttpStatus.CONFLICT),
    AUTH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED),
    REFRESH_RETRY_REQUIRED(HttpStatus.CONFLICT),
    REFRESH_TOKEN_REUSED(HttpStatus.UNAUTHORIZED),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
}
