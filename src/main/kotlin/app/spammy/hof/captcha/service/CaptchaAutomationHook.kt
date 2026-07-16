package app.spammy.hof.captcha.service

import app.spammy.hof.automation.entity.AutomationActionStatus
import app.spammy.hof.automation.service.AutomationActionContext
import app.spammy.hof.automation.service.AutomationActionLockCoordinator
import app.spammy.hof.automation.service.AutomationAfterCommitWakeupService
import app.spammy.hof.automation.service.TypedAutomationRuntimeService
import app.spammy.hof.automation.service.TypedCaptchaAutomationResumeService
import app.spammy.hof.captcha.entity.CaptchaChallengeEntity
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.push.service.PushOutboxService
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.slf4j.LoggerFactory

/** 자동화 action과 전역 캡차 challenge의 일시정지·재개 상태를 연결한다. */
@Component
class CaptchaAutomationHook(
    private val actionLockCoordinator: AutomationActionLockCoordinator,
    private val pushOutboxService: PushOutboxService,
    private val afterCommitWakeupService: AutomationAfterCommitWakeupService,
    private val typedRuntimeService: TypedAutomationRuntimeService,
    private val typedCaptchaResumeService: TypedCaptchaAutomationResumeService,
    private val timeProvider: TimeProvider,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 현재 토큰의 RUNNING attempt만 캡차 대기로 연결하고 사용자 알림 outbox를 생성한다. */
    fun detected(challenge: CaptchaChallengeEntity) {
        val token = AutomationActionContext.currentToken()
        if (token != null) {
            val locked = actionLockCoordinator.lock(token.actionId) ?: return
            val action = locked.action
            val job = locked.job
            if (job.account.id != challenge.account.id) return
            if (action.status != AutomationActionStatus.RUNNING || action.attemptCount != token.attemptCount) {
                log.debug(
                    "Ignoring stale captcha detection actionId={} attempt={} currentAttempt={} currentStatus={}",
                    token.actionId,
                    token.attemptCount,
                    action.attemptCount,
                    action.status,
                )
                return
            }
            challenge.automationActionRun = action
            action.status = AutomationActionStatus.WAITING_CAPTCHA
            job.status = "WAITING_CAPTCHA"
            job.message = "인증이 필요합니다."
            job.nextRunAt = null
            job.updatedAt = timeProvider.now()
            pushOutboxService.enqueueCaptchaRequired(challenge.account, challenge.id)
            return
        }
        if (typedRuntimeService.isRunning(challenge.account.id)) {
            pushOutboxService.enqueueCaptchaRequired(challenge.account, challenge.id)
        }
    }

    /**
     * 캡차를 발생시킨 과거 action을 종료하고 같은 job의 다음 step을 최신 설정 판단으로 재개한다.
     *
     * 과거 prepared payload를 재시도하지 않고 다음 실행 요청이 새 설정을 다시 읽도록 한다.
     */
    fun answered(challenge: CaptchaChallengeEntity) {
        val linkedAction = challenge.automationActionRun
        if (linkedAction == null) {
            afterCommit { deliverTypedResumeSafely(challenge.account.id) }
            return
        }
        val locked = actionLockCoordinator.lock(linkedAction.id) ?: return
        val action = locked.action
        val job = locked.job
        if (job.account.id != challenge.account.id) return
        if (action.status != AutomationActionStatus.WAITING_CAPTCHA) return
        val now = timeProvider.now()
        action.status = AutomationActionStatus.ABORTED
        action.nextAttemptAt = null
        action.finishedAt = now
        action.updatedAt = now
        job.status = "RUNNING"
        job.currentStepIndex += 1
        job.currentModule = null
        job.currentModuleConfigId = null
        job.currentAction = null
        job.message = "인증이 완료되어 최신 설정으로 자동화를 이어갑니다."
        job.nextRunAt = now
        job.lastHeartbeatAt = now
        job.updatedAt = now
        afterCommit { deliverLegacyWakeSafely(challenge.account.id) }
    }

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

    /** 이미 성공한 캡차 API 응답을 wake 전달 오류로 실패시키지 않는다. */
    private fun deliverLegacyWakeSafely(accountId: Long) {
        try {
            afterCommitWakeupService.wake(accountId, "CAPTCHA_ANSWERED")
        } catch (exception: Exception) {
            log.warn(
                "Captcha automation wake failed accountId={} errorType={}",
                accountId,
                exception.javaClass.name,
            )
        }
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
}
