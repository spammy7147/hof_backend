package app.spammy.hof.town.reward.model

import app.spammy.hof.town.common.model.ParsedTownResult

enum class StashOpenAction(val drawCount: Int?) { ONE(1), TWENTY(20), HUNDRED(100), THOUSAND(1000), ALL(null) }
data class StashActionCandidate(val action: StashOpenAction, val label: String, internal val actionId: String)
data class StashBox(
    val id: String,
    val name: String,
    val selectable: Boolean,
    val owned: Int?,
    val cost: Long?,
    val detail: String?,
)
data class StashSnapshot(
    val boxes: List<StashBox>,
    val actions: List<StashActionCandidate>,
    val result: StashOpenResult? = null,
)
data class StashReward(val name: String, val quantity: Int, val detail: String?)
data class StashOpenResult(val rewards: List<StashReward>, val failures: List<String> = emptyList())

enum class OrbExchangeAction(val repetitions: Int) { ONE(1), FIVE(5) }
data class OrbActionCandidate(val action: OrbExchangeAction, val label: String, internal val actionId: String)
data class OrbCounts(val red: Int?, val blue: Int?, val green: Int?) {
    fun deduct(successfulDraws: Int): OrbCounts {
        val cost = successfulDraws.coerceAtLeast(0) * ORBS_PER_DRAW
        return OrbCounts(red?.minus(cost)?.coerceAtLeast(0), blue?.minus(cost)?.coerceAtLeast(0), green?.minus(cost)?.coerceAtLeast(0))
    }

    private companion object { const val ORBS_PER_DRAW = 1_000 }
}
data class OrbLimitedReward(val key: String, val name: String, val remaining: Int?)
data class OrbExchangeOutcome(
    val text: String,
    val quantity: Int,
    val success: Boolean,
    val inferred: Boolean,
)
data class OrbExchangeSnapshot(
    val displayedOrbs: OrbCounts,
    val orbCountsEstimated: Boolean,
    val remainingRewards: Int?,
    val rewardMonth: String?,
    val rewards: List<OrbLimitedReward>,
    val actions: List<OrbActionCandidate>,
    val outcomes: List<OrbExchangeOutcome> = emptyList(),
    val lastAction: OrbExchangeAction? = null,
    val result: ParsedTownResult? = null,
)
