package app.spammy.hof.external.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class BattleMapParserTest {
    private val parser = BattleMapParser()

    @Test
    fun `completed catacomb has no victories remaining while unattempted next map stays available`() {
        val html = """
            <div>HOF 마을 지하 묘지(적정 레벨 : 45-60)( 6 )</div>
            <div id="mapgroup9">
              <div>
                <a href="index.php?sp_common=Conc001">Catacomb- 지하 묘소 - 열기가 느껴지는 묘소</a>
                [ 승리!(총 2회 도전) ] (타임 소모 : 0)
              </div>
              <div>
                <a href="index.php?sp_common=Conc002">Catacomb- 지하 묘소 - 차갑게 얼어붙은 묘소</a>
                [ 0회 도전함 ] (타임 소모 : 0)
              </div>
            </div>
        """.trimIndent()

        val maps = parser.parse("adventure_map", "sp_common", html)

        assertEquals(listOf("Conc001", "Conc002"), maps.map { it.mapCode })
        assertEquals(listOf(0, null), maps.map { it.winCount })
        assertEquals(listOf(null, null), maps.map { it.cooldownRemainingSeconds })
    }

    @Test
    fun `separates trailing cooldown state from normal and easy placeholder names`() {
        val html = """
            <div>천공성 이지 모드 (적정 레벨 : 50-60) (1)</div>
            <div id="mapgroup1">
              <a href="index.php?sp_hunt#">Castle In The Sky- 천공성(제 2탑) (EASY) (19분) 남음 (타임 소모 : 50)</a>
            </div>
            <div>천공성 (적정 레벨 : 55-63) (1)</div>
            <div id="mapgroup2">
              <a href="index.php?sp_hunt#">Castle In The Sky- 천공성(제 2탑) (1시간 4분) 남음 (Time : 50)</a>
            </div>
        """.trimIndent()

        val maps = parser.parse("adventure_map", "sp_common", html)

        assertEquals(
            listOf(
                "Castle In The Sky- 천공성(제 2탑) (EASY)",
                "Castle In The Sky- 천공성(제 2탑)",
            ),
            maps.map { it.name },
        )
        assertEquals(listOf(1_140L, 3_840L), maps.map { it.cooldownRemainingSeconds })
        assertEquals(listOf(50, 50), maps.map { it.requiredTime })
        assertEquals(listOf("천공성 이지 모드", "천공성"), maps.map { it.groupName })
        assertEquals(listOf(null, null), maps.map { it.mapCode })
    }

    @Test
    fun `parses the raid next battle cooldown expressed in seconds`() {
        val html = """
            <div id="mapgroup1">
              <div>
                <span>다음 전투까지 99초 남음</span>
                <a href="index.php?raid_common=raid001">Raid - 마을 시가지</a>
              </div>
            </div>
        """.trimIndent()

        val map = parser.parse("raid", "raid_common", html).single()

        assertEquals("raid001", map.mapCode)
        assertEquals(99L, map.cooldownRemainingSeconds)
    }

    @Test
    fun `resolves numeric union link labels to their map names`() {
        val html = """
            <div><a href="index.php?union=0003">0003</a></div>
            <div><a href="index.php?union=0004">0004</a></div>
        """.trimIndent()

        val maps = parser.parse("union", "union", html)

        assertEquals(listOf("도적소탕", "사막의 살인적"), maps.map { it.name })
    }
}
