package app.spammy.hof.account.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.entity.HofCookieEntity
import app.spammy.hof.account.repository.CookieQueryRepository
import java.time.Instant
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class HofCookieEncryptionMigrationServiceTest {
    private val queryRepository = mock(CookieQueryRepository::class.java)
    private val cipher = HofCookieCipher(Base64.getEncoder().encodeToString(ByteArray(32) { 7 }))
    private val service = HofCookieEncryptionMigrationService(queryRepository, cipher)

    @Test
    fun encryptsOnlyLegacyPlaintextCookies() {
        val legacy = cookie("legacy-session")
        val encrypted = cookie(cipher.encrypt("already-encrypted"))
        `when`(queryRepository.findAll()).thenReturn(listOf(legacy, encrypted))

        service.encryptLegacyCookies()

        assertTrue(cipher.isEncrypted(legacy.value))
        assertEquals("legacy-session", cipher.decrypt(legacy.value))
        assertEquals("already-encrypted", cipher.decrypt(encrypted.value))
    }

    private fun cookie(value: String): HofCookieEntity =
        HofCookieEntity(
            account = HofAccountEntity(
                loginId = "migration-user",
                encryptedPassword = "encrypted-password",
                createdAt = Instant.EPOCH,
            ),
            name = "PHPSESSID",
            value = value,
            updatedAt = Instant.EPOCH,
        )
}
