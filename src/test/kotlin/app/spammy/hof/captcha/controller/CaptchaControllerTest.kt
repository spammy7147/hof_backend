package app.spammy.hof.captcha.controller

import app.spammy.hof.captcha.dto.CaptchaChallengeResponse
import app.spammy.hof.captcha.dto.SubmitCaptchaAnswerRequest
import app.spammy.hof.captcha.service.CaptchaImageResponse
import app.spammy.hof.captcha.service.CaptchaService
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import kotlin.test.assertEquals

class CaptchaControllerTest {
    private val captchaService = Mockito.mock(CaptchaService::class.java)
    private val controller = CaptchaController(captchaService)

    @Test
    fun findCurrentDelegatesToService() {
        Mockito.`when`(captchaService.findCurrent(1L))
            .thenReturn(challenge(status = "PENDING"))

        val response = controller.findCurrent(accountId = 1L)

        assertEquals("PENDING", response?.status)
        Mockito.verify(captchaService).findCurrent(1L)
    }

    @Test
    fun submitAnswerDelegatesToService() {
        Mockito.`when`(captchaService.submitAnswer(accountId = 1L, challengeId = 3L, answer = "1234"))
            .thenReturn(challenge(status = "ANSWERED"))

        val response = controller.submitAnswer(
            accountId = 1L,
            challengeId = 3L,
            request = SubmitCaptchaAnswerRequest(answer = "1234"),
        )

        assertEquals("ANSWERED", response.status)
        Mockito.verify(captchaService).submitAnswer(accountId = 1L, challengeId = 3L, answer = "1234")
    }

    @Test
    fun loadImageDelegatesToServiceAndReturnsNoStoreBinaryResponse() {
        val bytes = byteArrayOf(1, 2, 3)
        Mockito.`when`(captchaService.loadImage(accountId = 1L, challengeId = 3L))
            .thenReturn(CaptchaImageResponse(contentType = "image/png", bytes = bytes))

        val response: ResponseEntity<ByteArray> = controller.loadImage(accountId = 1L, challengeId = 3L)

        assertEquals(MediaType.IMAGE_PNG, response.headers.contentType)
        assertEquals(listOf("no-store"), response.headers[HttpHeaders.CACHE_CONTROL])
        assertArrayEquals(bytes, response.body)
        Mockito.verify(captchaService).loadImage(accountId = 1L, challengeId = 3L)
    }

    private fun challenge(status: String): CaptchaChallengeResponse =
        CaptchaChallengeResponse(
            id = 3L,
            accountId = 1L,
            status = status,
            prompt = "통행증을 입력하세요",
            imageUrl = null,
            sourceUrl = "http://sic.zerosic.com/ZeroHOF/index.php",
            createdAt = "2026-07-08T00:00:00Z",
            answeredAt = null,
        )
}
