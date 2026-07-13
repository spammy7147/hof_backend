package app.spammy.hof.automation.policy

import app.spammy.hof.automation.entity.AutomationModuleType
import app.spammy.hof.quest.model.QuestSnapshot
import java.time.Instant
import org.springframework.stereotype.Component

enum class AutomationDecisionType {
    CLAIM_QUEST,
    ACCEPT_QUEST,
    RUN_BATTLE,
    WAITING_CONFIG,
    SLEEP,
}

data class AutomationMapCandidate(
    val mapCode: String,
    val mapName: String,
    val executionOrder: Int,
    val partyPresetId: Long? = null,
)

data class UnionCandidate(
    val targetId: String,
    val hp: Long,
)

data class AutomationSnapshot(
    val claimableQuest: QuestSnapshot?,
    val acceptablePriorityQuest: QuestSnapshot?,
    val priorityQuestDecision: QuestDecision?,
    val timeCurrent: Int,
    val timeMax: Int,
    val timeThresholdPercent: Int,
    val timeMap: AutomationMapCandidate?,
    val unionTarget: UnionCandidate?,
    val readyCooldownMap: AutomationMapCandidate?,
    val readyDailyMap: AutomationMapCandidate?,
    val normalQuestDecision: QuestDecision?,
    val earliestNextRunAt: Instant?,
)

data class AutomationDecision(
    val type: AutomationDecisionType,
    val moduleType: AutomationModuleType?,
    val questId: String? = null,
    val map: AutomationMapCandidate? = null,
    val keyQuestBattle: QuestDecision.Battle? = null,
    val unionTarget: UnionCandidate? = null,
    val message: String? = null,
    val nextRunAt: Instant? = null,
)

@Component
class AutomationDecisionPolicy {
    fun decide(snapshot: AutomationSnapshot): AutomationDecision {
        snapshot.claimableQuest?.let { quest ->
            return AutomationDecision(
                AutomationDecisionType.CLAIM_QUEST,
                moduleForQuest(quest.questId),
                questId = quest.questId,
            )
        }
        snapshot.acceptablePriorityQuest?.let { quest ->
            return AutomationDecision(AutomationDecisionType.ACCEPT_QUEST, AutomationModuleType.KEY_QUEST, questId = quest.questId)
        }
        snapshot.priorityQuestDecision?.let { return it.toAutomationDecision(AutomationModuleType.KEY_QUEST) }
        if (isTimeOverflow(snapshot) && snapshot.timeMap != null) {
            return AutomationDecision(AutomationDecisionType.RUN_BATTLE, AutomationModuleType.TIME_BURN, map = snapshot.timeMap)
        }
        snapshot.unionTarget?.takeIf { it.hp > 0 }?.let { target ->
            return AutomationDecision(
                AutomationDecisionType.RUN_BATTLE,
                AutomationModuleType.UNION,
                unionTarget = target,
            )
        }
        snapshot.readyCooldownMap?.let { map ->
            return AutomationDecision(AutomationDecisionType.RUN_BATTLE, AutomationModuleType.COOLDOWN_ADVENTURE, map = map)
        }
        snapshot.readyDailyMap?.let { map ->
            return AutomationDecision(AutomationDecisionType.RUN_BATTLE, AutomationModuleType.DAILY_ADVENTURE, map = map)
        }
        snapshot.normalQuestDecision?.let { return it.toAutomationDecision(AutomationModuleType.OTHER_QUEST) }
        return AutomationDecision(
            type = AutomationDecisionType.SLEEP,
            moduleType = null,
            nextRunAt = snapshot.earliestNextRunAt,
        )
    }

    private fun isTimeOverflow(snapshot: AutomationSnapshot): Boolean =
        snapshot.timeMax > 0 && snapshot.timeCurrent.toLong() * 100 > snapshot.timeMax.toLong() * snapshot.timeThresholdPercent

    private fun QuestDecision.toAutomationDecision(module: AutomationModuleType): AutomationDecision = when (this) {
        is QuestDecision.Accept -> AutomationDecision(AutomationDecisionType.ACCEPT_QUEST, module, questId = questId)
        is QuestDecision.Claim -> AutomationDecision(AutomationDecisionType.CLAIM_QUEST, module, questId = questId)
        is QuestDecision.Battle -> AutomationDecision(
            AutomationDecisionType.RUN_BATTLE,
            module,
            questId = questId,
            map = AutomationMapCandidate(map.mapCode, map.mapName, map.executionOrder, map.partyPresetId),
            keyQuestBattle = this,
        )
        is QuestDecision.WaitingConfig -> AutomationDecision(
            AutomationDecisionType.WAITING_CONFIG,
            module,
            questId = questId,
            message = message,
        )
    }

    private fun moduleForQuest(questId: String): AutomationModuleType =
        if (questId in KeyQuestDefaultCatalog.priorityQuestIds) AutomationModuleType.KEY_QUEST else AutomationModuleType.OTHER_QUEST
}
