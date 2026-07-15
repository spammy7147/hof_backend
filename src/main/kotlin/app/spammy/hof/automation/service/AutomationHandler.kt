package app.spammy.hof.automation.service

import java.time.Instant
import app.spammy.hof.battle.dto.BattlePatternLoadRequest

data class ResolvedAutomationParty(val characterIds: List<String>, val patternLoads: List<BattlePatternLoadRequest>) {
    init { require(characterIds.isNotEmpty() && characterIds.size <= 5); require(patternLoads.size == characterIds.size) }
}

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
    NETWORK,
    FATAL,
    UNKNOWN,
}
