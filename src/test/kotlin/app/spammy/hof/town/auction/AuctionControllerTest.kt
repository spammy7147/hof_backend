package app.spammy.hof.town.auction

import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.town.auction.controller.AuctionController
import app.spammy.hof.town.auction.parser.AuctionPageParser
import app.spammy.hof.town.auction.entity.AuctionObservationEntity
import app.spammy.hof.town.auction.repository.AuctionObservationRepository
import app.spammy.hof.town.auction.repository.AuctionQueryRepository
import app.spammy.hof.town.auction.service.AuctionAction
import app.spammy.hof.town.auction.service.AuctionMarket
import app.spammy.hof.town.auction.service.AuctionObservationService
import app.spammy.hof.town.auction.service.AuctionService
import app.spammy.hof.town.common.model.ParsedTownPage
import app.spammy.hof.town.common.service.TownAuthenticatedExecutor
import app.spammy.hof.town.common.service.TownLocationResolver
import app.spammy.hof.town.common.parser.HofFormParser
import java.time.Instant
import java.time.Clock
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.mockito.ArgumentCaptor
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
    fun `live auction remains available when observation fails`() {
        val executor = Mockito.mock(TownAuthenticatedExecutor::class.java)
        val locations = Mockito.mock(TownLocationResolver::class.java)
        val parser = Mockito.mock(AuctionPageParser::class.java)
        val observations = Mockito.mock(AuctionObservationService::class.java)
        val page = Mockito.mock(ParsedTownPage::class.java)
        Mockito.`when`(parser.snapshots("auction-html", page)).thenThrow(IllegalStateException("observation unavailable"))

        AuctionService(executor, locations, parser, observations).observeBestEffort("auction-html", page)

        Mockito.verifyNoInteractions(observations)
    }

    @Test
    fun `live auction visit forwards current and sold rows to market observation`() {
        val html = """
          <table><tr><th>No</th><th>나머지</th><th>가격</th><th>Item</th><th>Bids</th><th>입찰자</th><th>출품자</th></tr>
          <tr><td>250</td><td>1시간</td><td>${'$'} 3,000,000</td><td>Guiltnie's Card (Card) x2</td><td>1</td><td>PRIVATE_BIDDER</td><td>PRIVATE_SELLER</td></tr></table>
          <form method="post"><input type="text" name="BidPrice"><input type="submit" value="입찰"><input type="hidden" name="ArticleNo"></form>
          <div>옥션 로그(AuctionLog) No. 249 출품한 Potion (item) x3를 사용자에게 ${'$'} 9,000에 낙찰하였습니다.</div>
        """.trimIndent()
        val parsedPage = HofFormParser().parse(html)
        val executor = Mockito.mock(TownAuthenticatedExecutor::class.java)
        val locations = Mockito.mock(TownLocationResolver::class.java)
        val repository = Mockito.mock(AuctionObservationRepository::class.java)
        val queryRepository = Mockito.mock(AuctionQueryRepository::class.java)
        val observations = AuctionObservationService(
            repository,
            queryRepository,
            Clock.fixed(Instant.parse("2026-08-14T06:00:00Z"), ZoneOffset.UTC),
        )

        AuctionService(executor, locations, AuctionPageParser(), observations).observeBestEffort(html, parsedPage)

        val captor = ArgumentCaptor.forClass(AuctionObservationEntity::class.java)
        Mockito.verify(repository, Mockito.times(2)).save(captor.capture() ?: AuctionObservationEntity())
        assertEquals(setOf("CURRENT", "SOLD"), captor.allValues.map { it.observationKind }.toSet())
    }

    @Test
    fun `persisted auction entity defines no seller bidder or account identity columns`() {
        val names = app.spammy.hof.town.auction.entity.AuctionObservationEntity::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(names.none { it.contains("seller") || it.contains("bidder") || it.contains("account") })
    }

    @Test
    fun `APK auction contract exposes exact forms without synthetic candidate actions`() {
        val html = """
          <table><tr><th>No</th><th>나머지</th><th>가격</th><th>아이템</th><th>입찰</th><th>입찰자</th><th>판매자</th></tr>
          <tr><td>17</td><td>1시간</td><td>${'$'} 12,000</td><td>Potion (item) x2</td><td>1</td><td>PRIVATE_BIDDER</td><td>PRIVATE_SELLER</td></tr></table>
          <form method="post"><input name="ArticleNo"><input name="BidPrice"><input type="submit" name="Bid" value="Bid"></form>
          <form method="post"><input type="submit" name="ExhibitItemForm" value="Put Auction"></form>
          <form method="post"><input type="submit" name="GetAutuonItem" value="Get"></form>
          <form method="post"><input type="submit" name="GetAutuonMoney" value="Get"></form>
        """.trimIndent()
        val parsedPage = HofFormParser().parse(html)
        val page = AuctionPageParser().parse(html, parsedPage)

        assertEquals(listOf("17"), page.listings.mapNotNull { it.listingId })
        assertTrue(page.listings.none { it.rowKey.startsWith("action:") })
        assertTrue(page.listings.all { it.candidateId == null })
        assertTrue(page.capabilities.bidActionId != null)
        assertTrue(page.capabilities.exhibitEntryActionId != null)
        assertTrue(page.capabilities.claimItemActionId != null)
        assertTrue(page.capabilities.claimFundsActionId != null)
        assertFalse(page.toString().contains("PRIVATE_"))
    }

    @Test
    fun `live auction contract accepts editable bid price with unnamed submit`() {
        val html = """
          <table>
            <tr><th>No</th><th>나머지</th><th>가격</th><th>Item</th><th>Bids</th><th>입찰자</th><th>출품자</th></tr>
            <tr><td>252</td><td>16분</td><td>${'$'} 120,000,000</td><td>Mask of Scorn (Hat) x3</td><td>1</td><td>PRIVATE_BIDDER</td><td>PRIVATE_SELLER</td></tr>
            <tr><td>250</td><td>1시간20분</td><td>${'$'} 3,000,000</td><td>Guiltnie's Card (Card) x2</td><td>1</td><td>PRIVATE_BIDDER</td><td>PRIVATE_SELLER</td></tr>
            <tr><td>249</td><td>3시간4분</td><td>${'$'} 8,000,000</td><td>Skill Seal - 'Armor Coating' (SkillSeal) x1</td><td>1</td><td>PRIVATE_BIDDER</td><td>PRIVATE_SELLER</td></tr>
          </table>
          <form action="index.php?menu=auction" method="post">
            <input type="text" name="BidPrice" value="0">
            <input type="submit" value="입찰">
            <input type="hidden" name="ArticleNo" value="0">
          </form>
        """.trimIndent()

        val page = AuctionPageParser().parse(html, HofFormParser().parse(html))

        assertEquals(listOf("252", "250", "249"), page.listings.mapNotNull { it.listingId })
        assertTrue(page.capabilities.bidActionId != null)
        assertTrue(AuctionAction.BID in page.actions)
        assertFalse(page.toString().contains("PRIVATE_"))
    }

    @Test
    fun `sold observation includes only completed sale logs`() {
        val html = """
          <div>옥션 로그(AuctionLog)
          No. 1 출품한 Potion (item) x2를 사용자에게 ${'$'} 2,000에 낙찰하였습니다.
          No. 2 Potion에 ${'$'} 3,000 입찰하였습니다.
          No. 3 Elixir ${'$'} 4,000 출품되었습니다.
          No. 4 Sword ${'$'} 5,000 취소되었습니다.
          No. 5 Shield ${'$'} 6,000 입찰자가 없어 종료되었습니다.
          No. 6 Herb (item) x3 sold for ${'$'} 9,000.
          </div>
        """.trimIndent()
        val snapshots = AuctionPageParser().snapshots(html, HofFormParser().parse(html))
            .filter { it.kind.name == "SOLD" }

        assertEquals(listOf("1", "6"), snapshots.mapNotNull { it.listingId })
        val english = snapshots.single { it.listingId == "6" }
        assertEquals("Herb", english.name)
        assertEquals("item", english.type)
        assertEquals(3, english.quantity)
        assertEquals(9_000, english.totalPrice)
    }

    @Test
    fun `market observation uses completed sale total and quantity for auction number 252`() {
        val html = """
          <div>옥션 로그(AuctionLog)
          No.252 에 Mask of Scorn (Hat) x3개가 출품되었습니다.
          No.252 판매자가 출품한 Mask of Scorn (Hat) x3개를 구매자가 ${'$'} 120,000,000 로 낙찰하였습니다.
          </div>
        """.trimIndent()

        val sold = AuctionPageParser().snapshots(html, HofFormParser().parse(html))
            .single { it.kind == app.spammy.hof.town.auction.service.ObservationKind.SOLD }

        assertEquals("252", sold.listingId)
        assertEquals("Mask of Scorn", sold.name)
        assertEquals("Hat", sold.type)
        assertEquals(3, sold.quantity)
        assertEquals(120_000_000, sold.totalPrice)
    }

    @Test
    fun `APK exhibit contract keeps real item candidate and duration values`() {
        val html = """
          <form action="index.php?menu=auction" method="post">
            <table><tr><td><input type="radio" name="item_no" value="77">Elixir (item) x5</td></tr></table>
            <input name="Amount" value="1"><select name="ExhibitTime"><option value="24">24시간</option><option value="48">48시간</option></select>
            <input name="StartPrice" value="0"><input name="Comment" value=""><input type="submit" name="PutAuction" value="1">
          </form>
        """.trimIndent()
        val page = HofFormParser().parse(html)
        val exhibit = AuctionPageParser().parseExhibit(html, page)

        assertEquals("77", exhibit.items.single().candidateId)
        assertEquals(listOf("24", "48"), exhibit.durations.map { it.value })
        assertEquals(page.forms.first { it.submitFields.any { field -> field.name == "PutAuction" } }.actionId, exhibit.actionId)
    }
}
