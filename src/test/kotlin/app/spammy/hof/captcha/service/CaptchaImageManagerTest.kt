package app.spammy.hof.captcha.service

import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.external.client.HofAutomationDeferredException
import app.spammy.hof.external.client.HofBinaryGateway
import app.spammy.hof.external.model.HofBinaryResponse
import app.spammy.hof.external.model.HofRequestOrigin
import java.time.Instant
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class CaptchaImageManagerTest {
    private val gateway = RecordingBinaryGateway()
    private val store = MemoryCaptchaImageStore()
    private val manager = CaptchaImageManager(gateway, store)

    @Test
    fun storesAndReadsOnlyTheRequestedPreparationVersion() {
        gateway.response = imageResponse(byteArrayOf(1, 2, 3))

        manager.storePrepared(
            accountId = 1L,
            origin = HofRequestOrigin.INTERACTIVE,
            challengeId = 7L,
            preparationVersion = 1,
            imageUrl = IMAGE_URL,
            cookies = mapOf("PHPSESSID" to "session-a"),
        )

        assertContentEquals(byteArrayOf(1, 2, 3), manager.readStored(1L, 7L, 1)?.bytes)
        assertNull(manager.readStored(1L, 7L, 2))
        assertEquals(listOf(1L), gateway.accountIds)
        assertEquals(listOf(HofRequestOrigin.INTERACTIVE), gateway.origins)
    }

    @Test
    fun rejectsHtmlBeforePublishingPreparedVersion() {
        gateway.response = HofBinaryResponse(200, IMAGE_URL, "text/html", "Notice".toByteArray())

        val error = assertFailsWith<ApiException> {
            manager.storePrepared(
                accountId = 1L,
                origin = HofRequestOrigin.INTERACTIVE,
                challengeId = 7L,
                preparationVersion = 2,
                imageUrl = IMAGE_URL,
                cookies = mapOf("PHPSESSID" to "session-a"),
            )
        }

        assertEquals(ErrorCode.HOF_REQUEST_FAILED, error.errorCode)
        assertNull(manager.readStored(1L, 7L, 2))
    }

    @Test
    fun savesAnImageWithTheCurrentSessionCookies() {
        gateway.response = imageResponse(byteArrayOf(1, 2, 3))

        manager.saveAfterCommit(
            accountId = 1L,
            origin = HofRequestOrigin.INTERACTIVE,
            challengeId = 7L,
            imageUrl = IMAGE_URL,
            cookies = mapOf("PHPSESSID" to "session-a"),
        )

        assertEquals(mapOf("PHPSESSID" to "session-a"), gateway.lastCookies)
        assertEquals(listOf(1L), gateway.accountIds)
        assertEquals(listOf(HofRequestOrigin.INTERACTIVE), gateway.origins)
        assertContentEquals(byteArrayOf(1, 2, 3), manager.readStored(1L, 7L)?.bytes)
    }

    @Test
    fun neverStoresAnHtmlNoticeAsAnImage() {
        gateway.response = HofBinaryResponse(200, IMAGE_URL, "text/html", "Notice".toByteArray())

        manager.saveAfterCommit(
            accountId = 1L,
            origin = HofRequestOrigin.INTERACTIVE,
            challengeId = 8L,
            imageUrl = IMAGE_URL,
            cookies = mapOf("PHPSESSID" to "session-a"),
        )

        assertNull(manager.readStored(1L, 8L))
    }

    @Test
    fun requiredDownloadRejectsMissingSessionCookies() {
        val error = assertFailsWith<ApiException> {
            manager.downloadRequired(1L, HofRequestOrigin.INTERACTIVE, IMAGE_URL, emptyMap())
        }

        assertEquals(ErrorCode.HOF_SESSION_EXPIRED, error.errorCode)
    }

    @Test
    fun requiredDownloadPreservesFriendlyUnavailableSignal() {
        val signal = ApiException(
            ErrorCode.HOF_TEMPORARILY_UNAVAILABLE,
            "HOF 서버 연결이 일시적으로 원활하지 않습니다.",
        )
        gateway.failure = signal

        val actual = assertFailsWith<ApiException> {
            manager.downloadRequired(
                1L,
                HofRequestOrigin.INTERACTIVE,
                IMAGE_URL,
                mapOf("PHPSESSID" to "session-a"),
            )
        }

        assertSame(signal, actual)
        assertEquals(ErrorCode.HOF_TEMPORARILY_UNAVAILABLE, actual.errorCode)
    }

    @Test
    fun requiredDownloadPreservesAutomationDeferredSignal() {
        val retryAt = Instant.parse("2026-07-30T01:02:30Z")
        val signal = HofAutomationDeferredException(retryAt, 2)
        gateway.failure = signal

        val actual = assertFailsWith<HofAutomationDeferredException> {
            manager.downloadRequired(
                1L,
                HofRequestOrigin.AUTOMATION,
                IMAGE_URL,
                mapOf("PHPSESSID" to "session-a"),
            )
        }

        assertSame(signal, actual)
        assertEquals(retryAt, actual.retryAt)
        assertEquals(2, actual.consecutiveFailures)
    }

    private class RecordingBinaryGateway : HofBinaryGateway {
        var response: HofBinaryResponse = imageResponse(byteArrayOf())
        val accountIds = mutableListOf<Long>()
        val origins = mutableListOf<HofRequestOrigin>()
        var lastCookies: Map<String, String> = emptyMap()
        var failure: RuntimeException? = null

        override fun get(
            accountId: Long,
            origin: HofRequestOrigin,
            url: String,
            cookies: Map<String, String>,
        ): HofBinaryResponse {
            accountIds += accountId
            origins += origin
            lastCookies = cookies
            failure?.let { throw it }
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
        const val IMAGE_URL = "https://hof.zerosic.com/simple-php-captcha.php?_CAPTCHA=1"

        fun imageResponse(bytes: ByteArray) = HofBinaryResponse(200, IMAGE_URL, "image/png", bytes)
    }
}
