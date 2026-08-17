package app.spammy.hof.external.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HofMainStatusParserTest {
    private val parser = HofMainStatusParser()

    @Test
    fun parsesMainStatusValuesFromHeaderTable() {
        val html = """
            <table id="menu2">
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
    fun parsesMainStatusValuesFromNestedMenuDivs() {
        val html = """
            <div id="menu2">
              <div style="width:100%">
                <div style="width:33%;float:left">《얼어붙은 손길》공민이</div>
                <div style="width:67%;float:right">
                  <div style="width:50%;float:left"><span class="bold">Funds</span> : ${'$'}&nbsp;844,370,206</div>
                  <div style="width:50%;float:right"><span class="bold">Time</span> : 223/6000</div>
                  <div style="width:50%;float:left"><span class="bold">Work</span> : Nothing</div>
                  <div style="width:50%;float:left"><span class="bold">Auction</span> : Nothing</div>
                </div>
                <div class="c-both"></div>
              </div>
            </div>
        """.trimIndent()

        val status = parser.parse(html)

        assertEquals("《얼어붙은 손길》공민이", status.playerName)
        assertEquals(844_370_206L, status.funds)
        assertEquals(223, status.timeCurrent)
        assertEquals(6000, status.timeMax)
        assertEquals("Nothing", status.work)
        assertEquals("Nothing", status.auction)
    }

    @Test
    fun readsAccountStatusOnlyFromMenu2AndIgnoresInternalMenuDecoys() {
        val html = """
            <div id="menu">Funds : ${'$'} 999 Time : 999/999 Work : Wrong Auction : Wrong</div>
            <table id="menu2"><tr>
              <td>공민이</td>
              <td>Funds : ${'$'} 100<br>Work : Nothing</td>
              <td>Time : 10/6000<br>Auction : Nothing</td>
            </tr></table>
        """.trimIndent()

        val status = parser.parse(html)

        assertEquals(100L, status.funds)
        assertEquals(10, status.timeCurrent)
        assertEquals(6000, status.timeMax)
        assertEquals("Nothing", status.work)
        assertEquals("Nothing", status.auction)
    }

    @Test
    fun parsesAnUntitledPlayerNameFromAHeaderTable() {
        val html = """
            <table id="menu2">
              <tr>
                <td>공민이</td>
                <td>Funds : ${'$'} 100<br>Work : Nothing</td>
                <td>Time : 100/100<br>Auction : Nothing</td>
              </tr>
            </table>
        """.trimIndent()

        assertEquals("공민이", parser.parse(html).playerName)
    }

    @Test
    fun parsesFlattenedTextWithinMenu2() {
        val html = """
            <div id="menu2">
              Hall of Fame Ver ZeroHOF Top자격단전투모험시나리오아이템마을설정로그BBSCHAT
              《얼어붙은 손길》공민이 Funds : $ 1,720 Time : 4100/6000 Work : 12:34 Auction : Nothing
              소셜 Lv.60 Social Knight
            </div>
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
            <table id="menu2">
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
            <table id="menu2">
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
            <table id="menu2">
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
            <div id="menu2">
              《얼어붙은 손길》공민이
              <div class="ranking">《순금 120%》켄류</div>
              Funds : ${'$'} 1,720 Time : 4100/6000 Work : 12:34 Auction : Nothing
            </div>
        """.trimIndent()

        val status = parser.parse(html)

        assertEquals("《얼어붙은 손길》공민이", status.playerName)
    }

    @Test
    fun ignoresPublicNameInOuterRowWhenNestedStatusRowIsAnonymous() {
        val html = """
            <table id="menu2">
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
            <table id="menu2">
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
            <header id="menu2">
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
            <header id="menu2">
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
    fun ignoresPageContentAfterMenu2StatusBar() {
        val html = """
            <div id="menu2">
              <div style="width:100%">
                <div style="width:33%;float:left">《얼어붙은 손길》공민이</div>
                <div style="width:67%;float:right">
                  <div><span class="bold">Funds</span> : ${'$'}&nbsp;343,509,180</div>
                  <div><span class="bold">Time</span> : 4522/6000</div>
                  <div><span class="bold">Work</span> : Nothing</div>
                  <div><span class="bold">Auction</span> : Nothing</div>
                </div>
              </div>
            </div>
            <div>Pattern Load Character Settings Battle Configuration</div>
        """.trimIndent()

        val status = parser.parse(html)

        assertEquals("《얼어붙은 손길》공민이", status.playerName)
        assertEquals(343509180L, status.funds)
        assertEquals(4522, status.timeCurrent)
        assertEquals(6000, status.timeMax)
        assertEquals("Nothing", status.work)
        assertEquals("Nothing", status.auction)
    }

    @Test
    fun ignoresStatusLookalikesOutsideMenu2() {
        val html = """
            <div>
              《가짜 타이틀》가짜이름
              Funds : ${'$'} 999 Time : 999/999 Work : Pattern Load Auction : Character Settings
            </div>
            <div id="menu2">
              <div>
                <div>《얼어붙은 손길》공민이</div>
                <div>
                  <div><span>Funds</span> : ${'$'} 343,509,180</div>
                  <div><span>Time</span> : 4522/6000</div>
                  <div><span>Work</span> : Nothing</div>
                  <div><span>Auction</span> : Nothing</div>
                </div>
              </div>
            </div>
            <div>Funds : ${'$'} 111 Time : 111/111 Work : Battle Auction : Configuration</div>
        """.trimIndent()

        val status = parser.parse(html)

        assertEquals("《얼어붙은 손길》공민이", status.playerName)
        assertEquals(343509180L, status.funds)
        assertEquals(4522, status.timeCurrent)
        assertEquals(6000, status.timeMax)
        assertEquals("Nothing", status.work)
        assertEquals("Nothing", status.auction)
    }

    @Test
    fun returnsIncompleteStatusWhenMenu2IsMissing() {
        val status = parser.parse(
            """
                <main>
                  《가짜 타이틀》가짜이름
                  Funds : ${'$'} 999 Time : 999/999 Work : Pattern Load Auction : Character Settings
                </main>
            """.trimIndent(),
        )

        assertEquals("Unknown", status.playerName)
        assertNull(status.funds)
        assertNull(status.timeCurrent)
        assertNull(status.timeMax)
        assertEquals("Unknown", status.work)
        assertEquals("Unknown", status.auction)
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
