package app.spammy.hof.town.pvp.dto

import app.spammy.hof.town.fishing.dto.TownActionResultResponse
import app.spammy.hof.town.pvp.model.*
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class ColosseumFighterResponse(val id: String, val label: String, val detail: String?, val imageUrl: String?, val selected: Boolean, val characterId: String?)
data class ColosseumRankingResponse(val rank: String, val teams: List<String>)
data class ColosseumBattleTeamResultResponse(val label: String?, val hp: String?, val survivors: String?, val totalDamage: Long?)
data class ColosseumBattleResultResponse(
    val turns: Int?, val winner: String?, val summary: String, val teams: List<ColosseumBattleTeamResultResponse>,
    val reward: String?, val detailUrl: String?,
)
data class ColosseumBattleResponse(
    val fighters: List<ColosseumFighterResponse>, val selectedTeam: List<String>, val minTeamSize: Int, val maxTeamSize: Int,
    val topRankings: List<ColosseumRankingResponse>, val nearRankings: List<ColosseumRankingResponse>,
    val teamSaveAvailable: Boolean, val challengeAvailable: Boolean, val challengeNotice: String?,
    val battleResult: ColosseumBattleResultResponse?, val result: TownActionResultResponse?,
) {
    companion object { fun from(v: ColosseumBattleSnapshot) = ColosseumBattleResponse(
        v.fighters.map { ColosseumFighterResponse(it.id, it.label, it.detail, it.imageUrl, it.selected, it.characterId) }, v.selectedTeam,
        v.minTeamSize, v.maxTeamSize, v.topRankings.map { ColosseumRankingResponse(it.rank, it.teams) },
        v.nearRankings.map { ColosseumRankingResponse(it.rank, it.teams) }, v.teamActionId != null, v.challengeAvailable, v.challengeNotice,
        v.battleResult?.let { b -> ColosseumBattleResultResponse(b.turns, b.winner, b.summary, b.teams.map { ColosseumBattleTeamResultResponse(it.label, it.hp, it.survivors, it.totalDamage) }, b.reward, b.detailUrl) },
        v.result?.let(TownActionResultResponse::from),
    ) }
}

data class SaveColosseumTeamRequest(@field:Size(min = 5, max = 5) val fighterCandidateIds: List<@NotBlank String>)

data class ColosseumShopCategoryResponse(val id: String, val label: String, val current: Boolean)
data class ColosseumCurrencyResponse(val label: String, val quantity: Int?)
data class ColosseumShopRowResponse(val id: String, val label: String, val selectable: Boolean, val detail: String?, val cost: Long?, val owned: Int?)
data class ColosseumShopResponse(
    val categories: List<ColosseumShopCategoryResponse>, val currentCategoryId: String?, val items: List<ColosseumShopRowResponse>,
    val currencies: List<ColosseumCurrencyResponse>, val result: TownActionResultResponse?,
) { companion object { fun from(v: ColosseumShopSnapshot) = ColosseumShopResponse(
    v.categories.map { ColosseumShopCategoryResponse(it.id, it.label, it.current) }, v.currentCategoryId,
    v.items.map { ColosseumShopRowResponse(it.id, it.label, it.selectable, it.detail, it.cost, it.owned) },
    v.currencies.map { ColosseumCurrencyResponse(it.label, it.quantity) }, v.result?.let(TownActionResultResponse::from),
) } }
data class ColosseumTradeRequest(@field:NotBlank val candidateId: String, val categoryCandidateId: String?, @field:jakarta.validation.constraints.Min(1) val quantity: Int)
