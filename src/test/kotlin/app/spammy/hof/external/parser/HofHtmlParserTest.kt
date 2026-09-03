package app.spammy.hof.external.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HofHtmlParserTest {
    @Test
    fun `removes foot subtree before any feature parser reads the document`() {
        val document = HofHtmlParser.parse(
            """
                <main><p>기능 본문</p></main>
                <div id="foot"><a href="?menu=manual">UpDate - Manual</a><form><button>푸터 동작</button></form></div>
            """.trimIndent(),
            "https://hof.zerosic.com/index.php?menu=rest",
        )

        assertNull(document.selectFirst("#foot"))
        assertEquals("기능 본문", document.body().text())
        assertEquals(emptyList(), document.select("form"))
    }

    @Test
    fun `feature cleanup 전에 foot 안의 공통 페이지 종료 표식을 확인한다`() {
        val html = """
            <main><p>기능 본문</p></main>
            <div id="foot">
              <h5>Copy Right sanitized fixture</h5>
              <h6>H.O.F Korean Ver sanitized fixture</h6>
              <img src="image/zerohof.gif?version=1">
            </div>
        """.trimIndent()

        assertTrue(HofHtmlParser.hasCompletePageTerminator(html))
        assertFalse(HofHtmlParser.hasCompletePageTerminator(html.substringBefore("<div id=\"foot\"")))
        assertNull(HofHtmlParser.parse(html).selectFirst("#foot"))
    }
}
