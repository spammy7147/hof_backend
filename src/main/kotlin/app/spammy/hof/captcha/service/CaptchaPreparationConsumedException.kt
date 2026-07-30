package app.spammy.hof.captcha.service

import app.spammy.hof.external.client.isHofControlSignal
import java.util.Collections

internal class CaptchaPreparationConsumedException private constructor(
    val controlSignal: RuntimeException,
    val responseSetCookies: Map<String, String>,
) : RuntimeException("CAPTCHA preparation was consumed before a follow-up request failed", controlSignal) {
    companion object {
        fun from(
            controlSignal: Throwable,
            responseSetCookies: Map<String, String>,
        ): CaptchaPreparationConsumedException {
            require(controlSignal.isHofControlSignal()) { "Only HOF control signals can consume a CAPTCHA preparation" }
            return CaptchaPreparationConsumedException(
                controlSignal = controlSignal as RuntimeException,
                responseSetCookies = Collections.unmodifiableMap(LinkedHashMap(responseSetCookies)),
            )
        }
    }
}
