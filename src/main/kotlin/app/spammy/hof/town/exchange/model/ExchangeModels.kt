package app.spammy.hof.town.exchange.model

import app.spammy.hof.town.common.model.ParsedTownResult

enum class ExchangeMode { EMBLEM, EVENT, LEGACY, ANN }
enum class AnnAction { MODIFY_ITEM, GIVE_GIFT }

data class ExchangeCategory(val id: String, val label: String, val current: Boolean)

data class ExchangeRow(
    val id: String,
    val label: String,
    val selectable: Boolean,
    val detail: String?,
    val cost: Long?,
    val owned: Int?,
    val minQuantity: Int,
    val maxQuantity: Int?,
    internal val itemT: String? = null,
)

data class OwnedExchangeCurrency(val label: String, val quantity: Long?)

data class LegacyGradeAction(
    val id: String,
    val label: String,
    val consumedItemsPerPress: Int = 1,
    val allowsTargetSelection: Boolean = false,
)

data class AnnActionGroup(
    val type: AnnAction,
    val label: String,
    val rows: List<ExchangeRow>,
    internal val actionId: String,
)

data class ExchangeSnapshot(
    val mode: ExchangeMode,
    val categories: List<ExchangeCategory>,
    val currentCategoryId: String?,
    val rows: List<ExchangeRow>,
    val ownedCurrencies: List<OwnedExchangeCurrency>,
    val gradeActions: List<LegacyGradeAction>,
    val annActions: List<AnnActionGroup>,
    val warning: String?,
    val history: List<String>,
    val result: ParsedTownResult?,
    internal val tradeActionId: String?,
    internal val categoryField: String?,
)

const val LEGACY_AUTOMATIC_TARGET_WARNING =
    "HOF가 +10 레거시 장비 중 1개를 자동 선택합니다. 앱에서는 대상을 지정할 수 없습니다."
