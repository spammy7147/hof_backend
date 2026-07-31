package app.spammy.hof.town.crafting.dto

import app.spammy.hof.town.crafting.model.*
import app.spammy.hof.town.fishing.dto.TownActionResultResponse
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

data class CraftingCategoryResponse(val id: String, val label: String, val current: Boolean)
data class CraftingRowResponse(
    val id: String, val label: String, val selectable: Boolean, val detail: String?, val cost: Long?,
    val owned: Int?, val workSeconds: Int?,
)
data class ActiveCraftingJobResponse(val label: String, val remainingSeconds: Int?, val completionAvailable: Boolean)
data class AdditionalMaterialResponse(val id: String, val label: String, val selectable: Boolean, val owned: Int?, val detail: String?)
data class CraftingResponse(
    val mode: CraftingMode,
    val categories: List<CraftingCategoryResponse>,
    val currentCategoryId: String?,
    val rows: List<CraftingRowResponse>,
    val minQuantity: Int,
    val maxQuantity: Int,
    val activeJob: ActiveCraftingJobResponse?,
    val allowedRefineCounts: List<Int>,
    val additionalMaterials: List<AdditionalMaterialResponse>,
    val additionalMaterialsOptional: Boolean,
    val warningCode: String?,
    val history: List<String>,
    val result: TownActionResultResponse?,
) {
    companion object {
        fun from(value: CraftingSnapshot) = CraftingResponse(
            value.mode,
            value.categories.map { CraftingCategoryResponse(it.id, it.label, it.current) },
            value.currentCategoryId,
            value.rows.map { CraftingRowResponse(it.id, it.label, it.selectable, it.detail, it.cost, it.owned, it.workSeconds) },
            value.minQuantity,
            value.maxQuantity,
            value.activeJob?.let { ActiveCraftingJobResponse(it.label, it.remainingSeconds, it.completionAvailable) },
            value.allowedRefineCounts,
            value.additionalMaterials.map { AdditionalMaterialResponse(it.id, it.label, it.selectable, it.owned, it.detail) },
            value.additionalMaterialsOptional,
            value.warningCode,
            value.history,
            value.result?.let(TownActionResultResponse::from),
        )
    }
}

data class WorkbaseStartRequest(
    @field:NotBlank val candidateId: String,
    @field:NotBlank val categoryCandidateId: String,
    @field:Min(1) @field:Max(10) val quantity: Int,
)
data class ClarisCraftRequest(
    @field:NotBlank val candidateId: String,
    @field:NotBlank val categoryCandidateId: String,
    @field:Min(1) val quantity: Int,
)
data class RefineRequest(
    @field:NotBlank val candidateId: String,
    @field:NotBlank val categoryCandidateId: String,
    @field:Min(1) val refineCount: Int,
)
data class CreateCraftRequest(
    @field:NotBlank val recipeCandidateId: String,
    @field:NotBlank val categoryCandidateId: String,
    @field:Min(1) @field:Max(100) val quantity: Int,
    val additionalMaterialCandidateId: String? = null,
)
