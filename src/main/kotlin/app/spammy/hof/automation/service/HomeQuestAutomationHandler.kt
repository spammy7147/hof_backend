package app.spammy.hof.automation.service

import app.spammy.hof.town.home.dto.HomeQuestResponse
import app.spammy.hof.town.home.model.HomeQuestState
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

        findRunnable(context.accountId, enabled, liveById, HomeQuestState.AVAILABLE, HomeQuestAutomationActionType.ACCEPT)?.let {
            return HandlerEvaluation.Runnable(it)
        }
        findRunnable(context.accountId, enabled, liveById, HomeQuestState.CLAIMABLE, HomeQuestAutomationActionType.CLAIM)?.let {
            return HandlerEvaluation.Runnable(it)
        }
        return HandlerEvaluation.Skipped
    }

    private fun findRunnable(
        accountId: Long,
        selections: List<HomeQuestAutomationSelection>,
        liveById: Map<String, HomeQuestResponse>,
        state: HomeQuestState,
        action: HomeQuestAutomationActionType,
    ): HomeQuestAutomationAction? = selections.firstNotNullOfOrNull { selection ->
        val quest = liveById[selection.questId]?.takeIf { it.state == state } ?: return@firstNotNullOfOrNull null
        val actionId = quest.actionId ?: return@firstNotNullOfOrNull null
        HomeQuestAutomationAction(accountId, quest.id, quest.name, actionId, action)
    }
}
