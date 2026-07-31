package app.spammy.hof.town.auction.repository

import app.spammy.hof.common.persistence.CommandRepository
import app.spammy.hof.town.auction.entity.AuctionObservationEntity
import app.spammy.hof.town.auction.entity.QAuctionObservationEntity.auctionObservationEntity
import com.querydsl.jpa.impl.JPAQueryFactory
import java.time.Instant
import org.springframework.stereotype.Repository

interface AuctionObservationRepository : CommandRepository<AuctionObservationEntity, String>

@Repository
class AuctionQueryRepository(private val queryFactory: JPAQueryFactory) {
    fun findByKey(key: String): AuctionObservationEntity? = queryFactory.selectFrom(auctionObservationEntity)
        .where(auctionObservationEntity.observationKey.eq(key)).fetchOne()

    fun findRecent(query: String?, since: Instant, limit: Long = 500): List<AuctionObservationEntity> {
        val normalized = query?.trim()?.takeIf(String::isNotEmpty)
        return queryFactory.selectFrom(auctionObservationEntity)
            .where(
                auctionObservationEntity.observedAt.goe(since),
                normalized?.let { auctionObservationEntity.itemName.containsIgnoreCase(it) },
            )
            .orderBy(auctionObservationEntity.observedAt.desc())
            .limit(limit)
            .fetch()
    }
}
