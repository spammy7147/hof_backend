package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.battle.model.BattleMapKeyMode
import app.spammy.hof.battle.model.hasUsableKey
import java.time.Instant
import org.springframework.stereotype.Service

data class AdventureMapPresetSelection(
    val mode: PresetSelectionMode,
    /** Fixed only for EXPLICIT. PRIMARY is resolved afresh at the snapshot-loader boundary. */
    val configuredPresetId: Long? = null,
)

data class AdventureMapAutomationSetting(
    val settingIdentity: Long,
    val enabled: Boolean,
    val categoryId: String,
    val mapCode: String,
    val preset: AdventureMapPresetSelection,
    val executionOrder: Int,
)

data class AdventureMapRunnableState(
    val categoryId: String,
    val mapCode: String,
    val resolved: Boolean,
    val visible: Boolean,
    val enabled: Boolean,
    val cooldownUntil: Instant? = null,
    val dailyRemaining: Int? = null,
    val attemptRemaining: Int? = null,
    val winRemaining: Int? = null,
    val availableCount: Int? = null,
    val keyMode: BattleMapKeyMode,
    val keyCount: Int? = null,
    val mapName: String? = null,
    val requiredTime: Int? = null,
)

sealed interface AdventureMapPresetResolution {
    data class Valid(val resolvedPresetId: Long, val resolvedParty: ResolvedAutomationParty? = null) : AdventureMapPresetResolution
    data class Invalid(val warning: String) : AdventureMapPresetResolution
}

/** All values are prepared by the loader; evaluation itself performs no I/O or clock access. */
data class AdventureMapAutomationSnapshot(
    val accountId: Long,
    val settings: List<AdventureMapAutomationSetting>,
    val mapStates: List<AdventureMapRunnableState>,
    val presetResolutions: Map<Long, AdventureMapPresetResolution>,
    /**
     * Task 9's loader must provide one fresh identity per enabled setting for every new evaluation/action attempt.
     * Re-evaluating this same immutable snapshot remains deterministic and therefore returns the same identity.
     */
    val executionIdentities: Map<Long, String>,
    val evaluationInstant: Instant,
    val timeSnapshot: AutomationTimeSnapshot? = null,
)

data class AdventureMapAutomationAction(
    val accountId: Long,
    val categoryId: String,
    val mapCode: String,
    val presetMode: PresetSelectionMode,
    val presetId: Long,
    val battleCount: Int = 1,
    val settingIdentity: Long,
    val executionIdentity: String,
    val resolvedParty: ResolvedAutomationParty? = null,
    val mapName: String? = null,
    val observedCooldownUntil: Instant? = null,
    val observedAttemptRemaining: Int? = null,
    val observedWinRemaining: Int? = null,
    val observedAvailableCount: Int? = null,
) : PreparedAutomationAction

/** Pure, ordered selection over the latest live adventure-map snapshot. */
@Service
class AdventureMapAutomationHandler(
    private val timePolicy: BattleTimePolicy = BattleTimePolicy(),
) : AutomationHandler<AdventureMapAutomationSnapshot> {
    override fun evaluate(context: AdventureMapAutomationSnapshot): HandlerEvaluation = evaluate(context) { true }

    override fun evaluate(context: AdventureMapAutomationSnapshot, accepts: (PreparedAutomationAction) -> Boolean): HandlerEvaluation {
        val stateGroups = context.mapStates.groupBy { it.categoryId to it.mapCode }
        val duplicateStateIdentities = stateGroups.filterValues { it.size > 1 }.keys
        val states = stateGroups.mapNotNull { (identity, matches) ->
            matches.singleOrNull()?.let { identity to it }
        }.toMap()
        val duplicateSettingIdentities = context.settings
            .groupingBy(AdventureMapAutomationSetting::settingIdentity)
            .eachCount()
            .filterValues { it > 1 }
            .keys
        val duplicateExecutionIdentities = context.settings
            .asSequence()
            .filter(AdventureMapAutomationSetting::enabled)
            .mapNotNull { context.executionIdentities[it.settingIdentity] }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        var firstWarning: HandlerEvaluation.ConfigurationWarning? = null
        val waits = mutableListOf<HandlerEvaluation.Unavailable>()

        context.settings
            .asSequence()
            .filter(AdventureMapAutomationSetting::enabled)
            .sortedWith(
                compareBy<AdventureMapAutomationSetting> { it.executionOrder }
                    .thenBy(AdventureMapAutomationSetting::categoryId)
                    .thenBy(AdventureMapAutomationSetting::mapCode)
                    .thenBy(AdventureMapAutomationSetting::settingIdentity),
            )
            .forEach { setting ->
                if (setting.settingIdentity in duplicateSettingIdentities) {
                    firstWarning = firstWarning ?: HandlerEvaluation.ConfigurationWarning(
                        "Adventure setting identity ${setting.settingIdentity} is duplicated.",
                    )
                    return@forEach
                }
                val executionIdentity = context.executionIdentities[setting.settingIdentity]
                if (executionIdentity == null || executionIdentity.isBlank() || executionIdentity.length > MAX_EXECUTION_IDENTITY_LENGTH) {
                    firstWarning = firstWarning ?: HandlerEvaluation.ConfigurationWarning(
                        "Adventure setting ${setting.settingIdentity} has a missing or invalid execution identity.",
                    )
                    return@forEach
                }
                if (executionIdentity in duplicateExecutionIdentities) {
                    firstWarning = firstWarning ?: HandlerEvaluation.ConfigurationWarning(
                        "Adventure execution identity '$executionIdentity' is reused.",
                    )
                    return@forEach
                }
                val presetId = setting.resolvePreset(context.presetResolutions[setting.settingIdentity])
                if (presetId == null) {
                    if (firstWarning == null) {
                        firstWarning = HandlerEvaluation.ConfigurationWarning(
                            setting.presetWarning(context.presetResolutions[setting.settingIdentity]),
                        )
                    }
                    return@forEach
                }

                val mapIdentity = setting.categoryId to setting.mapCode
                if (mapIdentity in duplicateStateIdentities) {
                    firstWarning = firstWarning ?: HandlerEvaluation.ConfigurationWarning(
                        "Adventure map ${setting.categoryId}/${setting.mapCode} has duplicate live states.",
                    )
                    return@forEach
                }
                val state = states[mapIdentity]
                    ?.takeIf { it.isAvailableMap() }
                    ?: return@forEach
                if (state.hasExhaustedCapacity()) return@forEach
                state.cooldownUntil?.takeIf { it.isAfter(context.evaluationInstant) }?.let {
                    waits += HandlerEvaluation.Unavailable(it)
                    return@forEach
                }

                when (val time = timePolicy.forAdventureMap(
                    snapshot = context.timeSnapshot,
                    now = context.evaluationInstant,
                    requiredTime = state.requiredTime,
                )) {
                    is BattleTimeDecision.Wait -> {
                        waits += time.toUnavailable()
                        return@forEach
                    }
                    is BattleTimeDecision.Run -> {
                        val candidate = HandlerEvaluation.Runnable(
                            AdventureMapAutomationAction(
                                accountId = context.accountId,
                                categoryId = setting.categoryId,
                                mapCode = setting.mapCode,
                                presetMode = setting.preset.mode,
                                presetId = presetId,
                                settingIdentity = setting.settingIdentity,
                                executionIdentity = executionIdentity,
                                resolvedParty = (context.presetResolutions[setting.settingIdentity] as? AdventureMapPresetResolution.Valid)?.resolvedParty,
                                mapName = state.mapName,
                                observedCooldownUntil = state.cooldownUntil,
                                observedAttemptRemaining = state.attemptRemaining,
                                observedWinRemaining = state.winRemaining,
                                observedAvailableCount = state.availableCount,
                            ),
                        )
                        if (accepts(candidate.action)) return candidate
                    }
                }
            }

        firstWarning?.let { return it }
        waits.minByOrNull { it.nextRunAt }?.let { return it }
        return HandlerEvaluation.Skipped
    }

    private fun AdventureMapAutomationSetting.resolvePreset(
        resolution: AdventureMapPresetResolution?,
    ): Long? {
        val resolvedId = (resolution as? AdventureMapPresetResolution.Valid)?.resolvedPresetId
            ?.takeIf { it > 0 }
            ?: return null
        return when (preset.mode) {
            PresetSelectionMode.PRIMARY -> resolvedId.takeIf { preset.configuredPresetId == null }
            PresetSelectionMode.EXPLICIT -> resolvedId.takeIf {
                preset.configuredPresetId != null && preset.configuredPresetId == resolvedId
            }
        }
    }

    private fun AdventureMapAutomationSetting.presetWarning(
        resolution: AdventureMapPresetResolution?,
    ): String = (resolution as? AdventureMapPresetResolution.Invalid)?.warning
        ?: "Adventure map $categoryId/$mapCode setting $settingIdentity has an invalid ${preset.mode} preset selection."

    private fun AdventureMapRunnableState.isAvailableMap(): Boolean =
        resolved && visible && enabled && keyMode.hasUsableKey(keyCount)

    private fun AdventureMapRunnableState.hasExhaustedCapacity(): Boolean =
        listOf(dailyRemaining, attemptRemaining, winRemaining, availableCount).any { it != null && it <= 0 }

    private companion object {
        const val MAX_EXECUTION_IDENTITY_LENGTH = 128
    }
}
