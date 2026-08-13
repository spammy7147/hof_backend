package app.spammy.hof.external.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HofHtmlParserTest {
    @Test
    fun `removes foot subtree before any feature parser reads the document`() {
        val document = HofHtmlParser.parse(
            """
                <main><p>기능 본문</p></main>
                <div id="foot"><a href="?menu=manual">UpDate - Manual</a><form><button>푸터 동작</button></form></div>
            """.trimIndent(),
            "http://sic.zerosic.com/ZeroHOF/index.php?menu=rest",
        )

        assertNull(document.selectFirst("#foot"))
        assertEquals("기능 본문", document.body().text())
        assertEquals(emptyList(), document.select("form"))
    }
}
