package app.spammy.hof.external.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class CharacterRosterParserTest {
    private val parser = CharacterRosterParser()

    @Test
    fun collectsUniqueCharacterLinksAndVisibleNames() {
        val html = """
            <a href="index.php?char=111">소셜</a>
            <a href="index.php?char=111">중복</a>
            <button onclick="location.href='index.php?char=222'">카발</button>
            <script>var url = "index.php?char=333";</script>
        """.trimIndent()

        val characters = parser.parse(html)

        assertEquals(listOf("111", "222", "333"), characters.map { it.id })
        assertEquals("소셜", characters[0].name)
        assertEquals("카발", characters[1].name)
        assertEquals("", characters[2].name)
        assertEquals(listOf(0, 1, 2), characters.map { it.rosterOrder })
    }
}
