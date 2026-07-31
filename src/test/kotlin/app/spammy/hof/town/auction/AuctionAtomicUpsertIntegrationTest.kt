package app.spammy.hof.town.auction

import app.spammy.hof.town.auction.entity.AuctionObservationEntity
import app.spammy.hof.town.auction.repository.AuctionAtomicUpsertRepository
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@SpringBootTest
@ActiveProfiles("test")
class AuctionAtomicUpsertIntegrationTest {
    @Autowired
    private lateinit var repository: AuctionAtomicUpsertRepository

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Test
    fun `concurrent observations with the same key leave one row`() {
        val key = "auction-test-${UUID.randomUUID()}"
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val futures = (1..8).map { sequence ->
                pool.submit(Callable {
                    start.await()
                    repository.upsert(
                        AuctionObservationEntity(
                            observationKey = key,
                            listingId = "listing-1",
                            observationKind = "SOLD",
                            itemKey = "potion-item",
                            itemName = "Potion",
                            itemType = "item",
                            quantity = 2,
                            totalPrice = 2_000,
                            unitPrice = 1_000,
                            observedAt = Instant.parse("2026-07-31T00:00:00Z"),
                            lastSeenAt = Instant.parse("2026-07-31T00:00:0${sequence}Z"),
                        ),
                    )
                })
            }

            start.countDown()
            futures.forEach { it.get() }

            assertEquals(
                1,
                jdbc.queryForObject(
                    "select count(*) from auction_observation where observation_key = ?",
                    Int::class.java,
                    key,
                ),
            )
        } finally {
            pool.shutdownNow()
            jdbc.update("delete from auction_observation where observation_key = ?", key)
        }
    }

    companion object {
        private val databaseName = "auction_upsert_${UUID.randomUUID().toString().replace("-", "")}"

        @JvmStatic
        @DynamicPropertySource
        fun isolatedDatabase(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") {
                "jdbc:h2:mem:$databaseName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;" +
                    "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
            }
        }
    }
}
