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
    val battleTarget: FishingBattleTarget?,
    val catches: List<FishingCatchItem>,
    val result: ParsedTownResult?,
    val battleObservationComplete: Boolean = true,
)

data class FishingBattleTarget(val categoryId: String, val mapCode: String, val name: String? = null)

data class FishingCatchItem(
    val name: String,
    val quantity: Int,
    val remainingUses: Int?,
    val effect: String?,
)

data class FishingExchangeItem(
    val id: String,
    val name: String,
    val selectable: Boolean,
    val detail: String?,
    val price: Long?,
    val materials: List<String>,
    internal val itemT: String? = null,
)

data class FishingExchangeCategory(val id: String, val label: String, val current: Boolean)

data class FishingExchangeSnapshot(
    val actionId: String?,
    val categories: List<FishingExchangeCategory>,
    val currentCategoryId: String?,
    val items: List<FishingExchangeItem>,
    val result: ParsedTownResult? = null,
)
