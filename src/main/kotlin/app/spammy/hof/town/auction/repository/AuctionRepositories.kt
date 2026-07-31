package app.spammy.hof.town.auction.repository

import app.spammy.hof.common.persistence.CommandRepository
import app.spammy.hof.town.auction.entity.QAuctionObservationEntity.auctionObservationEntity
import com.querydsl.jpa.impl.JPAQueryFactory
import java.time.Instant
import org.springframework.stereotype.Repository
import org.springframework.jdbc.core.JdbcTemplate
import app.spammy.hof.town.auction.entity.AuctionObservationEntity

interface AuctionObservationRepository : CommandRepository<AuctionObservationEntity, String>

@Repository
class AuctionAtomicUpsertRepository(private val jdbc: JdbcTemplate) {
    fun upsert(row: AuctionObservationEntity) {
        val h2 = jdbc.dataSource?.connection.use { it?.metaData?.databaseProductName?.contains("H2", true) == true }
        val args = arrayOf<Any?>(
            row.observationKey, row.listingId, row.observationKind, row.itemKey, row.itemName, row.itemType,
            row.quantity, row.totalPrice, row.unitPrice, java.sql.Timestamp.from(row.observedAt), java.sql.Timestamp.from(row.lastSeenAt),
        )
        if (h2) {
            jdbc.update("""merge into auction_observation
                (observation_key,listing_id,observation_kind,item_key,item_name,item_type,quantity,total_price,unit_price,observed_at,last_seen_at)
                key(observation_key) values (?,?,?,?,?,?,?,?,?,?,?)""".trimIndent(), *args)
        } else {
            jdbc.update("""insert into auction_observation
                (observation_key,listing_id,observation_kind,item_key,item_name,item_type,quantity,total_price,unit_price,observed_at,last_seen_at)
                values (?,?,?,?,?,?,?,?,?,?,?)
                on conflict (observation_key) do update set
                  listing_id=excluded.listing_id, observation_kind=excluded.observation_kind, item_key=excluded.item_key,
                  item_name=excluded.item_name, item_type=excluded.item_type, quantity=excluded.quantity,
                  total_price=excluded.total_price, unit_price=excluded.unit_price, last_seen_at=excluded.last_seen_at""".trimIndent(), *args)
        }
    }
}

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
