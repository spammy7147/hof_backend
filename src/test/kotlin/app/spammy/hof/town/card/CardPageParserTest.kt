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

    @Test fun `identify accepts the live cardshop field contract and duplicate submit controls`() {
        val html = """
            <html><body><form method="post" action="?menu=cardshop">
              <input type="submit" name="cardshop" value="감정">
              <table><tr><td>${'$'} 50,000</td><td><input type="radio" name="item_no" value="101"> Bat's Card x13 / ★</td></tr></table>
              <input type="submit" name="cardshop" value="감정">
              <input type="hidden" name="cardshop" value="1">
            </form></body></html>
        """.trimIndent()

        val page = parser.parseIdentify(html, URL, forms.parse(html, URL))

        assertEquals(1, page.cards.size)
        assertTrue(page.cards.single().selectable)
        assertNotNull(page.actionId)
    }

    @Test fun `identify extracts equipment option rows inserted before the live cardshop form`() {
        val html = """
            <html><body><div style="margin:0 20px">
              <img src="./image/char/ArgonPlanet.gif"> <b>아스트로맨서 '아르곤'</b>
              <p><font>˝가,감정...할게요...˝</font></p>
              <img src="./image/icon/sword.gif" class="vcent">Bat's Sword<span class="light"> (Sword)</span> / <font>M:Metal</font> / <span>옵션 : DEX+22 ,SPD+5</span><br>
              <img src="./image/icon/armor.gif" class="vcent">Bat's Armor<span class="light"> (Armor)</span> / <font>M:Metal</font> / <span>옵션 : DEX+22 ,SPD+5</span><br>
              보유한 카드의 목록
              <form action="?menu=cardshop" method="post">
                <input type="radio" name="item_no" value="7101"> Bat's Card x13
                <input type="submit" name="cardshop" value="감정">
                <input type="hidden" name="cardshop" value="1">
              </form>
            </div></body></html>
        """.trimIndent()

        val page = parser.parseIdentify(
            html,
            URL,
            forms.parse(html, URL),
            ParsedTownResult(emptyList(), emptyList()),
        )

        assertEquals(
            listOf(
                "Bat's Sword (Sword) / M:Metal / 옵션 : DEX+22 ,SPD+5",
                "Bat's Armor (Armor) / M:Metal / 옵션 : DEX+22 ,SPD+5",
            ),
            page.result?.items?.map { it.label },
        )
    }

    @Test fun `upgrade keeps base and material as distinct two stage HOF fields`() {
        val html = fixture("upgrade.html")
        val page = parser.parseUpgrade(html, URL, forms.parse(html, URL))
        assertEquals(listOf("base"), page.selectionSlots.map { it.id })
        assertEquals("ItemNo", page.selectionSlots[0].fieldName)
        assertTrue(page.materialCards.isEmpty())
        val optionsHtml = fixture("upgrade-options.html")
        val options = parser.parseUpgrade(optionsHtml, URL, forms.parse(optionsHtml, URL))
        assertEquals(listOf("material"), options.selectionSlots.map { it.id })
        assertEquals("AddMaterial", options.selectionSlots[0].fieldName)
        assertEquals(20, options.maxQuantity)
        assertTrue(page.history.isNotEmpty())
        val afterAction = parser.parseUpgrade(html, URL, forms.parse(html, URL), ParsedTownResult(emptyList(), emptyList()))
        assertTrue(afterAction.result!!.messages.any { it.contains("합성 성공") })
    }

    @Test fun `upgrade and change keep candidates when the live form repeats its submit control`() {
        val html = """
            <html><body><form method="post">
              <input type="submit" name="Create" value="Create">
              <table><tr><td><input type="radio" name="ItemNo" value="201"> Base Card x2</td></tr></table>
              <input type="text" name="amount" value="1">
              <input type="hidden" name="Create" value="1">
              <table><tr><td><input type="radio" name="AddMaterial" value="202"> Material Card x3</td></tr></table>
              <input type="submit" name="Create" value="Create">
            </form></body></html>
        """.trimIndent()
        val parsed = forms.parse(html, URL)

        val upgrade = parser.parseUpgrade(html, URL, parsed)
        val change = parser.parseChange(html, URL, parsed)

        assertEquals(1, upgrade.baseCards.size)
        assertEquals(1, upgrade.materialCards.size)
        assertNotNull(upgrade.actionId)
        assertEquals(1, change.baseCards.size)
        assertEquals(1, change.materialCards.size)
        assertNotNull(change.actionId)
    }

    @Test fun `change reads HOF max ten and actual labels`() {
        val html = fixture("change-options.html")
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

    @Test fun `sell table header is not exposed as an unavailable card`() {
        val html = fixture("sell.html").replace(
            "<form method=\"post\" action=\"?menu=cardsell\"><table>",
            "<form method=\"post\" action=\"?menu=cardsell\"><table><tr><th>가격</th><th>수</th><th>아이템</th></tr>",
        )

        val page = parser.parseSell(html, URL, forms.parse(html, URL))

        assertEquals(2, page.cards.size)
        assertTrue(page.cards.none { it.label.contains("가격") || !it.selectable })
    }

    @Test fun `soul echo separates recipes owned materials and history`() {
        val html = fixture("soul-echo.html")
        val page = parser.parseSoulEcho(html, URL, forms.parse(html, URL))
        assertEquals(2, page.recipes.size)
        assertTrue(page.recipes.first().selectable)
        assertFalse(page.recipes.last().selectable)
        assertEquals(2, page.ownedEchoes.size)
        assertEquals("Arena Boss", page.ownedEchoes.first().region)
        assertEquals("type:weapon", page.currentCategoryId)
        assertTrue(page.recipes.all { it.category == page.currentCategoryId })
        assertTrue(page.history.any { it.success })
        assertTrue(page.history.any { !it.success })
    }

    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/fixtures/town/card/$name")).readText()
    companion object { const val URL = "http://sic.zerosic.com/ZeroHOF/index.php?menu=cardshop" }
}
