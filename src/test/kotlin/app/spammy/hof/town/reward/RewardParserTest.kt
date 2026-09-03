package app.spammy.hof.town.reward

import app.spammy.hof.town.common.model.ParsedTownResult
import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.reward.model.OrbExchangeAction
import app.spammy.hof.town.reward.model.StashOpenAction
import app.spammy.hof.town.reward.parser.OrbExchangeParser
import app.spammy.hof.town.reward.parser.StashPageParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RewardParserTest {
    private val forms = HofFormParser()
    private val stash = StashPageParser()
    private val orbs = OrbExchangeParser()

    @Test fun `stash exposes only fixed server buttons and dynamic account candidates`() {
        val html = fixture("stash.html")
        val snapshot = stash.parse(html, STASH_URL, forms.parse(html, STASH_URL))

        assertEquals(listOf(StashOpenAction.ONE, StashOpenAction.TWENTY, StashOpenAction.HUNDRED, StashOpenAction.THOUSAND, StashOpenAction.ALL), snapshot.actions.map { it.action })
        assertEquals(listOf("1개 열기", "20개 열기", "100개 열기", "1000개 열기", "전부 열기"), snapshot.actions.map { it.label })
        assertEquals(2, snapshot.boxes.size)
        val box = snapshot.boxes.first()
        assertTrue(box.selectable)
        assertEquals("Plumpy Fish (Stash)", box.name)
        assertEquals("Bind / 설명", box.detail)
        assertEquals(10, box.cost)
        assertEquals(5, box.owned)
        assertFalse(snapshot.boxes.last().selectable)
        assertEquals("선택할 수 없는 상자", snapshot.boxes.last().name)
        assertEquals(null, snapshot.boxes.last().detail)
        assertTrue(snapshot.actions.none { it.label.contains("미리") })
    }

    @Test fun `stash splits the historical box title and description without repeating its price`() {
        val html = fixture("stash.html").replace(
            "${'$'} 10</td><td><img src=\"/item/plumpy.gif\"> Plumpy Fish (Stash) x5 / Bind / 설명",
            "${'$'} 0</td><td>Treasure Box - Historical Weapon (Stash) x8 / 고대인들의 시대로부터 지금까지 남아있는 역사적인 무기가 들어있습니다.",
        )
        val box = stash.parse(html, STASH_URL, forms.parse(html, STASH_URL)).boxes.first()

        assertEquals("Treasure Box - Historical Weapon (Stash)", box.name)
        assertEquals("고대인들의 시대로부터 지금까지 남아있는 역사적인 무기가 들어있습니다.", box.detail)
        assertEquals(0, box.cost)
        assertEquals(8, box.owned)
    }

    @Test fun `실서버 AllOpen 필드의 1000개 문구를 thousand action으로 해석한다`() {
        val html = fixture("stash.html")
            .replace("  <input type=\"submit\" name=\"Open1000\" value=\"1000개 열기\">\n", "")
            .replace("name=\"AllOpen\" value=\"전부 열기\"", "name=\"AllOpen\" value=\"1000개 열기\"")

        val snapshot = stash.parse(html, STASH_URL, forms.parse(html, STASH_URL))

        assertEquals(
            listOf(StashOpenAction.ONE, StashOpenAction.TWENTY, StashOpenAction.HUNDRED, StashOpenAction.THOUSAND),
            snapshot.actions.map { it.action },
        )
        assertEquals("1000개 열기", snapshot.actions.single { it.action == StashOpenAction.THOUSAND }.label)
    }

    @Test fun `orb parser keeps actual GET balances and confirmed one and five actions`() {
        val html = fixture("orbs-before.html")
        val snapshot = orbs.parse(html, ORB_URL, forms.parse(html, ORB_URL))

        assertEquals(154_240, snapshot.displayedOrbs.red)
        assertFalse(snapshot.orbCountsEstimated)
        assertEquals(listOf(OrbExchangeAction.ONE, OrbExchangeAction.FIVE), snapshot.actions.map { it.action })
        assertEquals(3, snapshot.rewards.size)
        assertEquals(3, snapshot.rewards.single { it.name.startsWith("Event Box") }.remaining)
        assertEquals(null, snapshot.rewards.single { it.name.startsWith("Funds Bag") }.remaining)
    }

    @Test fun `explicit reward items win over inventory inference`() {
        val beforeHtml = fixture("orbs-before.html")
        val before = orbs.parse(beforeHtml, ORB_URL, forms.parse(beforeHtml, ORB_URL))
        val afterHtml = fixture("orbs-explicit.html")
        val after = orbs.parseExchange(afterHtml, ORB_URL, forms.parse(afterHtml, ORB_URL), ParsedTownResult(emptyList(), emptyList()), before, 1)

        assertEquals(listOf("Funds Bag($ 5,000) (Other)", "Material Sack (Stash)"), after.outcomes.map { it.text })
        assertTrue(after.outcomes.all { !it.inferred })
    }

    @Test fun `explicit rewards are parsed in order when images are nested in result rows`() {
        val beforeHtml = fixture("orbs-before.html")
        val before = orbs.parse(beforeHtml, ORB_URL, forms.parse(beforeHtml, ORB_URL))
        val afterHtml = fixture("orbs-explicit.html").replace(
            "<img src=\"/item/funds.gif\"> Funds Bag($ 5,000) (Other) / Bind / 을(를) 획득했다!<br>\n<img src=\"/item/sack.gif\"> Material Sack (Stash) 을(를) 획득했다!<br>",
            "<div class=\"outcome\"><span><img src=\"/item/funds.gif\"> Funds Bag($ 5,000) (Other) / Bind / 을(를) 획득했다!</span></div>\n<div class=\"outcome\"><span><img src=\"/item/sack.gif\"> Material Sack (Stash) 을(를) 획득했다!</span></div>",
        )
        val after = orbs.parseExchange(afterHtml, ORB_URL, forms.parse(afterHtml, ORB_URL), ParsedTownResult(emptyList(), emptyList()), before, 1)

        assertEquals(listOf("Funds Bag($ 5,000) (Other)", "Material Sack (Stash)"), after.outcomes.map { it.text })
    }

    @Test fun `duplicate orb submit contracts fail closed`() {
        val html = fixture("orbs-before.html").replace(
            "</body>",
            "<form method=\"post\" action=\"?menu=other\"><input type=\"submit\" name=\"TestBTN2\" value=\"위장 기부\"></form></body>",
        )
        val snapshot = orbs.parse(html, ORB_URL, forms.parse(html, ORB_URL))

        assertTrue(snapshot.actions.none { it.action == OrbExchangeAction.ONE })
        assertTrue(snapshot.actions.any { it.action == OrbExchangeAction.FIVE })
    }

    @Test fun `missing result infers finite decreases then unlimited funds bag and calculated orbs`() {
        val beforeHtml = fixture("orbs-before.html")
        val before = orbs.parse(beforeHtml, ORB_URL, forms.parse(beforeHtml, ORB_URL))
        val afterHtml = fixture("orbs-inferred.html")
        val after = orbs.parseExchange(afterHtml, ORB_URL, forms.parse(afterHtml, ORB_URL), ParsedTownResult(listOf("필요한 오브의 개수가 부족합니다."), emptyList()), before, 5)

        assertEquals(OrbExchangeAction.FIVE, after.lastAction)
        assertEquals("Event Box(Avatar)", after.outcomes[0].text)
        assertEquals(1, after.outcomes[0].quantity)
        assertEquals("Funds Bag($ 1,000)", after.outcomes[1].text)
        assertEquals(3, after.outcomes[1].quantity)
        assertEquals(false, after.outcomes[2].success)
        assertTrue(after.outcomes.take(2).all { it.inferred })
        assertEquals(150_240, after.displayedOrbs.red)
        assertEquals(1_086, after.displayedOrbs.blue)
        assertEquals(3_448, after.displayedOrbs.green)
        assertTrue(after.orbCountsEstimated)
        assertNotNull(after.result)
    }

    @Test fun `missing after catalog does not guess every success as an unlimited funds bag`() {
        val beforeHtml = fixture("orbs-before.html")
        val before = orbs.parse(beforeHtml, ORB_URL, forms.parse(beforeHtml, ORB_URL))
        val afterHtml = """
            <html><body>
            <p>Red Orb : 154240개</p><p>Blue Orb : 5086개</p><p>Green Orb : 7448개</p>
            <form method="post" action="?menu=orbboxshop">
              <input type="submit" name="TestBTN2" value="오브를 기부한다">
              <input type="submit" name="TestBTN3" value="오브를 5회 기부한다">
            </form>
            </body></html>
        """.trimIndent()
        val after = orbs.parseExchange(afterHtml, ORB_URL, forms.parse(afterHtml, ORB_URL), ParsedTownResult(emptyList(), emptyList()), before, 1)

        assertTrue(after.outcomes.isEmpty())
        assertEquals(153_240, after.displayedOrbs.red)
        assertTrue(after.orbCountsEstimated)
    }

    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/fixtures/town/reward/$name")).readText()
    private companion object {
        const val STASH_URL = "https://hof.zerosic.com/index.php?menu=stash"
        const val ORB_URL = "https://hof.zerosic.com/index.php?menu=orbboxshop"
    }
}
