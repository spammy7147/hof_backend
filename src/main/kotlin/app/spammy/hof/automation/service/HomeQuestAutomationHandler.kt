package app.spammy.hof.automation.service

import app.spammy.hof.automation.convergence.AutomationActionKind
import app.spammy.hof.automation.convergence.AutomationIsolationScopeKind
import app.spammy.hof.town.home.dto.HomeQuestResponse
import app.spammy.hof.town.home.model.HomeQuestState
import java.time.Instant
import org.springframework.stereotype.Service

data class HomeQuestAutomationSelection(
    val questId: String,
    val questName: String,
    val enabled: Boolean,
    val sourceOrder: Int,
)

data class HomeQuestAutomationSnapshot(
    val accountId: Long,
    val quests: List<HomeQuestResponse>,
    val selections: List<HomeQuestAutomationSelection>,
    val now: Instant,
    val timeSnapshot: AutomationTimeSnapshot? = null,
    val workSessionId: Long? = null,
    val workSessionRevision: Long? = null,
)

enum class HomeQuestAutomationActionType { ACCEPT, CLAIM }

data class HomeQuestAutomationAction(
    val accountId: Long,
    val questId: String,
    val questName: String,
    val actionId: String,
    val action: HomeQuestAutomationActionType,
) : PreparedAutomationAction

@Service
class HomeQuestAutomationHandler : AutomationHandler<HomeQuestAutomationSnapshot> {
    override fun evaluate(context: HomeQuestAutomationSnapshot): HandlerEvaluation = evaluate(context) { true }

    override fun evaluate(
        context: HomeQuestAutomationSnapshot,
        accepts: (PreparedAutomationAction) -> Boolean,
    ): HandlerEvaluation {
        val liveById = context.quests.associateBy { it.id }
        val enabled = context.selections.filter { it.enabled }.sortedBy { it.sourceOrder }
        if (enabled.isEmpty()) return HandlerEvaluation.ConfigurationWarning("활성화된 자택 퀘스트가 없습니다.")

        val claim = evaluateState(
            context,
            enabled,
            liveById,
            HomeQuestState.CLAIMABLE,
            HomeQuestAutomationActionType.CLAIM,
            accepts,
        )
        claim.runnable?.let { return it }
        val accept = evaluateState(
            context,
            enabled,
            liveById,
            HomeQuestState.AVAILABLE,
            HomeQuestAutomationActionType.ACCEPT,
            accepts,
        )
        accept.runnable?.let { return it }
        claim.gap?.let { return it }
        accept.gap?.let { return it }
        if (context.workSessionId != null) {
            val configuredIds = enabled.map(HomeQuestAutomationSelection::questId).toSet()
            val target = context.quests.firstOrNull { it.id in configuredIds }
            return if (
                target == null ||
                target.state in setOf(HomeQuestState.WAITING, HomeQuestState.COMPLETED)
            ) {
                HandlerEvaluation.WorkTransition(
                    AutomationWorkTransition.Complete,
                    "HOME_QUEST_CYCLE_COMPLETE",
                    "현재 자택 퀘스트 수락·완료 사이클을 마쳤습니다.",
                )
            } else {
                HandlerEvaluation.WorkTransition(
                    AutomationWorkTransition.WaitForUnknownCooldown,
                    "HOME_QUEST_CYCLE_OPEN",
                    "현재 자택 퀘스트가 완료 가능해질 때까지 열린 사이클을 보존합니다.",
                )
            }
        }
        return HandlerEvaluation.Skipped
    }

    private fun evaluateState(
        context: HomeQuestAutomationSnapshot,
        selections: List<HomeQuestAutomationSelection>,
        liveById: Map<String, HomeQuestResponse>,
        state: HomeQuestState,
        action: HomeQuestAutomationActionType,
        accepts: (PreparedAutomationAction) -> Boolean,
    ): StateEvaluation {
        var gap: HandlerEvaluation.ObservationGap? = null
        selections.forEach { selection ->
            val quest = liveById[selection.questId]
            if (quest?.state != state || !(
                action != HomeQuestAutomationActionType.CLAIM ||
                    TimeRewardClaimPolicy.canReceive(listOf(quest.reward), context.timeSnapshot, context.now)
            )) return@forEach
            val actionId = quest.actionId
            if (actionId == null) {
                if (gap == null) gap = observationGap(context, quest, action)
                return@forEach
            }
            val candidate = HomeQuestAutomationAction(context.accountId, quest.id, quest.name, actionId, action)
            if (accepts(candidate)) return StateEvaluation(runnable = HandlerEvaluation.Runnable(candidate))
        }
        return StateEvaluation(gap = gap)
    }

    private fun observationGap(
        context: HomeQuestAutomationSnapshot,
        quest: HomeQuestResponse,
        action: HomeQuestAutomationActionType,
    ): HandlerEvaluation.ObservationGap {
        val actionKind = if (action == HomeQuestAutomationActionType.ACCEPT) {
            AutomationActionKind.HOME_ACCEPT
        } else {
            AutomationActionKind.HOME_CLAIM
        }
        return HandlerEvaluation.ObservationGap(
            actionKind = actionKind,
            scopeKind = AutomationIsolationScopeKind.HOME_TARGET,
            scopeKey = quest.id,
            baseline = "home|$action|${quest.id}|${quest.state}|action-id-missing",
            nextRunAt = context.now.plusSeconds(10),
            reasonCode = "HOME_ACTION_ID_MISSING",
            message = "${quest.name}의 실행 식별자를 읽지 못해 최신 상태를 다시 확인합니다.",
            authoritative = true,
        )
    }

    private data class StateEvaluation(
        val runnable: HandlerEvaluation.Runnable? = null,
        val gap: HandlerEvaluation.ObservationGap? = null,
    )
}
