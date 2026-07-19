package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.automation.entity.QuestAutomationCycleEntity
import app.spammy.hof.automation.entity.QuestAutomationProcessedResultEntity
import app.spammy.hof.automation.entity.QuestAutomationResultKind
import app.spammy.hof.automation.entity.QuestMapExecutionCounterEntity
import app.spammy.hof.automation.repository.QuestAutomationCycleCommandRepository
import app.spammy.hof.automation.repository.QuestAutomationProcessedResultCommandRepository
import app.spammy.hof.automation.repository.QuestMapExecutionCounterCommandRepository
import app.spammy.hof.automation.repository.TypedAutomationQueryRepository
import app.spammy.hof.battle.service.BattleMapAliasResolution
import app.spammy.hof.battle.service.BattleMapIdentityCandidate
import app.spammy.hof.battle.service.resolveBattleMapAlias
import app.spammy.hof.quest.model.QuestMission
import app.spammy.hof.quest.model.QuestMissionType
import app.spammy.hof.quest.model.QuestSnapshot
import app.spammy.hof.quest.model.QuestState
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class QuestPresetSelection(
    val mode: PresetSelectionMode,
    val presetId: Long? = null,
    val resolvedPresetId: Long? = presetId,
    val resolutionChecked: Boolean = false,
    val resolvedParty: ResolvedAutomationParty? = null,
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
        val resolvedParty: ResolvedAutomationParty? = preset.resolvedParty,
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
    val primaryPresetId: Long? = null,
    val primaryParty: ResolvedAutomationParty? = null,
)

interface QuestAutomationProgressStore {
    fun startNewCycle(accountId: Long, resultId: String, questCode: String): String
    fun recordVictory(accountId: Long, resultId: String, action: QuestAction.Battle)
}

class QuestAutomationResultConflictException(resultId: String) : IllegalStateException(
    "Quest result identity '$resultId' is already bound to a different action.",
)

/** Writes only quest cycle/counter tables; the account lock makes read-modify-write increments atomic. */
@Service
class JpaQuestAutomationProgressStore(
    private val queryRepository: TypedAutomationQueryRepository,
    private val cycleRepository: QuestAutomationCycleCommandRepository,
    private val processedResultRepository: QuestAutomationProcessedResultCommandRepository,
    private val counterRepository: QuestMapExecutionCounterCommandRepository,
) : QuestAutomationProgressStore {
    @Transactional
    override fun startNewCycle(accountId: Long, resultId: String, questCode: String): String {
        validateResultIdentity(resultId)
        val fingerprint = actionFingerprint(QuestAutomationResultKind.ACCEPT, listOf(questCode))
        val account = queryRepository.lockAccount(accountId)
        queryRepository.findQuestProcessedResult(accountId, resultId)?.let {
            it.requireReplayMatches(resultId, QuestAutomationResultKind.ACCEPT, fingerprint)
            return requireNotNull(it.resultValue) { "Processed accept result $resultId has no cycle value." }
        }
        val cycle = queryRepository.findQuestCycle(accountId, questCode)
        val nextCycle = if (cycle == null) {
            cycleRepository.save(QuestAutomationCycleEntity(account = account, questCode = questCode, currentCycle = 1))
            1L
        } else {
            cycle.currentCycle += 1
            cycle.currentCycle
        }
        val resultValue = nextCycle.toString()
        processedResultRepository.save(
            QuestAutomationProcessedResultEntity(
                account = account,
                resultKind = QuestAutomationResultKind.ACCEPT,
                resultIdentity = resultId,
                actionFingerprint = fingerprint,
                resultValue = resultValue,
                processedAt = Instant.now(),
            ),
        )
        return resultValue
    }

    @Transactional
    override fun recordVictory(accountId: Long, resultId: String, action: QuestAction.Battle) {
        validateResultIdentity(resultId)
        val fingerprint = actionFingerprint(
            QuestAutomationResultKind.BATTLE_VICTORY,
            listOf(
                action.questCode,
                action.questCycle,
                action.missionKey,
                action.categoryId,
                action.mapCode,
            ),
        )
        val account = queryRepository.lockAccount(accountId)
        queryRepository.findQuestProcessedResult(accountId, resultId)?.let {
            it.requireReplayMatches(resultId, QuestAutomationResultKind.BATTLE_VICTORY, fingerprint)
            return
        }
        processedResultRepository.save(
            QuestAutomationProcessedResultEntity(
                account = account,
                resultKind = QuestAutomationResultKind.BATTLE_VICTORY,
                resultIdentity = resultId,
                actionFingerprint = fingerprint,
                processedAt = Instant.now(),
            ),
        )
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

    private fun validateResultIdentity(resultId: String) {
        require(resultId.isNotBlank()) { "Quest result identity must not be blank." }
        require(resultId.length <= QuestAutomationProcessedResultEntity.MAX_RESULT_IDENTITY_LENGTH) {
            "Quest result identity must be at most ${QuestAutomationProcessedResultEntity.MAX_RESULT_IDENTITY_LENGTH} characters."
        }
    }

    private fun QuestAutomationProcessedResultEntity.requireReplayMatches(
        resultId: String,
        expectedKind: QuestAutomationResultKind,
        expectedFingerprint: String,
    ) {
        if (resultKind != expectedKind || actionFingerprint != expectedFingerprint) {
            throw QuestAutomationResultConflictException(resultId)
        }
    }

    private fun actionFingerprint(kind: QuestAutomationResultKind, actionFields: List<String>): String {
        val canonical = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                val fields = listOf(kind.name) + actionFields
                output.writeInt(fields.size)
                fields.forEach { field ->
                    val encoded = field.toByteArray(StandardCharsets.UTF_8)
                    output.writeInt(encoded.size)
                    output.write(encoded)
                }
            }
            bytes.toByteArray()
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical))
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

        candidates.firstOrNull { it.state == QuestState.AVAILABLE }?.let { quest ->
            return quest.actionNo?.let { HandlerEvaluation.Runnable(QuestAction.Accept(quest.questId, it)) }
                ?: HandlerEvaluation.ConfigurationWarning("Quest ${quest.questId} has no accept action.")
        }

        evaluateCombat(candidates, selections, context, QuestMissionType.MONSTER_KILL)?.let { return it }
        evaluateCombat(candidates, selections, context, QuestMissionType.MAP_CLEAR)?.let { return it }
        return HandlerEvaluation.Skipped
    }

    fun onAcceptSucceeded(accountId: Long, resultId: String, action: QuestAction.Accept): String =
        progressStore.startNewCycle(accountId, resultId, action.questCode)

    fun onBattleCompleted(
        accountId: Long,
        resultId: String,
        action: QuestAction.Battle,
        outcome: QuestBattleOutcome,
    ) {
        if (outcome == QuestBattleOutcome.VICTORY) progressStore.recordVictory(accountId, resultId, action)
    }

    private fun evaluateCombat(
        quests: List<QuestSnapshot>,
        selections: Map<String, QuestAutomationSelection>,
        context: QuestAutomationSnapshot,
        missionType: QuestMissionType,
    ): HandlerEvaluation? {
        val evaluations = mutableListOf<HandlerEvaluation>()
        quests.filter { it.state == QuestState.ACTIVE }.forEach { quest ->
            quest.missions.filter { it.type == missionType && !it.completable }.forEach { mission ->
                val selection = selections.getValue(quest.questId)
                val result = when (missionType) {
                    QuestMissionType.MONSTER_KILL -> monsterAction(quest, mission, selection, context)
                    QuestMissionType.MAP_CLEAR -> mapClearAction(quest, mission, selection, context)
                    else -> null
                }
                if (result is HandlerEvaluation.Runnable) return result
                if (result != null) evaluations += result
            }
        }
        if (evaluations.isEmpty()) return null
        return evaluations.filterIsInstance<HandlerEvaluation.ConfigurationWarning>().firstOrNull()
            ?: evaluations.filterIsInstance<HandlerEvaluation.Unavailable>().minByOrNull { it.nextRunAt }
            ?: HandlerEvaluation.Skipped
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
        val invalidPresetExists = configured.any { !it.hasValidPreset() }
        val validConfigured = configured.filter { it.hasValidPreset() }
        val stateByMap = context.mapStates.associateBy { it.categoryId to it.mapCode }
        val ready = validConfigured.filter { map ->
            stateByMap[map.categoryId to map.mapCode]?.isRunnable(context.now) == true
        }
        if (ready.isEmpty()) {
            if (invalidPresetExists) {
                return HandlerEvaluation.ConfigurationWarning(
                    "Quest ${quest.questId} mission ${mission.key} has an invalid preset selection.",
                )
            }
            val next = validConfigured.mapNotNull { stateByMap[it.categoryId to it.mapCode]?.cooldownUntil }
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
            .sortedBy(QuestAutomationMapSelection::executionOrder)
        val selected = if (manual.isNotEmpty()) {
            val states = context.mapStates.associateBy { it.categoryId to it.mapCode }
            manual.firstOrNull { candidate ->
                candidate.hasValidPreset() &&
                    states[candidate.categoryId to candidate.mapCode]?.isRunnable(context.now) == true
            } ?: run {
                if (manual.any { !it.hasValidPreset() }) {
                    return HandlerEvaluation.ConfigurationWarning(
                        "Quest ${quest.questId} mission ${mission.key} has an invalid preset selection.",
                    )
                }
                val next = manual.mapNotNull { states[it.categoryId to it.mapCode]?.cooldownUntil }
                    .filter { it.isAfter(context.now) }
                    .minOrNull()
                return next?.let(HandlerEvaluation::Unavailable) ?: HandlerEvaluation.Skipped
            }
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
                    if (context.primaryPresetId == null && context.primaryParty == null) {
                        QuestPresetSelection(PresetSelectionMode.PRIMARY)
                    } else {
                        QuestPresetSelection(
                            PresetSelectionMode.PRIMARY,
                            resolvedPresetId = context.primaryPresetId,
                            resolutionChecked = true,
                            resolvedParty = context.primaryParty,
                        )
                    },
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
        if (!hasValidPreset()) {
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

    private fun AutomationMapState.isRunnable(now: Instant): Boolean =
        visible && enabled &&
            (cooldownUntil == null || !cooldownUntil.isAfter(now)) &&
            (winRemaining == null || winRemaining > 0) &&
            (attemptRemaining == null || attemptRemaining > 0) &&
            (availableCount == null || availableCount > 0) &&
            (keyCount == null || keyCount > 0)

    private fun QuestAutomationMapSelection.hasValidPreset(): Boolean =
        when (preset.mode) {
            PresetSelectionMode.EXPLICIT -> preset.presetId != null && (!preset.resolutionChecked || preset.resolvedPresetId == preset.presetId)
            PresetSelectionMode.PRIMARY -> preset.presetId == null && (!preset.resolutionChecked || preset.resolvedPresetId != null)
        }

    private companion object {
        const val INITIAL_CYCLE = "0"
        const val DEFAULT_BATTLE_CATEGORY = "battle_map"
    }
}
