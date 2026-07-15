package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.automation.entity.QuestAutomationCycleEntity
import app.spammy.hof.automation.entity.QuestMapExecutionCounterEntity
import app.spammy.hof.automation.policy.AutomationMapState
import app.spammy.hof.automation.repository.QuestAutomationCycleCommandRepository
import app.spammy.hof.automation.repository.QuestMapExecutionCounterCommandRepository
import app.spammy.hof.automation.repository.TypedAutomationQueryRepository
import app.spammy.hof.battle.service.BattleMapAliasResolution
import app.spammy.hof.battle.service.BattleMapIdentityCandidate
import app.spammy.hof.battle.service.resolveBattleMapAlias
import app.spammy.hof.quest.model.QuestMission
import app.spammy.hof.quest.model.QuestMissionType
import app.spammy.hof.quest.model.QuestSnapshot
import app.spammy.hof.quest.model.QuestState
import java.time.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class QuestPresetSelection(
    val mode: PresetSelectionMode,
    val presetId: Long? = null,
)

sealed interface QuestAction : PreparedAutomationAction {
    val questCode: String

    data class Claim(
        override val questCode: String,
        val actionNo: String,
    ) : QuestAction

    data class Accept(
        override val questCode: String,
        val actionNo: String,
    ) : QuestAction

    data class Battle(
        override val questCode: String,
        val questCycle: String,
        val missionKey: String,
        val missionType: QuestMissionType,
        val categoryId: String,
        val mapCode: String,
        val mapName: String,
        val preset: QuestPresetSelection,
        val battleCount: Int = 1,
    ) : QuestAction
}

data class QuestAutomationMapSelection(
    val missionKey: String,
    val categoryId: String,
    val mapCode: String,
    val mapName: String,
    val preset: QuestPresetSelection,
    val executionOrder: Int,
    val manuallyOverridden: Boolean,
)

data class QuestAutomationSelection(
    val questCode: String,
    val enabled: Boolean,
    val maps: List<QuestAutomationMapSelection>,
)

data class QuestCounterKey(
    val questCode: String,
    val questCycle: String,
    val missionKey: String,
    val categoryId: String,
    val mapCode: String,
)

data class QuestAutomationSnapshot(
    val accountId: Long,
    val quests: List<QuestSnapshot>,
    val selections: List<QuestAutomationSelection>,
    val mapStates: List<AutomationMapState>,
    val currentCycles: Map<String, String>,
    val counters: Map<QuestCounterKey, Int>,
    val mapIdentityCandidates: List<BattleMapIdentityCandidate>,
    val now: Instant,
)

interface QuestAutomationProgressStore {
    fun startNewCycle(accountId: Long, questCode: String): String
    fun recordVictory(accountId: Long, action: QuestAction.Battle)
}

/** Writes only quest cycle/counter tables; the account lock makes read-modify-write increments atomic. */
@Service
class JpaQuestAutomationProgressStore(
    private val queryRepository: TypedAutomationQueryRepository,
    private val cycleRepository: QuestAutomationCycleCommandRepository,
    private val counterRepository: QuestMapExecutionCounterCommandRepository,
) : QuestAutomationProgressStore {
    @Transactional
    override fun startNewCycle(accountId: Long, questCode: String): String {
        val account = queryRepository.lockAccount(accountId)
        val cycle = queryRepository.findQuestCycle(accountId, questCode)
        if (cycle == null) {
            cycleRepository.save(QuestAutomationCycleEntity(account = account, questCode = questCode, currentCycle = 1))
            return "1"
        }
        cycle.currentCycle += 1
        return cycle.currentCycle.toString()
    }

    @Transactional
    override fun recordVictory(accountId: Long, action: QuestAction.Battle) {
        val account = queryRepository.lockAccount(accountId)
        val counter = queryRepository.findQuestCounter(
            accountId,
            action.questCode,
            action.questCycle,
            action.missionKey,
            action.categoryId,
            action.mapCode,
        )
        if (counter == null) {
            counterRepository.save(
                QuestMapExecutionCounterEntity(
                    account = account,
                    questCode = action.questCode,
                    questCycle = action.questCycle,
                    missionKey = action.missionKey,
                    categoryId = action.categoryId,
                    mapCode = action.mapCode,
                    successfulRuns = 1,
                ),
            )
        } else {
            counter.successfulRuns += 1
        }
    }
}

enum class QuestBattleOutcome { VICTORY, DEFEAT, NETWORK_FAILURE }

/**
 * Pure quest priority evaluation plus explicit post-success progress callbacks.
 * No network request is made and [evaluate] never writes persistence state.
 */
@Service
class QuestAutomationHandler(
    private val progressStore: QuestAutomationProgressStore,
) : AutomationHandler<QuestAutomationSnapshot> {
    override fun evaluate(context: QuestAutomationSnapshot): HandlerEvaluation {
        val selections = context.selections
            .asSequence()
            .filter(QuestAutomationSelection::enabled)
            .associateBy(QuestAutomationSelection::questCode)
        val candidates = context.quests
            .filter { it.questId in selections }
            .sortedBy(QuestSnapshot::sourceOrder)

        candidates.firstOrNull { it.state == QuestState.CLAIMABLE }?.let { quest ->
            return quest.actionNo?.let { HandlerEvaluation.Runnable(QuestAction.Claim(quest.questId, it)) }
                ?: HandlerEvaluation.ConfigurationWarning("Quest ${quest.questId} has no claim action.")
        }

        candidates.firstOrNull { it.state == QuestState.AVAILABLE && it.isImmediatelyCompletable() }?.let { quest ->
            return quest.actionNo?.let { HandlerEvaluation.Runnable(QuestAction.Accept(quest.questId, it)) }
                ?: HandlerEvaluation.ConfigurationWarning("Quest ${quest.questId} has no accept action.")
        }

        evaluateCombat(candidates, selections, context, QuestMissionType.MONSTER_KILL)?.let { return it }
        evaluateCombat(candidates, selections, context, QuestMissionType.MAP_CLEAR)?.let { return it }
        return HandlerEvaluation.Skipped
    }

    fun onAcceptSucceeded(accountId: Long, action: QuestAction.Accept): String =
        progressStore.startNewCycle(accountId, action.questCode)

    fun onBattleCompleted(accountId: Long, action: QuestAction.Battle, outcome: QuestBattleOutcome) {
        if (outcome == QuestBattleOutcome.VICTORY) progressStore.recordVictory(accountId, action)
    }

    private fun evaluateCombat(
        quests: List<QuestSnapshot>,
        selections: Map<String, QuestAutomationSelection>,
        context: QuestAutomationSnapshot,
        missionType: QuestMissionType,
    ): HandlerEvaluation? {
        quests.filter { it.state == QuestState.ACTIVE }.forEach { quest ->
            quest.missions.filter { it.type == missionType && !it.completable }.forEach { mission ->
                val selection = selections.getValue(quest.questId)
                val result = when (missionType) {
                    QuestMissionType.MONSTER_KILL -> monsterAction(quest, mission, selection, context)
                    QuestMissionType.MAP_CLEAR -> mapClearAction(quest, mission, selection, context)
                    else -> null
                }
                if (result != null) return result
            }
        }
        return null
    }

    private fun monsterAction(
        quest: QuestSnapshot,
        mission: QuestMission,
        selection: QuestAutomationSelection,
        context: QuestAutomationSnapshot,
    ): HandlerEvaluation {
        val configured = selection.maps.filter { it.missionKey == mission.key }
        if (configured.isEmpty()) {
            return HandlerEvaluation.ConfigurationWarning("Quest ${quest.questId} mission ${mission.key} has no battle map.")
        }
        val stateByMap = context.mapStates.associateBy { it.categoryId to it.mapCode }
        val ready = configured.filter { map -> stateByMap[map.categoryId to map.mapCode]?.isRunnable(context.now) == true }
        if (ready.isEmpty()) {
            val next = configured.mapNotNull { stateByMap[it.categoryId to it.mapCode]?.cooldownUntil }
                .filter { it.isAfter(context.now) }
                .minOrNull()
            return next?.let(HandlerEvaluation::Unavailable) ?: HandlerEvaluation.Skipped
        }
        val cycle = context.currentCycles[quest.questId] ?: INITIAL_CYCLE
        val selected = ready.minWithOrNull(
            compareBy<QuestAutomationMapSelection> {
                context.counters[QuestCounterKey(quest.questId, cycle, mission.key, it.categoryId, it.mapCode)] ?: 0
            }.thenBy(QuestAutomationMapSelection::executionOrder),
        )!!
        return selected.toBattleEvaluation(quest, cycle, mission)
    }

    private fun mapClearAction(
        quest: QuestSnapshot,
        mission: QuestMission,
        selection: QuestAutomationSelection,
        context: QuestAutomationSnapshot,
    ): HandlerEvaluation {
        val configured = selection.maps.filter { it.missionKey == mission.key }
        val manual = configured.filter(QuestAutomationMapSelection::manuallyOverridden)
            .minByOrNull(QuestAutomationMapSelection::executionOrder)
        val selected = if (manual != null) {
            manual
        } else {
            val target = mission.target?.takeIf(String::isNotBlank)
                ?: return HandlerEvaluation.ConfigurationWarning("Quest ${quest.questId} mission ${mission.key} has no map target.")
            val categoryId = configured.firstOrNull()?.categoryId ?: DEFAULT_BATTLE_CATEGORY
            val identityCandidates = context.mapIdentityCandidates.filter { it.categoryId == categoryId }
            when (val resolved = resolveBattleMapAlias(target, identityCandidates)) {
                is BattleMapAliasResolution.Resolved -> configured.firstOrNull {
                    it.categoryId == resolved.categoryId && it.mapCode == resolved.mapCode
                } ?: QuestAutomationMapSelection(
                    mission.key,
                    resolved.categoryId,
                    resolved.mapCode,
                    resolved.mapName,
                    QuestPresetSelection(PresetSelectionMode.PRIMARY),
                    0,
                    false,
                )
                BattleMapAliasResolution.Missing,
                BattleMapAliasResolution.Ambiguous,
                -> return HandlerEvaluation.ConfigurationWarning(
                    "Quest ${quest.questId} mission ${mission.key} map '$target' is missing or ambiguous.",
                )
            }
        }
        val state = context.mapStates.firstOrNull {
            it.categoryId == selected.categoryId && it.mapCode == selected.mapCode
        }
        if (state?.isRunnable(context.now) != true) {
            return state?.cooldownUntil?.takeIf { it.isAfter(context.now) }
                ?.let(HandlerEvaluation::Unavailable)
                ?: HandlerEvaluation.Skipped
        }
        return selected.toBattleEvaluation(quest, context.currentCycles[quest.questId] ?: INITIAL_CYCLE, mission)
    }

    private fun QuestAutomationMapSelection.toBattleEvaluation(
        quest: QuestSnapshot,
        cycle: String,
        mission: QuestMission,
    ): HandlerEvaluation {
        if ((preset.mode == PresetSelectionMode.EXPLICIT) != (preset.presetId != null)) {
            return HandlerEvaluation.ConfigurationWarning(
                "Quest ${quest.questId} mission ${mission.key} has an invalid preset selection.",
            )
        }
        return HandlerEvaluation.Runnable(
            QuestAction.Battle(
                quest.questId,
                cycle,
                mission.key,
                mission.type,
                categoryId,
                mapCode,
                mapName,
                preset,
                battleCount = 1,
            ),
        )
    }

    private fun QuestSnapshot.isImmediatelyCompletable(): Boolean =
        missions.isNotEmpty() && missions.all { mission ->
            when (mission.type) {
                QuestMissionType.IMMEDIATE -> true
                QuestMissionType.ITEM_TURN_IN -> mission.completable
                else -> false
            }
        }

    private fun AutomationMapState.isRunnable(now: Instant): Boolean =
        visible && enabled &&
            (cooldownUntil == null || !cooldownUntil.isAfter(now)) &&
            (winRemaining == null || winRemaining > 0) &&
            (attemptRemaining == null || attemptRemaining > 0) &&
            (availableCount == null || availableCount > 0) &&
            (keyCount == null || keyCount > 0)

    private companion object {
        const val INITIAL_CYCLE = "0"
        const val DEFAULT_BATTLE_CATEGORY = "battle_map"
    }
}
