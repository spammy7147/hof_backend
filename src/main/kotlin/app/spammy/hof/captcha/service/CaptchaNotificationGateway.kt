package app.spammy.hof.captcha.service

import app.spammy.hof.account.entity.HofAccountEntity
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

interface CaptchaNotificationGateway {
    fun captchaRequired(account: HofAccountEntity, challengeId: Long, eventId: String? = null)

    fun loginRequired(account: HofAccountEntity, eventId: String)
}

@Component
@Profile("dev | test")
class NoOpCaptchaNotificationGateway : CaptchaNotificationGateway {
    override fun captchaRequired(account: HofAccountEntity, challengeId: Long, eventId: String?) = Unit

    override fun loginRequired(account: HofAccountEntity, eventId: String) = Unit
}
