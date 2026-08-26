package app.spammy.hof.push.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.captcha.service.CaptchaNotificationGateway
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

@Component
@Profile("prod")
class OutboxCaptchaNotificationGateway(
    private val pushOutboxService: PushOutboxService,
) : CaptchaNotificationGateway {
    override fun captchaRequired(account: HofAccountEntity, challengeId: Long, eventId: String?) {
        if (eventId == null) {
            pushOutboxService.enqueueCaptchaRequired(account, challengeId)
        } else {
            pushOutboxService.enqueueCaptchaRequired(account, challengeId, eventId)
        }
    }

    override fun loginRequired(account: HofAccountEntity, eventId: String) {
        pushOutboxService.enqueueLoginRequired(account, eventId)
    }
}
