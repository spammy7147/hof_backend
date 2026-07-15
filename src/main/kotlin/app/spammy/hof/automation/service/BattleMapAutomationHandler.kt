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
import java.time.ZoneId
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
    val settings: List<BattleMapAutomationSetting>,
    val mapStates: List<BattleMapRunnableState>,
    val successfulRuns: Map<BattleMapProgressIdentity, Int>,
    val primaryPresetId: Long?,
    val availablePresetIds: Set<Long>,
    /** Supplied by the coordinator so retries retain identity without making pure evaluation random. */
    val executionIdentity: String,
    /** Deterministic action-time input; the handler derives the Korea calendar date itself. */
    val evaluationInstant: Instant,
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

enum class BattleAutomationRoundOutcome { VICTORY, DEFEAT, DRAW, NETWORK_FAILURE, UNKNOWN }

data class BattleAuthoritativeOutcomeEvidence(
    val accountId: Long,
    val executionIdentity: String,
    val categoryId: String,
    val mapCode: String,
    val battleCount: Int,
    val resultIdentity: String,
    val outcomes: List<BattleAutomationRoundOutcome>,
) {
    val victoryCount: Int get() = outcomes.count { it == BattleAutomationRoundOutcome.VICTORY }

    fun binds(action: BattleMapAutomationAction): Boolean =
        accountId == action.accountId &&
            executionIdentity == action.executionIdentity &&
            categoryId == action.categoryId &&
            mapCode == action.mapCode &&
            battleCount == action.battleCount

    fun isCompleteTerminal(): Boolean =
        battleCount in setOf(1, 3) && outcomes.size == battleCount && outcomes.all {
            it == BattleAutomationRoundOutcome.VICTORY ||
                it == BattleAutomationRoundOutcome.DEFEAT ||
                it == BattleAutomationRoundOutcome.DRAW
        }
}

interface BattleMapAutomationProgressStore {
    fun recordResult(
        action: BattleMapAutomationAction,
        evidence: BattleAuthoritativeOutcomeEvidence,
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
        evidence: BattleAuthoritativeOutcomeEvidence,
    ) {
        require(evidence.binds(action)) { "Authoritative battle evidence does not bind the prepared action." }
        require(evidence.isCompleteTerminal()) { "Authoritative battle evidence must contain every terminal round." }
        val resultIdentity = evidence.resultIdentity
        val victories = evidence.victoryCount
        val outcomeFingerprint = outcomeFingerprint(evidence.outcomes)
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

    private fun outcomeFingerprint(outcomes: List<BattleAutomationRoundOutcome>): String {
        val canonical = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(outcomes.size)
                outcomes.forEach { output.writeCanonical(it.name) }
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
    /** Reloads authoritative recent results/state. It must never submit or resend the battle action. */
    fun reloadRecentAuthoritativeEvidence(action: BattleMapAutomationAction): BattleOutcomeReconciliation
}

sealed interface BattleOutcomeReconciliation {
    data class Proven(val evidence: BattleAuthoritativeOutcomeEvidence) : BattleOutcomeReconciliation

    data class Unproven(val message: String) : BattleOutcomeReconciliation
}

sealed interface BattleOutcomeResolution {
    data class Applied(val resultIdentity: String, val victories: Int) : BattleOutcomeResolution
    data object Ignored : BattleOutcomeResolution
    data class Fatal(val evaluation: HandlerEvaluation.Fatal) : BattleOutcomeResolution
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
            .sortedWith(
                compareBy<BattleMapAutomationSetting> { it.executionOrder }
                    .thenBy(BattleMapAutomationSetting::categoryId)
                    .thenBy(BattleMapAutomationSetting::mapCode),
            )
            .forEach { setting ->
                val identity = BattleMapProgressIdentity(setting.categoryId, setting.mapCode)
                val remaining = setting.dailyTargetCount - (context.successfulRuns[identity] ?: 0)
                if (remaining <= 0) return@forEach
                val presetId = setting.preset.resolvePreset(context) ?: return@forEach
                val state = states[identity]?.takeIf { it.isRunnable(context.evaluationInstant) } ?: return@forEach
                val battleCount = when {
                    !state.supportsThreeBattles -> 1
                    remaining == 1 -> 1
                    !state.hasCapacityForThree() -> 1
                    else -> 3
                }
                return HandlerEvaluation.Runnable(
                    BattleMapAutomationAction(
                        accountId = context.accountId,
                        progressDate = context.evaluationInstant.atZone(KOREA_ZONE).toLocalDate(),
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
        action: BattleMapAutomationAction,
        source: BattleAutomationActionSource,
        resultIdentity: String,
        outcomes: List<BattleAutomationRoundOutcome>,
        reconciler: BattleOutcomeReconciler,
    ): BattleOutcomeResolution {
        if (source != BattleAutomationActionSource.BATTLE_MAP_AUTOMATION || action.source != source) {
            return BattleOutcomeResolution.Ignored
        }
        val direct = BattleAuthoritativeOutcomeEvidence(
            action.accountId,
            action.executionIdentity,
            action.categoryId,
            action.mapCode,
            action.battleCount,
            resultIdentity,
            outcomes,
        )
        if (!direct.hasValidResultIdentity()) {
            return fatalNetwork("Battle result identity is missing or exceeds the protocol limit.")
        }
        if (direct.isCompleteTerminal()) return applyEvidence(action, direct)

        return when (val reconciliation = reconciler.reloadRecentAuthoritativeEvidence(action)) {
            is BattleOutcomeReconciliation.Proven -> {
                val evidence = reconciliation.evidence
                if (!evidence.hasValidResultIdentity() || !evidence.binds(action) || !evidence.isCompleteTerminal()) {
                    fatalNetwork("Reloaded battle evidence did not exactly match the prepared action and rounds.")
                } else {
                    applyEvidence(action, evidence)
                }
            }
            is BattleOutcomeReconciliation.Unproven -> fatalNetwork(reconciliation.message)
        }
    }

    private fun applyEvidence(
        action: BattleMapAutomationAction,
        evidence: BattleAuthoritativeOutcomeEvidence,
    ): BattleOutcomeResolution.Applied {
        progressStore.recordResult(action, evidence)
        return BattleOutcomeResolution.Applied(evidence.resultIdentity, evidence.victoryCount)
    }

    private fun fatalNetwork(message: String) = BattleOutcomeResolution.Fatal(
        HandlerEvaluation.Fatal(AutomationStopReason.NETWORK, message),
    )

    private fun BattleAuthoritativeOutcomeEvidence.hasValidResultIdentity(): Boolean =
        resultIdentity.isNotBlank() && resultIdentity.length <= MAX_RESULT_IDENTITY_LENGTH

    private fun BattleMapPresetSelection.resolvePreset(context: BattleMapAutomationSnapshot): Long? = when (mode) {
        PresetSelectionMode.PRIMARY -> context.primaryPresetId?.takeIf(context.availablePresetIds::contains)
        PresetSelectionMode.EXPLICIT -> presetId?.takeIf(context.availablePresetIds::contains)
    }

    private fun BattleMapRunnableState.isRunnable(now: Instant): Boolean =
        visible && enabled && cooldownUntil?.isAfter(now) != true &&
            listOf(availableCount, attemptRemaining, winRemaining, keyCount).none { it != null && it <= 0 }

    private fun BattleMapRunnableState.hasCapacityForThree(): Boolean =
        listOf(availableCount, attemptRemaining, winRemaining, keyCount).none { it != null && it < 3 }

    private companion object {
        const val MAX_RESULT_IDENTITY_LENGTH = 128
        val KOREA_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
