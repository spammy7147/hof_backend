package app.spammy.hof.external.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SharedBattleCooldownParserTest {
    private val parser = SharedBattleCooldownParser()

    @Test
    fun `extracts remaining seconds from large raid cooldown notice`() {
        val html = """
            <div class="error">
              대형 레이드를 잇는 전투를 실행한 상태입니다. (56초 후 전투 가능)
            </div>
        """.trimIndent()

        assertEquals(SharedBattleCooldownNotice(56), parser.parse(html))
    }

    @Test
    fun `rejects unrelated malformed and non-positive cooldown text`() {
        listOf(
            "<div>56초 후 전투 가능</div>",
            "<div>대형 레이드 전투 상태입니다.</div>",
            "<div>대형 레이드 (곧 전투 가능)</div>",
            "<div>대형 레이드 (0초 후 전투 가능)</div>",
        ).forEach { html -> assertNull(parser.parse(html), html) }
    }
}
