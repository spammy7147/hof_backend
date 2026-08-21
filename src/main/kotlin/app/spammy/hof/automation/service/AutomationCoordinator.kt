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
    val union: UnionAutomationSnapshot? = null,
    val fishing: FishingAutomationSnapshot? = null,
    val homeQuest: HomeQuestAutomationSnapshot? = null,
)

data class AutomationCoordinatorSnapshot(val entries: List<AutomationCoordinatorEntry>)

enum class AutomationDecisionOutcome {
    SELECTED,
    SKIPPED,
    WAITING,
    CONFIGURATION_WARNING,
    CYCLE_COMPLETED,
    CYCLE_ABORTED,
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
    val actionKind: String? = null,
    val targetKey: String? = null,
    val targetName: String? = null,
    val presetId: Long? = null,
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
        val waitScope: AutomationWaitScope = AutomationWaitScope.RELEASE_OTHER_AUTOMATIONS,
    ) : AutomationCoordination

    data class Idle(
        override val warnings: List<String>,
        override val trace: List<AutomationEvaluationTrace> = emptyList(),
        val workTransition: AutomationWorkTransition? = null,
    ) : AutomationCoordination
}

@Service
class AutomationCoordinator(
    private val quest: QuestWorkCycleModule,
    private val battle: AutomationHandler<BattleMapAutomationSnapshot>,
    private val adventure: AutomationHandler<AdventureMapAutomationSnapshot>,
    private val union: AutomationHandler<UnionAutomationSnapshot>? = null,
    private val fishing: AutomationHandler<FishingAutomationSnapshot>? = null,
    private val homeQuest: AutomationHandler<HomeQuestAutomationSnapshot>? = null,
) {
    fun coordinate(snapshot: AutomationCoordinatorSnapshot): AutomationCoordination {
        val warnings = mutableListOf<String>()
        val trace = mutableListOf<AutomationEvaluationTrace>()
        var earliest: Instant? = null
        var earliestWaitScope = AutomationWaitScope.RELEASE_OTHER_AUTOMATIONS
        snapshot.entries.forEach { entry ->
            val evaluation = when (entry.type) {
                AutomationType.QUEST -> entry.quest?.let { quest.decideNext(it).toHandlerEvaluation() }
                AutomationType.HOME_QUEST -> entry.homeQuest?.let { homeQuest?.evaluate(it) }
                AutomationType.BATTLE_MAP -> entry.battle?.let(battle::evaluate)
                AutomationType.ADVENTURE_MAP -> entry.adventure?.let(adventure::evaluate)
                AutomationType.RAID -> HandlerEvaluation.Skipped
                AutomationType.UNION -> entry.union?.let { union?.evaluate(it) }
                AutomationType.FISHING -> entry.fishing?.let { fishing?.evaluate(it) }
            } ?: HandlerEvaluation.ConfigurationWarning("${entry.type} automation snapshot is missing.")
            val sequence = trace.size
            when (evaluation) {
                is HandlerEvaluation.Runnable -> {
                    trace += AutomationEvaluationTrace(
                        sequence, entry.id, entry.type, AutomationDecisionOutcome.SELECTED, "RUNNABLE",
                        "자동화 행동을 선택했습니다.",
                    )
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
                    if (earliest == null || evaluation.nextRunAt < earliest) {
                        earliest = evaluation.nextRunAt
                        earliestWaitScope = evaluation.waitScope
                    } else if (
                        evaluation.nextRunAt == earliest &&
                        evaluation.waitScope == AutomationWaitScope.HOLD_CURRENT_WORK
                    ) {
                        earliestWaitScope = evaluation.waitScope
                    }
                    val detail = entry.waitingTrace(evaluation)
                    trace += AutomationEvaluationTrace(
                        sequence, entry.id, entry.type, AutomationDecisionOutcome.WAITING,
                        evaluation.reasonCode, detail?.message ?: evaluation.message, evaluation.nextRunAt,
                        detail?.actionKind, detail?.targetKey, detail?.targetName, detail?.presetId,
                    )
                }
                is HandlerEvaluation.WorkTransition -> {
                    val outcome = when (evaluation.transition) {
                        AutomationWorkTransition.Complete -> AutomationDecisionOutcome.CYCLE_COMPLETED
                        is AutomationWorkTransition.WaitForConfiguration ->
                            AutomationDecisionOutcome.CONFIGURATION_WARNING
                        is AutomationWorkTransition.WaitForResource,
                        AutomationWorkTransition.WaitForUnknownCooldown,
                        -> AutomationDecisionOutcome.WAITING
                    }
                    if (evaluation.transition is AutomationWorkTransition.WaitForConfiguration) {
                        warnings += evaluation.message
                    }
                    trace += AutomationEvaluationTrace(
                        sequence,
                        entry.id,
                        entry.type,
                        outcome,
                        evaluation.reasonCode,
                        evaluation.message,
                        actionKind = when (evaluation.transition) {
                            AutomationWorkTransition.Complete -> "COMPLETE"
                            else -> "WAIT"
                        },
                    )
                    return AutomationCoordination.Idle(
                        warnings.toList(),
                        trace.toList(),
                        evaluation.transition,
                    )
                }
                HandlerEvaluation.Skipped -> {
                    trace += AutomationEvaluationTrace(sequence, entry.id, entry.type, AutomationDecisionOutcome.SKIPPED, HandlerEvaluation.Skipped.reasonCode, HandlerEvaluation.Skipped.message)
                }
            }
        }
        return earliest?.let { AutomationCoordination.Unavailable(it, warnings, trace.toList(), earliestWaitScope) }
            ?: AutomationCoordination.Idle(warnings, trace.toList())
    }
}

private fun QuestDirective.toHandlerEvaluation(): HandlerEvaluation = when (this) {
    is QuestDirective.Execute -> HandlerEvaluation.Runnable(action)
    is QuestDirective.WaitUntil -> HandlerEvaluation.Unavailable(nextRunAt, reasonCode, message)
    is QuestDirective.Recheck -> HandlerEvaluation.Unavailable(
        at,
        reasonCode,
        message,
        AutomationWaitScope.HOLD_CURRENT_WORK,
    )
    is QuestDirective.WaitForResource -> HandlerEvaluation.WorkTransition(
        AutomationWorkTransition.WaitForResource(resourceName, missingCount),
        "QUEST_RESOURCE_WAIT",
        "퀘스트 완료에 필요한 재료를 기다립니다.",
    )
    QuestDirective.WaitForUnknownCooldown -> HandlerEvaluation.WorkTransition(
        AutomationWorkTransition.WaitForUnknownCooldown,
        "QUEST_COOLDOWN_UNKNOWN",
        "반복 퀘스트의 다음 시작 가능 상태를 기다립니다.",
    )
    is QuestDirective.WaitForConfiguration -> HandlerEvaluation.WorkTransition(
        AutomationWorkTransition.WaitForConfiguration(message),
        reasonCode,
        message,
    )
    QuestDirective.CompleteWork -> HandlerEvaluation.WorkTransition(
        AutomationWorkTransition.Complete,
        "QUEST_WORK_COMPLETE",
        "현재 퀘스트 작업 사이클을 완료했습니다.",
    )
    is QuestDirective.Hold -> HandlerEvaluation.ConfigurationWarning(message, reasonCode)
    is QuestDirective.Fatal -> HandlerEvaluation.Fatal(reason, message)
    QuestDirective.Skip -> HandlerEvaluation.Skipped
}

private fun AutomationCoordinatorEntry.waitingTrace(evaluation: HandlerEvaluation.Unavailable): WaitingTraceDetail? {
    fishing?.let { snapshot ->
        val observations = listOfNotNull(
            snapshot.state.primaryAction.name.let { "현재 동작 $it" },
            snapshot.state.remainingCasts?.let { "남은 낚시 ${it}회" },
            snapshot.state.escapeSeconds?.let { "도망까지 ${it}초" },
            snapshot.state.lastOutcome?.name?.let { "직전 결과 $it" },
        ).joinToString(" · ")
        return WaitingTraceDetail(
            actionKind = "WAIT",
            message = "${evaluation.message}${if (observations.isBlank()) "" else " · $observations"}",
        )
    }
    union?.let { snapshot ->
        val target = snapshot.settings.sortedBy(UnionAutomationSetting::executionOrder).firstOrNull()
        return WaitingTraceDetail(
            actionKind = "WAIT",
            message = "${evaluation.message} · 설정 맵 ${snapshot.settings.size}개 · 현재 순환 기준 ${snapshot.currentTargetKey ?: "첫 대상"}",
            targetKey = target?.let { "${it.categoryId}/${it.mapCode}" },
            presetId = target?.presetId,
        )
    }
    return null
}

private data class WaitingTraceDetail(
    val actionKind: String,
    val message: String,
    val targetKey: String? = null,
    val targetName: String? = null,
    val presetId: Long? = null,
)
