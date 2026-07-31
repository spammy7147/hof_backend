package app.spammy.hof.town.home.dto

import app.spammy.hof.town.fishing.dto.TownActionResultResponse
import app.spammy.hof.town.home.model.*
import jakarta.validation.constraints.NotBlank

data class HomeQuestResponse(
    val id: String, val name: String, val state: HomeQuestState, val mission: String?, val reward: String?, val actionId: String?,
)
data class HomeActionResponse(val id: String, val type: HomeActionType, val label: String)
data class HomeResponse(
    val mode: HomeMode,
    val quests: List<HomeQuestResponse>,
    val actions: List<HomeActionResponse>,
    val result: TownActionResultResponse?,
) {
    companion object {
        fun from(value: HomeSnapshot) = HomeResponse(
            value.mode,
            value.quests.map { HomeQuestResponse(it.id, it.name, it.state, it.mission, it.reward, it.actionId) },
            value.actions.map { HomeActionResponse(it.id, it.type, it.label) },
            value.result?.let(TownActionResultResponse::from),
        )
    }
}
data class HomeActionRequest(@field:NotBlank val actionId: String)
