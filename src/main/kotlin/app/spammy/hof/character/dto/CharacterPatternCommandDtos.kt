package app.spammy.hof.character.dto

import app.spammy.hof.character.pattern.CharacterPatternDraft
import app.spammy.hof.character.pattern.CharacterPatternSetting
import app.spammy.hof.character.pattern.PatternSlotAfterApply
import java.time.Instant

data class CharacterPatternApplyRequest(
    val characterId: Long,
    val baseRevision: Instant,
    val base: CharacterPatternSetting,
    val draft: CharacterPatternDraft,
    val slotAction: PatternSlotAction = PatternSlotAction.NONE,
    val targetSlotCode: String? = null,
    val slotName: String? = null,
    val force: Boolean = false,
) {
    fun slotAfterApply(): PatternSlotAfterApply = when (slotAction) {
        PatternSlotAction.NONE -> PatternSlotAfterApply.None
        PatternSlotAction.SAVE_EMPTY -> PatternSlotAfterApply.SaveEmpty(
            requireNotNull(targetSlotCode) { "저장할 빈 슬롯을 선택해 주세요." },
            requireNotNull(slotName) { "저장 이름을 입력해 주세요." },
        )
        PatternSlotAction.REPLACE -> PatternSlotAfterApply.Replace(
            requireNotNull(targetSlotCode) { "교체할 슬롯을 선택해 주세요." },
            requireNotNull(slotName) { "저장 이름을 입력해 주세요." },
        )
    }
}

data class CharacterPatternSlotCommandRequest(
    val characterId: Long,
    val slotCode: String,
)

enum class PatternSlotAction { NONE, SAVE_EMPTY, REPLACE }
