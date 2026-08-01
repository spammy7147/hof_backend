package app.spammy.hof.town.shop.service

import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.external.model.HofRequestOrigin
import app.spammy.hof.town.shop.catalog.ShopId
import app.spammy.hof.town.shop.config.ShopCatalogCollectorProperties
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

/** 사용자 상점 진입과 분리된 전용 계정으로 서버 공용 카탈로그를 하루 한 번 확인한다. */
@Service
class ShopCatalogCollector(
    private val properties: ShopCatalogCollectorProperties,
    private val refresh: ShopCatalogRefreshService,
    private val recovery: HofSessionRecoveryService,
) {
    @Scheduled(cron = "\${hof.shop.collector-cron:0 15 4 * * *}")
    fun dailyTick() {
        val accountId = properties.collectorAccountId
        if (accountId <= 0) return
        ShopId.entries.forEach { shopId ->
            runCatching {
                recovery.execute(accountId, HofRequestOrigin.AUTOMATION) {
                    refresh.refreshIfDue(accountId, shopId, HofRequestOrigin.AUTOMATION)
                }
            }.onFailure { failure ->
                logger.warn("Daily {} catalog refresh failed; retaining last-good snapshot: {}", shopId, failure.javaClass.simpleName)
            }
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(ShopCatalogCollector::class.java)
    }
}
