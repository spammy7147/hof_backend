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
    override fun evaluate(context: HomeQuestAutomationSnapshot): HandlerEvaluation {
        val liveById = context.quests.associateBy { it.id }
        val enabled = context.selections.filter { it.enabled }.sortedBy { it.sourceOrder }
        if (enabled.isEmpty()) return HandlerEvaluation.ConfigurationWarning("활성화된 자택 퀘스트가 없습니다.")

        evaluateState(context, enabled, liveById, HomeQuestState.AVAILABLE, HomeQuestAutomationActionType.ACCEPT)?.let {
            return it
        }
        evaluateState(context, enabled, liveById, HomeQuestState.CLAIMABLE, HomeQuestAutomationActionType.CLAIM)?.let {
            return it
        }
        return HandlerEvaluation.Skipped
    }

    private fun evaluateState(
        context: HomeQuestAutomationSnapshot,
        selections: List<HomeQuestAutomationSelection>,
        liveById: Map<String, HomeQuestResponse>,
        state: HomeQuestState,
        action: HomeQuestAutomationActionType,
    ): HandlerEvaluation? {
        val selection = selections.firstOrNull { liveById[it.questId]?.state == state } ?: return null
        val quest = requireNotNull(liveById[selection.questId])
        val actionId = quest.actionId
        if (actionId == null) {
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
        return HandlerEvaluation.Runnable(
            HomeQuestAutomationAction(context.accountId, quest.id, quest.name, actionId, action),
        )
    }
}
