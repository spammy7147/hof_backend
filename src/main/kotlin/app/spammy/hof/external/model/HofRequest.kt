package app.spammy.hof.external.model

/**
 * HOF 원본 서버로 보낼 HTTP 요청의 method, URL, form 필드를 묶은 모델이다.
 */
data class HofRequest(
    val method: HofHttpMethod,
    val url: String,
    val formFields: Map<String, String> = emptyMap(),
    val origin: HofRequestOrigin = HofRequestOrigin.INTERACTIVE,
    val formEntries: List<HofFormField> = formFields.map { (name, value) -> HofFormField(name, value) },
)

/** 같은 이름이 반복되는 HTML form의 순서와 값을 손실 없이 보존한다. */
data class HofFormField(
    val name: String,
    val value: String,
)
