package app.spammy.hof.town.shop.dto

import app.spammy.hof.town.fishing.dto.TownActionResultResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

data class ShopItemResponse(
    val id: String,
    val label: String,
    val selectable: Boolean,
    val detail: String?,
    val imageUrl: String? = null,
    val price: Long,
    val quantity: Int? = null,
    val type: String? = null,
)

data class ShopResponse(
    val shopId: String,
    val items: List<ShopItemResponse>,
    val stale: Boolean,
    val lastVerifiedAt: Instant?,
    val result: TownActionResultResponse? = null,
)

data class PurchaseLineRequest(@field:NotBlank val itemId: String, @field:Min(1) val quantity: Int)
data class PurchaseRequest(@field:Valid @field:Size(min = 1, max = 100) val items: List<PurchaseLineRequest>)

data class SellItemResponse(
    val id: String,
    val label: String,
    val selectable: Boolean,
    val detail: String?,
    val imageUrl: String? = null,
    val price: Long,
    val quantity: Int?,
    val type: String? = null,
)
data class SellResponse(val items: List<SellItemResponse>, val result: TownActionResultResponse? = null)
data class SellLineRequest(@field:NotBlank val candidateId: String, @field:Min(1) val quantity: Int)
data class SellRequest(@field:Valid @field:Size(min = 1, max = 100) val items: List<SellLineRequest>)

data class CombineOptionResponse(val id: String, val label: String, val quantity: Int? = null)
data class CombineResponse(
    val primary: List<CombineOptionResponse>,
    val secondarySlots: List<List<CombineOptionResponse>>,
    val result: TownActionResultResponse? = null,
)
data class CombineRequest(
    @field:NotBlank val primaryCandidateId: String,
    @field:Size(min = 3, max = 3) val secondaryCandidateIds: List<@NotBlank String>,
    @field:Min(1) val quantity: Int,
)
