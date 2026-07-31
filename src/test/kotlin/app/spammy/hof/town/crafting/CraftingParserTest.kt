package app.spammy.hof.town.crafting

import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.crafting.model.CraftingMode
import app.spammy.hof.town.crafting.parser.CraftingPageParser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CraftingParserTest {
    private val forms = HofFormParser()
    private val parser = CraftingPageParser()

    @Test fun `작업장은 radio 없는 행과 제작 시간 및 최대 수량을 보존한다`() {
        val value = parse("workbase.html", CraftingMode.WORKBASE)
        assertThat(value.rows.single { it.label.contains("재료 부족") }.selectable).isFalse()
        assertThat(value.rows.single { it.label.contains("Striker Coat") }.workSeconds).isEqualTo(600)
        assertThat(value.maxQuantity).isEqualTo(10)
        assertThat(value.categories.single { it.current }.label).contains("무기")
    }

    @Test fun `작업 중 남은 시간과 수동 완료 action을 파싱한다`() {
        val value = parse("workbase-active.html", CraftingMode.WORKBASE)
        assertThat(value.activeJob?.remainingSeconds).isEqualTo(2974)
        assertThat(value.activeJob?.completionAvailable).isTrue()
    }

    @Test fun `클라리스는 같은 행의 고정 ItemT 대입이 있는 radio만 선택 가능하다`() {
        val value = parse("claris.html", CraftingMode.CLARIS)
        assertThat(value.rows.single { it.label.contains("Dreamweave") }.selectable).isTrue()
        assertThat(value.rows.single { it.label.contains("Avatar Ticket") }.selectable).isFalse()
    }

    @Test fun `제련과 장로대장간은 timesB 선택 가능 횟수를 노출한다`() {
        assertThat(parse("refine.html", CraftingMode.REFINE).allowedRefineCounts).containsExactly(1, 2, 3)
        assertThat(parse("veteran.html", CraftingMode.VETERAN).allowedRefineCounts).containsExactly(1, 2, 3)
    }

    @Test fun `제작공방은 수량 100과 선택적 추가 소재를 파싱한다`() {
        val value = parse("create.html", CraftingMode.CREATE)
        assertThat(value.maxQuantity).isEqualTo(100)
        assertThat(value.additionalMaterialsOptional).isTrue()
        assertThat(value.additionalMaterials.single().label).contains("Blue Sphere")
    }

    @Test fun `임의 javascript는 ItemT로 평가하지 않는다`() {
        val html = resource("workbase.html").replace("document.getElementById('ItemT').value='17'", "window.runRecipe('17')")
        val value = parser.parse(CraftingMode.WORKBASE, html, URL, forms.parse(html, URL))
        assertThat(value.rows.single { it.label.contains("Striker Coat") }.selectable).isFalse()
    }

    private fun parse(name: String, mode: CraftingMode) = resource(name).let { html ->
        parser.parse(mode, html, URL, forms.parse(html, URL))
    }
    private fun resource(name: String) = requireNotNull(javaClass.getResource("/fixtures/town/crafting/$name")).readText()
    private companion object { const val URL = "http://sic.zerosic.com/ZeroHOF/index.php" }
}
