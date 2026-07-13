package app.spammy.hof.character.dto

/**
 * 캐릭터 목록에 필요한 요약 정보를 앱으로 내려주는 응답 DTO다.
 */
data class CharacterResponse(
    val id: Long,
    val hofCharacterId: String,
    val name: String,
    val job: String,
    val level: Int?,
    val patternSlotCount: Int,
    val imageUrl: String?,
    val patternSlots: List<CharacterPatternSlotResponse> = emptyList(),
)
