package app.spammy.hof.common.error

import app.spammy.hof.common.time.TimeProvider
import org.springframework.http.ResponseEntity
import org.springframework.http.HttpHeaders
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
/**
 * Controller/Service에서 던진 예외를 HTTP 응답으로 변환한다.
 */
class GlobalExceptionHandler(
    private val timeProvider: TimeProvider,
) {
    /**
     * ApiException을 ErrorResponse JSON으로 바꾼다.
     */
    @ExceptionHandler(ApiException::class)
    fun handleApiException(exception: ApiException): ResponseEntity<ErrorResponse> {
        val code = exception.errorCode
        val builder = ResponseEntity.status(code.status)
        exception.retryAfterSeconds?.let { seconds -> builder.header(HttpHeaders.RETRY_AFTER, seconds.toString()) }
        return builder
            .body(
                ErrorResponse(
                    code = code.name,
                    message = exception.message.orEmpty(),
                    timestamp = timeProvider.now(),
                ),
            )
    }
}
