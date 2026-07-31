package app.spammy.hof.town.pvp

import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.pvp.parser.ColosseumParser
import kotlin.test.*

class ColosseumTest {
    private val forms = HofFormParser()
    private val parser = ColosseumParser()
    @Test fun `팀 checkbox와 challenge form을 서로 다른 계약으로 파싱한다`() {
        val html = fixture("battle.html"); val value = parser.parseBattle(html, BASE, forms.parse(html, BASE))
        assertEquals(3, value.selectedTeam.size); assertEquals(3, value.fighters.size); assertEquals(1, value.opponents.size)
    }
    @Test fun `challenge 결과를 현재 응답에서 구조화한다`() {
        val html = fixture("result.html"); val result = assertNotNull(parser.parseBattle(html, BASE, forms.parse(html, BASE)).battleResult)
        assertEquals(12, result.turns); assertEquals("공민이", result.winner); assertEquals("0/27306", result.playerHp); assertEquals(1936, result.totalDamage); assertNotNull(result.reward); assertEquals(1, result.detail.first().turn)
    }
    @Test fun `교환소 radio 없는 항목은 선택 불가다`() {
        val html = fixture("shop.html"); val shop = parser.parseShop(html, BASE, forms.parse(html, BASE))
        assertTrue(shop.items.single { it.label.contains("Sword") }.selectable); assertFalse(shop.items.single { it.label.contains("Emblem") }.selectable); assertEquals(250, shop.currencies.single().quantity)
    }
    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/fixtures/town/pvp/$name")).readText()
    private companion object { const val BASE = "http://sic.zerosic.com/ZeroHOF/index.php?menu=colosseum" }
}
