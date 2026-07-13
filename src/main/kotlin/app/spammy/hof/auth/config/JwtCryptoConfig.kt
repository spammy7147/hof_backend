package app.spammy.hof.auth.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimValidator
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import java.util.Base64
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** HS256 Access JWT의 발급과 검증에 동일한 서버 비밀키를 연결한다. */
@Configuration
class JwtCryptoConfig(
    private val properties: AuthProperties,
) {
    private val secretKey: SecretKey by lazy { decodeSecret(properties.jwtSecret) }

    /** Access JWT를 HS256으로 서명하는 encoder를 제공한다. */
    @Bean
    fun jwtEncoder(): JwtEncoder =
        NimbusJwtEncoder.withSecretKey(secretKey)
            .algorithm(MacAlgorithm.HS256)
            .build()

    /** 서명, 표준 시간 claim, issuer와 audience를 모두 검증하는 decoder를 제공한다. */
    @Bean
    fun jwtDecoder(): JwtDecoder =
        NimbusJwtDecoder.withSecretKey(secretKey)
            .macAlgorithm(MacAlgorithm.HS256)
            .build()
            .also { decoder ->
                val issuerValidator = JwtValidators.createDefaultWithIssuer(properties.issuer)
                val audienceValidator = JwtClaimValidator<List<String>>("aud") { audience ->
                    audience.contains(properties.audience)
                }
                decoder.setJwtValidator(DelegatingOAuth2TokenValidator(issuerValidator, audienceValidator))
            }

    private fun decodeSecret(base64Secret: String): SecretKey {
        val decoded = try {
            Base64.getDecoder().decode(base64Secret)
        } catch (exception: IllegalArgumentException) {
            throw IllegalArgumentException("JWT 서명 키는 Base64 형식이어야 합니다.", exception)
        }
        require(decoded.size >= MINIMUM_HS256_KEY_BYTES) { "JWT HS256 서명 키는 32바이트 이상이어야 합니다." }
        return SecretKeySpec(decoded, "HmacSHA256")
    }

    private companion object {
        const val MINIMUM_HS256_KEY_BYTES = 32
    }
}
