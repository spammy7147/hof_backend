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
    val actions: List<PantheonActionResponse>,
)

data class PantheonStreetResponse(val shrines: List<PantheonShrineResponse>) {
    companion object {
        fun from(value: PantheonStreetSnapshot, actions: Map<String, List<PantheonAction>>) = PantheonStreetResponse(value.shrines.map {
            PantheonShrineResponse(it.id, it.name, it.alias, it.color, it.imageUrl, actions[it.id].orEmpty().map(PantheonActionResponse::from))
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
) {
    companion object {
        fun from(value: PantheonAction) = PantheonActionResponse(
            value.id, value.type, value.label, value.costFunds, value.fundsPercent, value.itemName, value.itemQuantity,
        )
    }
}

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
            value.actions.map(PantheonActionResponse::from),
            value.result?.let(TownActionResultResponse::from),
        )
    }
}

data class PantheonActionRequest(
    @field:Pattern(regexp = "[0-9a-f]{32}") val actionId: String,
)
