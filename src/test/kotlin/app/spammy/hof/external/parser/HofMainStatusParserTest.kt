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

    @Test
    fun prefersPlayerNameInStatusRowOverEarlierPublicName() {
        val html = """
            <div class="ranking">《순금 120%》켄류</div>
            <table>
              <tr>
                <td>《얼어붙은 손길》공민이</td>
                <td>Funds : ${'$'} 309,385,362<br>Work : Nothing</td>
                <td>Time : 6000/6000<br>Auction : item/funds</td>
              </tr>
            </table>
        """.trimIndent()

        val status = parser.parse(html)

        assertEquals("《얼어붙은 손길》공민이", status.playerName)
    }

    @Test
    fun returnsUnknownWhenStatusRowHasNoPlayerName() {
        val html = """
            <div class="ranking">《순금 120%》켄류</div>
            <table>
              <tr>
                <td>Funds : ${'$'} 309,385,362<br>Work : Nothing</td>
                <td>Time : 6000/6000<br>Auction : item/funds</td>
              </tr>
            </table>
        """.trimIndent()

        val status = parser.parse(html)

        assertEquals("Unknown", status.playerName)
    }

    @Test
    fun parsesWorkAndAuctionOutsidePlayerStatusRow() {
        val html = """
            <table>
              <tr>
                <td>《얼어붙은 손길》공민이</td>
                <td>Funds : ${'$'} 309,385,362</td>
                <td>Time : 6000/6000</td>
              </tr>
              <tr>
                <td>Work : Nothing</td>
                <td>Auction : item/funds</td>
              </tr>
            </table>
        """.trimIndent()

        val status = parser.parse(html)

        assertEquals("Nothing", status.work)
        assertEquals("item/funds", status.auction)
    }

    @Test
    fun ignoresNestedPublicNameInsideFlattenedStatusBody() {
        val html = """
            <body>
              《얼어붙은 손길》공민이
              <div class="ranking">《순금 120%》켄류</div>
              Funds : ${'$'} 1,720 Time : 4100/6000 Work : 12:34 Auction : Nothing
            </body>
        """.trimIndent()

        val status = parser.parse(html)

        assertEquals("《얼어붙은 손길》공민이", status.playerName)
    }

    @Test
    fun ignoresPublicNameInOuterRowWhenNestedStatusRowIsAnonymous() {
        val html = """
            <table>
              <tr>
                <td>《순금 120%》켄류</td>
                <td>
                  <table>
                    <tr>
                      <td>Funds : ${'$'} 1,720</td>
                      <td>Time : 4100/6000</td>
                    </tr>
                  </table>
                </td>
              </tr>
            </table>
        """.trimIndent()

        val status = parser.parse(html)

        assertEquals("Unknown", status.playerName)
    }

    @Test
    fun selectsAuthenticatedNameInNestedStatusRow() {
        val html = """
            <table>
              <tr>
                <td>《순금 120%》켄류</td>
                <td>
                  <table>
                    <tr>
                      <td>《얼어붙은 손길》공민이</td>
                      <td>Funds : ${'$'} 1,720</td>
                      <td>Time : 4100/6000</td>
                    </tr>
                  </table>
                </td>
              </tr>
            </table>
        """.trimIndent()

        val status = parser.parse(html)

        assertEquals("《얼어붙은 손길》공민이", status.playerName)
    }

    @Test
    fun ignoresPublicNameInHeaderWhenNestedStatusContainerIsAnonymous() {
        val html = """
            <header>
              <div class="ranking">《순금 120%》켄류</div>
              <div class="status">
                <span>Funds : ${'$'} 1,720</span>
                <span>Time : 4100/6000</span>
              </div>
            </header>
        """.trimIndent()

        val status = parser.parse(html)

        assertEquals("Unknown", status.playerName)
    }

    @Test
    fun selectsAuthenticatedNameInNestedStatusContainer() {
        val html = """
            <header>
              <div class="ranking">《순금 120%》켄류</div>
              <div class="status">
                <span>《얼어붙은 손길》공민이</span>
                <span>Funds : ${'$'} 1,720</span>
                <span>Time : 4100/6000</span>
              </div>
            </header>
        """.trimIndent()

        val status = parser.parse(html)

        assertEquals("《얼어붙은 손길》공민이", status.playerName)
    }

    @Test
    fun parsesPlayerDisplayNameFromMenu2StatusBar() {
        listOf(
            "《얼어붙은 손길》공민이",
            "공민이",
        ).forEach { expectedPlayerName ->
            val html = """
                <div id="menu2">
                  <div style="width:100%">
                    <div style="width:33%;float:left">$expectedPlayerName</div>
                    <div style="width:67%;float:right">
                      <div style="width:50%;float:left"><span class="bold">Funds</span> : ${'$'}&nbsp;311,953,216</div>
                      <div style="width:50%;float:right"><span class="bold">Time</span> : 6000/6000</div>
                      <div style="width:50%;float:left"><span class="bold">Work</span> : Nothing</div>
                      <div style="width:50%;float:left"><span class="bold">Auction</span> : Nothing</div>
                    </div>
                    <div class="c-both"></div>
                  </div>
                </div>
            """.trimIndent()

            val status = parser.parse(html)

            assertEquals(expectedPlayerName, status.playerName)
        }
    }
}
