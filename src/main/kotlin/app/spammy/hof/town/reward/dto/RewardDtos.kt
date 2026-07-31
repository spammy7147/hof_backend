package app.spammy.hof.town.reward.dto

import app.spammy.hof.town.fishing.dto.TownActionResultResponse
import app.spammy.hof.town.reward.model.*
import jakarta.validation.constraints.NotBlank

data class StashBoxResponse(
    val id: String,
    val label: String,
    val selectable: Boolean,
    val owned: Int?,
    val cost: Long?,
    val detail: String?,
)
data class StashActionResponse(val action: StashOpenAction, val label: String)
data class StashResponse(
    val boxes: List<StashBoxResponse>,
    val actions: List<StashActionResponse>,
    val result: TownActionResultResponse?,
) {
    companion object {
        fun from(value: StashSnapshot) = StashResponse(
            boxes = value.boxes.map { StashBoxResponse(it.id, it.name, it.selectable, it.owned, it.cost, it.detail) },
            actions = value.actions.map { StashActionResponse(it.action, it.label) },
            result = value.result?.let(TownActionResultResponse::from),
        )
    }
}
data class StashOpenRequest(@field:NotBlank val boxCandidateId: String, val action: StashOpenAction)

data class OrbCountsResponse(val red: Int?, val blue: Int?, val green: Int?)
data class OrbActionResponse(val action: OrbExchangeAction, val label: String, val repetitions: Int)
data class OrbRewardResponse(val key: String, val label: String, val remaining: Int?, val unlimited: Boolean)
data class OrbOutcomeResponse(val text: String, val quantity: Int, val success: Boolean, val inferred: Boolean)
data class OrbExchangeResponse(
    val displayedOrbs: OrbCountsResponse,
    val orbCountsEstimated: Boolean,
    val remainingRewards: Int?,
    val rewardMonth: String?,
    val rewards: List<OrbRewardResponse>,
    val actions: List<OrbActionResponse>,
    val outcomes: List<OrbOutcomeResponse>,
    val lastAction: OrbExchangeAction?,
    val result: TownActionResultResponse?,
) {
    companion object {
        fun from(value: OrbExchangeSnapshot) = OrbExchangeResponse(
            displayedOrbs = OrbCountsResponse(value.displayedOrbs.red, value.displayedOrbs.blue, value.displayedOrbs.green),
            orbCountsEstimated = value.orbCountsEstimated,
            remainingRewards = value.remainingRewards,
            rewardMonth = value.rewardMonth,
            rewards = value.rewards.map { OrbRewardResponse(it.key, it.name, it.remaining, it.remaining == null) },
            actions = value.actions.map { OrbActionResponse(it.action, it.label, it.action.repetitions) },
            outcomes = value.outcomes.map { OrbOutcomeResponse(it.text, it.quantity, it.success, it.inferred) },
            lastAction = value.lastAction,
            result = value.result?.let { parsed ->
                TownActionResultResponse.from(parsed).copy(status = when {
                    value.outcomes.any { it.success } && value.outcomes.any { !it.success } -> "INFORMATIONAL"
                    value.outcomes.any { it.success } -> "SUCCESS"
                    value.outcomes.any { !it.success } -> "FAILURE"
                    else -> TownActionResultResponse.from(parsed).status
                })
            },
        )
    }
}
data class OrbExchangeRequest(val action: OrbExchangeAction)
