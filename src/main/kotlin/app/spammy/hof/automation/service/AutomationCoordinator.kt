package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.AutomationType
import java.time.Instant
import org.springframework.stereotype.Service

data class AutomationCoordinatorEntry(
    val id: Long,
    val type: AutomationType,
    val quest: QuestAutomationSnapshot? = null,
    val battle: BattleMapAutomationSnapshot? = null,
    val adventure: AdventureMapAutomationSnapshot? = null,
)

data class AutomationCoordinatorSnapshot(val entries: List<AutomationCoordinatorEntry>)

sealed interface AutomationCoordination {
    val warnings: List<String>

    data class Runnable(
        val entryId: Long,
        val action: PreparedAutomationAction,
        override val warnings: List<String>,
    ) : AutomationCoordination

    data class Fatal(
        val reason: AutomationStopReason,
        val message: String,
        override val warnings: List<String>,
    ) : AutomationCoordination

    data class Unavailable(
        val nextRunAt: Instant,
        override val warnings: List<String>,
    ) : AutomationCoordination

    data class Idle(override val warnings: List<String>) : AutomationCoordination
}

@Service
class AutomationCoordinator(
    private val quest: AutomationHandler<QuestAutomationSnapshot>,
    private val battle: AutomationHandler<BattleMapAutomationSnapshot>,
    private val adventure: AutomationHandler<AdventureMapAutomationSnapshot>,
) {
    fun coordinate(snapshot: AutomationCoordinatorSnapshot): AutomationCoordination {
        val warnings = mutableListOf<String>()
        var earliest: Instant? = null
        snapshot.entries.forEach { entry ->
            val evaluation = when (entry.type) {
                AutomationType.QUEST -> entry.quest?.let(quest::evaluate)
                AutomationType.BATTLE_MAP -> entry.battle?.let(battle::evaluate)
                AutomationType.ADVENTURE_MAP -> entry.adventure?.let(adventure::evaluate)
            } ?: HandlerEvaluation.ConfigurationWarning("${entry.type} automation snapshot is missing.")
            when (evaluation) {
                is HandlerEvaluation.Runnable -> return AutomationCoordination.Runnable(entry.id, evaluation.action, warnings.toList())
                is HandlerEvaluation.Fatal -> return AutomationCoordination.Fatal(evaluation.reason, evaluation.message, warnings.toList())
                is HandlerEvaluation.ConfigurationWarning -> warnings += evaluation.message
                is HandlerEvaluation.Unavailable -> if (earliest == null || evaluation.nextRunAt < earliest) earliest = evaluation.nextRunAt
                HandlerEvaluation.Skipped -> Unit
            }
        }
        return earliest?.let { AutomationCoordination.Unavailable(it, warnings) }
            ?: AutomationCoordination.Idle(warnings)
    }
}
