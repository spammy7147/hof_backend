package app.spammy.hof.town.fishing.model

import app.spammy.hof.town.common.model.ParsedTownResult

enum class FishingAction { START, CATCH, STATUS, FILTER }

enum class FishingPrimaryAction { START, CATCH, NONE }

enum class FishingOutcome { STARTED, CAUGHT, ESCAPED, INFORMATIONAL }

data class FishingActionCandidate(
    val action: FishingAction,
    val actionId: String,
)

data class FishingSnapshot(
    val notice: String?,
    val remainingCasts: Int?,
    val waterStatus: String?,
    val baitCount: Int?,
    val shiningBaitCount: Int?,
    val escapeSeconds: Int?,
    val combo: Int?,
    val locationName: String,
    val primaryAction: FishingPrimaryAction,
    val availableActions: List<FishingActionCandidate>,
    val lastOutcome: FishingOutcome?,
    val blockedByBattle: Boolean,
    val battleLink: String?,
    val result: ParsedTownResult?,
)

data class FishingExchangeItem(
    val id: String,
    val name: String,
    val selectable: Boolean,
    val detail: String?,
)

data class FishingExchangeSnapshot(
    val actionId: String?,
    val items: List<FishingExchangeItem>,
    val result: ParsedTownResult? = null,
)
