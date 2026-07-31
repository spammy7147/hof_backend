package app.spammy.hof.town.shop.repository

import app.spammy.hof.town.shop.entity.QShopCatalogItemEntity.shopCatalogItemEntity
import app.spammy.hof.town.shop.entity.QTownGlobalJobLeaseEntity.townGlobalJobLeaseEntity
import app.spammy.hof.town.shop.entity.ShopCatalogItemEntity
import app.spammy.hof.town.shop.entity.TownGlobalJobLeaseEntity
import com.querydsl.jpa.impl.JPAQueryFactory
import java.time.Instant
import org.springframework.stereotype.Repository

@Repository
class ShopQueryRepository(private val queryFactory: JPAQueryFactory) {
    fun findActiveItems(shopId: String): List<ShopCatalogItemEntity> = queryFactory
        .selectFrom(shopCatalogItemEntity)
        .where(shopCatalogItemEntity.id.shopId.eq(shopId), shopCatalogItemEntity.active.isTrue)
        .orderBy(shopCatalogItemEntity.name.asc(), shopCatalogItemEntity.id.itemKey.asc())
        .fetch()

    fun findActiveItem(shopId: String, itemKey: String): ShopCatalogItemEntity? = queryFactory
        .selectFrom(shopCatalogItemEntity)
        .where(
            shopCatalogItemEntity.id.shopId.eq(shopId),
            shopCatalogItemEntity.id.itemKey.eq(itemKey),
            shopCatalogItemEntity.active.isTrue,
        )
        .fetchOne()

    fun findLease(jobKey: String): TownGlobalJobLeaseEntity? = queryFactory
        .selectFrom(townGlobalJobLeaseEntity)
        .where(townGlobalJobLeaseEntity.jobKey.eq(jobKey))
        .fetchOne()

    fun deactivateAll(shopId: String): Long = queryFactory.update(shopCatalogItemEntity)
        .set(shopCatalogItemEntity.active, false)
        .where(shopCatalogItemEntity.id.shopId.eq(shopId))
        .execute()

    fun tryAcquire(
        jobKey: String,
        owner: String,
        now: Instant,
        leaseUntil: Instant,
        freshAfter: Instant,
        retryAfter: Instant,
    ): Long =
        queryFactory.update(townGlobalJobLeaseEntity)
            .set(townGlobalJobLeaseEntity.leaseOwner, owner)
            .set(townGlobalJobLeaseEntity.leaseUntil, leaseUntil)
            .set(townGlobalJobLeaseEntity.lastAttemptAt, now)
            .where(
                townGlobalJobLeaseEntity.jobKey.eq(jobKey),
                townGlobalJobLeaseEntity.leaseUntil.isNull.or(townGlobalJobLeaseEntity.leaseUntil.lt(now)),
                townGlobalJobLeaseEntity.lastSuccessAt.isNull.or(townGlobalJobLeaseEntity.lastSuccessAt.lt(freshAfter)),
                townGlobalJobLeaseEntity.lastAttemptAt.isNull.or(townGlobalJobLeaseEntity.lastAttemptAt.lt(retryAfter)),
            )
            .execute()

    fun markSuccess(jobKey: String, owner: String, now: Instant): Long = queryFactory.update(townGlobalJobLeaseEntity)
        .setNull(townGlobalJobLeaseEntity.leaseOwner)
        .setNull(townGlobalJobLeaseEntity.leaseUntil)
        .set(townGlobalJobLeaseEntity.lastSuccessAt, now)
        .where(townGlobalJobLeaseEntity.jobKey.eq(jobKey), townGlobalJobLeaseEntity.leaseOwner.eq(owner))
        .execute()

    fun releaseFailure(jobKey: String, owner: String): Long = queryFactory.update(townGlobalJobLeaseEntity)
        .setNull(townGlobalJobLeaseEntity.leaseOwner)
        .setNull(townGlobalJobLeaseEntity.leaseUntil)
        .where(townGlobalJobLeaseEntity.jobKey.eq(jobKey), townGlobalJobLeaseEntity.leaseOwner.eq(owner))
        .execute()

    fun releaseAuthenticationFailure(jobKey: String, owner: String): Long = queryFactory.update(townGlobalJobLeaseEntity)
        .setNull(townGlobalJobLeaseEntity.leaseOwner)
        .setNull(townGlobalJobLeaseEntity.leaseUntil)
        .setNull(townGlobalJobLeaseEntity.lastAttemptAt)
        .where(townGlobalJobLeaseEntity.jobKey.eq(jobKey), townGlobalJobLeaseEntity.leaseOwner.eq(owner))
        .execute()
}
