package app.spammy.hof.captcha.config

import java.net.URI
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("hof.captcha-auto-solve")
data class CaptchaAutoSolveProperties(
    val enabled: Boolean = false,
    val maxAttempts: Int = 3,
    val maxOcrFailures: Int = 3,
    val baseUrl: String = "",
    val token: String = "",
    val timeout: Duration = Duration.ofSeconds(10),
    val ocrRetryDelay: Duration = Duration.ofMillis(500),
    val allowedCharacters: String = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789",
) {
    init {
        require(maxAttempts in 1..10) { "maxAttempts must be between 1 and 10" }
        require(maxOcrFailures in 1..10) { "maxOcrFailures must be between 1 and 10" }
        require(!timeout.isZero && !timeout.isNegative) { "timeout must be positive" }
        require(!ocrRetryDelay.isNegative) { "ocrRetryDelay must not be negative" }
        require(allowedCharacters.isNotBlank()) { "allowedCharacters must not be blank" }
        if (enabled) {
            requireValidBaseUrl(baseUrl)
            require(token.length >= MINIMUM_TOKEN_LENGTH) {
                "token must contain at least $MINIMUM_TOKEN_LENGTH characters when automatic solving is enabled"
            }
        }
    }

    private fun requireValidBaseUrl(value: String) {
        val uri = runCatching { URI.create(value.trim()) }
            .getOrElse { throw IllegalArgumentException("baseUrl must be a valid HTTP URL", it) }
        require(uri.scheme == "http" || uri.scheme == "https") { "baseUrl must use HTTP or HTTPS" }
        require(!uri.host.isNullOrBlank()) { "baseUrl must contain a host" }
        require(uri.rawQuery == null && uri.rawFragment == null) { "baseUrl must not contain a query or fragment" }
    }

    private companion object {
        const val MINIMUM_TOKEN_LENGTH = 32
    }
}
