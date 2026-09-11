package app.spammy.hof.town.auction.repository

import app.spammy.hof.common.persistence.CommandRepository
import app.spammy.hof.town.auction.entity.QAuctionObservationEntity.auctionObservationEntity
import com.querydsl.jpa.impl.JPAQueryFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import org.springframework.stereotype.Repository
import org.springframework.jdbc.core.JdbcTemplate
import app.spammy.hof.town.auction.entity.AuctionObservationEntity

interface AuctionObservationRepository : CommandRepository<AuctionObservationEntity, String>

data class AuctionMarketSummary(
    val averageUnitPrice: Long,
    val minimumUnitPrice: Long,
    val maximumUnitPrice: Long,
    val tradeCount: Int,
    val volume: Long,
    val recentObservations: List<AuctionObservationEntity>,
)

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

    /** 최초 관측 시각 범위의 낙찰 기록 전체를 집계하고, 품목별 그래프만 최근 100건으로 제한한다. */
    fun findMarket(query: String?, since: Instant, until: Instant): List<AuctionMarketSummary> {
        val row = auctionObservationEntity
        val normalized = query?.trim()?.takeIf(String::isNotEmpty)
        val eligible = row.observationKind.eq("SOLD")
            .and(row.observedAt.between(since, until))
            .and(normalized?.let { row.itemName.containsIgnoreCase(it) })
        val count = row.observationKey.count()
        // AVG(Long)의 부동소수점 손실과 SUM(Long)의 오버플로를 피한다.
        val totalUnitPrice = row.unitPrice.castToNum(BigDecimal::class.java).sum()
        val minimum = row.unitPrice.min()
        val maximum = row.unitPrice.max()
        val volume = row.quantity.longValue().sum()
        return queryFactory.select(row.itemKey, count, totalUnitPrice, minimum, maximum, volume)
            .from(row).where(eligible).groupBy(row.itemKey).fetch().map { summary ->
                val tradeCount = requireNotNull(summary[count])
                // ponytail: 품목당 한 쿼리로 전송량을 제한한다. 품목 수가 병목이면 일괄 순위 조회로 바꾼다.
                val points = queryFactory.selectFrom(row)
                    .where(eligible, row.itemKey.eq(requireNotNull(summary[row.itemKey])))
                    .orderBy(row.observedAt.desc(), row.observationKey.desc())
                    .limit(100).fetch().asReversed()
                AuctionMarketSummary(
                    averageUnitPrice = requireNotNull(summary[totalUnitPrice])
                        .divide(BigDecimal.valueOf(tradeCount), 0, RoundingMode.DOWN).longValueExact(),
                    minimumUnitPrice = requireNotNull(summary[minimum]),
                    maximumUnitPrice = requireNotNull(summary[maximum]),
                    tradeCount = Math.toIntExact(tradeCount),
                    volume = requireNotNull(summary[volume]),
                    recentObservations = points,
                )
            }
    }
}
