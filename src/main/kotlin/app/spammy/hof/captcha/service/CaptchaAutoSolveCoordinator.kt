package app.spammy.hof.captcha.service

import app.spammy.hof.auth.service.AccountExecutionAuthorizationReader
import app.spammy.hof.captcha.config.CaptchaAutoSolveProperties
import app.spammy.hof.captcha.dto.CaptchaChallengeResponse
import app.spammy.hof.external.client.HofCaptchaRetryException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class CaptchaAutoSolveOutcome {
    SOLVED,
    MANUAL_INPUT_REQUIRED,
    NO_PENDING_CHALLENGE,
    CANCELLED,
}

@Service
class CaptchaAutoSolveCoordinator(
    private val captchaService: CaptchaService,
    private val recognizer: CaptchaImageRecognizer,
    private val properties: CaptchaAutoSolveProperties,
    private val executionAuthorization: AccountExecutionAuthorizationReader? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val accountLocks = Array(LOCK_STRIPES) { ReentrantLock() }

    fun solve(accountId: Long, challengeId: Long): CaptchaAutoSolveOutcome =
        accountLock(accountId).withLock {
            solveLocked(
                accountId,
                challengeId,
                authorizeSubmission = { executionAuthorization?.isExecutionAllowed(accountId) != false },
                manualInputRequired = { attemptCount ->
                    captchaService.markManualInputRequired(accountId, challengeId, attemptCount)
                },
                propagateInfrastructureFailure = false,
            )
        }

    /** 선제 갱신은 실제 HOF 답안 POST 직전에 영속 실행 권한을 다시 확인한다. */
    fun solve(
        accountId: Long,
        challengeId: Long,
        authorizeSubmission: (() -> Boolean)?,
    ): CaptchaAutoSolveOutcome = solve(accountId, challengeId, authorizeSubmission, null)

    /** 선제 갱신 terminal callback은 유지보수 상태와 수동 challenge 안내를 한 commit으로 묶는다. */
    fun solve(
        accountId: Long,
        challengeId: Long,
        authorizeSubmission: (() -> Boolean)?,
        manualInputRequired: ((Int) -> Unit)?,
    ): CaptchaAutoSolveOutcome =
        accountLock(accountId).withLock {
            solveLocked(
                accountId,
                challengeId,
                authorizeSubmission = {
                    executionAuthorization?.isExecutionAllowed(accountId) != false &&
                        requireNotNull(authorizeSubmission).invoke()
                },
                manualInputRequired = manualInputRequired ?: { attemptCount ->
                    captchaService.markManualInputRequired(accountId, challengeId, attemptCount)
                },
                propagateInfrastructureFailure = true,
            )
        }

    private fun solveLocked(
        accountId: Long,
        challengeId: Long,
        authorizeSubmission: () -> Boolean,
        manualInputRequired: (Int) -> Unit,
        propagateInfrastructureFailure: Boolean,
    ): CaptchaAutoSolveOutcome {
        if (!properties.enabled) return CaptchaAutoSolveOutcome.MANUAL_INPUT_REQUIRED

        var challenge = captchaService.findCurrent(accountId)
            ?.takeIf { it.id == challengeId }
            ?: return CaptchaAutoSolveOutcome.NO_PENDING_CHALLENGE

        var hofFailureCount = 0
        var consecutiveOcrFailureCount = 0
        try {
            while (hofFailureCount < properties.maxAttempts) {
                challenge = prepareIfRequired(accountId, challenge)
                if (challenge.status == STATUS_ANSWERED) return CaptchaAutoSolveOutcome.SOLVED
                if (challenge.id != challengeId) return CaptchaAutoSolveOutcome.NO_PENDING_CHALLENGE

                val image = captchaService.loadImage(accountId, challengeId, challenge.preparationVersion)
                val recognition = try {
                    recognizer.recognize(image)
                } catch (error: Exception) {
                    consecutiveOcrFailureCount += 1
                    log.warn(
                        "CAPTCHA OCR request failed accountId={} challengeId={} consecutiveOcrFailures={} errorType={}",
                        accountId,
                        challengeId,
                        consecutiveOcrFailureCount,
                        error.javaClass.name,
                    )
                    if (consecutiveOcrFailureCount >= properties.maxOcrFailures) {
                        manualInputRequired(0)
                        return CaptchaAutoSolveOutcome.MANUAL_INPUT_REQUIRED
                    }
                    waitBeforeOcrRetry()
                    continue
                }
                if (recognition == null) {
                    consecutiveOcrFailureCount += 1
                    log.info(
                        "CAPTCHA OCR returned no answer accountId={} challengeId={} consecutiveOcrFailures={}",
                        accountId,
                        challengeId,
                        consecutiveOcrFailureCount,
                    )
                    if (consecutiveOcrFailureCount >= properties.maxOcrFailures) {
                        manualInputRequired(0)
                        return CaptchaAutoSolveOutcome.MANUAL_INPUT_REQUIRED
                    }
                    captchaService.invalidateCurrentPreparation(accountId)
                    challenge = captchaService.findCurrent(accountId)
                        ?: return CaptchaAutoSolveOutcome.NO_PENDING_CHALLENGE
                    waitBeforeOcrRetry()
                    continue
                }
                consecutiveOcrFailureCount = 0

                log.info(
                    "Submitting CAPTCHA OCR answer accountId={} challengeId={} hofAttempt={}",
                    accountId,
                    challengeId,
                    hofFailureCount + 1,
                )
                while (true) {
                    try {
                        challenge = captchaService.submitAutomaticAnswer(
                            accountId = accountId,
                            challengeId = challengeId,
                            recognition = recognition,
                            preparationVersion = challenge.preparationVersion,
                            authorizeSubmission = authorizeSubmission,
                        )
                        break
                    } catch (_: CaptchaSubmissionAuthorizationCancelledException) {
                        return CaptchaAutoSolveOutcome.CANCELLED
                    } catch (_: HofCaptchaRetryException) {
                        // Governor가 다음 CAPTCHA 호출에 전용 간격을 적용한 뒤 권한부터 다시 확인한다.
                    }
                }
                if (challenge.status == STATUS_ANSWERED) return CaptchaAutoSolveOutcome.SOLVED
                hofFailureCount += 1
            }
        } catch (error: Exception) {
            if (propagateInfrastructureFailure) throw error
            log.warn(
                "CAPTCHA automatic solve failed accountId={} challengeId={} errorType={} message={}",
                accountId,
                challengeId,
                error.javaClass.name,
                error.message,
            )
            manualInputRequired(0)
            return CaptchaAutoSolveOutcome.MANUAL_INPUT_REQUIRED
        }

        manualInputRequired(properties.maxAttempts)
        return CaptchaAutoSolveOutcome.MANUAL_INPUT_REQUIRED
    }

    private fun prepareIfRequired(
        accountId: Long,
        challenge: CaptchaChallengeResponse,
    ): CaptchaChallengeResponse =
        if (challenge.status == STATUS_DETECTED) retryCaptcha503 { captchaService.prepareCurrent(accountId) } else challenge

    private fun <T> retryCaptcha503(action: () -> T): T {
        while (true) {
            try {
                return action()
            } catch (_: HofCaptchaRetryException) {
                // Governor가 다음 CAPTCHA 호출에 전용 간격을 적용하고 이전 transaction은 이미 종료됐다.
            }
        }
    }

    private fun waitBeforeOcrRetry() {
        if (properties.ocrRetryDelay.isZero) return
        try {
            Thread.sleep(properties.ocrRetryDelay.toMillis())
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while waiting to retry OCR", error)
        }
    }

    private fun accountLock(accountId: Long): ReentrantLock =
        accountLocks[Math.floorMod(accountId.hashCode(), accountLocks.size)]

    private companion object {
        const val LOCK_STRIPES = 64
        const val STATUS_DETECTED = "DETECTED"
        const val STATUS_ANSWERED = "ANSWERED"
    }
}
