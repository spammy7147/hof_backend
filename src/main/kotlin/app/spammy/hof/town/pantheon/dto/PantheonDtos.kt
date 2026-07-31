package app.spammy.hof.town.pantheon.dto

import app.spammy.hof.town.fishing.dto.TownActionResultResponse
import app.spammy.hof.town.pantheon.model.*
import jakarta.validation.constraints.Pattern

data class PantheonShrineResponse(
    val id: String,
    val name: String,
    val alias: String?,
    val color: String?,
    val imageUrl: String?,
)

data class PantheonStreetResponse(val shrines: List<PantheonShrineResponse>) {
    companion object {
        fun from(value: PantheonStreetSnapshot) = PantheonStreetResponse(value.shrines.map {
            PantheonShrineResponse(it.id, it.name, it.alias, it.color, it.imageUrl)
        })
    }
}

data class PantheonActionResponse(
    val id: String,
    val type: ShrineAction,
    val label: String,
    val costFunds: Long?,
    val fundsPercent: Int?,
    val itemName: String?,
    val itemQuantity: Int?,
)

data class PantheonDetailResponse(
    val shrineId: String,
    val name: String,
    val alias: String?,
    val description: String?,
    val imageUrl: String?,
    val deity: String?,
    val alignment: String?,
    val domains: List<String>,
    val relation: String?,
    val currentJob: String?,
    val actions: List<PantheonActionResponse>,
    val result: TownActionResultResponse?,
) {
    companion object {
        fun from(value: PantheonDetailSnapshot) = PantheonDetailResponse(
            value.shrineId, value.name, value.alias, value.description, value.imageUrl, value.deity,
            value.alignment, value.domains, value.relation, value.currentJob,
            value.actions.map { PantheonActionResponse(it.id, it.type, it.label, it.costFunds, it.fundsPercent, it.itemName, it.itemQuantity) },
            value.result?.let(TownActionResultResponse::from),
        )
    }
}

data class PantheonActionRequest(
    @field:Pattern(regexp = "[0-9a-f]{32}") val actionId: String,
)
