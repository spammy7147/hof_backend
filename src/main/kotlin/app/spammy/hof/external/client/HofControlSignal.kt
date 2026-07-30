package app.spammy.hof.external.client

import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode

/** Governor가 호출자에게 전달하는 재시도 제어 신호만 원형 그대로 다시 던진다. */
fun Throwable.rethrowIfHofControlSignal() {
    when {
        this is HofAutomationDeferredException -> throw this
        this is ApiException && errorCode == ErrorCode.HOF_TEMPORARILY_UNAVAILABLE -> throw this
    }
}
