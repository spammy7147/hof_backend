package app.spammy.hof.captcha.service

import app.spammy.hof.captcha.config.CaptchaAutoSolveProperties
import app.spammy.hof.captcha.dto.CaptchaChallengeResponse
import app.spammy.hof.auth.service.AccountExecutionAuthorizationReader
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.external.client.HofCaptchaRetryException
import java.time.Instant
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import org.mockito.Mockito

class CaptchaAutoSolveCoordinatorTest {
    private val captchaService = Mockito.mock(CaptchaService::class.java)
    private val recognizer = Mockito.mock(CaptchaImageRecognizer::class.java)
    private val properties = CaptchaAutoSolveProperties(
        enabled = true,
        maxAttempts = 3,
        baseUrl = "http://ocr.test:8090",
        token = "x".repeat(64),
        ocrRetryDelay = Duration.ZERO,
    )
    private val coordinator = CaptchaAutoSolveCoordinator(captchaService, recognizer, properties)

    @Test
    fun `submits recognized answers until the second attempt succeeds`() {
        val detected = challenge(status = "DETECTED", version = 0)
        val firstReady = challenge(status = "READY", version = 1)
        val secondReady = challenge(status = "READY", version = 2)
        val answered = challenge(status = "ANSWERED", version = 2)
        val firstImage = CaptchaImageResponse("image/png", byteArrayOf(1))
        val secondImage = CaptchaImageResponse("image/png", byteArrayOf(2))
        Mockito.`when`(captchaService.findCurrent(1L)).thenReturn(detected)
        Mockito.`when`(captchaService.prepareCurrent(1L)).thenReturn(firstReady)
        Mockito.`when`(captchaService.loadImage(1L, 7L, 1)).thenReturn(firstImage)
        Mockito.`when`(captchaService.loadImage(1L, 7L, 2)).thenReturn(secondImage)
        val wrong = recognition("WRONG")
        val correct = recognition("AB12")
        Mockito.`when`(recognizer.recognize(firstImage)).thenReturn(wrong)
        Mockito.`when`(recognizer.recognize(secondImage)).thenReturn(correct)
        stubSubmission(wrong, 1, secondReady)
        stubSubmission(correct, 2, answered)

        val outcome = coordinator.solve(1L, 7L)

        assertEquals(CaptchaAutoSolveOutcome.SOLVED, outcome)
        Mockito.verify(captchaService, Mockito.never()).markManualInputRequired(1L, 7L, 3)
    }

    @Test
    fun `hands the prepared challenge to manual input after three wrong answers`() {
        val firstReady = challenge(status = "READY", version = 1)
        val secondReady = challenge(status = "READY", version = 2)
        val thirdReady = challenge(status = "READY", version = 3)
        val fourthReady = challenge(status = "READY", version = 4)
        Mockito.`when`(captchaService.findCurrent(1L)).thenReturn(firstReady)
        listOf(firstReady, secondReady, thirdReady).forEach { ready ->
            val image = CaptchaImageResponse("image/png", byteArrayOf(ready.preparationVersion.toByte()))
            Mockito.`when`(captchaService.loadImage(1L, 7L, ready.preparationVersion)).thenReturn(image)
            Mockito.`when`(recognizer.recognize(image)).thenReturn(recognition("BAD${ready.preparationVersion}"))
        }
        stubSubmission(recognition("BAD1"), 1, secondReady)
        stubSubmission(recognition("BAD2"), 2, thirdReady)
        stubSubmission(recognition("BAD3"), 3, fourthReady)

        val outcome = coordinator.solve(1L, 7L)

        assertEquals(CaptchaAutoSolveOutcome.MANUAL_INPUT_REQUIRED, outcome)
        Mockito.verify(captchaService).markManualInputRequired(1L, 7L, 3)
    }

    @Test
    fun `refreshes an unreadable image and counts it as an attempt`() {
        val firstReady = challenge(status = "READY", version = 1)
        val detected = challenge(status = "DETECTED", version = 0)
        val secondReady = challenge(status = "READY", version = 2)
        val answered = challenge(status = "ANSWERED", version = 2)
        val unreadable = CaptchaImageResponse("image/png", byteArrayOf(1))
        val readable = CaptchaImageResponse("image/png", byteArrayOf(2))
        Mockito.`when`(captchaService.findCurrent(1L)).thenReturn(firstReady, detected)
        Mockito.`when`(captchaService.loadImage(1L, 7L, 1)).thenReturn(unreadable)
        Mockito.`when`(recognizer.recognize(unreadable)).thenReturn(null)
        Mockito.`when`(captchaService.prepareCurrent(1L)).thenReturn(secondReady)
        Mockito.`when`(captchaService.loadImage(1L, 7L, 2)).thenReturn(readable)
        Mockito.`when`(recognizer.recognize(readable)).thenReturn(recognition("AB12"))
        stubSubmission(recognition("AB12"), 2, answered)

        val outcome = coordinator.solve(1L, 7L)

        assertEquals(CaptchaAutoSolveOutcome.SOLVED, outcome)
        Mockito.verify(captchaService).invalidateCurrentPreparation(1L)
    }

    @Test
    fun `OCR failures do not consume the HOF rejection limit`() {
        val oneHofAttemptProperties = properties.copy(
            maxAttempts = 1,
            maxOcrFailures = 2,
        )
        val oneHofAttemptCoordinator = CaptchaAutoSolveCoordinator(
            captchaService,
            recognizer,
            oneHofAttemptProperties,
        )
        val firstReady = challenge(status = "READY", version = 1)
        val detected = challenge(status = "DETECTED", version = 0)
        val secondReady = challenge(status = "READY", version = 2)
        val answered = challenge(status = "ANSWERED", version = 2)
        val unreadable = CaptchaImageResponse("image/png", byteArrayOf(1))
        val readable = CaptchaImageResponse("image/png", byteArrayOf(2))
        Mockito.`when`(captchaService.findCurrent(1L)).thenReturn(firstReady, detected)
        Mockito.`when`(captchaService.loadImage(1L, 7L, 1)).thenReturn(unreadable)
        Mockito.`when`(recognizer.recognize(unreadable)).thenReturn(null)
        Mockito.`when`(captchaService.prepareCurrent(1L)).thenReturn(secondReady)
        Mockito.`when`(captchaService.loadImage(1L, 7L, 2)).thenReturn(readable)
        Mockito.`when`(recognizer.recognize(readable)).thenReturn(recognition("AB12"))
        stubSubmission(recognition("AB12"), 2, answered)

        val outcome = oneHofAttemptCoordinator.solve(1L, 7L)

        assertEquals(CaptchaAutoSolveOutcome.SOLVED, outcome)
        Mockito.verify(captchaService).submitAutomaticAnswer(
            Mockito.eq(1L), Mockito.eq(7L), eqRecognition(recognition("AB12")), Mockito.eq(2), anyAuthorization(),
        )
    }

    @Test
    fun `hands off to manual input after consecutive OCR API failures`() {
        val ready = challenge(status = "READY", version = 1)
        val image = CaptchaImageResponse("image/png", byteArrayOf(1))
        Mockito.`when`(captchaService.findCurrent(1L)).thenReturn(ready)
        Mockito.`when`(captchaService.loadImage(1L, 7L, 1)).thenReturn(image)
        Mockito.`when`(recognizer.recognize(image)).thenThrow(IllegalStateException("unavailable"))

        val outcome = coordinator.solve(1L, 7L)

        assertEquals(CaptchaAutoSolveOutcome.MANUAL_INPUT_REQUIRED, outcome)
        Mockito.verify(recognizer, Mockito.times(3)).recognize(image)
        assertFalse(
            Mockito.mockingDetails(captchaService).invocations
                .any { invocation -> invocation.method.name == "submitAutomaticAnswer" },
        )
        Mockito.verify(captchaService).markManualInputRequired(1L, 7L, 0)
    }

    @Test
    fun `does nothing when the detection event no longer owns the current challenge`() {
        Mockito.`when`(captchaService.findCurrent(1L)).thenReturn(challenge(id = 8L, status = "READY", version = 1))

        val outcome = coordinator.solve(1L, 7L)

        assertEquals(CaptchaAutoSolveOutcome.NO_PENDING_CHALLENGE, outcome)
        Mockito.verifyNoInteractions(recognizer)
    }

    @Test
    fun `retries a captcha 503 without consuming an automatic recognition attempt`() {
        val ready = challenge(status = "READY", version = 1)
        val answered = challenge(status = "ANSWERED", version = 1)
        val image = CaptchaImageResponse("image/png", byteArrayOf(1))
        Mockito.`when`(captchaService.findCurrent(1L)).thenReturn(ready)
        Mockito.`when`(captchaService.loadImage(1L, 7L, 1)).thenReturn(image)
        Mockito.`when`(recognizer.recognize(image)).thenReturn(recognition("AB12"))
        Mockito.`when`(captchaService.submitAutomaticAnswer(
            Mockito.eq(1L), Mockito.eq(7L), eqRecognition(recognition("AB12")), Mockito.eq(1), anyAuthorization(),
        ))
            .thenThrow(HofCaptchaRetryException())
            .thenReturn(answered)

        val outcome = coordinator.solve(1L, 7L)

        assertEquals(CaptchaAutoSolveOutcome.SOLVED, outcome)
        Mockito.verify(recognizer, Mockito.times(1)).recognize(image)
        Mockito.verify(captchaService, Mockito.times(2))
            .submitAutomaticAnswer(
                Mockito.eq(1L), Mockito.eq(7L), eqRecognition(recognition("AB12")), Mockito.eq(1), anyAuthorization(),
            )
    }

    @Test
    fun `checks the caller execution right immediately before every remote answer`() {
        val ready = challenge(status = "READY", version = 1)
        val image = CaptchaImageResponse("image/png", byteArrayOf(1))
        Mockito.`when`(captchaService.findCurrent(1L)).thenReturn(ready)
        Mockito.`when`(captchaService.loadImage(1L, 7L, 1)).thenReturn(image)
        Mockito.`when`(recognizer.recognize(image)).thenReturn(recognition("AB12"))
        stubAuthorizationCancellation()

        val outcome = coordinator.solve(1L, 7L) { false }

        assertEquals(CaptchaAutoSolveOutcome.CANCELLED, outcome)
        Mockito.verify(captchaService).submitAutomaticAnswer(
            Mockito.eq(1L), Mockito.eq(7L), eqRecognition(recognition("AB12")), Mockito.eq(1), anyAuthorization(),
        )
        Mockito.verify(captchaService, Mockito.never()).markManualInputRequired(1L, 7L, 0)
    }

    @Test
    fun `proactive solving propagates HOF infrastructure failures to maintenance recovery`() {
        val detected = challenge(status = "DETECTED", version = 0)
        Mockito.`when`(captchaService.findCurrent(1L)).thenReturn(detected)
        Mockito.doThrow(ApiException(ErrorCode.HOF_SESSION_EXPIRED, "expired"))
            .`when`(captchaService).prepareCurrent(1L)

        val error = assertFailsWith<ApiException> {
            coordinator.solve(1L, 7L) { true }
        }

        assertEquals(ErrorCode.HOF_SESSION_EXPIRED, error.errorCode)
        Mockito.verify(captchaService, Mockito.never()).markManualInputRequired(1L, 7L, 0)
    }

    @Test
    fun `queued reactive solving stops before POST when the last app session ended`() {
        val authorization = Mockito.mock(AccountExecutionAuthorizationReader::class.java)
        val guarded = CaptchaAutoSolveCoordinator(captchaService, recognizer, properties, authorization)
        val ready = challenge(status = "READY", version = 1)
        val image = CaptchaImageResponse("image/png", byteArrayOf(1))
        Mockito.`when`(captchaService.findCurrent(1L)).thenReturn(ready)
        Mockito.`when`(captchaService.loadImage(1L, 7L, 1)).thenReturn(image)
        Mockito.`when`(recognizer.recognize(image)).thenReturn(recognition("AB12"))
        Mockito.`when`(authorization.isExecutionAllowed(1L)).thenReturn(false)
        stubAuthorizationCancellation()

        val outcome = guarded.solve(1L, 7L)

        assertEquals(CaptchaAutoSolveOutcome.CANCELLED, outcome)
        Mockito.verify(captchaService).submitAutomaticAnswer(
            Mockito.eq(1L), Mockito.eq(7L), eqRecognition(recognition("AB12")), Mockito.eq(1), anyAuthorization(),
        )
    }

    private fun recognition(text: String) = CaptchaRecognition(text, "2.1.1")

    private fun stubSubmission(
        recognition: CaptchaRecognition,
        version: Int,
        response: CaptchaChallengeResponse,
    ) {
        Mockito.`when`(
            captchaService.submitAutomaticAnswer(
                Mockito.eq(1L), Mockito.eq(7L), eqRecognition(recognition), Mockito.eq(version), anyAuthorization(),
            ),
        ).thenAnswer { invocation ->
            val authorize = invocation.getArgument<() -> Boolean>(4)
            if (!authorize()) throw CaptchaSubmissionAuthorizationCancelledException()
            response
        }
    }

    private fun stubAuthorizationCancellation() {
        Mockito.doAnswer { invocation ->
            val authorize = invocation.getArgument<() -> Boolean>(4)
            if (!authorize()) throw CaptchaSubmissionAuthorizationCancelledException()
            challenge(status = "ANSWERED", version = 1)
        }.`when`(captchaService).submitAutomaticAnswer(
            Mockito.eq(1L), Mockito.eq(7L), eqRecognition(recognition("AB12")), Mockito.eq(1), anyAuthorization(),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun anyAuthorization(): () -> Boolean = Mockito.any<() -> Boolean>() ?: { true }

    private fun eqRecognition(value: CaptchaRecognition): CaptchaRecognition = Mockito.eq(value) ?: value

    private fun challenge(
        id: Long = 7L,
        status: String,
        version: Int,
    ) = CaptchaChallengeResponse(
        id = id,
        accountId = 1L,
        status = status,
        prompt = "captcha",
        imageUrl = if (status == "READY") "/api/captcha/$id/image?version=$version" else null,
        sourceUrl = "https://example.test/captcha",
        preparationVersion = version,
        createdAt = Instant.parse("2026-08-04T00:00:00Z").toString(),
        answeredAt = if (status == "ANSWERED") Instant.parse("2026-08-04T00:00:01Z").toString() else null,
    )
}
