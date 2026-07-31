package app.spammy.hof.town.card.dto

import app.spammy.hof.town.card.model.*
import app.spammy.hof.town.fishing.dto.TownActionResultResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty

data class CardItemResponse(
    val id: String, val label: String, val selectable: Boolean, val owned: Int?, val rarity: String?,
    val restrictions: List<String>, val detail: String?, val cost: Long?, val blankCardValue: Int? = null,
    val maxQuantity: Int? = null,
) { companion object { fun from(value: CardCandidate) = CardItemResponse(value.id, value.label, value.selectable, value.owned, value.rarity, value.restrictions, value.description, value.cost, value.blankCardValue, value.maxQuantity) } }

data class CardSelectionSlotResponse(val id: String, val label: String)

data class CardIdentifyResponse(val selectionSlots: Int, val cards: List<CardItemResponse>, val result: TownActionResultResponse?) {
    companion object { fun from(value: CardIdentifySnapshot) = CardIdentifyResponse(value.selectionSlots, value.cards.map(CardItemResponse::from), value.result?.let(TownActionResultResponse::from)) }
}
data class CardIdentifyRequest(@field:NotBlank val candidateId: String)

data class CardUpgradeResponse(
    val selectionSlots: List<CardSelectionSlotResponse>, val baseCards: List<CardItemResponse>,
    val materialCards: List<CardItemResponse>, val minQuantity: Int, val maxQuantity: Int,
    val history: List<String>, val result: TownActionResultResponse?,
) { companion object { fun from(value: CardUpgradeSnapshot) = CardUpgradeResponse(value.selectionSlots.map { CardSelectionSlotResponse(it.id, it.label) }, value.baseCards.map(CardItemResponse::from), value.materialCards.map(CardItemResponse::from), value.minQuantity, value.maxQuantity, value.history, value.result?.let(TownActionResultResponse::from)) } }
data class CardUpgradeRequest(@field:NotBlank val baseCandidateId: String, @field:NotBlank val materialCandidateId: String, @field:Min(1) val quantity: Int)

data class CardChangeResponse(
    val selectionSlots: List<CardSelectionSlotResponse>, val baseCards: List<CardItemResponse>,
    val materialCards: List<CardItemResponse>, val minQuantity: Int, val maxQuantity: Int,
    val history: List<String>, val result: TownActionResultResponse?,
) { companion object { fun from(value: CardChangeSnapshot) = CardChangeResponse(value.selectionSlots.map { CardSelectionSlotResponse(it.id, it.label) }, value.baseCards.map(CardItemResponse::from), value.materialCards.map(CardItemResponse::from), value.minQuantity, value.maxQuantity, value.history, value.result?.let(TownActionResultResponse::from)) } }
data class CardChangeRequest(@field:NotBlank val baseCandidateId: String, @field:NotBlank val materialCandidateId: String, @field:Min(1) @field:Max(10) val quantity: Int)

data class CardSellResponse(
    val cards: List<CardItemResponse>, val multiSelect: Boolean, val rewardKind: CardRewardKind,
    val blankCardsOwned: Int?, val result: TownActionResultResponse?,
) { companion object { fun from(value: CardSellSnapshot) = CardSellResponse(value.cards.map(CardItemResponse::from), value.multiSelect, value.rewardKind, value.blankCardsOwned, value.result?.let(TownActionResultResponse::from)) } }
data class CardSellLineRequest(@field:NotBlank val candidateId: String, @field:Min(1) val quantity: Int)
data class CardSellRequest(@field:NotEmpty @field:Valid val cards: List<CardSellLineRequest>)

data class SoulEchoRecipeResponse(
    val id: String, val label: String, val selectable: Boolean, val category: String?,
    val requiredEchoes: List<String>, val cost: Long?, val successBonus: Int?,
)
data class SoulEchoOwnedResponse(val name: String, val region: String?, val quantity: Int)
data class SoulEchoHistoryResponse(val text: String, val success: Boolean)
data class SoulEchoCategoryResponse(val id: String, val label: String)
data class SoulEchoResponse(
    val categories: List<SoulEchoCategoryResponse>, val recipes: List<SoulEchoRecipeResponse>,
    val ownedEchoes: List<SoulEchoOwnedResponse>, val history: List<SoulEchoHistoryResponse>,
    val result: TownActionResultResponse?,
) { companion object { fun from(value: SoulEchoSnapshot) = SoulEchoResponse(
    value.categories.map { SoulEchoCategoryResponse(it.id, it.label) },
    value.recipes.map { SoulEchoRecipeResponse(it.id, it.label, it.selectable, it.category, it.requiredEchoes, it.cost, it.successBonus) },
    value.ownedEchoes.map { SoulEchoOwnedResponse(it.name, it.region, it.quantity) },
    value.history.map { SoulEchoHistoryResponse(it.text, it.success) }, value.result?.let(TownActionResultResponse::from),
) } }
data class SoulEchoFuseRequest(@field:NotBlank val recipeCandidateId: String, @field:NotBlank val categoryCandidateId: String)
