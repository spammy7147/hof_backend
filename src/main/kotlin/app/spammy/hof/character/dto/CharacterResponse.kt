package app.spammy.hof.character.dto

import java.time.Instant

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
    val lifecycle: String = "ACTIVE",
    val lastSeenAt: Instant? = null,
    val missingSince: Instant? = null,
    val archivedAt: Instant? = null,
    val rosterOrder: Int? = null,
    /** 낙관적 잠금에 사용하는 캐릭터 레코드 변경 버전. */
    val revision: Instant,
    val detailSyncedAt: Instant? = null,
    val sectionStates: List<CharacterSectionStateResponse> = emptyList(),
)
