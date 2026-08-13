package app.spammy.hof.town.common.model

import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofFormField
import com.fasterxml.jackson.annotation.JsonIgnore

data class ParsedTownPage(
    val forms: List<ParsedTownForm>,
)

data class ParsedTownForm(
    val actionId: String,
    @get:JsonIgnore
    val method: HofHttpMethod,
    @get:JsonIgnore
    val actionUrl: String,
    val rows: List<ParsedTownRow>,
    @get:JsonIgnore
    internal val hiddenFields: List<HofFormField>,
    @get:JsonIgnore
    internal val submitFields: List<HofFormField>,
    @get:JsonIgnore
    internal val submitLabel: String = "",
    @get:JsonIgnore
    internal val submitSource: String = submitFields.joinToString("+") { it.name },
    @get:JsonIgnore
    internal val hiddenFieldPositions: List<Int> = hiddenFields.indices.toList(),
    @get:JsonIgnore
    internal val submitFieldPositions: List<Int> = submitFields.indices.map { hiddenFields.size + it },
    @get:JsonIgnore
    internal val editableFields: List<ParsedTownEditableField> = emptyList(),
) {
    val candidates: List<ParsedTownCandidate>
        get() = rows.mapNotNull(ParsedTownRow::candidate)
}

data class ParsedTownEditableField(
    val id: String,
    val label: String,
    @get:JsonIgnore
    internal val inputName: String,
    val value: String = "",
    val inputType: TownEditableFieldType = TownEditableFieldType.TEXT,
    val maxLength: Int? = null,
    @get:JsonIgnore
    internal val inputPosition: Int = Int.MAX_VALUE - 1,
)

enum class TownEditableFieldType {
    TEXT,
    NUMBER,
}

data class ParsedTownRow(
    val label: String,
    @get:JsonIgnore
    internal val candidate: ParsedTownCandidate? = null,
) {
    val selectable: Boolean
        get() = candidate != null
}

enum class TownSelectionType {
    RADIO,
    CHECKBOX,
    SELECT,
}

data class ParsedTownCandidate(
    val id: String,
    val label: String,
    @get:JsonIgnore
    internal val inputName: String,
    @get:JsonIgnore
    internal val inputValue: String,
    @get:JsonIgnore
    internal val quantityFieldName: String? = null,
    val minQuantity: Int = 1,
    val maxQuantity: Int? = null,
    val selected: Boolean = false,
    @get:JsonIgnore
    internal val selectionType: TownSelectionType = TownSelectionType.RADIO,
    @get:JsonIgnore
    internal val inputPosition: Int = Int.MAX_VALUE - 1,
    @get:JsonIgnore
    internal val quantityPosition: Int? = null,
)

data class TownActionRequest(
    val actionId: String,
    val selections: List<TownActionSelection> = emptyList(),
    val values: List<TownFieldValue> = emptyList(),
)

data class TownFieldValue(
    val fieldId: String,
    val value: String,
)

data class TownActionSelection(
    val candidateId: String,
    val quantity: Int = 1,
)

class GuardedTownAction internal constructor(
    @get:JsonIgnore
    internal val form: ParsedTownForm,
    @get:JsonIgnore
    internal val formEntries: List<HofFormField>,
) {
    internal val formFields: Map<String, String>
        get() = formEntries.associate { it.name to it.value }
}

data class ParsedTownResult(
    val messages: List<String>,
    val items: List<ParsedTownResultItem>,
)

data class ParsedTownResultItem(
    val label: String,
)

data class ExecutedTownAction(
    val result: ParsedTownResult,
    val page: ParsedTownPage,
)
