package app.spammy.hof.captcha.service

import app.spammy.hof.automation.entity.AutomationActionStatus
import app.spammy.hof.automation.port.AutomationWakeupPort
import app.spammy.hof.automation.repository.AutomationActionRunQueryRepository
import app.spammy.hof.automation.service.AutomationActionContext
import app.spammy.hof.captcha.entity.CaptchaChallengeEntity
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.push.service.PushOutboxService
import org.springframework.stereotype.Component

@Component
class CaptchaAutomationHook(
    private val actionQueryRepository: AutomationActionRunQueryRepository,
    private val pushOutboxService: PushOutboxService,
    private val wakeupPort: AutomationWakeupPort,
    private val timeProvider: TimeProvider,
) {
    fun detected(challenge: CaptchaChallengeEntity) {
        val action = AutomationActionContext.currentActionId()?.let(actionQueryRepository::findById) ?: return
        challenge.automationActionRun = action
        action.status = AutomationActionStatus.WAITING_CAPTCHA
        action.job.status = "WAITING_CAPTCHA"
        action.job.message = "인증이 필요합니다."
        action.job.nextRunAt = null
        action.job.updatedAt = timeProvider.now()
        pushOutboxService.enqueueCaptchaRequired(challenge.account, challenge.id)
    }

    fun answered(challenge: CaptchaChallengeEntity) {
        val action = challenge.automationActionRun ?: return
        val now = timeProvider.now()
        action.status = AutomationActionStatus.RETRY_WAIT
        action.nextAttemptAt = now
        action.updatedAt = now
        action.job.status = "RUNNING"
        action.job.message = "인증 완료. 자동으로 이어서 실행합니다."
        action.job.nextRunAt = now
        action.job.updatedAt = now
        wakeupPort.wake(challenge.account.id, "CAPTCHA_ANSWERED")
    }
}
