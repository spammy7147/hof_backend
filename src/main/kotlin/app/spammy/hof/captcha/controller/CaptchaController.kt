package app.spammy.hof.captcha.controller

import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.captcha.dto.CaptchaChallengeResponse
import app.spammy.hof.captcha.dto.SubmitCaptchaAnswerRequest
import app.spammy.hof.captcha.service.CaptchaPreparationConsumedException
import app.spammy.hof.captcha.service.CaptchaService
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.security.CurrentAccountId
import org.slf4j.LoggerFactory
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/captcha")
/**
 * 캡차 조회, 이미지 로드, 답안 제출 API다.
 */
class CaptchaController(
    private val captchaService: CaptchaService,
    private val sessionRecoveryService: HofSessionRecoveryService,
) {
    private val log = LoggerFactory.getLogger(CaptchaController::class.java)

    /**
     * 현재 계정에 대기 중인 캡차 challenge를 조회한다.
     */
    @GetMapping("/current")
    fun findCurrent(
        @CurrentAccountId accountId: Long,
    ): CaptchaChallengeResponse? =
        captchaService.findCurrent(accountId)

    @PostMapping("/current/prepare")
    fun prepareCurrent(
        @CurrentAccountId accountId: Long,
    ): CaptchaChallengeResponse =
        try {
            sessionRecoveryService.execute(accountId) {
                captchaService.prepareCurrent(accountId)
            }
        } catch (error: Throwable) {
            runCatching { captchaService.invalidateCurrentPreparation(accountId) }
            throw error
        }

    /**
     * 서버에 임시 저장한 캡차 이미지를 앱으로 내려준다.
     */
    @GetMapping("/{challengeId}/image")
    fun loadImage(
        @CurrentAccountId accountId: Long,
        @PathVariable challengeId: Long,
        @RequestParam("version") preparationVersion: Int,
    ): ResponseEntity<ByteArray> {
        val image = captchaService.loadImage(
            accountId = accountId,
            challengeId = challengeId,
            preparationVersion = preparationVersion,
        )

        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(image.contentType))
            .cacheControl(CacheControl.noStore())
            .body(image.bytes)
    }

    /**
     * 사용자가 입력한 캡차 답안을 HOF 원본 서버에 제출한다.
     */
    @PostMapping("/{challengeId}/answer")
    fun submitAnswer(
        @CurrentAccountId accountId: Long,
        @PathVariable challengeId: Long,
        @RequestBody request: SubmitCaptchaAnswerRequest,
    ): CaptchaChallengeResponse =
        try {
            captchaService.submitAnswer(
                accountId = accountId,
                challengeId = challengeId,
                answer = request.answer,
                preparationVersion = request.preparationVersion,
            )
        } catch (error: CaptchaPreparationConsumedException) {
            val controlSignal = error.controlSignal
            try {
                captchaService.invalidateCurrentPreparation(accountId)
            } catch (cleanupError: Throwable) {
                log.error(
                    "Consumed CAPTCHA preparation cleanup failed accountId={} cleanupErrorType={} cleanupMessage={}",
                    accountId,
                    cleanupError.javaClass.simpleName,
                    cleanupError.message,
                )
                controlSignal.addSuppressed(cleanupError)
            }
            throw controlSignal
        } catch (error: ApiException) {
            if (error.errorCode == ErrorCode.HOF_SESSION_EXPIRED) {
                runCatching { captchaService.invalidateCurrentPreparation(accountId) }
            }
            throw error
        }
}
