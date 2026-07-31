package app.spammy.hof.town.auction

import app.spammy.hof.town.auction.config.AuctionCollectorProperties
import app.spammy.hof.town.auction.entity.AuctionObservationEntity
import app.spammy.hof.town.auction.repository.AuctionObservationRepository
import app.spammy.hof.town.auction.repository.AuctionQueryRepository
import app.spammy.hof.town.auction.service.AuctionCollector
import app.spammy.hof.town.auction.service.AuctionLeaseService
import app.spammy.hof.town.auction.service.AuctionObservationService
import app.spammy.hof.town.auction.service.AuctionService
import app.spammy.hof.town.auction.service.AuctionSnapshot
import app.spammy.hof.town.auction.service.ObservationKind
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.mockito.ArgumentCaptor
import org.mockito.Mockito

class AuctionCollectorTest {
    @Test
    fun `server lease permits one GET-only hourly collector and zero account disables it`() {
        val service = Mockito.mock(AuctionService::class.java)
        val observations = Mockito.mock(AuctionObservationService::class.java)
        val lease = Mockito.mock(AuctionLeaseService::class.java)
        Mockito.`when`(lease.acquire(anyString(), eqInstant(NOW))).thenReturn(true, false)
        Mockito.`when`(service.collectorPage(77)).thenReturn(listOf(SNAPSHOT))
        val enabled = AuctionCollector(AuctionCollectorProperties(77), service, observations, lease, CLOCK)

        enabled.hourlyTick()
        enabled.hourlyTick()
        AuctionCollector(AuctionCollectorProperties(0), service, observations, lease, CLOCK).hourlyTick()

        Mockito.verify(service, Mockito.times(1)).collectorPage(77)
        Mockito.verify(observations, Mockito.times(1)).observe(listOf(SNAPSHOT), NOW)
        Mockito.verify(lease, Mockito.times(1)).success(anyString(), eqInstant(NOW))
    }

    @Test
    fun `anonymous upsert computes unit price and carries no participant identity`() {
        val repository = Mockito.mock(AuctionObservationRepository::class.java)
        val query = Mockito.mock(AuctionQueryRepository::class.java)
        val service = AuctionObservationService(repository, query, CLOCK)
        service.observe(listOf(SNAPSHOT), NOW)
        val captor = ArgumentCaptor.forClass(AuctionObservationEntity::class.java)
        Mockito.verify(repository).save(captor.capture() ?: AuctionObservationEntity())
        val saved = captor.value
        assertEquals(saved.totalPrice / saved.quantity, saved.unitPrice)
        assertNull(saved.listingId)
        assertEquals("Potion", saved.itemName)
    }

    companion object {
        val NOW: Instant = Instant.parse("2026-07-31T00:00:00Z")
        val CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
        val SNAPSHOT = AuctionSnapshot(null, "Potion", "item", 2, 2_001, ObservationKind.CURRENT)
    }
    private fun anyString(): String = Mockito.anyString() ?: ""
    private fun eqInstant(value: Instant): Instant = Mockito.eq(value) ?: value
}
