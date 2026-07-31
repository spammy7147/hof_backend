package app.spammy.hof.town.exchange.dto

import app.spammy.hof.town.exchange.model.*
import app.spammy.hof.town.fishing.dto.TownActionResultResponse
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

data class ExchangeCategoryResponse(val id: String, val label: String, val current: Boolean)
data class ExchangeRowResponse(
    val id: String, val label: String, val selectable: Boolean, val detail: String?, val cost: Long?,
    val owned: Int?, val minQuantity: Int, val maxQuantity: Int?,
)
data class OwnedExchangeCurrencyResponse(val label: String, val quantity: Long?)
data class LegacyGradeActionResponse(
    val id: String, val label: String, val consumedItemsPerPress: Int, val allowsTargetSelection: Boolean,
)
data class AnnActionGroupResponse(val type: AnnAction, val label: String, val rows: List<ExchangeRowResponse>)

data class ExchangeResponse(
    val mode: ExchangeMode,
    val categories: List<ExchangeCategoryResponse>,
    val currentCategoryId: String?,
    val rows: List<ExchangeRowResponse>,
    val ownedCurrencies: List<OwnedExchangeCurrencyResponse>,
    val gradeActions: List<LegacyGradeActionResponse>,
    val annActions: List<AnnActionGroupResponse>,
    val warning: String?,
    val history: List<String>,
    val result: TownActionResultResponse?,
) {
    companion object {
        fun from(value: ExchangeSnapshot) = ExchangeResponse(
            value.mode,
            value.categories.map { ExchangeCategoryResponse(it.id, it.label, it.current) },
            value.currentCategoryId,
            value.rows.map(::row),
            value.ownedCurrencies.map { OwnedExchangeCurrencyResponse(it.label, it.quantity) },
            value.gradeActions.map { LegacyGradeActionResponse(it.id, it.label, it.consumedItemsPerPress, it.allowsTargetSelection) },
            value.annActions.map { action -> AnnActionGroupResponse(action.type, action.label, action.rows.map(::row)) },
            value.warning,
            value.history,
            value.result?.let(TownActionResultResponse::from),
        )

        private fun row(it: ExchangeRow) = ExchangeRowResponse(
            it.id, it.label, it.selectable, it.detail, it.cost, it.owned, it.minQuantity, it.maxQuantity,
        )
    }
}

data class ExchangeTradeRequest(
    @field:NotBlank val candidateId: String,
    val categoryCandidateId: String? = null,
    @field:Min(1) val quantity: Int,
)
data class LegacyGradeExchangeRequest(@field:NotBlank val gradeActionId: String)
data class AnnActionRequest(
    val action: AnnAction,
    val candidateId: String? = null,
    @field:Min(1) val quantity: Int = 1,
)
