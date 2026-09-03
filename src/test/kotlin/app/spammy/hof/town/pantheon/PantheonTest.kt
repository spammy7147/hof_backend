package app.spammy.hof.town.pantheon

import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.pantheon.model.ShrineAction
import app.spammy.hof.town.pantheon.parser.PantheonParser
import kotlin.test.*

class PantheonTest {
    private val parser = PantheonParser()
    private val forms = HofFormParser()
    private val base = "https://hof.zerosic.com/index.php?menu=pantheon"

    @Test
    fun `신전 거리의 열 개 신전을 관측 링크로 파싱한다`() {
        val street = parser.parseStreet(fixture("street.html"), base)
        assertEquals(10, street.shrines.size)
        assertTrue(street.shrines.all { it.id.length == 32 })
        assertTrue(street.shrines.none { it.detailUrl.contains("evil.example") })
        assertEquals("green", street.shrines.first { it.name.contains("라이라") }.color)
    }

    @Test
    fun `장소명과 실제 링크 문구가 나뉜 캡처형 목록도 li의 관측 문맥으로 파싱한다`() {
        val html = "<ul><li>마르두크의 전당 - <a href='?menu=marduktemple'>군신 마르두크(Marduk)</a></li></ul>"
        val street = parser.parseStreet(html, base)
        assertEquals(1, street.shrines.size)
        assertEquals("군신 마르두크", street.shrines.single().name)
        assertEquals("Marduk", street.shrines.single().alias)
        assertEquals("https://hof.zerosic.com/index.php?menu=marduktemple", street.shrines.single().detailUrl)
    }

    @Test
    fun `상세 정보와 현재 페이지에 관측된 서로 다른 action만 파싱한다`() {
        val url = "$base&shrine=Marduk"
        val html = fixture("detail.html")
        val detail = parser.parseDetail("opaque", html, url, forms.parse(html, url))
        assertEquals("Neutral", detail.alignment)
        assertEquals(listOf("전쟁", "무예", "투쟁심"), detail.domains)
        assertEquals(5, detail.actions.size)
        assertTrue(detail.actions.any { it.type == ShrineAction.DONATE_PERCENT && it.fundsPercent == 1 })
        assertTrue(detail.actions.any { it.type == ShrineAction.BUY_PRIEST_ITEM && it.costFunds == 10_000L })
        assertTrue(detail.actions.any { it.type == ShrineAction.DONATE_ITEM && it.itemName == "Funds Bag" && it.itemQuantity == 3 })
        assertEquals(listOf("doctrine"), detail.actions.single { it.type == ShrineAction.CHECK_DOCTRINE }.query?.map { it.name })
    }

    @Test
    fun `실제 신전의 한 form에 함께 있는 네 submit을 각각 action으로 파싱한다`() {
        val url = "https://hof.zerosic.com/index.php?menu=marduktemple"
        val html = """
            <h4>마르두크의 전당 - 군신 마르두크(Marduk)</h4>
            <form method="post" action="?menu=marduktemple">
              <input type="submit" name="MardukTempleRule" value="교리를 확인한다.">
              <input type="submit" name="MardukTempleBaptism" value="세례 아이템을 구입한다(10,000 Funds).">
              <input type="submit" name="MardukTempleDonation" value="교단에 기부한다(50,000 Funds).">
              <input type="submit" name="MardukTempleDonation2" value="교단에 기부한다(자신의 Funds의 1%,최대 10,000,000).">
            </form>
        """.trimIndent()

        val actions = parser.parseDetail("marduk", html, url, forms.parse(html, url)).actions

        assertEquals(4, actions.size)
        assertEquals(
            setOf(
                ShrineAction.CHECK_DOCTRINE,
                ShrineAction.BUY_PRIEST_ITEM,
                ShrineAction.DONATE_FIXED,
                ShrineAction.DONATE_PERCENT,
            ),
            actions.map { it.type }.toSet(),
        )
    }

    @Test
    fun `외부 링크와 추측 동작은 노출하지 않는다`() {
        val html = """<a href='https://evil.example/?menu=pantheon&shrine=x'>악신의 신전</a><a href='?menu=pantheon&shrine=x'>알 수 없는 곳</a>"""
        assertTrue(parser.parseStreet(html, base).shrines.isEmpty())
        val detailHtml = """<h4>신전</h4><a href='https://evil.example/a'>교리를 확인한다</a><button>모두 기부한다</button>"""
        assertTrue(parser.parseDetail("x", detailHtml, "$base&shrine=x", forms.parse(detailHtml, "$base&shrine=x")).actions.isEmpty())
    }

    @Test
    fun `중복 query 잘못된 encoding path escape와 중복 신전 identity를 거부한다`() {
        val html = """
            <a href='?menu=pantheon&shrine=x&shrine=y'>중복 신전</a>
            <a href='?menu=pantheon&shrine=%ZZ'>오류 신전</a>
            <a href='/other.php?menu=pantheon&shrine=z'>탈출 신전</a>
            <a href='?menu=pantheon&shrine=a'>같은 신전(Alias)</a>
            <a href='?menu=pantheon&shrine=b'>다른 신전(Alias)</a>
        """.trimIndent()
        assertTrue(parser.parseStreet(html, base).shrines.isEmpty())
    }

    @Test
    fun `POST는 여러 submit을 개별 허용하되 유일 hidden과 명시된 1퍼센트만 허용한다`() {
        val url = "$base&shrine=Marduk"
        val html = """
            <h4>신전</h4>
            <form method='post' action='?menu=pantheon&shrine=Marduk'>
              <input type='hidden' name='nonce' value='a'><input type='hidden' name='nonce' value='b'>
              <input type='submit' name='donate' value='교단에 기부한다(50,000 Funds)'>
            </form>
            <form method='post' action='?menu=pantheon&shrine=Marduk'>
              <input type='submit' name='one' value='교리를 확인한다'>
              <input type='submit' name='two' value='사제 아이템을 구입한다(10,000 Funds)'>
            </form>
            <a href='?menu=pantheon&shrine=Marduk&donate=2'>교단에 기부한다(자신의 Funds의 2%)</a>
        """.trimIndent()
        val actions = parser.parseDetail("x", html, url, forms.parse(html, url)).actions
        assertEquals(2, actions.size, actions.toString())
        assertEquals(
            setOf(ShrineAction.CHECK_DOCTRINE, ShrineAction.BUY_PRIEST_ITEM),
            actions.map { it.type }.toSet(),
        )
    }

    @Test
    fun `외부 form action과 과도한 hidden 값은 노출하지 않는다`() {
        val url = "$base&shrine=Marduk"
        val html = """
            <h4>신전</h4>
            <form method='post' action='https://evil.example/steal'>
              <input type='hidden' name='nonce' value='a'>
              <input type='submit' name='donate' value='교단에 기부한다(50,000 Funds)'>
            </form>
            <form method='post' action='?menu=pantheon&shrine=Marduk'>
              <input type='hidden' name='nonce' value='${"x".repeat(501)}'>
              <input type='submit' name='buy' value='사제 아이템을 구입한다(10,000 Funds)'>
            </form>
            <form method='post' action='?menu=pantheon&shrine=Marduk'>
              <input type='hidden' name='donate' value='shadow'>
              <input type='submit' name='donate' value='교단에 기부한다(50,000 Funds)'>
            </form>
        """.trimIndent()
        assertTrue(parser.parseDetail("x", html, url, forms.parse(html, url)).actions.isEmpty())
    }

    @Test
    fun `같은 이름이라도 신전 또는 action 대상이 바뀌면 opaque id가 바뀐다`() {
        val oldStreet = parser.parseStreet("<a href='?menu=pantheon&shrine=a'>빛의 신전(Alias)</a>", base)
        val newStreet = parser.parseStreet("<a href='?menu=pantheon&shrine=b'>빛의 신전(Alias)</a>", base)
        assertNotEquals(oldStreet.shrines.single().id, newStreet.shrines.single().id)

        val oldHtml = "<h4>신전</h4><a href='?menu=pantheon&shrine=a&doctrine=read'>교리를 확인한다</a>"
        val newHtml = oldHtml.replace("doctrine=read", "doctrine=changed")
        val url = "$base&shrine=a"
        val oldAction = parser.parseDetail("x", oldHtml, url, forms.parse(oldHtml, url)).actions.single()
        val newAction = parser.parseDetail("x", newHtml, url, forms.parse(newHtml, url)).actions.single()
        assertNotEquals(oldAction.id, newAction.id)
    }

    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/fixtures/town/pantheon/$name")).readText()
}
