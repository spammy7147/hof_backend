package app.spammy.hof.character.transfer

import app.spammy.hof.character.command.CharacterStat
import app.spammy.hof.character.pattern.CharacterPatternRowValue
import app.spammy.hof.character.pattern.CharacterPatternSetting
import app.spammy.hof.external.model.HofEquipment
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/** 같은 HOF 계정 안에서 복사할 수 있는 캐릭터 설정 스냅샷. */
data class CharacterTransferSource(
    val accountId: Long,
    val characterId: Long,
    val currentPattern: CharacterPatternSetting,
    val savedPatterns: Map<String, CharacterPatternSetting> = emptyMap(),
    val savedPatternNames: Map<String, String> = emptyMap(),
    val realStats: Map<CharacterStat, Int> = emptyMap(),
    val learnedSkills: Set<String> = emptySet(),
    val equipment: List<CharacterTransferEquipment> = emptyList(),
    val equipmentPresets: Map<Int, List<CharacterTransferEquipment>> = emptyMap(),
)

/** 슬롯 생성 전의 복사 원본과 대상 설정을 재시작 뒤에도 같은 값으로 사용한다. */
data class CharacterTransferSnapshot(
    val source: CharacterTransferSource,
    val originalCurrentPattern: CharacterPatternSetting,
    val originalEquipment: List<CharacterTransferEquipment>? = null,
)

data class CharacterTransferEquipment(
    val equipmentPart: String,
    /** 현재 관측에서만 유효한 HOF 후보 값. 재시작 때는 identity로 다시 찾는다. */
    val sourceValue: String,
    val identity: HofEquipment? = null,
)

data class CharacterTransferTarget(
    val accountId: Long,
    val characterId: Long,
    val patternCapacity: Int,
    val defaultPatternRow: CharacterPatternRowValue,
    val allowedJudges: Set<String>,
    val allowedSkills: Set<String>,
    val allowedPositions: Set<String>,
    val allowedGuards: Set<String>,
    val occupiedPatternSlots: Set<String> = emptySet(),
    val statusPoints: Int = 0,
    val realStats: Map<CharacterStat, Int> = emptyMap(),
    val learnedSkills: Set<String> = emptySet(),
    val learnableSkills: Set<String> = emptySet(),
    val equipmentCandidateValues: Set<String> = emptySet(),
    val currentPattern: CharacterPatternSetting,
    val currentEquipment: List<CharacterTransferEquipment>? = null,
)

data class CharacterTransferRequest(
    val includeCurrentPattern: Boolean = false,
    val savedPatternMappings: List<CharacterSavedPatternMapping> = emptyList(),
    val includeStats: Boolean = false,
    val includeSkills: Boolean = false,
    val includeEquipment: Boolean = false,
)

data class CharacterSavedPatternMapping(val sourceSlot: String, val targetSlot: String)

enum class CharacterTransferIssueSeverity { WARNING, NEEDS_SELECTION, BLOCKING }

data class CharacterTransferIssue(
    val code: String,
    val itemKey: String,
    val message: String,
    val severity: CharacterTransferIssueSeverity,
)

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(CharacterTransferStep.ApplyCurrentPattern::class, name = "APPLY_CURRENT_PATTERN"),
    JsonSubTypes.Type(CharacterTransferStep.SavePatternSlot::class, name = "SAVE_PATTERN_SLOT"),
    JsonSubTypes.Type(CharacterTransferStep.AllocateStats::class, name = "ALLOCATE_STATS"),
    JsonSubTypes.Type(CharacterTransferStep.LearnSkill::class, name = "LEARN_SKILL"),
    JsonSubTypes.Type(CharacterTransferStep.EquipItem::class, name = "EQUIP_ITEM"),
    JsonSubTypes.Type(CharacterTransferStep.RemoveAllEquipment::class, name = "REMOVE_ALL_EQUIPMENT"),
    JsonSubTypes.Type(CharacterTransferStep.SaveEquipmentPreset::class, name = "SAVE_EQUIPMENT_PRESET"),
)
sealed interface CharacterTransferStep {
    val id: String
    val dependsOn: Set<String>

    data class ApplyCurrentPattern(
        override val id: String,
        val setting: CharacterPatternSetting,
        override val dependsOn: Set<String> = emptySet(),
    ) : CharacterTransferStep

    data class SavePatternSlot(
        override val id: String,
        val sourceSlot: String,
        val targetSlot: String,
        val name: String,
        val setting: CharacterPatternSetting,
        val replacesExisting: Boolean,
        override val dependsOn: Set<String> = emptySet(),
    ) : CharacterTransferStep

    data class AllocateStats(
        override val id: String,
        val amounts: Map<CharacterStat, Int>,
        override val dependsOn: Set<String> = emptySet(),
    ) : CharacterTransferStep

    data class LearnSkill(
        override val id: String,
        val skillValue: String,
        override val dependsOn: Set<String> = emptySet(),
    ) : CharacterTransferStep

    data class EquipItem(
        override val id: String,
        val equipmentPart: String,
        val itemValue: String,
        override val dependsOn: Set<String> = emptySet(),
        val identity: HofEquipment? = null,
    ) : CharacterTransferStep

    data class RemoveAllEquipment(
        override val id: String,
        override val dependsOn: Set<String> = emptySet(),
    ) : CharacterTransferStep

    data class SaveEquipmentPreset(
        override val id: String,
        val slotNumber: Int,
        override val dependsOn: Set<String> = emptySet(),
    ) : CharacterTransferStep
}

data class CharacterTransferPreview(
    val sourceCharacterId: Long,
    val targetCharacterId: Long,
    val steps: List<CharacterTransferStep>,
    val issues: List<CharacterTransferIssue>,
    val confirmationToken: String? = null,
) {
    val executable: Boolean get() = issues.none { it.severity == CharacterTransferIssueSeverity.BLOCKING }
}
