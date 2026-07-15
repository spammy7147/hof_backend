package app.spammy.hof.automation.service

import java.time.Instant

/** A typed, side-effect-free handler decision consumed by the future coordinator. */
fun interface AutomationHandler<C> {
    fun evaluate(context: C): HandlerEvaluation
}

sealed interface HandlerEvaluation {
    data class Runnable(val action: PreparedAutomationAction) : HandlerEvaluation
    data object Skipped : HandlerEvaluation
    data class Unavailable(val nextRunAt: Instant) : HandlerEvaluation
    data class ConfigurationWarning(val message: String) : HandlerEvaluation
    data class Fatal(val reason: AutomationStopReason, val message: String) : HandlerEvaluation
}

sealed interface PreparedAutomationAction

enum class AutomationStopReason {
    AUTHENTICATION,
    CAPTCHA,
    MANUAL_STOP,
    UNKNOWN,
}
