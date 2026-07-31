package app.spammy.hof.town.auction.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("hof.auction")
data class AuctionCollectorProperties(
    val collectorAccountId: Long = 0,
    val collectorCron: String = "0 0 * * * *",
)
