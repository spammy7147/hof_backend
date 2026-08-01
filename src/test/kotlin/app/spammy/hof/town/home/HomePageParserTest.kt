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
            <tr><td>[BAD3] 깨진 인코딩</td><td>조건 1/1</td><td>-</td><td><a href="?menu=housing&amp;action=complete&amp;no=%ZZ">완료</a></td></tr>
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
        assertEquals(null, snapshot.quests.first { it.name.contains("BAD3") }.actionId)
    }

    @Test
    fun `rest exposes only observed restore form`() {
        val html = """
            <header>Time : 371/6,000</header>
            <p>기본적으로 3,000의 Time이 회복됩니다.</p>
            <ul class="facilities"><li data-facility>푹신한 침대 · Time 200 추가</li></ul>
            <form method="post" action="?menu=rest"><input type="hidden" name="nonce" value="n1"><input type="submit" name="Rest" value="휴식실에서 회복한다"></form>
            <form method="post" action="?menu=rest"><input type="submit" name="Delete" value="삭제"></form>
            <form method="post" action="?menu=rest"><input name="target"><input type="submit" name="Other" value="휴식 복구"></form>
            <form method="post" action="?menu=other"><input type="submit" name="Rest" value="휴식한다"></form>
        """.trimIndent()
        val url = "http://sic.zerosic.com/ZeroHOF/index.php?menu=rest"
        val snapshot = parser.parse(HomeMode.REST, html, url, forms.parse(html, url))
        assertEquals(1, snapshot.actions.size)
        assertTrue(snapshot.actions.single().label.contains("회복"))
        assertEquals(371, snapshot.restStatus?.currentTime)
        assertEquals(6000, snapshot.restStatus?.maxTime)
        assertEquals(3000, snapshot.restStatus?.baseRecovery)
        assertEquals(false, snapshot.restStatus?.usedToday)
        assertEquals(listOf("푹신한 침대 · Time 200 추가"), snapshot.restStatus?.facilities)
    }

    @Test
    fun `rest marks explicit daily use without inventing recovery numbers`() {
        val html = "<p>오늘은 이미 휴식을 사용했습니다.</p>"
        val url = "http://sic.zerosic.com/ZeroHOF/index.php?menu=rest"

        val snapshot = parser.parse(HomeMode.REST, html, url, forms.parse(html, url))

        assertEquals(true, snapshot.restStatus?.usedToday)
        assertEquals(null, snapshot.restStatus?.baseRecovery)
        assertEquals(null, snapshot.restStatus?.facilityRecovery)
    }

    @Test
    fun `rest does not mistake update footer for a facility`() {
        val html = """
            <header>Time : 1,259/6,000</header>
            <p>기본적으로 300의 Time이 회복됩니다.</p>
            <h3>보유 중인 시설</h3>
            <div>UpDate - Manual - Tutorial - GameData - Top 현재 접속자 수는 6명 입니다. Copy Right Tekito 2007-2008.</div>
            <form method="post" action="?menu=rest"><input type="submit" name="Rest" value="휴식을 취한다"></form>
        """.trimIndent()
        val url = "http://sic.zerosic.com/ZeroHOF/index.php?menu=rest"

        val snapshot = parser.parse(HomeMode.REST, html, url, forms.parse(html, url))

        assertEquals(emptyList(), snapshot.restStatus?.facilities)
    }
}
