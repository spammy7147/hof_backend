package app.spammy.hof.town.auction

import app.spammy.hof.town.auction.entity.AuctionObservationEntity
import app.spammy.hof.town.auction.repository.AuctionObservationRepository
import app.spammy.hof.town.auction.repository.AuctionQueryRepository
import app.spammy.hof.town.auction.service.AuctionObservationService
import app.spammy.hof.town.auction.service.AuctionSnapshot
import app.spammy.hof.town.auction.service.ObservationKind
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import org.mockito.ArgumentCaptor
import org.mockito.Mockito

class AuctionObservationServiceTest {
    @Test
    fun `anonymous upsert computes unit price and carries no participant identity`() {
        val repository = Mockito.mock(AuctionObservationRepository::class.java)
        val query = Mockito.mock(AuctionQueryRepository::class.java)
        val service = AuctionObservationService(repository, query, CLOCK)

        service.observe(listOf(SNAPSHOT), NOW)

        val saved = savedEntities(repository).single()
        assertEquals(saved.totalPrice / saved.quantity, saved.unitPrice)
        assertNull(saved.listingId)
        assertEquals("Potion", saved.itemName)
    }

    @Test
    fun `same auction number remains one observation across visits`() {
        val repository = Mockito.mock(AuctionObservationRepository::class.java)
        val query = Mockito.mock(AuctionQueryRepository::class.java)
        val service = AuctionObservationService(repository, query, CLOCK)
        val snapshot = SNAPSHOT.copy(listingId = "252")

        service.observe(listOf(snapshot), NOW)
        service.observe(listOf(snapshot), NOW.plusSeconds(3600))

        val saved = savedEntities(repository, 2)
        assertEquals(saved[0].observationKey, saved[1].observationKey)
    }

    @Test
    fun `current and sold views of the same auction number share identity`() {
        val repository = Mockito.mock(AuctionObservationRepository::class.java)
        val query = Mockito.mock(AuctionQueryRepository::class.java)
        val service = AuctionObservationService(repository, query, CLOCK)
        val snapshot = SNAPSHOT.copy(listingId = "252")

        service.observe(listOf(snapshot), NOW)
        service.observe(listOf(snapshot.copy(kind = ObservationKind.SOLD)), NOW.plusSeconds(7200))

        val saved = savedEntities(repository, 2)
        assertEquals(saved[0].observationKey, saved[1].observationKey)
        assertEquals(listOf("CURRENT", "SOLD"), saved.map { it.observationKind })
    }

    @Test
    fun `different auction numbers remain distinct even for the same item and price`() {
        val repository = Mockito.mock(AuctionObservationRepository::class.java)
        val query = Mockito.mock(AuctionQueryRepository::class.java)
        val service = AuctionObservationService(repository, query, CLOCK)

        service.observe(listOf(SNAPSHOT.copy(listingId = "252")), NOW)
        service.observe(listOf(SNAPSHOT.copy(listingId = "253")), NOW)

        val saved = savedEntities(repository, 2)
        assertNotEquals(saved[0].observationKey, saved[1].observationKey)
    }

    private fun savedEntities(
        repository: AuctionObservationRepository,
        times: Int = 1,
    ): List<AuctionObservationEntity> {
        val captor = ArgumentCaptor.forClass(AuctionObservationEntity::class.java)
        Mockito.verify(repository, Mockito.times(times)).save(captor.capture() ?: AuctionObservationEntity())
        return captor.allValues
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-31T00:00:00Z")
        val CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
        val SNAPSHOT = AuctionSnapshot(null, "Potion", "item", 2, 2_001, ObservationKind.CURRENT)
    }
}
