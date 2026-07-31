package app.spammy.hof.town.fishing.parser

import app.spammy.hof.town.common.model.ParsedTownResult
import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.common.parser.HofResultParser
import app.spammy.hof.town.fishing.model.FishingAction
import app.spammy.hof.town.fishing.model.FishingOutcome
import app.spammy.hof.town.fishing.model.FishingPrimaryAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FishingPageParserTest {
    private val forms = HofFormParser()
    private val results = HofResultParser()
    private val parser = FishingPageParser()
    private val url = "http://sic.zerosic.com/ZeroHOF/index.php?menu=fishing"

    @Test
    fun `날짜 갱신과 시작 action을 읽는다`() {
        val html = fixture("reset.html")
        val reset = parser.parse(html, url, forms.parse(html, url))

        assertTrue(reset.notice.orEmpty().contains("날짜가 갱신되었습니다"))
        assertEquals(18, reset.remainingCasts)
        assertEquals(FishingPrimaryAction.START, reset.primaryAction)
        assertEquals(setOf(FishingAction.START), reset.availableActions.map { it.action }.toSet())
    }

    @Test
    fun `낚기와 보조 action을 연결하되 자동 실행하지 않는다`() {
        val html = fixture("waiting.html")
        val waiting = parser.parse(html, url, forms.parse(html, url))

        assertEquals(FishingPrimaryAction.CATCH, waiting.primaryAction)
        assertEquals(30, waiting.escapeSeconds)
        assertEquals(setOf(FishingAction.CATCH, FishingAction.STATUS, FishingAction.FILTER), waiting.availableActions.map { it.action }.toSet())
    }

    @Test
    fun `물고기 도망은 정상 outcome이다`() {
        val html = fixture("escaped.html")
        val escaped = parser.parse(html, url, forms.parse(html, url), results.parse(html))

        assertEquals(FishingOutcome.ESCAPED, escaped.lastOutcome)
        assertEquals(FishingPrimaryAction.START, escaped.primaryAction)
    }

    @Test
    fun `전투 링크가 있으면 낚시 action을 차단한다`() {
        val html = fixture("monster.html")
        val monster = parser.parse(html, url, forms.parse(html, url))

        assertTrue(monster.blockedByBattle)
        assertNotNull(monster.battleLink)
        assertEquals(FishingPrimaryAction.NONE, monster.primaryAction)
        assertTrue(monster.availableActions.isEmpty())
    }

    @Test
    fun `radio 없는 교환품도 숨기지 않고 선택 불가로 둔다`() {
        val html = fixture("exchange.html")
        val exchange = parser.parseExchange(forms.parse(html, url))

        assertTrue(exchange.items.single { it.name.contains("Rank Fish") }.selectable)
        assertFalse(exchange.items.single { it.name == "교환 불가 기념 물고기" }.selectable)
    }

    private fun fixture(name: String): String = checkNotNull(javaClass.getResource("/fixtures/town/fishing/$name")).readText()
}
