package app.spammy.hof.town.pvp.model

import app.spammy.hof.town.common.model.ParsedTownResult

data class ColosseumFighter(
    val id: String,
    val label: String,
    val detail: String?,
    val imageUrl: String?,
    val selected: Boolean,
    val characterId: String?,
)

data class ColosseumRanking(val rank: String, val teams: List<String>)
data class ColosseumBattleTeamResult(val label: String?, val hp: String?, val survivors: String?, val totalDamage: Long?)

data class ColosseumBattleResult(
    val turns: Int?,
    val winner: String?,
    val summary: String,
    val teams: List<ColosseumBattleTeamResult>,
    val reward: String?,
    val detailUrl: String?,
)

data class ColosseumBattleSnapshot(
    val fighters: List<ColosseumFighter>,
    val selectedTeam: List<String>,
    val minTeamSize: Int,
    val maxTeamSize: Int,
    val topRankings: List<ColosseumRanking>,
    val nearRankings: List<ColosseumRanking>,
    val challengeAvailable: Boolean,
    val challengeNotice: String?,
    val battleResult: ColosseumBattleResult?,
    val result: ParsedTownResult?,
    internal val teamActionId: String?,
    internal val teamCandidateIds: Map<String, String>,
    internal val challengeActionId: String?,
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
