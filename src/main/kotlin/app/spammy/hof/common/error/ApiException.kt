package app.spammy.hof.common.error

/**
 * 비즈니스 로직에서 의도적으로 발생시키는 API 예외다.
 *
 * ErrorCode에 HTTP status가 연결되어 있어 GlobalExceptionHandler가 표준 에러 응답으로 바꾼다.
 */
open class ApiException(
    val errorCode: ErrorCode,
    override val message: String,
    override val cause: Throwable? = null,
    val retryAfterSeconds: Long? = null,
) : RuntimeException(message, cause)
