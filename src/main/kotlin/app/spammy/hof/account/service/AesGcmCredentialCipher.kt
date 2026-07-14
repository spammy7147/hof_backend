package app.spammy.hof.account.service

import app.spammy.hof.auth.config.AuthProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * HOF 비밀번호를 AES-256-GCM으로 암호화한다.
 *
 * 저장 형식은 `v1.nonce.ciphertext`이며 nonce와 암호문은 URL-safe Base64로 인코딩한다.
 * GCM 인증 태그가 암호문에 포함되므로 저장값이 변조되거나 다른 키로 복호화되면 즉시 실패한다.
 */
class AesGcmCredentialCipher(
    base64Key: String,
    private val secureRandom: SecureRandom = SecureRandom(),
    private val keyDescription: String = "HOF 자격 증명 암호화 키",
) : CredentialCipher {
    private val key = decodeKey(base64Key)

    override fun encrypt(rawPassword: String): String {
        val nonce = ByteArray(NONCE_SIZE_BYTES).also(secureRandom::nextBytes)
        val encrypted = cipher(Cipher.ENCRYPT_MODE, nonce).doFinal(rawPassword.toByteArray(Charsets.UTF_8))
        return listOf(VERSION, encoder.encodeToString(nonce), encoder.encodeToString(encrypted)).joinToString(".")
    }

    override fun decrypt(encryptedPassword: String): String {
        try {
            val parts = encryptedPassword.split('.')
            require(parts.size == 3 && parts[0] == VERSION) { "지원하지 않는 자격 증명 암호문 형식입니다." }
            val nonce = decodeCanonical(parts[1])
            require(nonce.size == NONCE_SIZE_BYTES) { "자격 증명 암호문의 nonce 길이가 올바르지 않습니다." }
            val decrypted = cipher(Cipher.DECRYPT_MODE, nonce).doFinal(decodeCanonical(parts[2]))
            return decrypted.toString(Charsets.UTF_8)
        } catch (exception: IllegalArgumentException) {
            throw exception
        } catch (exception: Exception) {
            throw IllegalArgumentException("자격 증명 암호문을 복호화할 수 없습니다.", exception)
        }
    }

    private fun cipher(mode: Int, nonce: ByteArray): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            init(mode, key, GCMParameterSpec(TAG_SIZE_BITS, nonce))
        }

    private fun decodeKey(base64Key: String): SecretKeySpec {
        val decoded = try {
            Base64.getDecoder().decode(base64Key)
        } catch (exception: IllegalArgumentException) {
            throw IllegalArgumentException("${keyDescription}는 Base64 형식이어야 합니다.", exception)
        }
        require(decoded.size == KEY_SIZE_BYTES) { "${keyDescription}는 32바이트여야 합니다." }
        return SecretKeySpec(decoded, "AES")
    }

    /** 같은 바이트를 여러 문자열로 표현하는 비정규 Base64 입력을 거부한다. */
    private fun decodeCanonical(encoded: String): ByteArray {
        val decoded = decoder.decode(encoded)
        require(encoder.encodeToString(decoded) == encoded) { "자격 증명 암호문의 Base64 형식이 올바르지 않습니다." }
        return decoded
    }

    private companion object {
        const val VERSION = "v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BYTES = 32
        const val NONCE_SIZE_BYTES = 12
        const val TAG_SIZE_BITS = 128
        val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        val decoder: Base64.Decoder = Base64.getUrlDecoder()
    }
}

/** Spring 설정으로 받은 암호화 키를 실제 계정 서비스 구현체에 연결한다. */
@Configuration
class CredentialCipherConfig {
    @Bean
    fun credentialCipher(properties: AuthProperties): CredentialCipher =
        AesGcmCredentialCipher(properties.credentialEncryptionKey)
}
