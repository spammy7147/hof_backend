package app.spammy.hof.captcha.service

import app.spammy.hof.account.entity.HofAccountEntity
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

fun interface CaptchaNotificationGateway {
    fun captchaRequired(account: HofAccountEntity, challengeId: Long)
}

@Component
@Profile("dev | test")
class NoOpCaptchaNotificationGateway : CaptchaNotificationGateway {
    override fun captchaRequired(account: HofAccountEntity, challengeId: Long) = Unit
}
