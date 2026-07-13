package app.spammy.hof.account.service

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class AesGcmCredentialCipherTest {
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { index -> (index + 1).toByte() })

    @Test
    fun encryptsWithRandomNonceAndDecryptsToOriginalPassword() {
        val cipher = AesGcmCredentialCipher(key)

        val first = cipher.encrypt("hof-password")
        val second = cipher.encrypt("hof-password")

        assertNotEquals(first, second)
        assertEquals("hof-password", cipher.decrypt(first))
        assertEquals("hof-password", cipher.decrypt(second))
    }

    @Test
    fun rejectsCiphertextEncryptedWithAnotherKey() {
        val encrypted = AesGcmCredentialCipher(key).encrypt("hof-password")
        val anotherKey = Base64.getEncoder().encodeToString(ByteArray(32) { index -> (index + 2).toByte() })

        assertFailsWith<IllegalArgumentException> {
            AesGcmCredentialCipher(anotherKey).decrypt(encrypted)
        }
    }

    @Test
    fun rejectsMalformedOrTamperedCiphertext() {
        val cipher = AesGcmCredentialCipher(key)
        val encrypted = cipher.encrypt("hof-password")
        val tampered = encrypted.dropLast(1) + if (encrypted.last() == 'A') "B" else "A"

        assertFailsWith<IllegalArgumentException> { cipher.decrypt("plain-password") }
        assertFailsWith<IllegalArgumentException> { cipher.decrypt(tampered) }
    }

    @Test
    fun requiresExactly256BitBase64Key() {
        val shortKey = Base64.getEncoder().encodeToString(ByteArray(16))

        assertFailsWith<IllegalArgumentException> {
            AesGcmCredentialCipher(shortKey)
        }
    }
}
