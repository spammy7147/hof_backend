package app.spammy.hof.automation.policy

import app.spammy.hof.quest.model.QuestSnapshot
import app.spammy.hof.quest.model.QuestState
import org.springframework.stereotype.Component

data class QuestExecutionConfig(
    val maps: List<KeyQuestMapCandidate>,
)

sealed interface QuestDecision {
    val questId: String

    data class Claim(override val questId: String, val actionNo: String? = null) : QuestDecision
    data class Accept(override val questId: String, val actionNo: String? = null) : QuestDecision
    data class Battle(
        override val questId: String,
        val map: KeyQuestMapCandidate,
        val blueprint: PartyBlueprint?,
    ) : QuestDecision
    data class WaitingConfig(
        override val questId: String,
        val message: String = "전투에 사용할 파티를 선택해 주세요.",
    ) : QuestDecision
}

@Component
class KeyQuestPolicy(
    private val eastMansionMapPolicy: EastMansionMapPolicy,
) {
    fun decide(
        quests: List<QuestSnapshot>,
        configs: Map<String, QuestExecutionConfig>,
    ): QuestDecision? {
        val orderedIds = configs.keys.ifEmpty { KeyQuestDefaultCatalog.priorityQuestIds }
        val ordered = orderedIds.mapNotNull { id -> quests.firstOrNull { it.questId == id } }
        ordered.firstOrNull { it.state == QuestState.CLAIMABLE }
            ?.let { return QuestDecision.Claim(it.questId, it.actionNo) }
        ordered.firstOrNull { it.state == QuestState.AVAILABLE }
            ?.let { return QuestDecision.Accept(it.questId, it.actionNo) }
        val active = ordered.firstOrNull { it.state == QuestState.ACTIVE } ?: return null
        return battleDecision(active.questId, configs[active.questId])
    }

    private fun battleDecision(
        questId: String,
        configured: QuestExecutionConfig?,
    ): QuestDecision {
        val default = KeyQuestDefaultCatalog.defaults[questId]
        val candidates = configured?.maps.orEmpty().ifEmpty {
            if (questId == "0571" && default != null) {
                default.candidates.mapIndexed { order, code ->
                    KeyQuestMapCandidate(code, default.mapNames.getValue(code), null, order)
                }
            } else {
                return QuestDecision.WaitingConfig(questId)
            }
        }
        val selected = if (questId == "0563") eastMansionMapPolicy.select(candidates) else candidates.minBy { it.executionOrder }
        val blueprint = default?.partyBlueprintByMap?.get(selected.mapCode) ?: default?.partyBlueprint
        if (selected.partyPresetId == null && blueprint == null) return QuestDecision.WaitingConfig(questId)
        return QuestDecision.Battle(questId, selected, blueprint)
    }
}
