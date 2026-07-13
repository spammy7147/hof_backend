package app.spammy.hof.external.model

/**
 * 캡차 이미지처럼 문자열이 아닌 바이트 응답을 받을 때 쓰는 HOF HTTP 응답 모델이다.
 */
data class HofBinaryResponse(
    val statusCode: Int,
    val finalUrl: String,
    val contentType: String?,
    val body: ByteArray,
)
