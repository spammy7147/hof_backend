package app.spammy.hof.town.fishing.dto

import app.spammy.hof.town.common.model.ParsedTownResult
import app.spammy.hof.town.fishing.model.FishingAction
import app.spammy.hof.town.fishing.model.FishingExchangeSnapshot
import app.spammy.hof.town.fishing.model.FishingOutcome
import app.spammy.hof.town.fishing.model.FishingPrimaryAction
import app.spammy.hof.town.fishing.model.FishingSnapshot
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

data class FishingResponse(
    val notice: String?,
    val remainingCasts: Int?,
    val waterStatus: String?,
    val baitCount: Int?,
    val shiningBaitCount: Int?,
    val escapeSeconds: Int?,
    val combo: Int?,
    val locationName: String,
    val primaryAction: FishingPrimaryAction,
    val availableActions: Set<FishingAction>,
    val lastOutcome: FishingOutcome?,
    val blockedByBattle: Boolean,
    val battleTarget: FishingBattleTargetResponse?,
    val catches: List<FishingCatchItemResponse>,
    val result: TownActionResultResponse?,
    val battleObservationComplete: Boolean = true,
) {
    companion object {
        fun from(snapshot: FishingSnapshot) = FishingResponse(
            notice = snapshot.notice,
            remainingCasts = snapshot.remainingCasts,
            waterStatus = snapshot.waterStatus,
            baitCount = snapshot.baitCount,
            shiningBaitCount = snapshot.shiningBaitCount,
            escapeSeconds = snapshot.escapeSeconds,
            combo = snapshot.combo,
            locationName = snapshot.locationName,
            primaryAction = snapshot.primaryAction,
            availableActions = snapshot.availableActions.map { it.action }.toSet(),
            lastOutcome = snapshot.lastOutcome,
            blockedByBattle = snapshot.blockedByBattle,
            battleTarget = snapshot.battleTarget?.let { FishingBattleTargetResponse(it.categoryId, it.mapCode, it.name) },
            catches = snapshot.catches.map { FishingCatchItemResponse(it.name, it.quantity, it.remainingUses, it.effect) },
            result = snapshot.result?.let(TownActionResultResponse::from),
            battleObservationComplete = snapshot.battleObservationComplete,
        )
    }
}

data class FishingBattleTargetResponse(val categoryId: String, val mapCode: String, val name: String?)
data class FishingCatchItemResponse(val name: String, val quantity: Int, val remainingUses: Int?, val effect: String?)

data class FishingExchangeResponse(
    val categories: List<FishingExchangeCategoryResponse>,
    val currentCategoryId: String?,
    val items: List<FishingExchangeItemResponse>,
    val result: TownActionResultResponse?,
) {
    companion object {
        fun from(snapshot: FishingExchangeSnapshot) = FishingExchangeResponse(
            categories = snapshot.categories.map { FishingExchangeCategoryResponse(it.id, it.label, it.current) },
            currentCategoryId = snapshot.currentCategoryId,
            items = snapshot.items.map { item ->
                FishingExchangeItemResponse(
                    id = item.id,
                    label = item.name,
                    selectable = item.selectable,
                    detail = item.detail,
                    price = item.price,
                    materials = item.materials,
                )
            },
            result = snapshot.result?.let(TownActionResultResponse::from),
        )
    }
}

data class FishingExchangeCategoryResponse(val id: String, val label: String, val current: Boolean)

data class FishingExchangeItemResponse(
    val id: String,
    val label: String,
    val selectable: Boolean,
    val detail: String?,
    val imageUrl: String? = null,
    val price: Long?,
    val quantity: Int? = null,
    val materials: List<String>,
)

data class FishingExchangeRequest(
    @field:NotBlank val candidateId: String,
    @field:NotBlank val categoryCandidateId: String,
    @field:Min(1) val quantity: Int = 1,
)

data class TownActionResultResponse(
    val status: String,
    val messages: List<String>,
    val items: List<TownResultItemResponse>,
    val refreshRequired: Boolean = true,
) {
    companion object {
        fun from(result: ParsedTownResult): TownActionResultResponse {
            val text = (result.messages + result.items.map { it.label }).joinToString(" ")
            val status = when {
                Regex("실패|부족|없습니다|불가능|오류").containsMatchIn(text) -> "FAILURE"
                result.messages.isNotEmpty() || result.items.isNotEmpty() -> "SUCCESS"
                else -> "UNKNOWN"
            }
            return TownActionResultResponse(
                status = status,
                messages = result.messages,
                items = result.items.map { TownResultItemResponse(it.label, null, null, null) },
            )
        }
    }
}

data class TownResultItemResponse(
    val name: String,
    val quantity: Int?,
    val imageUrl: String?,
    val detail: String?,
)
