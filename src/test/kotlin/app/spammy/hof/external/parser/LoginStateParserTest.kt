package app.spammy.hof.external.parser

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoginStateParserTest {
    private val parser = LoginStateParser()

    @Test
    fun detectsLoggedOutLoginForm() {
        val html = """
            <form method="post">
              <input name="id">
              <input name="pass" type="password">
              <input name="Login" value="login">
            </form>
        """.trimIndent()

        val state = parser.parse(html)

        assertFalse(state.isLoggedIn)
        assertTrue(state.hasLoginForm)
    }

    @Test
    fun detectsLoggedInHomeByCharacterLinks() {
        val html = """<a href="?char=1683198503393759">소셜</a>"""

        val state = parser.parse(html)

        assertTrue(state.isLoggedIn)
        assertTrue(state.hasCharacterLinks)
    }

    @Test
    fun detectsLoggedInTabByStatusHeader() {
        val html = """
            <div>Funds : ${'$'} 309,385,362</div>
            <div>Time : 6000/6000</div>
        """.trimIndent()

        val state = parser.parse(html)

        assertTrue(state.isLoggedIn)
        assertTrue(state.hasStatusHeader)
    }
}
