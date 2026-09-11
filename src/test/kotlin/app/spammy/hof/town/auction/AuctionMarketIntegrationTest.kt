package app.spammy.hof.town.auction

import app.spammy.hof.town.auction.service.AuctionObservationService
import app.spammy.hof.town.auction.service.AuctionSnapshot
import app.spammy.hof.town.auction.service.AuctionMarket
import app.spammy.hof.town.auction.service.ObservationKind
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.hibernate.resource.jdbc.spi.StatementInspector
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles

@SpringBootTest(properties = [
    "spring.datasource.url=jdbc:h2:mem:auction_market;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
    "spring.jpa.properties.hibernate.session_factory.statement_inspector=app.spammy.hof.town.auction.AuctionMarketStatementInspector",
])
@ActiveProfiles("test")
@Import(AuctionMarketIntegrationTest.FixedClock::class)
open class AuctionMarketIntegrationTest {
    @Autowired
    private lateinit var observations: AuctionObservationService

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private val prefix = "market-${UUID.randomUUID()}"

    @AfterEach
    fun removeObservations() {
        jdbc.update("delete from auction_observation where item_name like ?", "$prefix%")
    }

    @Test
    fun `최근 매물 501개가 있어도 30일 안의 낙찰 거래를 시세에서 누락하지 않는다`() {
        observations.observe(
            listOf(AuctionSnapshot("$prefix-sold", "$prefix 낙찰 품목", "item", 2, 2_000, ObservationKind.SOLD)),
            NOW.minusSeconds(3_600),
        )
        observations.observe(
            (1..501).map { AuctionSnapshot("$prefix-current-$it", "$prefix 현재 품목", "item", 1, 99_000, ObservationKind.CURRENT) },
            NOW,
        )

        val market = observations.market(prefix)

        assertEquals(listOf("$prefix 낙찰 품목"), market.items.map { it.name })
        val item = market.items.single()
        assertEquals(1, item.tradeCount)
        assertEquals(2L, item.volume)
        assertEquals(1_000L, item.averageUnitPrice)
        assertEquals(1_000L, item.latestUnitPrice)
        assertEquals(listOf(NOW.minusSeconds(3_600)), item.points.map { it.observedAt })
    }

    @Test
    fun `한 품목에 낙찰이 편중돼도 다른 품목과 전체 통계를 보존하고 그래프만 100건으로 제한한다`() {
        observations.observe(listOf(
            AuctionSnapshot("$prefix-old", "$prefix Potion", "item", 3, 3, ObservationKind.SOLD),
            AuctionSnapshot("$prefix-rare", "$prefix Shield", "armor", 4, 160, ObservationKind.SOLD),
        ), NOW.minusSeconds(86_400))
        observations.observe(
            (1..600).map { AuctionSnapshot("$prefix-sold-$it", "$prefix Potion", "item", 2, 400, ObservationKind.SOLD) },
            NOW.minusSeconds(3_600),
        )

        val market = observations.market("  ${prefix.uppercase()}  ")

        assertEquals(listOf("$prefix Potion", "$prefix Shield"), market.items.map { it.name })
        val potion = market.items.first()
        assertEquals(601, potion.tradeCount)
        assertEquals(1_203L, potion.volume)
        assertEquals(199L, potion.averageUnitPrice)
        assertEquals(1L, potion.minimumUnitPrice)
        assertEquals(200L, potion.maximumUnitPrice)
        assertEquals(200L, potion.latestUnitPrice)
        assertEquals(100, potion.points.size)
        assertEquals(setOf(200L), potion.points.map { it.unitPrice }.toSet())
        val shield = market.items.last()
        assertEquals(1, shield.tradeCount)
        assertEquals(4L, shield.volume)
        assertEquals(40L, shield.averageUnitPrice)
        assertEquals(1, shield.points.size)
        assertEquals(NOW, market.generatedAt)
        assertEquals(emptyList(), observations.market("$prefix absent").items)
    }

    @Test
    fun `30일 최초 관측 경계를 적용하고 낙찰 전환과 재방문으로 기간이나 건수를 늘리지 않는다`() {
        val boundary = NOW.minusSeconds(30L * 24 * 60 * 60)
        val sold = AuctionSnapshot("$prefix-boundary", "$prefix Potion", "item", 1, 100, ObservationKind.SOLD)
        observations.observe(listOf(sold.copy(kind = ObservationKind.CURRENT)), boundary)
        val expired = sold.copy(listingId = "$prefix-expired", totalPrice = 90_000)
        observations.observe(listOf(expired.copy(kind = ObservationKind.CURRENT)), boundary.minusSeconds(1))
        observations.observe(listOf(sold, expired), NOW.minusSeconds(60))
        observations.observe(listOf(sold, expired), NOW)
        observations.observe(listOf(sold.copy(listingId = "$prefix-now", totalPrice = 300)), NOW)
        observations.observe(listOf(sold.copy(listingId = "$prefix-future", totalPrice = 90_000)), NOW.plusSeconds(1))

        val item = observations.market(prefix).items.single()

        assertEquals(2, item.tradeCount)
        assertEquals(2L, item.volume)
        assertEquals(200L, item.averageUnitPrice)
        assertEquals(100L, item.minimumUnitPrice)
        assertEquals(300L, item.maximumUnitPrice)
        assertEquals(300L, item.latestUnitPrice)
        assertEquals(listOf(boundary, NOW), item.points.map { it.observedAt })
        assertEquals(setOf(ObservationKind.SOLD), item.points.map { it.kind }.toSet())
    }

    @Test
    fun `Long 범위의 단가 평균은 합계 오버플로나 부동소수점 반올림 없이 계산한다`() {
        observations.observe(listOf(
            AuctionSnapshot("$prefix-large-1", "$prefix 고가 품목", "item", 1, Long.MAX_VALUE, ObservationKind.SOLD),
            AuctionSnapshot("$prefix-large-2", "$prefix 고가 품목", "item", 1, 9_223_372_036_854_775_805L, ObservationKind.SOLD),
        ), NOW)

        val item = observations.market(prefix).items.single()

        assertEquals(9_223_372_036_854_775_806L, item.averageUnitPrice)
        assertEquals(9_223_372_036_854_775_805L, item.minimumUnitPrice)
        assertEquals(Long.MAX_VALUE, item.maximumUnitPrice)
        assertEquals(2, item.tradeCount)
    }

    @Test
    fun `집계 뒤 새 낙찰과 가격 변경이 저장돼도 진행 중인 시세는 같은 관측 상태를 보여준다`() {
        val initial = AuctionSnapshot("$prefix-initial", "$prefix Potion", "item", 1, 100, ObservationKind.SOLD)
        observations.observe(listOf(initial), NOW.minusSeconds(60))
        val pointsReached = CountDownLatch(1)
        val continueQuery = CountDownLatch(1)
        Executors.newSingleThreadExecutor().use { worker ->
            val pending = worker.submit<AuctionMarket> {
                AuctionMarketStatementInspector.beforePoints.set {
                    pointsReached.countDown()
                    check(continueQuery.await(10, TimeUnit.SECONDS)) { "동시 저장 후 조회 재개 시간 초과" }
                }
                try { observations.market(prefix) }
                finally { AuctionMarketStatementInspector.beforePoints.remove() }
            }
            try {
                assertTrue(pointsReached.await(10, TimeUnit.SECONDS), "집계 뒤 포인트 조회에 도달해야 한다")
                observations.observe(listOf(
                    initial.copy(totalPrice = 300),
                    initial.copy(listingId = "$prefix-new", totalPrice = 500),
                ), NOW)
                continueQuery.countDown()
                val inFlight = pending.get(10, TimeUnit.SECONDS).items.single()
                assertEquals(1, inFlight.tradeCount)
                assertEquals(100L, inFlight.averageUnitPrice)
                assertEquals(100L, inFlight.latestUnitPrice)
                assertEquals(listOf(100L), inFlight.points.map { it.unitPrice })

                val refreshed = observations.market(prefix).items.single()
                assertEquals(2, refreshed.tradeCount)
                assertEquals(400L, refreshed.averageUnitPrice)
                assertEquals(listOf(300L, 500L), refreshed.points.map { it.unitPrice })
            } finally {
                continueQuery.countDown()
            }
        }
    }

    @TestConfiguration
    class FixedClock {
        @Bean
        @Primary
        fun auctionMarketClock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }

    companion object {
        val NOW: Instant = Instant.parse("2026-09-12T00:00:00Z")
    }
}

/** SQL은 그대로 실행하며 읽기 트랜잭션의 두 조회 사이에 다른 커밋을 배치한다. */
class AuctionMarketStatementInspector : StatementInspector {
    override fun inspect(sql: String): String {
        if (sql.startsWith("select") && "auction_observation" in sql && "group by" !in sql) {
            beforePoints.get()?.let { callback ->
                beforePoints.remove()
                callback()
            }
        }
        return sql
    }

    companion object {
        val beforePoints = ThreadLocal<() -> Unit>()
    }
}
