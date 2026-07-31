package app.spammy.hof.town.pvp.model

import app.spammy.hof.town.common.model.ParsedTownResult

data class ColosseumFighter(
    val id: String,
    val label: String,
    val detail: String?,
    val imageUrl: String?,
    val selected: Boolean,
)

data class ColosseumOpponent(
    val id: String,
    val label: String,
    val detail: String?,
)

data class ColosseumTurnLine(val turn: Int?, val text: String)

data class ColosseumBattleResult(
    val turns: Int?,
    val winner: String?,
    val summary: String,
    val playerHp: String?,
    val opponentHp: String?,
    val playerStatus: String?,
    val opponentStatus: String?,
    val totalDamage: Long?,
    val reward: String?,
    val detail: List<ColosseumTurnLine>,
)

data class ColosseumBattleSnapshot(
    val fighters: List<ColosseumFighter>,
    val selectedTeam: List<String>,
    val minTeamSize: Int,
    val maxTeamSize: Int,
    val opponents: List<ColosseumOpponent>,
    val battleResult: ColosseumBattleResult?,
    val result: ParsedTownResult?,
    internal val teamActionId: String?,
    internal val challengeActionIds: Map<String, String>,
)

data class ColosseumShopCategory(val id: String, val label: String, val current: Boolean)
data class ColosseumCurrency(val label: String, val quantity: Int?)
data class ColosseumShopRow(
    val id: String,
    val label: String,
    val selectable: Boolean,
    val detail: String?,
    val cost: Long?,
    val owned: Int?,
    internal val minQuantity: Int = 1,
    internal val maxQuantity: Int? = null,
    internal val itemT: String? = null,
)

data class ColosseumShopSnapshot(
    val categories: List<ColosseumShopCategory>,
    val currentCategoryId: String?,
    val items: List<ColosseumShopRow>,
    val currencies: List<ColosseumCurrency>,
    val result: ParsedTownResult?,
    internal val actionId: String?,
    internal val categoryField: String?,
)
