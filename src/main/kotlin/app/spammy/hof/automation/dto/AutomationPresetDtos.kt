package app.spammy.hof.automation.dto

import app.spammy.hof.automation.entity.AutomationType
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class SaveAutomationPresetRequest(
    @field:NotBlank @field:Size(max = 100) val name: String,
    val settings: AutomationPresetSettings? = null,
    val revision: String? = null,
)

data class AutomationPresetResponse(val id: Long, val name: String, val entryCount: Int, val updatedAt: String)

data class AutomationPresetDetailResponse(
    val id: Long, val name: String, val revision: String, val settings: AutomationPresetSettings,
    val updatedAt: String? = null,
) {
    val entryCount: Int get() = settings.entries.size
}

data class AutomationPresetApplyResponse(val automation: TypedAutomationAggregateResponse, val preset: AutomationPresetDetailResponse)

data class ApplyAutomationPresetRequest(@field:NotBlank val revision: String)

data class AppliedAutomationPresetResponse(val id: Long, val name: String, val revision: String, val matchesCurrent: Boolean)

/** 사용자 설정만 보존한다. 진행량과 실행·수렴 상태는 현재 계정에 남는다. */
data class AutomationPresetSettings(@field:Valid @field:Size(max = 100) val entries: List<AutomationPresetEntry>) {
    fun forStorage() = copy(entries = entries.map { entry ->
        entry.copy(displayName = entry.displayName?.trim()?.takeIf { it.isNotEmpty() },
            adventureMaps = entry.adventureMaps.map { it.copy(displayName = null) },
            fishingMaps = entry.fishingMaps.map { it.copy(displayName = null) },
            unionMaps = entry.unionMaps.map { it.copy(displayName = null) })
    })
    companion object {
        fun from(entries: List<TypedAutomationEntryResponse>) = AutomationPresetSettings(entries.map { entry ->
            AutomationPresetEntry(entry.id, entry.type, entry.enabled, entry.displayName, entry.minimumRemainingTime,
                entry.quests, entry.homeQuests, entry.battleMaps, entry.adventureMaps, entry.fishingMaps,
                entry.unionMaps, entry.raidTargets)
        }).forStorage()
    }
}

data class AutomationPresetEntry(
    @field:Min(0) val sourceEntryId: Long,
    val type: AutomationType,
    val enabled: Boolean,
    @field:Size(max = 100) val displayName: String? = null,
    val minimumRemainingTime: Int? = null,
    val quests: List<QuestSelectionResponse> = emptyList(),
    val homeQuests: List<HomeQuestSelectionResponse> = emptyList(),
    val battleMaps: List<BattleMapSettingResponse> = emptyList(),
    val adventureMaps: List<AdventureMapSettingResponse> = emptyList(),
    val fishingMaps: List<FishingMapSettingResponse> = emptyList(),
    val unionMaps: List<UnionMapSettingResponse> = emptyList(),
    val raidTargets: List<RaidTargetSettingResponse> = emptyList(),
)

/** 현재 설정 저장과 프리셋 저장이 같은 타입별 요청 계약으로 검증되게 한다. */
fun AutomationPresetEntry.toSettingsRequest(revision: String = "0"): Any = when (type) {
    AutomationType.COLOSSEUM -> UpdateColosseumAutomationRequest(enabled)
    AutomationType.QUEST -> UpdateQuestAutomationRequest(enabled, quests.map { quest ->
        QuestSelectionRequest(quest.questKey, quest.displayCode, quest.questName, quest.enabled, quest.sourceOrder,
            quest.maps.map { QuestMapSettingRequest(it.missionKey, it.categoryId, it.mapCode, it.presetMode,
                it.partyPresetId, it.executionOrder, it.manuallyOverridden) })
    })
    AutomationType.HOME_QUEST -> UpdateHomeQuestAutomationRequest(enabled,
        homeQuests.map { HomeQuestSelectionRequest(it.questId, it.questName, it.enabled, it.sourceOrder) })
    AutomationType.BATTLE_MAP -> UpdateBattleMapGroupRequest(revision, displayName, enabled,
        battleMaps.map { BattleMapSettingRequest(it.categoryId, it.mapCode, it.dailyTargetCount,
            it.presetMode, it.partyPresetId, it.executionOrder) }, minimumRemainingTime)
    AutomationType.ADVENTURE_MAP -> UpdateAdventureMapGroupRequest(revision, displayName, enabled,
        adventureMaps.map { AdventureMapSettingRequest(it.categoryId, it.mapCode, it.presetMode, it.partyPresetId, it.executionOrder) })
    AutomationType.FISHING -> UpdateFishingAutomationRequest(enabled,
        fishingMaps.map { FishingMapSettingRequest(it.categoryId, it.mapCode, it.presetMode, it.partyPresetId, it.executionOrder) })
    AutomationType.UNION -> UpdateUnionAutomationRequest(enabled,
        unionMaps.map { UnionMapSettingRequest(it.categoryId, it.mapCode, it.presetMode, it.partyPresetId, it.executionOrder) })
    AutomationType.RAID -> UpdateRaidAutomationRequest(enabled,
        raidTargets.map { RaidTargetSettingRequest(it.raidId, it.displayName, it.presetMode, it.partyPresetId, it.executionOrder) })
}
