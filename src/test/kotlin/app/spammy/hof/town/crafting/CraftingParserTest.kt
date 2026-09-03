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

    @Test fun `실제 클라리스의 분리된 javascript 분류 선택기를 노출한다`() {
        val html = """
            <form id="create">
              <select name="type_create" onchange="ChangeTypecreate()">
                <option value="weapon" selected>무기(weapon)</option>
                <option value="cloak">외투(cloak)</option>
              </select>
            </form>
            <form method="post" action="?menu=sewingshop">
              <input type="hidden" name="ItemT" value="">
              <input type="submit" name="Create" value="Create">
              <input name="amount" value="1">
              <input type="hidden" name="Create" value="Create">
              <div id="list"></div>
            </form>
            <script>
              function Listtype_create(mode) {
                switch(mode) {
                  case "weapon": html = ''; break;
                  case "cloak": html = '<tr><td>${'$'} 1</td><td><input type="radio" name="ItemNo" value="cloak" onclick="document.ItemT.value=42">Dreamweave Cloak</td></tr>'; break;
                }
                return html;
              }
              function ChangeTypecreate() {}
            </script>
        """.trimIndent()
        val url = "https://hof.zerosic.com/index.php?menu=sewingshop"

        val value = parser.parse(CraftingMode.CLARIS, html, url, forms.parse(html, url))

        assertThat(value.categories.map { it.label }).containsExactly("무기(weapon)", "외투(cloak)")
        assertThat(value.currentCategoryId).isNotNull()

        val cloak = parser.parse(
            CraftingMode.CLARIS,
            html,
            url,
            forms.parse(html, url),
            categoryCandidateId = "type_create:cloak",
        )
        assertThat(cloak.currentCategoryId).isEqualTo("type_create:cloak")
        assertThat(cloak.rows.single { it.label.contains("Dreamweave Cloak") }.selectable).isTrue()
    }

    @Test fun `일반 제련은 timesB 선택지를 노출하고 장로대장간은 1회로 고정한다`() {
        val refine = parse("refine.html", CraftingMode.REFINE)
        assertThat(refine.allowedRefineCounts).containsExactly(1, 2, 3)
        assertThat(refine.history).containsExactly("제련 성공: +1 Short Sword")
        assertThat(parse("veteran.html", CraftingMode.VETERAN).allowedRefineCounts).containsExactly(1)
    }

    @Test fun `장로대장간의 제련가능 Item 표 헤더는 품목으로 노출하지 않는다`() {
        val html = resource("veteran.html").replace(
            "<table>",
            "<table><tr><th>제련가능</th><th>Item</th></tr>",
        )

        val value = parser.parse(CraftingMode.VETERAN, html, URL, forms.parse(html, URL))

        assertThat(value.rows).noneMatch { !it.selectable && it.label.contains("제련가능") }
    }

    @Test fun `제련 기록에서 사용자명과 링크 안의 아이템명을 모두 보존한다`() {
        val html = resource("refine.html").replace(
            "<p>제련 성공: +1 Short Sword</p>",
            "<p>-[《HOF 초보자》 soxlzzz] <a href='?item=1'>+10 Mask of Scorn</a> (+1)수치 제련 실패!</p>",
        )

        val value = parser.parse(CraftingMode.REFINE, html, URL, forms.parse(html, URL))

        assertThat(value.history).containsExactly(
            "-[《HOF 초보자》 soxlzzz] +10 Mask of Scorn (+1)수치 제련 실패!",
        )
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

    @Test fun `radio를 소유한 행의 직접 ItemT 대입은 후보에만 엄격히 연결한다`() {
        val html = resource("workbase.html")
            .replace(" onclick=\"document.getElementById('ItemT').value='17'\"", "")
            .replace("<tr><td>$ 8,000</td>", "<tr onclick=\"document.getElementById('ItemT').value='17'\"><td>$ 8,000</td>")
        val value = parser.parse(CraftingMode.WORKBASE, html, URL, forms.parse(html, URL))
        assertThat(value.rows.single { it.label.contains("Striker Coat") }.selectable).isTrue()
    }

    @Test fun `현재 분류에 제작 후보가 없어도 분류 전환 action은 유지한다`() {
        val html = resource("workbase.html").replace(
            Regex("<table>.*?</table>", setOf(RegexOption.DOT_MATCHES_ALL)),
            "<table><tr><th>제작비</th><th>Item</th></tr></table>",
        )
        val value = parser.parse(CraftingMode.WORKBASE, html, URL, forms.parse(html, URL))
        assertThat(value.rows).isEmpty()
        assertThat(value.categories.map { it.label }).contains("방어구(armor)")
        assertThat(value.actionId).isNotBlank()
    }

    @Test fun `긴 목록에 동일한 Create 버튼이 반복되어도 제작 품목을 파싱한다`() {
        val html = resource("workbase.html").replace(
            "<input type=\"submit\" name=\"Create\" value=\"Create\">",
            "<input type=\"submit\" name=\"Create\" value=\"Create\"><input type=\"submit\" name=\"Create\" value=\"Create\">",
        )

        val value = parser.parse(CraftingMode.WORKBASE, html, URL, forms.parse(html, URL))

        assertThat(value.categories).isNotEmpty()
        assertThat(value.rows).anyMatch { it.label.contains("Striker Coat") }
        assertThat(value.actionId).isNotBlank()
    }

    @Test fun `제련 폼의 상하단 버튼과 횟수 선택기가 반복되어도 목록을 파싱한다`() {
        val times = "<select name=\"timesB\"><option value=\"1\" selected>1</option><option value=\"2\">2</option><option value=\"3\">3</option></select>"
        val html = resource("refine.html")
            .replace(times, "$times$times")
            .replace(
                "<input type=\"submit\" name=\"refine\" value=\"Refine\">",
                "<input type=\"submit\" name=\"refine\" value=\"Refine\"><input type=\"submit\" name=\"refine\" value=\"제련\">",
            )

        val value = parser.parse(CraftingMode.REFINE, html, URL, forms.parse(html, URL))

        assertThat(value.categories).isNotEmpty()
        assertThat(value.rows).anyMatch { it.label.contains("Short Sword") }
        assertThat(value.allowedRefineCounts).containsExactly(1, 2, 3)
        assertThat(value.actionId).isNotBlank()
    }

    @Test fun `장로대장간의 분류 선택기가 제련 form 밖에 있어도 품목은 표시한다`() {
        val category = "<select name=\"type\"><option value=\"weapon\" selected>무기</option></select>"
        val html = resource("veteran.html")
            .replace(category, "")
            .replace("<body>", "<body><form method=\"post\">$category</form>")

        val value = parser.parse(CraftingMode.VETERAN, html, URL, forms.parse(html, URL))

        assertThat(value.rows).anyMatch { it.label.contains("Mask of Scorn") }
        assertThat(value.actionId).isNotBlank()
        assertThat(value.allowedRefineCounts).containsExactly(1)
    }

    @Test fun `제작공방 AJAX 목록이 form 밖에 렌더링되어도 품목을 누락하지 않는다`() {
        val row = "<table><tr><td>$ 2,000</td><td><input id=\"recipe-1\" type=\"radio\" name=\"ItemNo\" value=\"sword\" onclick=\"document.getElementById('ItemT').value='77'\"> Rune Sword · Steel Ingot x10</td></tr></table>"
        val html = resource("create.html")
            .replace(row, "")
            .replace("</form>", "</form><div id=\"list\">$row</div>")

        val value = parser.parse(CraftingMode.CREATE, html, URL, forms.parse(html, URL))

        assertThat(value.rows).anyMatch { it.label.contains("Rune Sword") }
        assertThat(value.rows.single { it.label.contains("Rune Sword") }.selectable).isFalse()
    }

    @Test fun `HOF의 비정상적으로 큰 보유량과 제작 시간은 Int overflow 없이 null 처리한다`() {
        val html = resource("workbase.html")
            .replace("x3", "x9,999,999,999")
            .replace("제작 시간: 600", "제작 시간: 9,999,999,999")
        val row = parser.parse(CraftingMode.WORKBASE, html, URL, forms.parse(html, URL))
            .rows.single { it.label.contains("Striker Coat") }
        assertThat(row.owned).isNull()
        assertThat(row.workSeconds).isNull()
    }

    private fun parse(name: String, mode: CraftingMode) = resource(name).let { html ->
        parser.parse(mode, html, URL, forms.parse(html, URL))
    }
    private fun resource(name: String) = requireNotNull(javaClass.getResource("/fixtures/town/crafting/$name")).readText()
    private companion object { const val URL = "https://hof.zerosic.com/index.php" }
}
