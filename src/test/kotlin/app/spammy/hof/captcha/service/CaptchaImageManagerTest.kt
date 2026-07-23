package app.spammy.hof.captcha.service

import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.external.client.HofBinaryGateway
import app.spammy.hof.external.model.HofBinaryResponse
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CaptchaImageManagerTest {
    private val gateway = RecordingBinaryGateway()
    private val store = MemoryCaptchaImageStore()
    private val manager = CaptchaImageManager(gateway, store)

    @Test
    fun storesAndReadsOnlyTheRequestedPreparationVersion() {
        gateway.response = imageResponse(byteArrayOf(1, 2, 3))

        manager.storePrepared(1L, 7L, 1, IMAGE_URL, mapOf("PHPSESSID" to "session-a"))

        assertContentEquals(byteArrayOf(1, 2, 3), manager.readStored(1L, 7L, 1)?.bytes)
        assertNull(manager.readStored(1L, 7L, 2))
    }

    @Test
    fun rejectsHtmlBeforePublishingPreparedVersion() {
        gateway.response = HofBinaryResponse(200, IMAGE_URL, "text/html", "Notice".toByteArray())

        val error = assertFailsWith<ApiException> {
            manager.storePrepared(1L, 7L, 2, IMAGE_URL, mapOf("PHPSESSID" to "session-a"))
        }

        assertEquals(ErrorCode.HOF_REQUEST_FAILED, error.errorCode)
        assertNull(manager.readStored(1L, 7L, 2))
    }

    @Test
    fun savesAnImageWithTheCurrentSessionCookies() {
        gateway.response = imageResponse(byteArrayOf(1, 2, 3))

        manager.saveAfterCommit(1L, 7L, IMAGE_URL, mapOf("PHPSESSID" to "session-a"))

        assertEquals(mapOf("PHPSESSID" to "session-a"), gateway.lastCookies)
        assertContentEquals(byteArrayOf(1, 2, 3), manager.readStored(1L, 7L)?.bytes)
    }

    @Test
    fun neverStoresAnHtmlNoticeAsAnImage() {
        gateway.response = HofBinaryResponse(200, IMAGE_URL, "text/html", "Notice".toByteArray())

        manager.saveAfterCommit(1L, 8L, IMAGE_URL, mapOf("PHPSESSID" to "session-a"))

        assertNull(manager.readStored(1L, 8L))
    }

    @Test
    fun requiredDownloadRejectsMissingSessionCookies() {
        val error = assertFailsWith<ApiException> {
            manager.downloadRequired(IMAGE_URL, emptyMap())
        }

        assertEquals(ErrorCode.HOF_SESSION_EXPIRED, error.errorCode)
    }

    private class RecordingBinaryGateway : HofBinaryGateway {
        var response: HofBinaryResponse = imageResponse(byteArrayOf())
        var lastCookies: Map<String, String> = emptyMap()

        override fun get(url: String, cookies: Map<String, String>): HofBinaryResponse {
            lastCookies = cookies
            return response
        }
    }

    private class MemoryCaptchaImageStore : CaptchaImageFileStore {
        private val files = mutableMapOf<Triple<Long, Long, Int>, StoredCaptchaImage>()

        override fun save(
            accountId: Long,
            challengeId: Long,
            preparationVersion: Int,
            contentType: String,
            bytes: ByteArray,
        ) {
            files[Triple(accountId, challengeId, preparationVersion)] = StoredCaptchaImage(contentType, bytes)
        }

        override fun read(accountId: Long, challengeId: Long, preparationVersion: Int): StoredCaptchaImage? =
            files[Triple(accountId, challengeId, preparationVersion)]

        override fun delete(accountId: Long, challengeId: Long, preparationVersion: Int) {
            files.remove(Triple(accountId, challengeId, preparationVersion))
        }
    }

    private companion object {
        const val IMAGE_URL = "http://sic.zerosic.com/ZeroHOF/simple-php-captcha.php?_CAPTCHA=1"

        fun imageResponse(bytes: ByteArray) = HofBinaryResponse(200, IMAGE_URL, "image/png", bytes)
    }
}
