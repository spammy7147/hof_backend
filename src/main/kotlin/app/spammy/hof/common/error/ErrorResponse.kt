package app.spammy.hof.common.error

import java.time.Instant

/**
 * 모든 API 에러가 공통으로 내려주는 응답 형식이다.
 */
data class ErrorResponse(
    val code: String,
    val message: String,
    val timestamp: Instant,
)
