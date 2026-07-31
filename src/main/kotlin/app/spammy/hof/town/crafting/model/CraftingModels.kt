package app.spammy.hof.town.crafting.model

import app.spammy.hof.town.common.model.ParsedTownResult

enum class CraftingMode { WORKBASE, CLARIS, REFINE, CREATE, VETERAN }

data class CraftingCategory(val id: String, val label: String, val current: Boolean)

data class CraftingRow(
    val id: String,
    val label: String,
    val selectable: Boolean,
    val detail: String?,
    val cost: Long?,
    val owned: Int?,
    val workSeconds: Int?,
    internal val itemT: String? = null,
)

data class ActiveCraftingJob(
    val label: String,
    val remainingSeconds: Int?,
    val completionAvailable: Boolean,
)

data class AdditionalMaterial(
    val id: String,
    val label: String,
    val selectable: Boolean,
    val owned: Int?,
    val detail: String?,
)

data class CraftingSnapshot(
    val mode: CraftingMode,
    val categories: List<CraftingCategory>,
    val currentCategoryId: String?,
    val rows: List<CraftingRow>,
    val minQuantity: Int,
    val maxQuantity: Int,
    val activeJob: ActiveCraftingJob?,
    val allowedRefineCounts: List<Int>,
    val additionalMaterials: List<AdditionalMaterial>,
    val additionalMaterialsOptional: Boolean,
    val warningCode: String?,
    val history: List<String>,
    val result: ParsedTownResult?,
    internal val actionId: String?,
    internal val completionActionId: String?,
    internal val categoryCandidateId: String?,
    internal val refineCountCandidateIds: Map<Int, String>,
    internal val timesADefaultCandidateId: String?,
)

const val NO_ADDITIONAL_MATERIAL = "NO_ADDITIONAL_MATERIAL"
