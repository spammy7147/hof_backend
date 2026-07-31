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
        assertEquals("create2", TownFeatureId.EMBLEM_SHOP.menuCode)
        assertEquals("raidpub", TownFeatureId.RAID_INFO.menuCode)
        assertEquals(
            setOf(
                "buy", "buy2", "sbuy", "cardshop", "cardmix", "cardmix2", "cardsell", "fishing", "createF",
                "stash", "orbboxshop", "sewingshop", "workbase", "refine", "create", "refine2", "create2", "raidpub",
            ),
            TownFeatureId.entries.mapNotNull(TownFeatureId::menuCode).toSet(),
        )
    }

    @Test
    fun `discovers only same-origin account-independent menu links by conservative aliases`() {
        val html = requireNotNull(javaClass.getResource("/fixtures/town/common/town-entry.html")).readText()

        val result = parser.parse(html)

        assertEquals("?menu=pantheon", result.getValue(TownFeatureId.PANTHEON).href)
        assertEquals("?menu=restroom", result.getValue(TownFeatureId.REST_ROOM).href)
        assertNull(result[TownFeatureId.AUCTION])
        assertNull(result[TownFeatureId.TALENT_AGENCY])
        assertFalse(result.values.any { it.href.contains("account") || it.href.contains("token") })
    }

    @Test
    fun `omits a feature when two different safe links match the same alias`() {
        val html = """
            <a href="?menu=pantheon">신전 거리</a>
            <a href="?menu=anotherPantheon">Pantheon</a>
        """.trimIndent()

        assertNull(parser.parse(html)[TownFeatureId.PANTHEON])
    }
}
