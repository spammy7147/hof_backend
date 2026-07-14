package app.spammy.hof.account.service

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class HofCookieCipherTest {
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { index -> (index + 41).toByte() })
    private val cipher = HofCookieCipher(key)

    @Test
    fun encryptsStoredCookieAndDecryptsOnlyAtUseTime() {
        val encrypted = cipher.encrypt("session-secret")

        assertNotEquals("session-secret", encrypted)
        assertEquals("session-secret", cipher.decrypt(encrypted))
    }

    @Test
    fun acceptsLegacyPlaintextCookieUntilItIsReplaced() {
        assertEquals("legacy-session", cipher.decrypt("legacy-session"))
    }

    @Test
    fun identifiesTheCookieKeyWhenItsConfigurationIsNotBase64() {
        val error = assertFailsWith<IllegalArgumentException> {
            HofCookieCipher("\$HOF_COOKIE_ENCRYPTION_KEY")
        }

        assertEquals("HOF 세션 쿠키 암호화 키는 Base64 형식이어야 합니다.", error.message)
    }
}
