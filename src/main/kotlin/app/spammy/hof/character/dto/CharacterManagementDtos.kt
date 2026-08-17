package app.spammy.hof.character.dto

import app.spammy.hof.town.common.model.TownEditableFieldType
import app.spammy.hof.town.common.model.TownSelectionType
import com.fasterxml.jackson.annotation.JsonIgnore

data class CharacterManagementSnapshotResponse(
    val character: CharacterDetailResponse?,
    val actions: List<CharacterObservedActionResponse>,
    val messages: List<String> = emptyList(),
    val characters: List<CharacterResponse> = emptyList(),
    val targetRemoved: Boolean = false,
    val identityResolutionRequired: Boolean = false,
    val identityCandidates: List<CharacterIdentityCandidateResponse> = emptyList(),
)

data class CharacterIdentityCandidateResponse(
    val hofCharacterId: String,
    val name: String,
    val job: String = "",
    val level: Int? = null,
    val matchingFields: Set<String> = emptySet(),
)

data class CharacterObservedActionResponse(
    @get:JsonIgnore
    val actionId: String,
    @get:JsonIgnore
    val source: String,
    val label: String,
    val candidates: List<CharacterActionCandidateResponse> = emptyList(),
    val fields: List<CharacterActionFieldResponse> = emptyList(),
)

data class CharacterActionCandidateResponse(
    @get:JsonIgnore
    val id: String,
    @get:JsonIgnore
    val groupId: String,
    val label: String,
    val selectionType: TownSelectionType,
    val minQuantity: Int = 1,
    val maxQuantity: Int? = null,
    val selected: Boolean = false,
)

data class CharacterActionFieldResponse(
    @get:JsonIgnore
    val id: String,
    val label: String,
    val value: String = "",
    val inputType: TownEditableFieldType = TownEditableFieldType.TEXT,
    val maxLength: Int? = null,
)
