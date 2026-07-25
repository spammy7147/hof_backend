package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.repository.AutomationWorkSessionQueryRepository
import app.spammy.hof.automation.repository.AutomationWorkSessionView
import app.spammy.hof.automation.repository.TypedAutomationQueryRepository
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.quest.model.QuestMission
import app.spammy.hof.quest.model.QuestMissionType
import app.spammy.hof.quest.model.QuestProgress
import app.spammy.hof.quest.model.QuestSection
import app.spammy.hof.quest.model.QuestSnapshot
import app.spammy.hof.quest.model.QuestState
import java.time.Instant
import org.springframework.stereotype.Service

fun interface AutomationDecisionSource {
    fun select(accountId: Long): AutomationCoordination
}

@Service
class AutomationTargetSelector(
    private val typed: TypedAutomationQueryRepository,
    private val work: AutomationWorkSessionQueryRepository,
    private val loader: TypedAutomationSnapshotLoader,
    private val coordinator: AutomationCoordinator,
    private val lifecycle: AutomationWorkLifecycle,
    private val lootSignals: AutomationLootSignalService,
    private val timeProvider: TimeProvider,
) : AutomationDecisionSource {
    override fun select(accountId: Long): AutomationCoordination {
        work.findRunning(accountId)?.let { return selectSession(accountId, it) }
        return selectConfigured(accountId)
    }

    private fun selectSession(
        accountId: Long,
        session: AutomationWorkSessionView,
    ): AutomationCoordination {
        val optimisticQuest = session.optimisticMapClearQuest()
        val entry = loader.loadEntry(accountId, session.entryId, session.targetKey, optimisticQuest)
        return when (val result = coordinate(entry)) {
                is AutomationCoordination.Runnable -> {
                    val battle = result.action as? QuestAction.Battle
                    if (
                        optimisticQuest == null &&
                        session.missionType == QuestMissionType.MAP_CLEAR.name &&
                        battle?.missionType == QuestMissionType.MAP_CLEAR &&
                        battle.missionCurrent != null && battle.missionRequired != null
                    ) {
                        lifecycle.reconcileQuestProgress(
                            accountId,
                            session.id,
                            battle.missionCurrent,
                            battle.missionRequired,
                        )
                    }
                    result
                }
                is AutomationCoordination.Fatal -> result
                is AutomationCoordination.Unavailable -> {
                    lifecycle.waitForCooldown(accountId, session.id, result.nextRunAt)
                    selectConfigured(accountId, result.warnings)
                }
                is AutomationCoordination.Idle -> {
                    val selectedQuest = entry.quest?.quests
                        ?.singleOrNull { it.questId == session.targetKey }
                    val material = selectedQuest
                        ?.missions
                        ?.firstOrNull { it.type == QuestMissionType.ITEM_TURN_IN && !it.completable }
                    if (
                        selectedQuest?.state == QuestState.UNAVAILABLE ||
                        selectedQuest?.section == QuestSection.WAITING
                    ) {
                        lifecycle.waitForUnknownCooldown(accountId, session.id)
                    } else if (material?.target?.isNotBlank() == true) {
                        val missing = material.progress?.let { (it.required - it.current).coerceAtLeast(0) }
                        lifecycle.waitForResource(
                            accountId,
                            session.id,
                            lootSignals.normalize(requireNotNull(material.target)),
                            missing,
                        )
                    } else {
                        lifecycle.complete(accountId, session.id)
                    }
                    selectConfigured(accountId, result.warnings)
                }
            }
    }

    private fun selectConfigured(accountId: Long, initialWarnings: List<String> = emptyList()): AutomationCoordination {
        val warnings = initialWarnings.toMutableList()
        var earliest: Instant? = null
        val now = timeProvider.now()
        val waitsByEntry = work.findWaiting(accountId).groupBy { it.entryId }
        typed.findEntries(accountId)
            .asSequence()
            .filter { it.enabled }
            .forEach { entry ->
                val waiting = waitsByEntry[entry.id].orEmpty()
                val blockedUntil = waiting.mapNotNull { it.nextCheckAt }.minOrNull()
                val hasDueTarget = waiting.any {
                    it.status == app.spammy.hof.automation.entity.AutomationWorkStatus.YIELDED_PRIORITY ||
                        it.nextCheckAt?.isAfter(now) == false
                }
                if (waiting.isNotEmpty() && !hasDueTarget) {
                    if (blockedUntil != null && (earliest == null || blockedUntil < earliest)) earliest = blockedUntil
                    if (entry.type != AutomationType.QUEST) return@forEach
                }
                waiting.firstOrNull {
                    it.status == app.spammy.hof.automation.entity.AutomationWorkStatus.YIELDED_PRIORITY ||
                        it.nextCheckAt?.isAfter(now) == false
                }?.let { due ->
                    lifecycle.resumeForCheck(accountId, due.id)
                    return selectSession(accountId, due)
                }
                val snapshot = loader.loadEntry(accountId, entry.id).excludingWaitingQuests(
                    waiting.map(AutomationWorkSessionView::targetKey).toSet(),
                )
                when (val result = coordinate(snapshot)) {
                    is AutomationCoordination.Runnable -> return result.copy(warnings = warnings + result.warnings)
                    is AutomationCoordination.Fatal -> return result.copy(warnings = warnings + result.warnings)
                    is AutomationCoordination.Unavailable -> {
                        warnings += result.warnings
                        if (earliest == null || result.nextRunAt < earliest) earliest = result.nextRunAt
                    }
                    is AutomationCoordination.Idle -> warnings += result.warnings
                }
            }
        return earliest?.let { AutomationCoordination.Unavailable(it, warnings) }
            ?: AutomationCoordination.Idle(warnings)
    }

    private fun coordinate(entry: AutomationCoordinatorEntry): AutomationCoordination =
        coordinator.coordinate(AutomationCoordinatorSnapshot(listOf(entry)))

    private fun AutomationCoordinatorEntry.excludingWaitingQuests(
        targetKeys: Set<String>,
    ): AutomationCoordinatorEntry {
        if (type != AutomationType.QUEST || targetKeys.isEmpty()) return this
        return copy(
            quest = quest?.copy(
                selections = quest.selections.filterNot { it.questCode in targetKeys },
            ),
        )
    }

    private fun AutomationWorkSessionView.optimisticMapClearQuest(): List<QuestSnapshot>? {
        val current = observedCurrent ?: return null
        val required = observedRequired ?: return null
        val mapMissionKey = missionKey ?: return null
        if (missionType != QuestMissionType.MAP_CLEAR.name || current >= required) return null
        return listOf(
            QuestSnapshot(
                questId = targetKey,
                name = targetKey,
                state = QuestState.ACTIVE,
                section = QuestSection.ACTIVE,
                sourceOrder = 0,
                missions = listOf(
                    QuestMission(
                        key = mapMissionKey,
                        type = QuestMissionType.MAP_CLEAR,
                        target = null,
                        progress = QuestProgress(current, required),
                        completable = false,
                    ),
                ),
                actionNo = null,
            ),
        )
    }
}
