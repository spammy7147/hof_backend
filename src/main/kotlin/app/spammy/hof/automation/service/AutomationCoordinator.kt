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
                    val detail = entry.waitingTrace(evaluation)
                    trace += AutomationEvaluationTrace(
                        sequence, entry.id, entry.type, AutomationDecisionOutcome.WAITING,
                        evaluation.reasonCode, detail?.message ?: evaluation.message, evaluation.nextRunAt,
                        detail?.actionKind, detail?.targetKey, detail?.targetName, detail?.presetId,
                    )
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

private fun AutomationCoordinatorEntry.waitingTrace(evaluation: HandlerEvaluation.Unavailable): SelectedActionTrace? {
    fishing?.let { snapshot ->
        val observations = listOfNotNull(
            snapshot.state.primaryAction.name.let { "현재 동작 $it" },
            snapshot.state.remainingCasts?.let { "남은 낚시 ${it}회" },
            snapshot.state.escapeSeconds?.let { "도망까지 ${it}초" },
            snapshot.state.lastOutcome?.name?.let { "직전 결과 $it" },
        ).joinToString(" · ")
        return SelectedActionTrace(
            actionKind = "WAIT",
            message = "${evaluation.message}${if (observations.isBlank()) "" else " · $observations"}",
        )
    }
    union?.let { snapshot ->
        val target = snapshot.settings.sortedBy(UnionAutomationSetting::executionOrder).firstOrNull()
        return SelectedActionTrace(
            actionKind = "WAIT",
            message = "${evaluation.message} · 설정 맵 ${snapshot.settings.size}개 · 현재 순환 기준 ${snapshot.currentTargetKey ?: "첫 대상"}",
            targetKey = target?.let { "${it.categoryId}/${it.mapCode}" },
            presetId = target?.presetId,
        )
    }
    val snapshot = raid ?: return null
    val cycle = snapshot.openCycle
    val targetId = cycle?.raidId ?: snapshot.currentTargetKey
    val raid = snapshot.pub.raids.singleOrNull { it.id == targetId }
    val configured = snapshot.targets.singleOrNull { it.raidId == targetId }
    val phase = when (evaluation.reasonCode) {
        "RAID_WAITING_TO_START" -> "출발 대기"
        "RAID_BATTLE_COOLDOWN" -> "반복 전투 쿨다운"
        "RAID_BATTLE_TARGET_MISSING" -> "전투 맵 확인"
        "RAID_RESET_PENDING" -> "리셋 가능 상태 확인"
        "RAID_REWARD_CONFIRMATION_WAIT" -> "보상 확인 종료 대기"
        "RAID_NEXT_CHECK" -> if (cycle?.status == app.spammy.hof.automation.entity.RaidAutomationCycleStatus.REWARD_PENDING) "보상 후 상태 갱신 대기" else "다음 상태 확인 대기"
        "RAID_SHARED_COOLDOWN" -> "공유 쿨다운"
        else -> "레이드 상태 확인"
    }
    val observations = listOfNotNull(
        raid?.status?.name?.let { "상태 $it" },
        raid?.statusText?.let { "HOF 표시 '$it'" },
        raid?.waitSeconds?.let { "남은 대기 ${it}초" },
    ).joinToString(" · ")
    return SelectedActionTrace(
        actionKind = "WAIT",
        message = "$phase 단계 · ${evaluation.message}${if (observations.isBlank()) "" else " · $observations"}",
        targetKey = targetId,
        targetName = raid?.name ?: configured?.name,
        presetId = configured?.presetId,
    )
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
        action.name,
        when (action) {
            app.spammy.hof.town.fishing.model.FishingAction.START -> "낚시 1회 사이클의 시작 단계입니다. 시작 다음에는 반드시 잡기(CATCH)를 실행합니다."
            app.spammy.hof.town.fishing.model.FishingAction.CATCH -> "낚시 1회 사이클의 잡기 단계입니다. 잡기 응답 이후 물고기 획득 또는 전투 발생 결과를 확인합니다."
            else -> "낚시 화면의 다음 동작이 ${action.name}입니다."
        } + (observedRemainingCasts?.let { " · 실행 전 남은 낚시 ${it}회" } ?: ""),
    )
    is RaidTownAutomationAction -> SelectedActionTrace(
        action.name, when (action) {
            app.spammy.hof.town.raid.model.RaidAction.REGISTER -> "공유 쿨다운이 끝나 순환 차례의 레이드에 파티 등록을 시작합니다."
            app.spammy.hof.town.raid.model.RaidAction.START -> "파티 모집 대기가 끝나 전투 시작이 활성화되었습니다."
            app.spammy.hof.town.raid.model.RaidAction.REWARD -> "레이드 완료를 확인했고 30분 보상 확인 시간 안에 보상을 수령합니다."
            app.spammy.hof.town.raid.model.RaidAction.REFRESH -> "보상 수령 후 진행 중인 레이드의 리셋 가능 상태를 새로 확인합니다."
            app.spammy.hof.town.raid.model.RaidAction.RESET -> "진행 중인 레이드가 '보상 확인 종료(리셋 가능)' 상태여서 다음 사이클을 위한 초기화를 진행합니다."
            else -> "레이드 상태에서 실행 가능한 ${action.name} 동작을 선택했습니다."
        }, targetRaidId ?: raidId, raidName,
    )
    is RaidCycleAbortAutomationAction -> SelectedActionTrace(
        "CYCLE_ABORT",
        when (reason) {
            RaidCycleAbortReason.CLOSED -> "레이드가 CLOSED 상태여서 현재 사이클을 중단으로 기록합니다."
            RaidCycleAbortReason.REGISTRATION_LOST -> "저장된 등록 상태와 달리 현재 신청되지 않아 기존 사이클을 정리하고 다시 등록합니다."
        },
        raidId,
    )
}
