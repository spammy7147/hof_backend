package app.spammy.hof.character.dto

import app.spammy.hof.character.entity.CharacterOperationStatus
import app.spammy.hof.character.entity.CharacterOperationType
import app.spammy.hof.character.service.CharacterDeepSyncResponse
import app.spammy.hof.character.transfer.CharacterTransferExecutionResult
import java.time.Instant

data class CharacterOperationJobResponse(
    val id: Long,
    val operationType: CharacterOperationType,
    val status: CharacterOperationStatus,
    val sourceCharacterId: Long?,
    val targetCharacterId: Long,
    val deepSync: CharacterDeepSyncResponse? = null,
    val transfer: CharacterTransferExecutionResult? = null,
    val message: String? = null,
    val updatedAt: Instant,
    val finishedAt: Instant? = null,
)

