package app.spammy.hof.battle.service

import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import kotlin.test.*

class BattleHtmlArchiveTest {
    @Test
    fun `실제 상세 로그의 이미지 배경 배치 색상과 표를 보존한다`() {
        // 비식별화로 공백이 생긴 파일명에 따옴표를 보완한다. 원본의 sprite URL은 정상 HTML이다.
        val source = javaClass.getResource("/fixtures/battle/detail-observed-sanitized.html")!!.readText()
            .replace("src=/image/char_rev/테스트 마법사.gif", "src=\"/image/char_rev/테스트 마법사.gif\"")
        val saved = Jsoup.parse(archiveBattleHtml(source, detailed = true))
        val original = Jsoup.parse(source)
        assertEquals(original.select("img").size, saved.select("img").size)
        assertTrue(saved.select("img").all { it.attr("src").startsWith("https://hof.zerosic.com/image/") })
        assertTrue(saved.select("[style]").any { it.attr("style").contains("https://hof.zerosic.com/image/other/bg_garden4.gif") })
        assertTrue(saved.select("[style]").any { it.attr("style").contains("width:480px") && it.attr("style").contains("height:315px") })
        assertEquals(original.select("td").map { it.text() }, saved.select("td").map { it.text() })
        assertEquals(original.select(".dmg").size, saved.select(".dmg").size)
        assertTrue(saved.select("style").single().data().contains(".dmg"))
    }

    @Test
    fun `외부 리소스 스크립트 폼과 위험한 CSS는 보관하지 않는다`() {
        val saved = Jsoup.parse(archiveBattleHtml("""
            <script>secret()</script><form><input value="password"></form>
            <img src="https://example.com/tracker"><img src="/index.php?logout">
            <img src="/image/icon/test.gif" onerror="evil()">
            <div style="background-image:url(https://example.com/tracker);width:expression(evil());height:100px" onclick="evil()">전투</div>
            <a href="javascript:evil()">로그</a>
        """, detailed = true))
        assertEquals(1, saved.select("img").size)
        assertTrue(saved.select("script,form,input,[onclick],[onerror],a[href]").isEmpty())
        assertFalse(saved.outerHtml().contains("example.com"))
        assertFalse(saved.outerHtml().contains("expression"))
        assertTrue(saved.select("div[style]").single().attr("style").contains("height:100px"))
    }
}
