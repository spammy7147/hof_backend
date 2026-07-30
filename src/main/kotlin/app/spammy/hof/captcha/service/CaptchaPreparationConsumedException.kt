package app.spammy.hof.captcha.service

import app.spammy.hof.external.client.isHofControlSignal

internal class CaptchaPreparationConsumedException private constructor(
    val controlSignal: RuntimeException,
) : RuntimeException("CAPTCHA preparation was consumed before a follow-up request failed", controlSignal) {
    companion object {
        fun from(controlSignal: Throwable): CaptchaPreparationConsumedException {
            require(controlSignal.isHofControlSignal()) { "Only HOF control signals can consume a CAPTCHA preparation" }
            return CaptchaPreparationConsumedException(controlSignal as RuntimeException)
        }
    }
}
