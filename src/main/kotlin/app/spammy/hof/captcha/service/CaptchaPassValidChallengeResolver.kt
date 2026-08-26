package app.spammy.hof.captcha.service

import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/** 외부 발급을 포함한 유효 통행증 관측을 기존 challenge·전투 관문 해소로 수렴시킨다. */
@Component
class CaptchaPassValidChallengeResolver(
    private val captcha: CaptchaService,
) {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onValidPass(event: CaptchaPassValidObservedEvent) {
        captcha.resolveCurrentPassChallenge(event.accountId)
    }
}
