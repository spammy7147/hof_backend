package app.spammy.hof.town.auction

import app.spammy.hof.town.auction.entity.AuctionObservationEntity
import app.spammy.hof.town.auction.repository.AuctionAtomicUpsertRepository
import app.spammy.hof.town.auction.service.AuctionObservationService
import app.spammy.hof.town.auction.service.AuctionSnapshot
import app.spammy.hof.town.auction.service.ObservationKind
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

    @Autowired
    private lateinit var observations: AuctionObservationService

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

    @Test
    fun `wired observation service persists a complete market batch`() {
        val suffix = UUID.randomUUID().toString()
        val names = listOf("Auction batch A $suffix", "Auction batch B $suffix")
        try {
            observations.observe(
                listOf(
                    AuctionSnapshot("901", names[0], "item", 2, 2_000, ObservationKind.CURRENT),
                    AuctionSnapshot("902", names[1], "item", 3, 6_000, ObservationKind.SOLD),
                ),
                Instant.parse("2026-08-14T06:00:00Z"),
            )

            assertEquals(
                2,
                jdbc.queryForObject(
                    "select count(*) from auction_observation where item_name in (?, ?)",
                    Int::class.java,
                    names[0],
                    names[1],
                ),
            )
        } finally {
            jdbc.update("delete from auction_observation where item_name in (?, ?)", names[0], names[1])
        }
    }

    @Test
    fun `revisiting one auction number updates one persisted row through sold state`() {
        val listingId = "visit-${UUID.randomUUID()}"
        try {
            observations.observe(
                listOf(AuctionSnapshot(listingId, "Visit item", "item", 2, 2_000, ObservationKind.CURRENT)),
                Instant.parse("2026-08-14T06:00:00Z"),
            )
            observations.observe(
                listOf(AuctionSnapshot(listingId, "Visit item", "item", 2, 3_000, ObservationKind.SOLD)),
                Instant.parse("2026-08-14T07:00:00Z"),
            )

            assertEquals(
                1,
                jdbc.queryForObject(
                    "select count(*) from auction_observation where listing_id = ?",
                    Int::class.java,
                    listingId,
                ),
            )
            assertEquals(
                "SOLD",
                jdbc.queryForObject(
                    "select observation_kind from auction_observation where listing_id = ?",
                    String::class.java,
                    listingId,
                ),
            )
        } finally {
            jdbc.update("delete from auction_observation where listing_id = ?", listingId)
        }
    }

    @Test
    fun `one visited page collapses current and sold number 252 into sold unit price`() {
        val listingId = "252"
        try {
            observations.observe(
                listOf(
                    AuctionSnapshot(listingId, "Mask of Scorn", "Hat", 3, 120_000_000, ObservationKind.CURRENT),
                    AuctionSnapshot(listingId, "Mask of Scorn", "Hat", 3, 120_000_000, ObservationKind.SOLD),
                ),
                Instant.parse("2026-08-14T06:00:00Z"),
            )

            assertEquals(
                1,
                jdbc.queryForObject(
                    "select count(*) from auction_observation where listing_id = ?",
                    Int::class.java,
                    listingId,
                ),
            )
            assertEquals(
                "SOLD",
                jdbc.queryForObject(
                    "select observation_kind from auction_observation where listing_id = ?",
                    String::class.java,
                    listingId,
                ),
            )
            assertEquals(
                40_000_000L,
                jdbc.queryForObject(
                    "select unit_price from auction_observation where listing_id = ?",
                    Long::class.java,
                    listingId,
                ),
            )
        } finally {
            jdbc.update("delete from auction_observation where listing_id = ?", listingId)
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
