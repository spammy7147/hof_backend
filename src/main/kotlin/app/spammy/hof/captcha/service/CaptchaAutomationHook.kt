package app.spammy.hof.captcha.service

import app.spammy.hof.automation.service.TypedAutomationRuntimeService
import app.spammy.hof.automation.service.TypedCaptchaAutomationResumeService
import app.spammy.hof.captcha.entity.CaptchaChallengeEntity
import app.spammy.hof.captcha.entity.CaptchaChallengeEntity.Companion.KIND_VIGILANTE_PASS
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.slf4j.LoggerFactory

/** 자동화 action과 전역 캡차 challenge의 일시정지·재개 상태를 연결한다. */
@Component
class CaptchaAutomationHook(
    private val typedRuntimeService: TypedAutomationRuntimeService,
    private val typedCaptchaResumeService: TypedCaptchaAutomationResumeService,
    private val eventPublisher: ApplicationEventPublisher,
    private val passCompletion: CaptchaPassCompletionService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** typed 자동화 실행 중 발생한 캡차만 commit 뒤 자동 인식 작업에 연결한다. */
    fun detected(challenge: CaptchaChallengeEntity) {
        if (typedRuntimeService.isRunning(challenge.account.id)) {
            afterCommit {
                eventPublisher.publishEvent(AutomationCaptchaDetectedEvent(challenge.account, challenge.id))
            }
        }
    }

    /** 캡차 답변 commit 뒤 typed 자동화가 최신 설정으로 재개할 수 있게 전달한다. */
    fun answered(challenge: CaptchaChallengeEntity, confirmPass: Boolean = true) {
        challenge.automationResumePending = true
        afterCommit {
            deliverTypedResumeSafely(challenge.account.id)
            if (confirmPass && challenge.challengeKind == KIND_VIGILANTE_PASS) {
                deliverPassCompletionSafely(challenge.account.id)
            }
        }
    }

    /** 유효 통행증을 다시 관측하면 이미 저장된 답안의 미완료 재개만 재시도한다. */
    fun retryPendingResume(accountId: Long) = afterCommit { deliverTypedResumeSafely(accountId) }

    /** 캡차와 automation 변경이 commit된 뒤에만 다음 스냅샷 판단을 요청한다. */
    private fun afterCommit(action: () -> Unit) {
        if (!TransactionSynchronizationManager.isActualTransactionActive() ||
            !TransactionSynchronizationManager.isSynchronizationActive()
        ) {
            action()
            return
        }
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = action()
            },
        )
    }

    private fun deliverTypedResumeSafely(accountId: Long) {
        try {
            typedCaptchaResumeService.resumeAfterCaptcha(accountId)
        } catch (exception: Exception) {
            log.warn(
                "Typed captcha automation resume failed accountId={} errorType={}",
                accountId,
                exception.javaClass.name,
            )
        }
    }

    /** 답안은 이미 commit됐으므로 후속 상태 확인 실패를 제출 실패로 되돌려 보고하지 않는다. */
    private fun deliverPassCompletionSafely(accountId: Long) {
        try {
            passCompletion.confirmAfterAnswer(accountId)
        } catch (exception: Exception) {
            log.warn(
                "Pass confirmation after captcha answer failed accountId={} errorType={}",
                accountId,
                exception.javaClass.name,
            )
        }
    }
}
