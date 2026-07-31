package app.spammy.hof.town.home

import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.home.model.HomeMode
import app.spammy.hof.town.home.model.HomeQuestState
import app.spammy.hof.town.home.parser.HomePageParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HomePageParserTest {
    private val forms = HofFormParser()
    private val parser = HomePageParser()

    @Test
    fun `home quests preserve accept active claim lifecycle and reject cross origin action`() {
        val html = """
            <div id="contents"><h4>수락 가능한 퀘스트</h4><table>
            <tr><td>[H001] 손님 맞이</td><td>미션 · 아이템 반납 0/1</td><td>보상 Item x1</td><td><a href="?menu=housing&amp;action=get&amp;no=11">수락</a></td></tr></table>
            <h4>진행 중</h4><table>
            <tr><td>[H002] 방 정리</td><td>조건 달성 1/1</td><td>보상 Funds</td><td><a href="?menu=housing&amp;action=complete&amp;no=12">완료</a></td></tr>
            <tr><td>[H003] 창고 정리</td><td>조건 0/1</td><td>-</td><td>-</td></tr>
            <tr><td>[BAD] 외부</td><td>조건 1/1</td><td>-</td><td><a href="https://evil.test/?action=complete&amp;no=9">완료</a></td></tr>
            <tr><td>[BAD2] 다른 메뉴</td><td>조건 1/1</td><td>-</td><td><a href="?menu=quest&amp;action=complete&amp;no=10">완료</a></td></tr>
            </table></div>
        """.trimIndent()
        val url = "http://sic.zerosic.com/ZeroHOF/index.php?menu=housing"
        val snapshot = parser.parse(HomeMode.HOME, html, url, forms.parse(html, url))

        assertEquals(HomeQuestState.AVAILABLE, snapshot.quests.first { it.name.contains("H001") }.state)
        assertEquals(HomeQuestState.CLAIMABLE, snapshot.quests.first { it.name.contains("H002") }.state)
        assertEquals(HomeQuestState.ACTIVE, snapshot.quests.first { it.name.contains("H003") }.state)
        assertNotNull(snapshot.quests.first { it.name.contains("H001") }.actionId)
        assertEquals(null, snapshot.quests.first { it.name.contains("BAD") }.actionId)
        assertEquals(null, snapshot.quests.first { it.name.contains("BAD2") }.actionId)
    }

    @Test
    fun `rest exposes only observed restore form`() {
        val html = """
            <form method="post" action="?menu=rest"><input type="hidden" name="nonce" value="n1"><input type="submit" name="Rest" value="휴식실에서 회복한다"></form>
            <form method="post" action="?menu=rest"><input type="submit" name="Delete" value="삭제"></form>
        """.trimIndent()
        val url = "http://sic.zerosic.com/ZeroHOF/index.php?menu=rest"
        val snapshot = parser.parse(HomeMode.REST, html, url, forms.parse(html, url))
        assertEquals(1, snapshot.actions.size)
        assertTrue(snapshot.actions.single().label.contains("회복"))
    }
}
