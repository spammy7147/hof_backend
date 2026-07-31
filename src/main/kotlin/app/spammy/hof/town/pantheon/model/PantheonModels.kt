package app.spammy.hof.town.pantheon.model

import app.spammy.hof.external.model.HofFormField
import app.spammy.hof.town.common.model.ParsedTownResult
import com.fasterxml.jackson.annotation.JsonIgnore

enum class ShrineAction {
    CHECK_DOCTRINE,
    BUY_PRIEST_ITEM,
    DONATE_FIXED,
    DONATE_PERCENT,
    DONATE_ITEM,
}

data class PantheonShrine(
    val id: String,
    val name: String,
    val alias: String?,
    val color: String?,
    val imageUrl: String?,
    @get:JsonIgnore internal val detailUrl: String,
)

data class PantheonAction(
    val id: String,
    val type: ShrineAction,
    val label: String,
    val costFunds: Long? = null,
    val fundsPercent: Int? = null,
    val itemName: String? = null,
    val itemQuantity: Int? = null,
    @get:JsonIgnore internal val formActionId: String? = null,
    @get:JsonIgnore internal val query: List<HofFormField>? = null,
)

data class PantheonStreetSnapshot(
    val shrines: List<PantheonShrine>,
)

data class PantheonDetailSnapshot(
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
    val actions: List<PantheonAction>,
    val result: ParsedTownResult? = null,
)
