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
        assertTrue(snapshot.boxes.first().selectable)
        assertEquals(5, snapshot.boxes.first().owned)
        assertFalse(snapshot.boxes.last().selectable)
        assertTrue(snapshot.actions.none { it.label.contains("미리") })
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

    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/fixtures/town/reward/$name")).readText()
    private companion object {
        const val STASH_URL = "http://sic.zerosic.com/ZeroHOF/index.php?menu=stash"
        const val ORB_URL = "http://sic.zerosic.com/ZeroHOF/index.php?menu=orbboxshop"
    }
}
