package app.spammy.hof.external.client

import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode

/** Governor가 호출자에게 전달하는 재시도 제어 신호인지 판별한다. */
fun Throwable.isHofControlSignal(): Boolean =
    this is HofAutomationDeferredException ||
        this is HofCaptchaRetryException ||
        this is ApiException && errorCode == ErrorCode.HOF_TEMPORARILY_UNAVAILABLE

/** Governor가 호출자에게 전달하는 재시도 제어 신호만 원형 그대로 다시 던진다. */
fun Throwable.rethrowIfHofControlSignal() {
    if (isHofControlSignal()) throw this
}
