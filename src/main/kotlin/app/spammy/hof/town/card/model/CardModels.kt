package app.spammy.hof.town.card.model

import app.spammy.hof.town.common.model.ParsedTownResult

data class CardCandidate(
    val id: String,
    val label: String,
    val selectable: Boolean,
    val fieldName: String?,
    val owned: Int?,
    val rarity: String?,
    val restrictions: List<String>,
    val description: String?,
    val cost: Long?,
    val blankCardValue: Int? = null,
    val maxQuantity: Int? = null,
    internal val sourceKey: String? = null,
)

data class CardSelectionSlot(val id: String, val label: String, val fieldName: String)
enum class CardRewardKind { BLANK_CARD }

data class CardIdentifySnapshot(
    val actionId: String?, val selectionSlots: Int, val cards: List<CardCandidate>,
    val result: ParsedTownResult? = null,
)

data class CardUpgradeSnapshot(
    val actionId: String?, val selectionSlots: List<CardSelectionSlot>,
    val baseCards: List<CardCandidate>, val materialCards: List<CardCandidate>,
    val minQuantity: Int, val maxQuantity: Int, val history: List<String>,
    val result: ParsedTownResult? = null,
)

data class CardChangeSnapshot(
    val actionId: String?, val selectionSlots: List<CardSelectionSlot>,
    val baseCards: List<CardCandidate>, val materialCards: List<CardCandidate>,
    val minQuantity: Int, val maxQuantity: Int, val history: List<String>,
    val result: ParsedTownResult? = null,
)

data class CardSellSnapshot(
    val actionId: String?, val cards: List<CardCandidate>, val multiSelect: Boolean,
    val rewardKind: CardRewardKind, val blankCardsOwned: Int?,
    val result: ParsedTownResult? = null,
)

data class SoulEchoRecipe(
    val id: String,
    val label: String,
    val selectable: Boolean,
    val category: String?,
    val requiredEchoes: List<String>,
    val cost: Long?,
    val successBonus: Int?,
)

data class OwnedSoulEcho(val name: String, val region: String?, val quantity: Int)
data class SoulEchoHistory(val text: String, val success: Boolean)
data class SoulEchoCategory(val id: String, val label: String)
data class SoulEchoSnapshot(
    val actionId: String?, val categories: List<SoulEchoCategory>, val recipes: List<SoulEchoRecipe>,
    val ownedEchoes: List<OwnedSoulEcho>, val history: List<SoulEchoHistory>,
    val result: ParsedTownResult? = null,
)
