package app.spammy.hof.external.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class HofMainStatusParserTest {
    private val parser = HofMainStatusParser()

    @Test
    fun parsesMainStatusValuesFromHeaderTable() {
        val html = """
            <table>
              <tr>
                <td>《얼어붙은 손길》공민이</td>
                <td>Funds : $ 309,385,362<br>Work : Nothing</td>
                <td>Time : 6000/6000<br>Auction : item/funds</td>
              </tr>
            </table>
            <div>소셜 Lv.60 Social Knight</div>
        """.trimIndent()

        val status = parser.parse(html)

        assertEquals("《얼어붙은 손길》공민이", status.playerName)
        assertEquals(309385362L, status.funds)
        assertEquals(6000, status.timeCurrent)
        assertEquals(6000, status.timeMax)
        assertEquals("Nothing", status.work)
        assertEquals("item/funds", status.auction)
    }

    @Test
    fun fallsBackToFullTextWhenStatusIsFlattened() {
        val html = """
            <body>
              Hall of Fame Ver ZeroHOF Top자격단전투모험시나리오아이템마을설정로그BBSCHAT
              《얼어붙은 손길》공민이 Funds : $ 1,720 Time : 4100/6000 Work : 12:34 Auction : Nothing
              소셜 Lv.60 Social Knight
            </body>
        """.trimIndent()

        val status = parser.parse(html)

        assertEquals("《얼어붙은 손길》공민이", status.playerName)
        assertEquals(1720L, status.funds)
        assertEquals(4100, status.timeCurrent)
        assertEquals(6000, status.timeMax)
        assertEquals("12:34", status.work)
        assertEquals("Nothing", status.auction)
    }
}
