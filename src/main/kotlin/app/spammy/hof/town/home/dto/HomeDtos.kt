package app.spammy.hof.town.home.dto

import app.spammy.hof.town.fishing.dto.TownActionResultResponse
import app.spammy.hof.town.home.model.*
import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class HomeQuestResponse(
    val id: String,
    val name: String,
    val state: HomeQuestState,
    val mission: String?,
    val reward: String?,
    val details: List<String>,
    val actionId: String?,
    @get:JsonIgnore
    val stateObserved: Boolean = false,
)
data class HomeActionResponse(val id: String, val type: HomeActionType, val label: String)
data class RestStatusResponse(
    val currentTime: Int?,
    val maxTime: Int?,
    val baseRecovery: Int?,
    val facilityRecovery: Int?,
    val usedToday: Boolean?,
    val facilities: List<String>,
) {
    companion object {
        fun from(value: RestStatus) = RestStatusResponse(
            value.currentTime,
            value.maxTime,
            value.baseRecovery,
            value.facilityRecovery,
            value.usedToday,
            value.facilities,
        )
    }
}
data class HomeResponse(
    val mode: HomeMode,
    val quests: List<HomeQuestResponse>,
    val actions: List<HomeActionResponse>,
    val restStatus: RestStatusResponse?,
    val result: TownActionResultResponse?,
) {
    companion object {
        fun from(value: HomeSnapshot) = HomeResponse(
            value.mode,
            value.quests.map { HomeQuestResponse(it.id, it.name, it.state, it.mission, it.reward, it.details, it.actionId, it.stateObserved) },
            value.actions.map { HomeActionResponse(it.id, it.type, it.label) },
            value.restStatus?.let(RestStatusResponse::from),
            value.result?.let(TownActionResultResponse::from),
        )
    }
}
data class HomeActionRequest(
    @field:NotBlank
    @field:Size(max = 64)
    val actionId: String,
)
