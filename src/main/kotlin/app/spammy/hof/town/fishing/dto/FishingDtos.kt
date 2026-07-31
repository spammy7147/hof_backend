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
    val battleLink: String?,
    val result: TownActionResultResponse?,
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
            // 앱에는 HOF URL 대신 전투 탭으로 넘길 typed target만 공개한다.
            battleLink = snapshot.battleLink?.let { "FISHING_BATTLE" },
            result = snapshot.result?.let(TownActionResultResponse::from),
        )
    }
}

data class FishingExchangeResponse(
    val items: List<FishingExchangeItemResponse>,
    val result: TownActionResultResponse?,
) {
    companion object {
        fun from(snapshot: FishingExchangeSnapshot) = FishingExchangeResponse(
            items = snapshot.items.map { item ->
                FishingExchangeItemResponse(item.id, item.name, item.selectable, item.detail)
            },
            result = snapshot.result?.let(TownActionResultResponse::from),
        )
    }
}

data class FishingExchangeItemResponse(
    val id: String,
    val label: String,
    val selectable: Boolean,
    val detail: String?,
)

data class FishingExchangeRequest(
    @field:NotBlank val candidateId: String,
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
