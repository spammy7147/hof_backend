package app.spammy.hof.character.dto

import java.time.Instant

/**
 * 캐릭터 동기화 작업의 현재 상태와 누적 결과를 앱으로 내려주는 응답 DTO다.
 */
data class CharacterSyncJobResponse(
    val jobId: Long,
    val accountId: Long,
    val status: String,
    val rosterCount: Int,
    val syncedCount: Int,
    val failedCharacterIds: List<String>,
    val characters: List<CharacterResponse>,
    val message: String?,
    val startedAt: Instant,
    val finishedAt: Instant?,
)
