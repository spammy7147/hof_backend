package app.spammy.hof.town.shop.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("hof.shop")
data class ShopCatalogCollectorProperties(
    val collectorAccountId: Long = 0,
    val collectorCron: String = "0 15 4 * * *",
)
