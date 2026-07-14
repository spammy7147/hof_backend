package app.spammy.hof.account.service

import app.spammy.hof.auth.config.AuthProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * DB에 저장하는 HOF 세션 쿠키 값을 암호화한다.
 *
 * 기존 배포본이 평문으로 저장한 값은 다음 로그인이나 Set-Cookie 갱신 전까지 읽을 수 있어야 하므로
 * `v1.` 암호문이 아닌 값은 레거시 평문으로 간주한다. 새로 저장하는 값은 항상 AES-256-GCM 암호문이다.
 */
class HofCookieCipher(base64Key: String) {
    private val delegate = AesGcmCredentialCipher(
        base64Key = base64Key,
        keyDescription = "HOF 세션 쿠키 암호화 키",
    )

    fun encrypt(rawValue: String): String = delegate.encrypt(rawValue)

    /** 저장값이 현재 애플리케이션에서 만든 AES-GCM 암호문인지 판별한다. */
    fun isEncrypted(storedValue: String): Boolean = storedValue.startsWith(ENCRYPTED_PREFIX)

    fun decrypt(storedValue: String): String =
        if (isEncrypted(storedValue)) {
            delegate.decrypt(storedValue)
        } else {
            storedValue
        }

    private companion object {
        const val ENCRYPTED_PREFIX = "v1."
    }
}

/** HOF 비밀번호 키와 분리한 쿠키 전용 키를 쿠키 저장·전송 경계에 연결한다. */
@Configuration
class HofCookieCipherConfig {
    @Bean
    fun hofCookieCipher(properties: AuthProperties): HofCookieCipher =
        HofCookieCipher(properties.cookieEncryptionKey)
}
