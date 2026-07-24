package app.spammy.hof.external.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class BattleMapParserTest {
    private val parser = BattleMapParser()

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
}
