package app.spammy.hof.town.shop.catalog

import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode

enum class ShopId(val pathValue: String, val menuCode: String) {
    GENERAL("general", "buy"),
    SUNDRIES("sundries", "buy2"),
    DARK("dark", "sbuy");

    companion object {
        fun fromPath(value: String): ShopId = entries.singleOrNull { it.pathValue == value.lowercase() }
            ?: throw ApiException(ErrorCode.INVALID_REQUEST, "지원하지 않는 상점입니다.")
    }
}

data class ParsedShopItem(
    val itemKey: String,
    val name: String,
    val type: String?,
    val description: String?,
    val price: Long,
)
