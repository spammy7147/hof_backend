package app.spammy.hof.auth.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 자체 토큰 인증과 HOF 자격 증명 암호화에 필요한 설정을 한곳에 모은다.
 *
 * JWT 서명 키와 자격 증명 암호화 키는 용도가 다르므로 반드시 서로 다른 값을 사용해야 한다.
 * 두 키는 소스 코드나 설정 파일에 직접 기록하지 않고 환경 변수로 주입한다.
 */
@ConfigurationProperties("hof.auth")
data class AuthProperties(
    val jwtSecret: String,
    val credentialEncryptionKey: String,
    val accessTokenTtl: Duration = Duration.ofMinutes(30),
    val refreshTokenTtl: Duration = Duration.ofDays(30),
    val refreshReuseGrace: Duration = Duration.ofSeconds(10),
    val issuer: String = "hof-backend",
    val audience: String = "hof-api",
    val refreshCookieSecure: Boolean = false,
    val allowedOrigins: List<String> = listOf("http://localhost:8081", "http://127.0.0.1:8081"),
)
