package app.spammy.hof.external.parser

import app.spammy.hof.external.model.HofCharacter

data class CharacterPageParseResult(
    val snapshot: HofCharacter,
    val sections: Map<CharacterPageSection, CharacterSectionParseResult>,
    /** 현재 화면에서 실제 불러오기 form을 제공하는 장비 저장 번호. */
    val equipmentPresetSlots: Set<Int> = emptySet(),
)

enum class CharacterPageSection {
    PROFILE, STATS, EFFECTS_FAITH, CURRENT_PATTERN, POSITION_GUARD,
    SAVED_PATTERNS, EQUIPMENT, EQUIPMENT_CANDIDATES, SKILLS, MANAGEMENT,
}

sealed interface CharacterSectionParseResult {
    val observedCount: Int

    data class Success(override val observedCount: Int) : CharacterSectionParseResult

    data class Failure(
        val parserVersion: String,
        val errorCode: String,
        val expected: String,
        override val observedCount: Int,
    ) : CharacterSectionParseResult {
        fun safeMessage(): String =
            "section contract failed: parser=$parserVersion code=$errorCode expected=$expected observed=$observedCount"
    }
}
