package app.spammy.hof.character.transfer

import app.spammy.hof.character.command.CharacterStat
import app.spammy.hof.character.pattern.CharacterPatternRowValue
import app.spammy.hof.character.pattern.CharacterPatternSetting

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

/** 슬롯 생성 전의 복사 원본과 대상의 행동 패턴을 재시작 뒤에도 같은 값으로 사용한다. */
data class CharacterTransferSnapshot(
    val source: CharacterTransferSource,
    val originalCurrentPattern: CharacterPatternSetting,
)

data class CharacterTransferEquipment(
    val equipmentPart: String,
    /** 이름이 중복될 수 있으므로 화면 문자열이 아닌 HOF 후보 값을 사용한다. */
    val sourceValue: String,
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
) {
    val executable: Boolean get() = issues.none { it.severity == CharacterTransferIssueSeverity.BLOCKING }
}
