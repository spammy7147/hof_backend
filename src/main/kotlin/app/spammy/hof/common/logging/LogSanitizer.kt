package app.spammy.hof.common.logging

/**
 * 로그에 남기면 안 되는 비밀번호, 쿠키, 토큰 값을 마스킹한다.
 */
object LogSanitizer {
    private val sensitiveHeaderNames = setOf(
        "authorization",
        "cookie",
        "set-cookie",
        "x-api-key",
    )
    private val sensitiveJsonFieldPattern =
        Regex("""("(?i:password|pass|encryptedPassword|cookie|token|authorization)"\s*:\s*")([^"]*)(")""")

    /**
     * JSON body 안의 민감 필드를 `***`로 바꾼다.
     */
    fun sanitizeBody(body: String): String =
        sensitiveJsonFieldPattern.replace(body) { match ->
            "${match.groupValues[1]}***${match.groupValues[3]}"
        }

    /**
     * 민감한 header 값은 `<masked>`로 바꾼다.
     */
    fun sanitizeHeader(name: String, value: String): String {
        val sanitizedValue = if (name.lowercase() in sensitiveHeaderNames) {
            "<masked>"
        } else {
            value
        }

        return "$name=$sanitizedValue"
    }

    /**
     * 너무 긴 로그 문자열을 일정 길이로 줄인다.
     */
    fun preview(value: String, maxLength: Int): String =
        if (value.length <= maxLength) {
            value
        } else {
            "${value.take(maxLength)}...(${value.length} chars)"
        }
}
