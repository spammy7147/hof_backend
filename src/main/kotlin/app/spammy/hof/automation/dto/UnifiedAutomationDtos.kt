package app.spammy.hof.automation.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class ModuleMapRequest(
    @field:NotBlank val categoryId: String,
    @field:NotBlank val mapCode: String,
    val partyPresetId: Long? = null,
    @field:Min(0) val executionOrder: Int = 0,
)

data class QuestExecutionRequest(
    @field:NotBlank val questId: String,
    @field:Valid @field:Size(max = 20) val maps: List<ModuleMapRequest> = emptyList(),
)

data class KeyQuestSettingsRequest(
    val enabled: Boolean = true,
    @field:Valid @field:Size(max = 20) val quests: List<QuestExecutionRequest> = emptyList(),
)

data class TimeSettingsRequest(
    val enabled: Boolean = true,
    @field:Min(70) @field:Max(100) val thresholdPercent: Int = 90,
    @field:Valid @field:Size(max = 100) val maps: List<ModuleMapRequest> = emptyList(),
)

data class ToggleModuleRequest(
    val enabled: Boolean = true,
    @field:Valid @field:Size(max = 100) val maps: List<ModuleMapRequest> = emptyList(),
)

data class NormalQuestSettingsRequest(
    val enabled: Boolean = false,
    @field:Size(max = 100) val questIds: List<@NotBlank String> = emptyList(),
)

data class UnifiedAutomationSettingsRequest(
    @field:Valid val keyQuest: KeyQuestSettingsRequest = KeyQuestSettingsRequest(),
    @field:Valid val time: TimeSettingsRequest = TimeSettingsRequest(),
    @field:Valid val cooldownAdventure: ToggleModuleRequest = ToggleModuleRequest(),
    @field:Valid val dailyAdventure: ToggleModuleRequest = ToggleModuleRequest(),
    @field:Valid val union: ToggleModuleRequest = ToggleModuleRequest(),
    @field:Valid val normalQuest: NormalQuestSettingsRequest = NormalQuestSettingsRequest(),
)

data class UnifiedAutomationStatusResponse(
    val profileId: Long,
    val job: AutomationJobResponse?,
    val settings: UnifiedAutomationSettingsRequest,
    val currentTitle: String?,
    val nextRunAt: String?,
)
