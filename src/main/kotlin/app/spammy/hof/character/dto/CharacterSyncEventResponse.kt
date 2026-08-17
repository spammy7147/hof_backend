package app.spammy.hof.character.dto

import java.time.Instant

/**
 * 캐릭터 동기화 SSE 스트림에서 앱으로 보내는 단일 이벤트 DTO다.
 */
data class CharacterSyncEventResponse(
    val eventId: Long,
    val eventType: String,
    val jobId: Long,
    val accountId: Long,
    val status: String,
    val rosterCount: Int,
    val syncedCount: Int,
    val failedCharacterIds: List<String>,
    val character: CharacterResponse?,
    val message: String?,
    val emittedAt: Instant,
    val stopRequested: Boolean = false,
    val lastCompletedRosterIndex: Int = -1,
    val currentHofCharacterId: String? = null,
)
