package app.spammy.hof.town.card

import app.spammy.hof.town.card.model.CardRewardKind
import app.spammy.hof.town.card.parser.CardPageParser
import app.spammy.hof.town.common.parser.HofFormParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import app.spammy.hof.town.common.model.ParsedTownResult

class CardPageParserTest {
    private val forms = HofFormParser()
    private val parser = CardPageParser()

    @Test fun `identify has exactly one selectable slot and keeps display-only rows unselectable`() {
        val html = fixture("identify.html")
        val page = parser.parseIdentify(html, URL, forms.parse(html, URL))
        assertEquals(1, page.selectionSlots)
        assertEquals(2, page.cards.size)
        assertTrue(page.cards.first().selectable)
        assertFalse(page.cards.last().selectable)
        assertEquals(50_000, page.cards.first().cost)
    }

    @Test fun `upgrade keeps base and material as distinct actual HOF fields`() {
        val html = fixture("upgrade.html")
        val page = parser.parseUpgrade(html, URL, forms.parse(html, URL))
        assertEquals(listOf("base", "material"), page.selectionSlots.map { it.id })
        assertEquals("ItemNo", page.selectionSlots[0].fieldName)
        assertEquals("AddMaterial", page.selectionSlots[1].fieldName)
        assertEquals(20, page.maxQuantity)
        assertTrue(page.history.isNotEmpty())
        val afterAction = parser.parseUpgrade(html, URL, forms.parse(html, URL), ParsedTownResult(emptyList(), emptyList()))
        assertTrue(afterAction.result!!.messages.any { it.contains("합성 성공") })
    }

    @Test fun `change reads HOF max ten and actual labels`() {
        val html = fixture("change.html")
        val page = parser.parseChange(html, URL, forms.parse(html, URL))
        assertEquals(10, page.maxQuantity)
        assertTrue(page.materialCards.single().label.contains("Cursed Crew"))
    }

    @Test fun `sell is multi select and rewards blank cards`() {
        val html = fixture("sell.html")
        val page = parser.parseSell(html, URL, forms.parse(html, URL))
        assertTrue(page.multiSelect)
        assertEquals(CardRewardKind.BLANK_CARD, page.rewardKind)
        assertEquals(660, page.blankCardsOwned)
        assertEquals(4, page.cards.last().blankCardValue)
        assertNotNull(page.actionId)
    }

    @Test fun `soul echo separates recipes owned materials and history`() {
        val html = fixture("soul-echo.html")
        val page = parser.parseSoulEcho(html, URL, forms.parse(html, URL))
        assertEquals(2, page.recipes.size)
        assertTrue(page.recipes.first().selectable)
        assertFalse(page.recipes.last().selectable)
        assertEquals(2, page.ownedEchoes.size)
        assertEquals("Arena Boss", page.ownedEchoes.first().region)
        assertTrue(page.history.any { it.success })
        assertTrue(page.history.any { !it.success })
    }

    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/fixtures/town/card/$name")).readText()
    companion object { const val URL = "http://sic.zerosic.com/ZeroHOF/index.php?menu=cardshop" }
}
