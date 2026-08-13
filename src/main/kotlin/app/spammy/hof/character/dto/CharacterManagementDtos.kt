package app.spammy.hof.character.dto

import app.spammy.hof.town.common.model.TownActionRequest
import app.spammy.hof.town.common.model.TownEditableFieldType
import app.spammy.hof.town.common.model.TownSelectionType

data class CharacterManagementSnapshotResponse(
    val character: CharacterDetailResponse?,
    val actions: List<CharacterObservedActionResponse>,
    val messages: List<String> = emptyList(),
    val characters: List<CharacterResponse> = emptyList(),
    val targetRemoved: Boolean = false,
)

data class CharacterObservedActionResponse(
    val actionId: String,
    val source: String,
    val label: String,
    val candidates: List<CharacterActionCandidateResponse> = emptyList(),
    val fields: List<CharacterActionFieldResponse> = emptyList(),
)

data class CharacterActionCandidateResponse(
    val id: String,
    val groupId: String,
    val label: String,
    val selectionType: TownSelectionType,
    val minQuantity: Int = 1,
    val maxQuantity: Int? = null,
    val selected: Boolean = false,
)

data class CharacterActionFieldResponse(
    val id: String,
    val label: String,
    val value: String = "",
    val inputType: TownEditableFieldType = TownEditableFieldType.TEXT,
    val maxLength: Int? = null,
)

data class CharacterManagementActionRequest(
    val action: TownActionRequest,
)
