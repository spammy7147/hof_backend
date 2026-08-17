package app.spammy.hof.character.dto

import app.spammy.hof.character.transfer.CharacterTransferRequest

data class CharacterTransferPreviewRequest(
    val sourceCharacterId: Long,
    val targetCharacterId: Long,
    val transfer: CharacterTransferRequest,
)

data class CharacterTransferExecuteRequest(
    val sourceCharacterId: Long,
    val targetCharacterId: Long,
    val transfer: CharacterTransferRequest,
    val completedStepIds: Set<String> = emptySet(),
)
