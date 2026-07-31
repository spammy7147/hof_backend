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
import app.spammy.hof.account.service.HofSessionRecoveryService
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotEquals
import org.mockito.ArgumentCaptor
import org.mockito.Mockito

class AuctionCollectorTest {
    @Test
    fun `server lease permits one GET-only hourly collector and zero account disables it`() {
        val service = Mockito.mock(AuctionService::class.java)
        val observations = Mockito.mock(AuctionObservationService::class.java)
        val lease = Mockito.mock(AuctionLeaseService::class.java)
        val recovery = passthroughRecovery()
        Mockito.`when`(lease.acquire(anyString(), eqInstant(NOW))).thenReturn(true, false)
        Mockito.`when`(service.collectorPage(77)).thenReturn(listOf(SNAPSHOT))
        val enabled = AuctionCollector(AuctionCollectorProperties(77), service, observations, lease, recovery, CLOCK)

        enabled.hourlyTick()
        enabled.hourlyTick()
        AuctionCollector(AuctionCollectorProperties(0), service, observations, lease, recovery, CLOCK).hourlyTick()

        Mockito.verify(service, Mockito.times(1)).collectorPage(77)
        Mockito.verify(recovery, Mockito.times(1)).execute<Any>(eqLong(77L), eqOrigin(app.spammy.hof.external.model.HofRequestOrigin.AUTOMATION), anyAction())
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

    @Test
    fun `same listing appends one observation per hour bucket`() {
        val repository = Mockito.mock(AuctionObservationRepository::class.java)
        val query = Mockito.mock(AuctionQueryRepository::class.java)
        val service = AuctionObservationService(repository, query, CLOCK)
        val snapshot = SNAPSHOT.copy(listingId = "77")

        service.observe(listOf(snapshot), NOW)
        service.observe(listOf(snapshot), NOW.plusSeconds(3600))

        val captor = ArgumentCaptor.forClass(AuctionObservationEntity::class.java)
        Mockito.verify(repository, Mockito.times(2)).save(captor.capture() ?: AuctionObservationEntity())
        assertNotEquals(captor.allValues[0].observationKey, captor.allValues[1].observationKey)
        assertEquals(NOW, captor.allValues[0].observedAt)
        assertEquals(NOW.plusSeconds(3600), captor.allValues[1].observedAt)
    }

    @Test
    fun `sold event identity is stable across collector hours`() {
        val repository = Mockito.mock(AuctionObservationRepository::class.java)
        val query = Mockito.mock(AuctionQueryRepository::class.java)
        val service = AuctionObservationService(repository, query, CLOCK)
        val sold = SNAPSHOT.copy(listingId = "77", kind = ObservationKind.SOLD)
        service.observe(listOf(sold), NOW)
        service.observe(listOf(sold), NOW.plusSeconds(7200))
        val captor = ArgumentCaptor.forClass(AuctionObservationEntity::class.java)
        Mockito.verify(repository, Mockito.times(2)).save(captor.capture() ?: AuctionObservationEntity())
        assertEquals(captor.allValues[0].observationKey, captor.allValues[1].observationKey)
    }

    @Test
    fun `captcha or authentication failure releases lease and retains previous observations`() {
        val service = Mockito.mock(AuctionService::class.java)
        val observations = Mockito.mock(AuctionObservationService::class.java)
        val lease = Mockito.mock(AuctionLeaseService::class.java)
        val recovery = passthroughRecovery()
        Mockito.`when`(lease.acquire(anyString(), eqInstant(NOW))).thenReturn(true)
        Mockito.doThrow(app.spammy.hof.common.error.ApiException(app.spammy.hof.common.error.ErrorCode.CAPTCHA_REQUIRED, "captcha"))
            .`when`(recovery).execute<Any>(eqLong(77L), eqOrigin(app.spammy.hof.external.model.HofRequestOrigin.AUTOMATION), anyAction())

        AuctionCollector(AuctionCollectorProperties(77), service, observations, lease, recovery, CLOCK).hourlyTick()

        Mockito.verify(lease).failure(anyString())
        Mockito.verifyNoInteractions(service, observations)
    }

    companion object {
        val NOW: Instant = Instant.parse("2026-07-31T00:00:00Z")
        val CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
        val SNAPSHOT = AuctionSnapshot(null, "Potion", "item", 2, 2_001, ObservationKind.CURRENT)
    }
    private fun anyString(): String = Mockito.anyString() ?: ""
    private fun eqInstant(value: Instant): Instant = Mockito.eq(value) ?: value
    private fun passthroughRecovery(): HofSessionRecoveryService {
        val recovery = Mockito.mock(HofSessionRecoveryService::class.java)
        Mockito.doAnswer { invocation -> invocation.getArgument<() -> Any>(2).invoke() }
            .`when`(recovery).execute<Any>(Mockito.anyLong(), anyOrigin(), anyAction())
        return recovery
    }
    private fun anyOrigin(): app.spammy.hof.external.model.HofRequestOrigin =
        Mockito.any(app.spammy.hof.external.model.HofRequestOrigin::class.java) ?: app.spammy.hof.external.model.HofRequestOrigin.AUTOMATION
    private fun eqOrigin(value: app.spammy.hof.external.model.HofRequestOrigin): app.spammy.hof.external.model.HofRequestOrigin = Mockito.eq(value) ?: value
    private fun eqLong(value: Long): Long = Mockito.eq(value) ?: value
    private fun anyAction(): () -> Any = Mockito.any<() -> Any>() ?: { Unit }
}
