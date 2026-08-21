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
    data object Skipped : HandlerEvaluation {
        const val reasonCode: String = "NOT_RUNNABLE"
        const val message: String = "현재 실행할 행동이 없습니다."
    }
    data class Unavailable(
        val nextRunAt: Instant,
        val reasonCode: String = "COOLDOWN",
        val message: String = "다음 실행 가능 시각까지 대기합니다.",
        val waitScope: AutomationWaitScope = AutomationWaitScope.RELEASE_OTHER_AUTOMATIONS,
    ) : HandlerEvaluation
    data class ConfigurationWarning(
        val message: String,
        val reasonCode: String = "CONFIGURATION_WARNING",
    ) : HandlerEvaluation
    data class Fatal(val reason: AutomationStopReason, val message: String) : HandlerEvaluation
}

enum class AutomationWaitScope {
    RELEASE_OTHER_AUTOMATIONS,
    HOLD_CURRENT_WORK,
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
