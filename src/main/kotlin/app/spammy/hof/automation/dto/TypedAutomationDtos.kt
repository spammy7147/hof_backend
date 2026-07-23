package app.spammy.hof.automation.dto

import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.automation.entity.TypedAutomationLifecycle
import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size

data class CreateAutomationEntryRequest(val type: AutomationType)

data class ReorderAutomationEntriesRequest(
    @field:Size(max = 3) val entryIds: List<Long>,
)

data class UpdateQuestAutomationRequest(
    val enabled: Boolean,
    @field:Valid @field:Size(max = 100) val quests: List<@Valid QuestSelectionRequest>,
) {
    @get:AssertTrue(message = "전체 퀘스트 맵은 최대 100개까지 저장할 수 있습니다.")
    val hasValidTotalMapCount: Boolean
        get() = quests.sumOf { it.maps.size } <= 100

    @get:AssertTrue(message = "퀘스트 출처 순서를 중복해서 사용할 수 없습니다.")
    val hasUniqueSourceOrders: Boolean
        get() = quests.map { it.sourceOrder }.let { it.size == it.toSet().size }
}

data class QuestSelectionRequest(
    @field:NotBlank @field:Size(max = 100) val questCode: String,
    val enabled: Boolean,
    @field:Min(0) val sourceOrder: Int,
    @field:Valid @field:Size(max = 100) val maps: List<@Valid QuestMapSettingRequest>,
) {
    @get:AssertTrue(message = "퀘스트 맵 실행 순서를 중복해서 사용할 수 없습니다.")
    val hasUniqueMapExecutionOrders: Boolean
        get() = maps.groupBy { it.missionKey }.values.all { missionMaps ->
            missionMaps.map { it.executionOrder }.let { it.size == it.toSet().size }
        }
}

data class QuestMapSettingRequest(
    @field:NotBlank @field:Size(max = 100) val missionKey: String,
    @field:NotBlank @field:Size(max = 50) val categoryId: String,
    @field:NotBlank @field:Size(max = 100) val mapCode: String,
    val presetMode: PresetSelectionMode,
    val partyPresetId: Long?,
    @field:Min(0) val executionOrder: Int,
    val manuallyOverridden: Boolean,
)

data class UpdateBattleMapAutomationRequest(
    val enabled: Boolean,
    @field:Valid @field:Size(max = 100) val maps: List<@Valid BattleMapSettingRequest>,
) {
    @get:AssertTrue(message = "전투 맵 실행 순서를 중복해서 사용할 수 없습니다.")
    val hasUniqueExecutionOrders: Boolean
        get() = maps.map { it.executionOrder }.let { it.size == it.toSet().size }
}

data class BattleMapSettingRequest(
    @field:NotBlank @field:Size(max = 50) val categoryId: String,
    @field:NotBlank @field:Size(max = 100) val mapCode: String,
    @field:Positive val dailyTargetCount: Int,
    val presetMode: PresetSelectionMode,
    val partyPresetId: Long?,
    @field:Min(0) val executionOrder: Int,
)

data class UpdateAdventureMapAutomationRequest(
    val enabled: Boolean,
    @field:Valid @field:Size(max = 100) val maps: List<@Valid AdventureMapSettingRequest>,
) {
    @get:AssertTrue(message = "모험맵 실행 순서를 중복해서 사용할 수 없습니다.")
    val hasUniqueExecutionOrders: Boolean
        get() = maps.map { it.executionOrder }.let { it.size == it.toSet().size }
}

data class AdventureMapSettingRequest(
    @field:NotBlank @field:Size(max = 50) val categoryId: String,
    @field:NotBlank @field:Size(max = 100) val mapCode: String,
    val presetMode: PresetSelectionMode,
    val partyPresetId: Long?,
    @field:Min(0) val executionOrder: Int,
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

data class BattleMapDailyProgressResponse(
    val categoryId: String,
    val mapCode: String,
    val successfulRuns: Int,
)

data class AdventureMapSettingResponse(
    val categoryId: String,
    val mapCode: String,
    val presetMode: PresetSelectionMode,
    val partyPresetId: Long?,
    val executionOrder: Int,
    val displayName: String?,
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
    val battleMapProgress: List<BattleMapDailyProgressResponse> = emptyList(),
    val adventureMaps: List<AdventureMapSettingResponse> = emptyList(),
)

data class TypedAutomationRuntimeResponse(
    val lifecycle: TypedAutomationLifecycle,
    val stopReason: String? = null,
    val nextAttemptAt: String? = null,
    val warnings: List<String> = emptyList(),
    val lastError: String? = null,
    val currentAction: TypedAutomationCurrentActionResponse? = null,
    val dailyRefresh: AdventureDailyRefreshResponse = AdventureDailyRefreshResponse(),
)

data class TypedAutomationCurrentActionResponse(
    val source: AutomationType,
    val kind: String,
    val actionLabel: String,
    val questName: String? = null,
    val missionLabel: String? = null,
    val missionCurrent: Int? = null,
    val missionRequired: Int? = null,
    val mapName: String? = null,
    val battleCount: Int? = null,
)

data class AdventureDailyRefreshResponse(
    val status: String = "PENDING",
    val refreshDate: String? = null,
    val refreshedAt: String? = null,
)

data class TypedAutomationAggregateResponse(
    val entries: List<TypedAutomationEntryResponse>,
    val runtime: TypedAutomationRuntimeResponse,
)
