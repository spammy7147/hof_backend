package app.spammy.hof.automation.policy

import app.spammy.hof.quest.model.QuestSnapshot
import app.spammy.hof.quest.model.QuestState
import org.springframework.stereotype.Component

@Component
class SelectedQuestPolicy {
    fun decide(
        quests: List<QuestSnapshot>,
        selectedQuestIds: Collection<String>,
    ): QuestDecision? {
        val selected = selectedQuestIds.mapNotNull { id -> quests.firstOrNull { it.questId == id } }
        selected.firstOrNull { it.state == QuestState.CLAIMABLE }
            ?.let { return QuestDecision.Claim(it.questId, it.actionNo) }
        selected.firstOrNull { it.state == QuestState.AVAILABLE }
            ?.let { return QuestDecision.Accept(it.questId, it.actionNo) }
        selected.firstOrNull { it.state == QuestState.ACTIVE }
            ?.let { return QuestDecision.WaitingConfig(it.questId) }
        return null
    }
}
