package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.AutomationWorkStatus
import app.spammy.hof.automation.entity.AutomationWorkType
import app.spammy.hof.automation.repository.AutomationWorkSessionQueryRepository
import app.spammy.hof.common.time.TimeProvider
import org.springframework.stereotype.Service

interface AutomationExecutionSignals {
    fun afterBattle(
        accountId: Long,
        source: BattleAutomationActionSource,
        outcomes: List<BattleAutomationRoundOutcome>,
        lootNames: List<String>,
        questTexts: List<String>,
    ): Boolean
}

@Service
class AutomationExecutionSignalService(
    private val queries: AutomationWorkSessionQueryRepository,
    private val lifecycle: AutomationWorkLifecycle,
    private val lootSignals: AutomationLootSignalService,
    private val timeProvider: TimeProvider,
) : AutomationExecutionSignals {
    override fun afterBattle(
        accountId: Long,
        source: BattleAutomationActionSource,
        outcomes: List<BattleAutomationRoundOutcome>,
        lootNames: List<String>,
        questTexts: List<String>,
    ): Boolean {
        val running = queries.findRunning(accountId)
            ?.takeIf { it.status == AutomationWorkStatus.RUNNING }
            ?: return false
        if (source == BattleAutomationActionSource.QUEST_AUTOMATION) {
            if (
                running.workType == AutomationWorkType.QUEST &&
                running.missionType == app.spammy.hof.quest.model.QuestMissionType.MAP_CLEAR.name
            ) {
                lifecycle.recordQuestVictories(
                    accountId,
                    running.id,
                    outcomes.count { it == BattleAutomationRoundOutcome.VICTORY },
                )
            }
            return false
        }
        if (source != BattleAutomationActionSource.BATTLE_MAP_AUTOMATION || running.workType != AutomationWorkType.BATTLE_MAP) {
            return false
        }
        val now = timeProvider.now()
        val higherWaits = queries.findWaiting(accountId).filter { it.entry.priority < running.entry.priority }
        val relatedMaterialWaits = higherWaits
            .asSequence()
            .filter { it.status == AutomationWorkStatus.WAITING_RESOURCE }
            .filter { waiting ->
                waiting.materialName?.let { required ->
                    lootNames.any { loot -> lootSignals.matchesLootDisplay(required, loot) }
                } == true
            }
            .toList()
        val relatedMaterial = relatedMaterialWaits.isNotEmpty()
        val higherDue = higherWaits.any { it.nextCheckAt?.let { due -> !due.isAfter(now) } == true }
        val questChanged = running.entry.priority > 0 && questTexts.any(String::isNotBlank)
        if (!relatedMaterial && !higherDue && !questChanged) return false
        relatedMaterialWaits.forEach { lifecycle.triggerCheck(accountId, it.id) }
        return lifecycle.yieldForPriority(accountId, running.id)
    }
}
