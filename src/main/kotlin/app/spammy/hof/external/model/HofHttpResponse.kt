package app.spammy.hof.external.model

/**
 * HOF 원본 서버 HTML 요청 결과와 갱신된 쿠키를 담는 응답 모델이다.
 */
data class HofHttpResponse(
    val statusCode: Int,
    val finalUrl: String,
    val body: String,
    val setCookies: Map<String, String>,
)
