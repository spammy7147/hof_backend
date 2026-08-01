package app.spammy.hof.town.common.parser

import app.spammy.hof.town.common.model.TownFeatureId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class TownEntryPageParserTest {
    private val parser = TownEntryPageParser()

    @Test
    fun `declares all approved features and verified menu codes`() {
        assertEquals(33, TownFeatureId.entries.size)
        assertEquals("buy2", TownFeatureId.SUNDRIES_STORE.menuCode)
        assertEquals("sell", TownFeatureId.SELL.menuCode)
        assertEquals("combine", TownFeatureId.COMBINE.menuCode)
        assertEquals("auction", TownFeatureId.AUCTION.menuCode)
        assertEquals("colosseum", TownFeatureId.COLOSSEUM_BATTLE.menuCode)
        assertEquals("colosseumshop", TownFeatureId.COLOSSEUM_EXCHANGE.menuCode)
        assertEquals("recruit", TownFeatureId.TALENT_AGENCY.menuCode)
        assertEquals("housing", TownFeatureId.HOME_MANAGEMENT.menuCode)
        assertEquals("create2", TownFeatureId.EMBLEM_SHOP.menuCode)
        assertEquals("raidpub", TownFeatureId.RAID_INFO.menuCode)
        assertEquals("restroom", TownFeatureId.REST_ROOM.menuCode)
        assertEquals("quest", TownFeatureId.ADVENTURE_AGENCY.menuCode)
        assertEquals("legacy", TownFeatureId.LEGACY_SHOP.menuCode)
        assertEquals("ann", TownFeatureId.ANN_SHOP.menuCode)
        assertEquals("soulecho", TownFeatureId.SOUL_ECHO.menuCode)
        assertEquals("pantheon", TownFeatureId.PANTHEON.menuCode)
        assertEquals(
            setOf(
                "buy", "buy2", "sbuy", "sell", "combine", "auction", "colosseum", "colosseumshop", "recruit", "housing",
                "cardshop", "cardmix", "cardmix2", "cardsell", "fishing", "createF",
                "stash", "orbboxshop", "sewingshop", "workbase", "refine", "create", "refine2", "create2", "raidpub",
                "restroom", "quest", "legacy", "ann", "soulecho", "pantheon",
            ),
            TownFeatureId.entries.mapNotNull(TownFeatureId::menuCode).toSet(),
        )
    }

    @Test
    fun `discovers only same-origin account-independent menu links by conservative aliases`() {
        val html = """
            <a href="?menu=event2026">특별 교환상점(Event Shop)</a>
            <a href="?menu=auction&amp;action=buy&amp;account=7">옥션(Auction)</a>
            <a href="https://evil.example/?menu=talent">인재 알선소(Recruit)</a>
        """.trimIndent()

        val result = parser.parse(html)

        assertEquals("?menu=event2026", result.getValue(TownFeatureId.EVENT_SHOP).href)
        assertNull(result[TownFeatureId.AUCTION])
        assertNull(result[TownFeatureId.TALENT_AGENCY])
        assertFalse(result.values.any { it.href.contains("account") || it.href.contains("token") })
    }

    @Test
    fun `omits a feature when two different safe links match the same alias`() {
        val html = """
            <a href="?menu=event1">특별 교환상점</a>
            <a href="?menu=event2">Event Shop</a>
        """.trimIndent()

        assertNull(parser.parse(html)[TownFeatureId.EVENT_SHOP])
    }
}
