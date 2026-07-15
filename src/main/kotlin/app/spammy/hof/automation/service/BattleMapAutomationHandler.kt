package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.BattleAutomationDailyProgressEntity
import app.spammy.hof.automation.entity.BattleAutomationProcessedResultEntity
import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.automation.repository.BattleAutomationDailyProgressCommandRepository
import app.spammy.hof.automation.repository.BattleAutomationProcessedResultCommandRepository
import app.spammy.hof.automation.repository.TypedAutomationQueryRepository
import app.spammy.hof.common.time.TimeProvider
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.HexFormat
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

const val BATTLE_MAP_PROGRESS_SOURCE = "battle_map"

data class BattleMapPresetSelection(
    val mode: PresetSelectionMode,
    val presetId: Long? = null,
)

data class BattleMapAutomationSetting(
    val enabled: Boolean,
    val categoryId: String,
    val mapCode: String,
    val dailyTargetCount: Int,
    val preset: BattleMapPresetSelection,
    val executionOrder: Int,
)

data class BattleMapRunnableState(
    val categoryId: String,
    val mapCode: String,
    val visible: Boolean,
    val enabled: Boolean,
    val supportsThreeBattles: Boolean = false,
    val cooldownUntil: Instant? = null,
    val availableCount: Int? = null,
    val attemptRemaining: Int? = null,
    val winRemaining: Int? = null,
    val keyCount: Int? = null,
)

data class BattleMapProgressIdentity(val categoryId: String, val mapCode: String)

data class BattleMapAutomationSnapshot(
    val accountId: Long,
    val progressDate: LocalDate,
    val settings: List<BattleMapAutomationSetting>,
    val mapStates: List<BattleMapRunnableState>,
    val successfulRuns: Map<BattleMapProgressIdentity, Int>,
    val primaryPresetId: Long?,
    val availablePresetIds: Set<Long>,
    /** Supplied by the coordinator so retries retain identity without making pure evaluation random. */
    val executionIdentity: String,
    val now: Instant = Instant.EPOCH,
)

enum class BattleAutomationActionSource {
    BATTLE_MAP_AUTOMATION,
    QUEST_AUTOMATION,
    ADVENTURE_AUTOMATION,
}

data class BattleMapAutomationAction(
    val accountId: Long,
    /** The action date is authoritative even if the response is reconciled after KST midnight. */
    val progressDate: LocalDate,
    val categoryId: String,
    val mapCode: String,
    val presetMode: PresetSelectionMode,
    val presetId: Long?,
    val battleCount: Int,
    val executionIdentity: String,
    val source: BattleAutomationActionSource = BattleAutomationActionSource.BATTLE_MAP_AUTOMATION,
) : PreparedAutomationAction

enum class BattleAutomationRoundOutcome { VICTORY, DEFEAT, NETWORK_FAILURE }

interface BattleMapAutomationProgressStore {
    fun recordResult(
        action: BattleMapAutomationAction,
        resultIdentity: String,
        outcomeFingerprint: String,
        victories: Int,
    )
}

class BattleMapAutomationResultConflictException(resultIdentity: String) : IllegalStateException(
    "Battle result identity '$resultIdentity' is already bound to a different execution or outcome.",
)

@Service
class JpaBattleMapAutomationProgressStore(
    private val queryRepository: TypedAutomationQueryRepository,
    private val progressRepository: BattleAutomationDailyProgressCommandRepository,
    private val processedResultRepository: BattleAutomationProcessedResultCommandRepository,
    private val timeProvider: TimeProvider,
) : BattleMapAutomationProgressStore {
    @Transactional
    override fun recordResult(
        action: BattleMapAutomationAction,
        resultIdentity: String,
        outcomeFingerprint: String,
        victories: Int,
    ) {
        require(resultIdentity.isNotBlank() && resultIdentity.length <= 128)
        require(action.executionIdentity.isNotBlank() && action.executionIdentity.length <= 128)
        require(outcomeFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(victories in 0..action.battleCount)
        val actionFingerprint = actionFingerprint(action)
        val account = queryRepository.lockAccount(action.accountId)
        queryRepository.findBattleProcessedResult(action.accountId, resultIdentity)?.let { prior ->
            if (
                prior.executionIdentity != action.executionIdentity ||
                prior.actionFingerprint != actionFingerprint ||
                prior.outcomeFingerprint != outcomeFingerprint ||
                prior.victoryCount != victories
            ) throw BattleMapAutomationResultConflictException(resultIdentity)
            return
        }
        queryRepository.findBattleProcessedExecution(action.accountId, action.executionIdentity)?.let { prior ->
            if (
                prior.resultIdentity != resultIdentity ||
                prior.actionFingerprint != actionFingerprint ||
                prior.outcomeFingerprint != outcomeFingerprint ||
                prior.victoryCount != victories
            ) throw BattleMapAutomationResultConflictException(resultIdentity)
            return
        }
        require(action.source == BattleAutomationActionSource.BATTLE_MAP_AUTOMATION)
        val now = timeProvider.now()
        processedResultRepository.save(
            BattleAutomationProcessedResultEntity(
                account = account,
                resultIdentity = resultIdentity,
                executionIdentity = action.executionIdentity,
                actionFingerprint = actionFingerprint,
                outcomeFingerprint = outcomeFingerprint,
                victoryCount = victories,
                processedAt = now,
            ),
        )
        val progress = queryRepository.findBattleProgress(
            action.accountId,
            action.progressDate,
            action.categoryId,
            action.mapCode,
            BATTLE_MAP_PROGRESS_SOURCE,
        )
        if (progress == null) {
            progressRepository.save(
                BattleAutomationDailyProgressEntity(
                    account = account,
                    progressDate = action.progressDate,
                    categoryId = action.categoryId,
                    mapCode = action.mapCode,
                    source = BATTLE_MAP_PROGRESS_SOURCE,
                    successfulRuns = victories,
                    updatedAt = now,
                ),
            )
        } else {
            progress.successfulRuns += victories
            progress.updatedAt = now
        }
    }

    private fun actionFingerprint(action: BattleMapAutomationAction): String {
        val canonical = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeCanonical(action.accountId.toString())
                output.writeCanonical(action.progressDate.toString())
                output.writeCanonical(action.categoryId)
                output.writeCanonical(action.mapCode)
                output.writeCanonical(action.presetMode.name)
                output.writeNullableCanonical(action.presetId?.toString())
                output.writeCanonical(action.battleCount.toString())
                output.writeCanonical(action.source.name)
                output.writeCanonical(BATTLE_MAP_PROGRESS_SOURCE)
                output.writeCanonical(action.executionIdentity)
            }
            bytes.toByteArray()
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical))
    }

    private fun DataOutputStream.writeCanonical(value: String) {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        writeInt(encoded.size)
        write(encoded)
    }

    private fun DataOutputStream.writeNullableCanonical(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeCanonical(value)
    }
}

fun interface BattleOutcomeReconciler {
    fun reloadAuthoritativeOutcome(action: BattleMapAutomationAction): BattleOutcomeReconciliation
}

sealed interface BattleOutcomeReconciliation {
    data class Proven(
        val resultIdentity: String,
        val outcomes: List<BattleAutomationRoundOutcome>,
    ) : BattleOutcomeReconciliation

    data class Unproven(val message: String) : BattleOutcomeReconciliation
    data class Applied(val resultIdentity: String, val victories: Int) : BattleOutcomeReconciliation
    data class Fatal(val evaluation: HandlerEvaluation.Fatal) : BattleOutcomeReconciliation
}

/** Pure ordered target selection plus explicit, idempotent result/reconciliation callbacks. */
@Service
class BattleMapAutomationHandler(
    private val progressStore: BattleMapAutomationProgressStore,
) : AutomationHandler<BattleMapAutomationSnapshot> {
    override fun evaluate(context: BattleMapAutomationSnapshot): HandlerEvaluation {
        val states = context.mapStates.associateBy { BattleMapProgressIdentity(it.categoryId, it.mapCode) }
        context.settings
            .asSequence()
            .filter(BattleMapAutomationSetting::enabled)
            .sortedWith(compareBy<BattleMapAutomationSetting> { it.executionOrder })
            .forEach { setting ->
                val identity = BattleMapProgressIdentity(setting.categoryId, setting.mapCode)
                val remaining = setting.dailyTargetCount - (context.successfulRuns[identity] ?: 0)
                if (remaining <= 0) return@forEach
                val presetId = setting.preset.resolvePreset(context) ?: return@forEach
                val state = states[identity]?.takeIf { it.isRunnable(context.now) } ?: return@forEach
                val battleCount = when {
                    !state.supportsThreeBattles -> 1
                    remaining == 1 -> 1
                    else -> 3
                }
                return HandlerEvaluation.Runnable(
                    BattleMapAutomationAction(
                        accountId = context.accountId,
                        progressDate = context.progressDate,
                        categoryId = setting.categoryId,
                        mapCode = setting.mapCode,
                        presetMode = setting.preset.mode,
                        presetId = presetId,
                        battleCount = battleCount,
                        executionIdentity = context.executionIdentity,
                    ),
                )
            }
        return HandlerEvaluation.Skipped
    }

    fun onBattleCompleted(
        resultIdentity: String,
        action: BattleMapAutomationAction,
        source: BattleAutomationActionSource,
        outcomes: List<BattleAutomationRoundOutcome>,
    ) {
        if (source != BattleAutomationActionSource.BATTLE_MAP_AUTOMATION || action.source != source) return
        val victories = outcomes.count { it == BattleAutomationRoundOutcome.VICTORY }
        progressStore.recordResult(action, resultIdentity, outcomeFingerprint(outcomes), victories)
    }

    /** Never resends: authoritative reload either proves one exact result or stops for manual resume. */
    fun reconcileAmbiguousOutcome(
        action: BattleMapAutomationAction,
        reconciler: BattleOutcomeReconciler,
    ): BattleOutcomeReconciliation = when (val reconciliation = reconciler.reloadAuthoritativeOutcome(action)) {
        is BattleOutcomeReconciliation.Proven -> {
            onBattleCompleted(
                reconciliation.resultIdentity,
                action,
                BattleAutomationActionSource.BATTLE_MAP_AUTOMATION,
                reconciliation.outcomes,
            )
            BattleOutcomeReconciliation.Applied(
                reconciliation.resultIdentity,
                reconciliation.outcomes.count { it == BattleAutomationRoundOutcome.VICTORY },
            )
        }
        is BattleOutcomeReconciliation.Unproven -> BattleOutcomeReconciliation.Fatal(
            HandlerEvaluation.Fatal(AutomationStopReason.NETWORK, reconciliation.message),
        )
        is BattleOutcomeReconciliation.Applied,
        is BattleOutcomeReconciliation.Fatal,
        -> reconciliation
    }

    private fun BattleMapPresetSelection.resolvePreset(context: BattleMapAutomationSnapshot): Long? = when (mode) {
        PresetSelectionMode.PRIMARY -> context.primaryPresetId?.takeIf(context.availablePresetIds::contains)
        PresetSelectionMode.EXPLICIT -> presetId?.takeIf(context.availablePresetIds::contains)
    }

    private fun BattleMapRunnableState.isRunnable(now: Instant): Boolean =
        visible && enabled && cooldownUntil?.isAfter(now) != true &&
            listOf(availableCount, attemptRemaining, winRemaining, keyCount).none { it != null && it <= 0 }

    private fun outcomeFingerprint(outcomes: List<BattleAutomationRoundOutcome>): String {
        val canonical = outcomes.joinToString(separator = "\u0000") { it.name }.toByteArray(StandardCharsets.UTF_8)
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical))
    }
}
