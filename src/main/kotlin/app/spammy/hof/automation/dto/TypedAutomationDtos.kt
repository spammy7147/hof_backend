package app.spammy.hof.automation.dto

import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.automation.entity.TypedAutomationLifecycle
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size

data class CreateAutomationEntryRequest(val type: AutomationType)

data class ReorderAutomationEntriesRequest(
    @field:Size(max = 3) val entryIds: List<Long>,
)

data class UpdateQuestAutomationRequest(
    val enabled: Boolean,
    @field:Valid @field:Size(max = 100) val quests: List<QuestSelectionRequest>,
)

data class QuestSelectionRequest(
    @field:NotBlank @field:Size(max = 100) val questCode: String,
    val enabled: Boolean,
    val sourceOrder: Int,
    @field:Valid @field:Size(max = 100) val maps: List<QuestMapSettingRequest>,
)

data class QuestMapSettingRequest(
    @field:NotBlank @field:Size(max = 100) val missionKey: String,
    @field:NotBlank @field:Size(max = 50) val categoryId: String,
    @field:NotBlank @field:Size(max = 100) val mapCode: String,
    val presetMode: PresetSelectionMode,
    val partyPresetId: Long?,
    val executionOrder: Int,
    val manuallyOverridden: Boolean,
)

data class UpdateBattleMapAutomationRequest(
    val enabled: Boolean,
    @field:Valid @field:Size(max = 100) val maps: List<BattleMapSettingRequest>,
)

data class BattleMapSettingRequest(
    @field:NotBlank @field:Size(max = 50) val categoryId: String,
    @field:NotBlank @field:Size(max = 100) val mapCode: String,
    @field:Positive val dailyTargetCount: Int,
    val presetMode: PresetSelectionMode,
    val partyPresetId: Long?,
    val executionOrder: Int,
)

data class UpdateAdventureMapAutomationRequest(
    val enabled: Boolean,
    @field:Valid @field:Size(max = 100) val maps: List<AdventureMapSettingRequest>,
)

data class AdventureMapSettingRequest(
    @field:NotBlank @field:Size(max = 50) val categoryId: String,
    @field:NotBlank @field:Size(max = 100) val mapCode: String,
    val presetMode: PresetSelectionMode,
    val partyPresetId: Long?,
    val executionOrder: Int,
)

data class QuestMapSettingResponse(
    val missionKey: String,
    val categoryId: String,
    val mapCode: String,
    val presetMode: PresetSelectionMode,
    val partyPresetId: Long?,
    val executionOrder: Int,
    val manuallyOverridden: Boolean,
)

data class QuestSelectionResponse(
    val questCode: String,
    val enabled: Boolean,
    val sourceOrder: Int,
    val maps: List<QuestMapSettingResponse>,
)

data class BattleMapSettingResponse(
    val categoryId: String,
    val mapCode: String,
    val dailyTargetCount: Int,
    val presetMode: PresetSelectionMode,
    val partyPresetId: Long?,
    val executionOrder: Int,
)

data class AdventureMapSettingResponse(
    val categoryId: String,
    val mapCode: String,
    val presetMode: PresetSelectionMode,
    val partyPresetId: Long?,
    val executionOrder: Int,
)

data class TypedAutomationEntryResponse(
    val id: Long,
    val type: AutomationType,
    val enabled: Boolean,
    val priority: Int,
    val ready: Boolean,
    val warnings: List<String>,
    val quests: List<QuestSelectionResponse> = emptyList(),
    val battleMaps: List<BattleMapSettingResponse> = emptyList(),
    val adventureMaps: List<AdventureMapSettingResponse> = emptyList(),
)

data class TypedAutomationRuntimeResponse(
    val lifecycle: TypedAutomationLifecycle,
    val stopReason: String? = null,
    val nextAttemptAt: String? = null,
    val warnings: List<String> = emptyList(),
    val lastError: String? = null,
)

data class TypedAutomationAggregateResponse(
    val entries: List<TypedAutomationEntryResponse>,
    val runtime: TypedAutomationRuntimeResponse,
)
