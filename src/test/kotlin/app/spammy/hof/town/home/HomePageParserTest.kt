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
    fun `home quest id remains stable when progress and section change`() {
        val url = "http://sic.zerosic.com/ZeroHOF/index.php?menu=housing"
        val availableHtml = """
            <h4>수락 가능한 퀘스트</h4><table>
            <tr><td>[H001] 손님 맞이</td><td>미션 0/1</td><td>보상 Item</td><td><a href="?menu=housing&amp;action=get&amp;no=11">수락</a></td></tr>
            </table>
        """.trimIndent()
        val claimableHtml = """
            <h4>진행 중</h4><table>
            <tr><td>[OTHER] 먼저 표시된 작업</td><td>조건 0/1</td><td>-</td><td>-</td></tr>
            <tr><td>[H001] 손님 맞이</td><td>미션 1/1</td><td>보상 Item</td><td><a href="?menu=housing&amp;action=complete&amp;no=11">완료</a></td></tr>
            </table>
        """.trimIndent()

        val available = parser.parse(HomeMode.HOME, availableHtml, url, forms.parse(availableHtml, url)).quests.single()
        val claimable = parser.parse(HomeMode.HOME, claimableHtml, url, forms.parse(claimableHtml, url))
            .quests.single { it.name.contains("H001") }

        assertEquals(available.id, claimable.id)
        assertEquals(HomeQuestState.CLAIMABLE, claimable.state)
    }

    @Test
    fun `home quests parse the observed three row housing tables by section`() {
        val html = """
            <div id="contents">
              <h4>진행중인 작업 목록</h4>
              <table>
                <tr><td>작업명</td><td>타입</td><td>제한</td><td>보상</td><td>행동</td></tr>
                <tr>
                  <td rowspan="3">[HQ9024] 선물 나눠주기</td><td>크리스마스(복각)</td><td>-</td>
                  <td>아이템( Present Box ) x1</td><td rowspan="3">-</td>
                </tr>
                <tr><td colspan="3">미션 : 아이템 반납( Present Sack ) - [ 0 / 4 ]</td></tr>
                <tr><td colspan="3">아직 나눠주지 못한 선물이 많습니다.</td></tr>
              </table>
              <h4>수락 가능한 작업 목록</h4>
              <table>
                <tr><td>작업명</td><td>타입</td><td>제한</td><td>보상</td><td>행동</td></tr>
                <tr>
                  <td rowspan="3">[HQ1] 빗자루 제작</td><td>자택 관리</td><td>-</td>
                  <td>아이템( Broom (Housing) ) x1</td>
                  <td rowspan="3"><a href="?menu=quest2&amp;action=get&amp;no=HQ1">수락</a></td>
                </tr>
                <tr><td colspan="3">미션 : 아이템 반납( Staff ) - [ 40 / 1 ]</td></tr>
                <tr><td colspan="3">마당을 깨끗이 청소해 주세요.</td></tr>
              </table>
              <h4>대기중인 작업 목록</h4>
              <table>
                <tr><td>작업명</td><td>타입</td><td>제한</td><td>보상</td><td>행동</td></tr>
                <tr>
                  <td rowspan="3">[0000] 마당 청소</td><td>자택 관리</td><td>-</td>
                  <td>아이템( Garbage Bag ) x1</td>
                  <td rowspan="3"><a href="?menu=quest2&amp;action=get&amp;no=HQ2">수락</a></td>
                </tr>
                <tr><td colspan="3">미션 : 아이템 반납( Broom (Housing) ) - [ 0 / 1 ]</td></tr>
                <tr><td colspan="3">빗자루 제작을 먼저 완료해야 합니다.</td></tr>
              </table>
            </div>
        """.trimIndent()
        val url = "http://sic.zerosic.com/ZeroHOF/index.php?menu=quest2"

        val snapshot = parser.parse(HomeMode.HOME, html, url, forms.parse(html, url))

        assertEquals(3, snapshot.quests.size)
        val active = snapshot.quests.single { it.name.contains("HQ9024") }
        assertEquals(HomeQuestState.ACTIVE, active.state)
        assertEquals("미션 : 아이템 반납( Present Sack ) - [ 0 / 4 ]", active.mission)
        assertEquals("아이템( Present Box ) x1", active.reward)
        val available = snapshot.quests.single { it.name.contains("HQ1") }
        assertEquals(HomeQuestState.AVAILABLE, available.state)
        assertNotNull(available.actionId)
        assertEquals("미션 : 아이템 반납( Staff ) - [ 40 / 1 ]", available.mission)
        val waiting = snapshot.quests.single { it.name.contains("마당 청소") }
        assertEquals(HomeQuestState.WAITING, waiting.state)
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

    @Test
    fun `rest excludes nested foot content from facility descriptions`() {
        val html = """
            <header>Time : 1,112/6,000</header>
            <p>기본적으로 300의 Time이 회복됩니다.</p>
            <ul class="facilities"><li data-facility>
                Sleep Wear (Housing) 편안한 숙면에 도움을 줍니다. 효과 : 휴식 시 1개를 소모하여 Time 회복량을 250 증가시킵니다.
                <div id="foot">UpDate - Manual - Tutorial - GameData - Top</div>
            </li></ul>
        """.trimIndent()
        val url = "http://sic.zerosic.com/ZeroHOF/index.php?menu=rest"

        val snapshot = parser.parse(HomeMode.REST, html, url, forms.parse(html, url))

        assertEquals(
            listOf("Sleep Wear (Housing) 편안한 숙면에 도움을 줍니다. 효과 : 휴식 시 1개를 소모하여 Time 회복량을 250 증가시킵니다."),
            snapshot.restStatus?.facilities,
        )
    }

    @Test
    fun `rest parses every facility cell when the housing table has two columns`() {
        val html = """
            <h3>보유 중인 시설</h3>
            <table border="3"><tbody>
              <tr>
                <td width="50%"><img src="./image/icon/stove.gif"> <b><font>Iron Stove (Housing)</font></b><p></p><font>추위로부터 집안을 따뜻하게 해줍니다.</font></td>
                <td width="50%"><img src="./image/icon/armor_027.gif"> <b><font>Sleep Wear (Housing)</font></b><p></p><font>편안한 숙면에 도움을 줍니다.</font></td>
              </tr>
              <tr>
                <td width="50%"><img src="./image/icon/artifact_7.gif"> <b><font>Dragon's Ink Print (Housing)</font></b><p></p><font>멋진 수룡의 어탁입니다.</font></td>
                <td width="50%"><img src="./image/icon/goldscreen.gif"> <b><font>Golden Folding Screen (Housing)</font></b><p></p><font>화려한 금박 병풍입니다.</font></td>
              </tr>
              <tr>
                <td width="50%"><img src="./image/icon/meltrobe.gif"> <b><font>Fire Curtain (Housing)</font></b><p></p><font>따뜻한 온기를 퍼트립니다.</font></td>
                <td width="50%"><img src="./image/icon/mat_025.png"> <b><font>Aromatic Wood (Housing)</font></b><p></p><font>고대의 숨결이 서린 향목입니다.</font></td>
              </tr>
              <tr>
                <td width="50%"><img src="./image/icon/ProtonShield.gif"> <b><font>Nanocell Energy Plate (Housing)</font></b><p></p><font>자가 증식하는 금속판입니다.</font></td>
                <td width="50%"><img src="./image/icon/OldCloak.gif"> <b><font>Antique Emblem Flag (Housing)</font></b><p></p><font>고풍스러운 문장 깃발입니다.</font></td>
              </tr>
              <tr>
                <td width="50%"><img src="./image/icon/mat_006.gif"> <b><font>Angel's Glass Feather (Housing)</font></b><p></p><font>무색 투명한 천사의 날개 비늘입니다.</font></td>
                <td width="50%"><img src="./image/icon/book.gif"> <b><font>Magical Card Book (Housing)</font></b><p></p><font>생생한 마법 도감입니다.</font></td>
              </tr>
              <tr></tr>
            </tbody></table>
        """.trimIndent()
        val url = "http://sic.zerosic.com/ZeroHOF/index.php?menu=rest"

        val snapshot = parser.parse(HomeMode.REST, html, url, forms.parse(html, url))
        val facilities = snapshot.restStatus?.facilities.orEmpty()

        val expectedNames =
            listOf(
                "Iron Stove (Housing)",
                "Sleep Wear (Housing)",
                "Dragon's Ink Print (Housing)",
                "Golden Folding Screen (Housing)",
                "Fire Curtain (Housing)",
                "Aromatic Wood (Housing)",
                "Nanocell Energy Plate (Housing)",
                "Antique Emblem Flag (Housing)",
                "Angel's Glass Feather (Housing)",
                "Magical Card Book (Housing)",
            )

        assertEquals(10, facilities.size)
        expectedNames.forEachIndexed { index, name -> assertTrue(facilities[index].startsWith(name)) }
    }

    @Test
    fun `rest keeps parsing facilities when more rows are added`() {
        val facilityCount = 240
        val rows = (1..facilityCount).chunked(2).joinToString("") { indexes ->
            val cells = indexes.joinToString("") { index ->
                "<td><b>Facility $index (Housing)</b><p></p><font>시설 설명 $index</font></td>"
            }
            "<tr>$cells</tr>"
        }
        val html = "<h3>보유 중인 시설</h3><table>$rows</table>"
        val url = "http://sic.zerosic.com/ZeroHOF/index.php?menu=rest"

        val facilities = parser.parse(HomeMode.REST, html, url, forms.parse(html, url))
            .restStatus?.facilities.orEmpty()

        assertEquals(facilityCount, facilities.size)
        assertTrue(facilities.last().startsWith("Facility 240 (Housing)"))
    }
}
