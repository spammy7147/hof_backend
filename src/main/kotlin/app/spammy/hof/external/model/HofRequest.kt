package app.spammy.hof.external.model

/**
 * HOF 원본 서버로 보낼 HTTP 요청의 method, URL, form 필드를 묶은 모델이다.
 */
data class HofRequest(
    val method: HofHttpMethod,
    val url: String,
    val formFields: Map<String, String> = emptyMap(),
)
