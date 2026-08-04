package app.spammy.hof.captcha.controller

import app.spammy.hof.account.service.HofAccountService
import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.captcha.dto.CaptchaChallengeResponse
import app.spammy.hof.captcha.dto.SubmitCaptchaAnswerRequest
import app.spammy.hof.captcha.service.CaptchaAutoSolveCoordinator
import app.spammy.hof.captcha.service.CaptchaAutoSolveOutcome
import app.spammy.hof.captcha.service.CaptchaPreparationConsumedException
import app.spammy.hof.captcha.service.CaptchaService
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.external.client.HofCaptchaRetryException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import org.mockito.Mockito

class CaptchaControllerTest {
    private val captchaService = Mockito.mock(CaptchaService::class.java)
    private val accountService = Mockito.mock(HofAccountService::class.java)
    private val autoSolveCoordinator = Mockito.mock(CaptchaAutoSolveCoordinator::class.java)
    private val controller = CaptchaController(
        captchaService,
        HofSessionRecoveryService(accountService),
        autoSolveCoordinator,
    )

    @Test
    fun retryAutomaticSolveStartsANewAttemptAndReturnsTheRemainingChallenge() {
        val ready = readyCaptcha()
        Mockito.`when`(autoSolveCoordinator.solve(1L, 7L))
            .thenReturn(CaptchaAutoSolveOutcome.MANUAL_INPUT_REQUIRED)
        Mockito.`when`(captchaService.findCurrent(1L)).thenReturn(ready)

        val response = controller.retryAutomaticSolve(1L, 7L)

        assertEquals(ready, response)
        Mockito.verify(autoSolveCoordinator).solve(1L, 7L)
    }

    @Test
    fun prepareCurrentReauthenticatesAndRetriesOnce() {
        val ready = readyCaptcha()
        Mockito.`when`(captchaService.prepareCurrent(1L))
            .thenThrow(ApiException(ErrorCode.HOF_SESSION_EXPIRED, "expired"))
            .thenReturn(ready)

        val response = controller.prepareCurrent(1L)

        assertEquals(ready, response)
        Mockito.verify(accountService).reauthenticate(1L)
        Mockito.verify(captchaService, Mockito.times(2)).prepareCurrent(1L)
        Mockito.verify(captchaService, Mockito.never()).invalidateCurrentPreparation(1L)
    }

    @Test
    fun prepareCurrentRetriesCaptcha503WithoutInvalidatingPreparation() {
        val ready = readyCaptcha()
        Mockito.`when`(captchaService.prepareCurrent(1L))
            .thenThrow(HofCaptchaRetryException())
            .thenReturn(ready)

        val response = controller.prepareCurrent(1L)

        assertEquals(ready, response)
        Mockito.verify(captchaService, Mockito.times(2)).prepareCurrent(1L)
        Mockito.verify(captchaService, Mockito.never()).invalidateCurrentPreparation(1L)
        Mockito.verifyNoInteractions(accountService)
    }

    @Test
    fun prepareCurrentInvalidatesSnapshotWhenRetryAlsoFails() {
        Mockito.`when`(captchaService.prepareCurrent(1L))
            .thenThrow(ApiException(ErrorCode.HOF_SESSION_EXPIRED, "expired"))
        Mockito.`when`(accountService.reauthenticate(1L))
            .thenThrow(ApiException(ErrorCode.HOF_LOGIN_FAILED, "login failed"))

        val error = assertFailsWith<ApiException> { controller.prepareCurrent(1L) }

        assertEquals(ErrorCode.HOF_LOGIN_FAILED, error.errorCode)
        Mockito.verify(captchaService).invalidateCurrentPreparation(1L)
    }

    @Test
    fun submitAnswerInvalidatesPreparedSnapshotWithoutRetryingExpiredSession() {
        Mockito.`when`(captchaService.submitAnswer(1L, 7L, "AB12", 3))
            .thenThrow(ApiException(ErrorCode.HOF_SESSION_EXPIRED, "expired"))

        val error = assertFailsWith<ApiException> {
            controller.submitAnswer(1L, 7L, SubmitCaptchaAnswerRequest("AB12", 3))
        }

        assertEquals(ErrorCode.HOF_SESSION_EXPIRED, error.errorCode)
        Mockito.verify(captchaService).invalidateCurrentPreparation(1L)
        Mockito.verifyNoInteractions(accountService)
        Mockito.verify(captchaService, Mockito.times(1)).submitAnswer(1L, 7L, "AB12", 3)
    }

    @Test
    fun submitAnswerRetriesCaptcha503WithoutInvalidatingPreparation() {
        val ready = readyCaptcha()
        Mockito.`when`(captchaService.submitAnswer(1L, 7L, "AB12", 3))
            .thenThrow(HofCaptchaRetryException())
            .thenReturn(ready)

        val response = controller.submitAnswer(1L, 7L, SubmitCaptchaAnswerRequest("AB12", 3))

        assertEquals(ready, response)
        Mockito.verify(captchaService, Mockito.times(2)).submitAnswer(1L, 7L, "AB12", 3)
        Mockito.verify(captchaService, Mockito.never()).invalidateCurrentPreparation(1L)
    }

    @Test
    fun submitAnswerInvalidatesConsumedPreparationAndRethrowsOriginalControlSignal() {
        val signal = ApiException(ErrorCode.HOF_TEMPORARILY_UNAVAILABLE, "retry later")
        val requestCookies = mapOf("PHPSESSID" to "initial")
        val responseSetCookies = mapOf("PHPSESSID" to "rotated")
        Mockito.`when`(captchaService.submitAnswer(1L, 7L, "AB12", 3))
            .thenThrow(CaptchaPreparationConsumedException.from(signal, requestCookies, responseSetCookies))

        val actual = assertFailsWith<ApiException> {
            controller.submitAnswer(1L, 7L, SubmitCaptchaAnswerRequest("AB12", 3))
        }

        assertSame(signal, actual)
        Mockito.verify(captchaService).recoverConsumedPreparation(1L, 7L, 3, requestCookies, responseSetCookies)
        Mockito.verify(captchaService, Mockito.never()).invalidateCurrentPreparation(1L)
    }

    @Test
    fun submitAnswerDirectControlRejectionDoesNotInvalidatePreparation() {
        val signal = ApiException(ErrorCode.HOF_TEMPORARILY_UNAVAILABLE, "retry later")
        Mockito.`when`(captchaService.submitAnswer(1L, 7L, "AB12", 3)).thenThrow(signal)

        val actual = assertFailsWith<ApiException> {
            controller.submitAnswer(1L, 7L, SubmitCaptchaAnswerRequest("AB12", 3))
        }

        assertSame(signal, actual)
        Mockito.verify(captchaService, Mockito.never()).invalidateCurrentPreparation(1L)
        Mockito.verify(captchaService).submitAnswer(1L, 7L, "AB12", 3)
        Mockito.verifyNoMoreInteractions(captchaService)
    }

    @Test
    fun submitAnswerCleanupFailureIsSuppressedOnOriginalControlSignal() {
        val signal = ApiException(ErrorCode.HOF_TEMPORARILY_UNAVAILABLE, "retry later")
        val cleanupError = IllegalStateException("cleanup failed")
        val requestCookies = mapOf("PHPSESSID" to "initial")
        val responseSetCookies = mapOf("PHPSESSID" to "rotated")
        Mockito.`when`(captchaService.submitAnswer(1L, 7L, "AB12", 3))
            .thenThrow(CaptchaPreparationConsumedException.from(signal, requestCookies, responseSetCookies))
        Mockito.doThrow(cleanupError).`when`(captchaService)
            .recoverConsumedPreparation(1L, 7L, 3, requestCookies, responseSetCookies)

        val actual = assertFailsWith<ApiException> {
            controller.submitAnswer(1L, 7L, SubmitCaptchaAnswerRequest("AB12", 3))
        }

        assertSame(signal, actual)
        assertEquals(listOf(cleanupError), actual.suppressed.toList())
        Mockito.verify(captchaService, Mockito.times(1))
            .recoverConsumedPreparation(1L, 7L, 3, requestCookies, responseSetCookies)
    }

    private fun readyCaptcha() = CaptchaChallengeResponse(
        id = 7L,
        accountId = 1L,
        status = "READY",
        prompt = "캡차 인증이 필요합니다.",
        imageUrl = "/api/captcha/7/image?version=1",
        sourceUrl = "http://sic.zerosic.com/ZeroHOF/index.php?menu=police",
        preparationVersion = 1,
        createdAt = Instant.parse("2026-07-23T00:00:00Z").toString(),
        answeredAt = null,
    )
}
