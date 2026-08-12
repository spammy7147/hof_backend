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
    val raid: RaidAutomationSnapshot? = null,
    val union: UnionAutomationSnapshot? = null,
    val fishing: FishingAutomationSnapshot? = null,
)

data class AutomationCoordinatorSnapshot(val entries: List<AutomationCoordinatorEntry>)

enum class AutomationDecisionOutcome {
    SELECTED,
    SKIPPED,
    WAITING,
    CONFIGURATION_WARNING,
    FATAL,
}

data class AutomationEvaluationTrace(
    val sequence: Int,
    val entryId: Long,
    val type: AutomationType,
    val outcome: AutomationDecisionOutcome,
    val reasonCode: String,
    val message: String,
    val nextRunAt: Instant? = null,
)

sealed interface AutomationCoordination {
    val warnings: List<String>
    val trace: List<AutomationEvaluationTrace>

    data class Runnable(
        val entryId: Long,
        val action: PreparedAutomationAction,
        override val warnings: List<String>,
        override val trace: List<AutomationEvaluationTrace> = emptyList(),
    ) : AutomationCoordination

    data class Fatal(
        val reason: AutomationStopReason,
        val message: String,
        override val warnings: List<String>,
        override val trace: List<AutomationEvaluationTrace> = emptyList(),
    ) : AutomationCoordination

    data class Unavailable(
        val nextRunAt: Instant,
        override val warnings: List<String>,
        override val trace: List<AutomationEvaluationTrace> = emptyList(),
    ) : AutomationCoordination

    data class Idle(
        override val warnings: List<String>,
        override val trace: List<AutomationEvaluationTrace> = emptyList(),
    ) : AutomationCoordination
}

@Service
class AutomationCoordinator(
    private val quest: AutomationHandler<QuestAutomationSnapshot>,
    private val battle: AutomationHandler<BattleMapAutomationSnapshot>,
    private val adventure: AutomationHandler<AdventureMapAutomationSnapshot>,
    private val raid: AutomationHandler<RaidAutomationSnapshot>? = null,
    private val union: AutomationHandler<UnionAutomationSnapshot>? = null,
    private val fishing: AutomationHandler<FishingAutomationSnapshot>? = null,
) {
    fun coordinate(snapshot: AutomationCoordinatorSnapshot): AutomationCoordination {
        val warnings = mutableListOf<String>()
        val trace = mutableListOf<AutomationEvaluationTrace>()
        var earliest: Instant? = null
        snapshot.entries.forEach { entry ->
            val evaluation = when (entry.type) {
                AutomationType.QUEST -> entry.quest?.let(quest::evaluate)
                AutomationType.BATTLE_MAP -> entry.battle?.let(battle::evaluate)
                AutomationType.ADVENTURE_MAP -> entry.adventure?.let(adventure::evaluate)
                AutomationType.RAID -> entry.raid?.let { raid?.evaluate(it) }
                AutomationType.UNION -> entry.union?.let { union?.evaluate(it) }
                AutomationType.FISHING -> entry.fishing?.let { fishing?.evaluate(it) }
            } ?: HandlerEvaluation.ConfigurationWarning("${entry.type} automation snapshot is missing.")
            val sequence = trace.size
            when (evaluation) {
                is HandlerEvaluation.Runnable -> {
                    trace += AutomationEvaluationTrace(sequence, entry.id, entry.type, AutomationDecisionOutcome.SELECTED, "RUNNABLE", "실행할 행동을 선택했습니다.")
                    return AutomationCoordination.Runnable(entry.id, evaluation.action, warnings.toList(), trace.toList())
                }
                is HandlerEvaluation.Fatal -> {
                    trace += AutomationEvaluationTrace(sequence, entry.id, entry.type, AutomationDecisionOutcome.FATAL, evaluation.reason.name, evaluation.message)
                    return AutomationCoordination.Fatal(evaluation.reason, evaluation.message, warnings.toList(), trace.toList())
                }
                is HandlerEvaluation.ConfigurationWarning -> {
                    warnings += evaluation.message
                    trace += AutomationEvaluationTrace(sequence, entry.id, entry.type, AutomationDecisionOutcome.CONFIGURATION_WARNING, evaluation.reasonCode, evaluation.message)
                }
                is HandlerEvaluation.Unavailable -> {
                    if (earliest == null || evaluation.nextRunAt < earliest) earliest = evaluation.nextRunAt
                    trace += AutomationEvaluationTrace(sequence, entry.id, entry.type, AutomationDecisionOutcome.WAITING, evaluation.reasonCode, evaluation.message, evaluation.nextRunAt)
                }
                HandlerEvaluation.Skipped -> {
                    trace += AutomationEvaluationTrace(sequence, entry.id, entry.type, AutomationDecisionOutcome.SKIPPED, HandlerEvaluation.Skipped.reasonCode, HandlerEvaluation.Skipped.message)
                }
            }
        }
        return earliest?.let { AutomationCoordination.Unavailable(it, warnings, trace.toList()) }
            ?: AutomationCoordination.Idle(warnings, trace.toList())
    }
}
