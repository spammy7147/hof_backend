package app.spammy.hof.character.dto

/**
 * 캐릭터 저장 패턴 로드 요청의 성공 여부와 안내 메시지를 담는 응답 DTO다.
 */
data class LoadPatternResponse(
    val accountId: Long,
    val hofCharacterId: String,
    val slot: Int,
    val loaded: Boolean,
    val message: String,
    val characterSynchronized: Boolean,
    val character: CharacterDetailResponse?,
)
