package app.spammy.hof.character.dto

data class CharacterManualLinkRequest(
    val characterId: Long,
    val newHofCharacterId: String,
)

data class CharacterLifecycleRequest(val characterId: Long)
