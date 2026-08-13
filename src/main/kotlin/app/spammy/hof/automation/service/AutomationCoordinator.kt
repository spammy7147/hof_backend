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
    val homeQuest: HomeQuestAutomationSnapshot? = null,
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
    private val homeQuest: AutomationHandler<HomeQuestAutomationSnapshot>? = null,
) {
    fun coordinate(snapshot: AutomationCoordinatorSnapshot): AutomationCoordination {
        val warnings = mutableListOf<String>()
        val trace = mutableListOf<AutomationEvaluationTrace>()
        var earliest: Instant? = null
        snapshot.entries.forEach { entry ->
            val evaluation = when (entry.type) {
                AutomationType.QUEST -> entry.quest?.let(quest::evaluate)
                AutomationType.HOME_QUEST -> entry.homeQuest?.let { homeQuest?.evaluate(it) }
                AutomationType.BATTLE_MAP -> entry.battle?.let(battle::evaluate)
                AutomationType.ADVENTURE_MAP -> entry.adventure?.let(adventure::evaluate)
                AutomationType.RAID -> entry.raid?.let { raid?.evaluate(it) }
                AutomationType.UNION -> entry.union?.let { union?.evaluate(it) }
                AutomationType.FISHING -> entry.fishing?.let { fishing?.evaluate(it) }
            } ?: HandlerEvaluation.ConfigurationWarning("${entry.type} automation snapshot is missing.")
            val sequence = trace.size
            when (evaluation) {
                is HandlerEvaluation.Runnable -> {
                    val detail = evaluation.action.selectionTrace()
                    trace += AutomationEvaluationTrace(
                        sequence, entry.id, entry.type, AutomationDecisionOutcome.SELECTED, "RUNNABLE",
                        detail.message, actionKind = detail.actionKind, targetKey = detail.targetKey,
                        targetName = detail.targetName, presetId = detail.presetId,
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

private data class SelectedActionTrace(
    val actionKind: String,
    val message: String,
    val targetKey: String? = null,
    val targetName: String? = null,
    val presetId: Long? = null,
)

private fun PreparedAutomationAction.selectionTrace(): SelectedActionTrace = when (this) {
    is QuestAction.Claim -> SelectedActionTrace(
        "QUEST_CLAIM", "완료되어 보상을 받을 수 있는 퀘스트를 선택했습니다.", questKey, questName,
    )
    is QuestAction.Accept -> SelectedActionTrace(
        "QUEST_ACCEPT", "수락 가능하고 자동화가 활성화된 퀘스트를 선택했습니다.", questKey, questName,
    )
    is HomeQuestAutomationAction -> SelectedActionTrace(
        "HOME_QUEST",
        if (action == HomeQuestAutomationActionType.ACCEPT) "수락 가능한 자택 퀘스트를 선택했습니다." else "완료 보상을 받을 수 있는 자택 퀘스트를 선택했습니다.",
        questId,
        questName,
    )
    is QuestAction.Battle -> SelectedActionTrace(
        "BATTLE",
        "퀘스트의 미완료 전투 조건을 확인해 이 맵을 선택했습니다. 진행 $missionCurrent/$missionRequired · ${battleCount}회 전투",
        "$categoryId/$mapCode", mapName, preset.resolvedPresetId ?: preset.presetId,
    )
    is BattleMapAutomationAction -> {
        val reason = when (source) {
            BattleAutomationActionSource.BATTLE_MAP_AUTOMATION -> "우선순위대로 확인한 결과 맵이 노출되어 있고, 일일 목표 잔여량·쿨다운·행동력 조건을 통과했습니다."
            BattleAutomationActionSource.UNION_AUTOMATION -> "순환 차례의 유니온 맵이 노출되어 있고 공유 쿨다운이 끝나 전투할 수 있습니다."
            BattleAutomationActionSource.FISHING_AUTOMATION -> "낚시가 이 전투 맵에 막혀 있어 맵별 프리셋(미설정 시 대표 프리셋)으로 처리합니다."
            BattleAutomationActionSource.RAID_AUTOMATION -> "진행 중인 레이드 맵이 노출되어 있고 약 2분 전투 쿨다운이 끝났습니다."
            BattleAutomationActionSource.QUEST_AUTOMATION -> "퀘스트 전투 조건을 충족하는 맵입니다."
            BattleAutomationActionSource.ADVENTURE_AUTOMATION -> "모험 맵 실행 조건을 충족했습니다."
        }
        SelectedActionTrace("BATTLE", "$reason · ${battleCount}회 전투", "$categoryId/$mapCode", mapName, presetId)
    }
    is AdventureMapAutomationAction -> {
        val observations = listOfNotNull(
            observedAttemptRemaining?.let { "남은 도전 ${it}회" },
            observedWinRemaining?.let { "남은 승리 ${it}회" },
            observedAvailableCount?.let { "실행 가능 ${it}회" },
            observedCooldownUntil?.let { "쿨다운 종료 $it" } ?: "쿨다운 없음",
        ).joinToString(" · ")
        SelectedActionTrace(
            "BATTLE", "설정 우선순위대로 확인해 노출·횟수·쿨다운·행동력 조건을 통과했습니다.${if (observations.isBlank()) "" else " · $observations"}",
            "$categoryId/$mapCode", mapName, presetId,
        )
    }
    is FishingTownAutomationAction -> SelectedActionTrace(
        action.name, "낚시 화면의 다음 동작이 ${action.name}입니다.${observedRemainingCasts?.let { " · 남은 낚시 ${it}회" } ?: ""}",
    )
    is RaidTownAutomationAction -> SelectedActionTrace(
        action.name, when (action) {
            app.spammy.hof.town.raid.model.RaidAction.REGISTER -> "공유 쿨다운이 끝나 순환 차례의 레이드에 파티 등록을 시작합니다."
            app.spammy.hof.town.raid.model.RaidAction.START -> "파티 모집 대기가 끝나 전투 시작이 활성화되었습니다."
            app.spammy.hof.town.raid.model.RaidAction.REWARD -> "레이드 완료를 확인했고 30분 보상 확인 시간 안에 보상을 수령합니다."
            app.spammy.hof.town.raid.model.RaidAction.RESET -> "보상 수령을 확인해 다음 사이클을 위한 레이드 초기화를 진행합니다."
            else -> "레이드 상태에서 실행 가능한 ${action.name} 동작을 선택했습니다."
        }, raidId,
    )
    is RaidCycleAbortAutomationAction -> SelectedActionTrace(
        "CYCLE_ABORT", "레이드가 CLOSED 상태여서 현재 사이클을 중단으로 기록합니다.", raidId,
    )
}
