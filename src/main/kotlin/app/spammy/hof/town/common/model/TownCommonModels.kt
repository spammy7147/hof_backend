package app.spammy.hof.town.common.model

import app.spammy.hof.external.model.HofHttpMethod
import com.fasterxml.jackson.annotation.JsonIgnore

data class ParsedTownPage(
    val forms: List<ParsedTownForm>,
)

data class ParsedTownForm(
    val actionId: String,
    val method: HofHttpMethod,
    val actionUrl: String,
    val rows: List<ParsedTownRow>,
    @get:JsonIgnore
    internal val hiddenFields: Map<String, String>,
    @get:JsonIgnore
    internal val submitFields: Map<String, String>,
) {
    val candidates: List<ParsedTownCandidate>
        get() = rows.mapNotNull(ParsedTownRow::candidate)
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
    @get:JsonIgnore
    internal val selectionType: TownSelectionType = TownSelectionType.RADIO,
)

data class TownActionRequest(
    val actionId: String,
    val selections: List<TownActionSelection> = emptyList(),
)

data class TownActionSelection(
    val candidateId: String,
    val quantity: Int = 1,
)

class GuardedTownAction internal constructor(
    @get:JsonIgnore
    internal val form: ParsedTownForm,
    @get:JsonIgnore
    internal val formFields: Map<String, String>,
)

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
