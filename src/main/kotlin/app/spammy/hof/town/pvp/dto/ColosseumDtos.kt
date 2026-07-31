package app.spammy.hof.town.pvp.dto

import app.spammy.hof.town.fishing.dto.TownActionResultResponse
import app.spammy.hof.town.pvp.model.*
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class ColosseumFighterResponse(val id: String, val label: String, val detail: String?, val imageUrl: String?, val selected: Boolean)
data class ColosseumOpponentResponse(val id: String, val label: String, val detail: String?)
data class ColosseumTurnLineResponse(val turn: Int?, val text: String)
data class ColosseumBattleResultResponse(
    val turns: Int?, val winner: String?, val summary: String, val playerHp: String?, val opponentHp: String?,
    val playerStatus: String?, val opponentStatus: String?, val totalDamage: Long?, val reward: String?,
    val detail: List<ColosseumTurnLineResponse>,
)
data class ColosseumBattleResponse(
    val fighters: List<ColosseumFighterResponse>, val selectedTeam: List<String>, val minTeamSize: Int, val maxTeamSize: Int,
    val opponents: List<ColosseumOpponentResponse>, val battleResult: ColosseumBattleResultResponse?, val result: TownActionResultResponse?,
) {
    companion object { fun from(v: ColosseumBattleSnapshot) = ColosseumBattleResponse(
        v.fighters.map { ColosseumFighterResponse(it.id, it.label, it.detail, it.imageUrl, it.selected) }, v.selectedTeam,
        v.minTeamSize, v.maxTeamSize, v.opponents.map { ColosseumOpponentResponse(it.id, it.label, it.detail) },
        v.battleResult?.let { b -> ColosseumBattleResultResponse(b.turns, b.winner, b.summary, b.playerHp, b.opponentHp, b.playerStatus, b.opponentStatus, b.totalDamage, b.reward, b.detail.map { ColosseumTurnLineResponse(it.turn, it.text) }) },
        v.result?.let(TownActionResultResponse::from),
    ) }
}

data class SaveColosseumTeamRequest(@field:Size(min = 1, max = 20) val fighterCandidateIds: List<@NotBlank String>)
data class ChallengeColosseumRequest(@field:NotBlank val opponentCandidateId: String)

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
