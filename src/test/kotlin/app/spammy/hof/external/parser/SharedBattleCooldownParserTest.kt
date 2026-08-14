package app.spammy.hof.external.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SharedBattleCooldownParserTest {
    private val parser = SharedBattleCooldownParser()

    @Test
    fun `extracts remaining seconds from confirmed shared cooldown notice`() {
        val html = """
            <div class="error">
              대형 데이터를 읽는 전투를 실행한 상태입니다. (56초 후 전투 가능)
            </div>
        """.trimIndent()

        assertEquals(SharedBattleCooldownNotice(56), parser.parse(html))
    }

    @Test
    fun `recognizes confirmed marker across html and whitespace`() {
        val html = """
            <div class="error">
              대형 <strong>데이터를</strong> 읽는 전투를
              실행한 상태입니다. (9초 후 전투 가능)
            </div>
        """.trimIndent()

        assertEquals(SharedBattleCooldownNotice(9), parser.parse(html))
    }

    @Test
    fun `uses one minute fallback when confirmed marker has no usable countdown`() {
        listOf(
            "<div>대형 데이터를 읽는 전투를 실행한 상태입니다.</div>",
            "<div>대형 데이터를 읽는 전투를 실행한 상태입니다. (곧 전투 가능)</div>",
            "<div>대형 데이터를 읽는 전투를 실행한 상태입니다. (0초 후 전투 가능)</div>",
        ).forEach { html ->
            assertEquals(SharedBattleCooldownNotice(60), parser.parse(html), html)
        }
    }

    @Test
    fun `extracts the union shared cooldown shown as minutes and seconds`() {
        val html = """
            <h4>UnionMonster</h4>
            <div>Time left to next battle : 8:32</div>
        """.trimIndent()

        assertEquals(SharedBattleCooldownNotice(512), parser.parse(html))
    }

    @Test
    fun `rejects countdowns and old mistaken marker without confirmed marker`() {
        listOf(
            "<div>56초 후 전투 가능</div>",
            "<div>대형 레이드를 잇는 전투를 실행한 상태입니다. (56초 후 전투 가능)</div>",
            "<div>일반 전투를 실행한 상태입니다.</div>",
        ).forEach { html -> assertNull(parser.parse(html), html) }
    }
}
