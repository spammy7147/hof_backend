package app.spammy.hof.character.dto

import app.spammy.hof.external.model.HofActionPatternRow
import app.spammy.hof.external.model.HofEquipment
import app.spammy.hof.external.model.HofPositionGuard
import java.time.Instant

data class CharacterRecoveryPreviewResponse(
    val jobId: Long,
    val characterId: Long,
    val confirmationToken: String,
    val observedAt: Instant,
    val expiresAt: Instant,
    val hofCharacterId: String,
    val name: String,
    val patterns: List<HofActionPatternRow>,
    val equipment: List<HofEquipment>,
    val positionGuard: HofPositionGuard,
)

data class CharacterRecoveryAcceptRequest(val confirmationToken: String)
