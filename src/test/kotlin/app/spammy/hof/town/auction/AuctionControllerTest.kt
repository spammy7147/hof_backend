package app.spammy.hof.town.auction

import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.town.auction.controller.AuctionController
import app.spammy.hof.town.auction.parser.AuctionPageParser
import app.spammy.hof.town.auction.service.AuctionMarket
import app.spammy.hof.town.auction.service.AuctionObservationService
import app.spammy.hof.town.auction.service.AuctionService
import app.spammy.hof.town.common.parser.HofFormParser
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.mockito.Mockito

class AuctionControllerTest {
    @Test
    fun `auction parser exposes typed actions and integer unit price without identities`() {
        val html = """<form method="post"><table><tr><td><input type="radio" name="auction" value="lot-7">Potion (item) x2 / ${'$'} 2,000 / 판매자: PRIVATE_USER</td></tr></table><button name="Bid" value="입찰">입찰</button></form>"""
        val page = AuctionPageParser().parse(HofFormParser().parse(html))
        val listing = page.listings.single()
        assertEquals(2, listing.quantity)
        assertEquals(2_000, listing.totalPrice)
        assertEquals(1_000, listing.unitPrice)
        assertEquals("BID", listing.action.name)
        assertFalse(listing.name.contains("PRIVATE_USER"))
    }

    @Test
    fun `market endpoint reads only DB observation service and never HOF service`() {
        val auction = Mockito.mock(AuctionService::class.java)
        val observations = Mockito.mock(AuctionObservationService::class.java)
        val recovery = Mockito.mock(HofSessionRecoveryService::class.java)
        val expected = AuctionMarket(emptyList(), Instant.EPOCH)
        Mockito.`when`(observations.market("potion")).thenReturn(expected)

        val actual = AuctionController(auction, observations, recovery).market("potion")

        assertEquals(expected, actual)
        Mockito.verifyNoInteractions(auction, recovery)
    }

    @Test
    fun `persisted auction entity defines no seller bidder or account identity columns`() {
        val names = app.spammy.hof.town.auction.entity.AuctionObservationEntity::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(names.none { it.contains("seller") || it.contains("bidder") || it.contains("account") })
    }
}
