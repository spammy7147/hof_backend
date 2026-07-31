package app.spammy.hof.town.shop.service

import app.spammy.hof.town.shop.catalog.ParsedShopItem
import app.spammy.hof.town.shop.catalog.ShopId
import app.spammy.hof.town.shop.entity.ShopCatalogItemEntity
import app.spammy.hof.town.shop.entity.ShopCatalogItemId
import app.spammy.hof.town.shop.repository.ShopCatalogItemRepository
import app.spammy.hof.town.shop.repository.ShopQueryRepository
import java.time.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** 네트워크 요청과 분리된 짧은 트랜잭션으로 lease와 카탈로그 snapshot을 원자적으로 저장한다. */
@Service
class ShopCatalogPersistenceService(
    private val catalogRepository: ShopCatalogItemRepository,
    private val queryRepository: ShopQueryRepository,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun tryAcquire(jobKey: String, owner: String, now: Instant, leaseUntil: Instant, freshAfter: Instant, retryAfter: Instant): Boolean =
        queryRepository.tryAcquire(jobKey, owner, now, leaseUntil, freshAfter, retryAfter) == 1L

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun replaceAndMarkSuccess(shopId: ShopId, jobKey: String, owner: String, now: Instant, items: List<ParsedShopItem>) {
        queryRepository.deactivateAll(shopId.name)
        catalogRepository.saveAll(items.map { item ->
            ShopCatalogItemEntity(
                id = ShopCatalogItemId(shopId.name, item.itemKey),
                name = item.name,
                itemType = item.type,
                description = item.description,
                price = item.price,
                active = true,
                lastSeenAt = now,
            )
        })
        check(queryRepository.markSuccess(jobKey, owner, now) == 1L) { "상점 카탈로그 lease 소유권이 만료되었습니다." }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun releaseFailure(jobKey: String, owner: String) {
        queryRepository.releaseFailure(jobKey, owner)
    }
}
