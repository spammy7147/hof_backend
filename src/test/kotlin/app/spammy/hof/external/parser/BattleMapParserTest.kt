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
        assertEquals(
            RaidCooldownAssociationStatus.HOF_DIRECT,
            parser.inspectRaidCooldown(html, listOf(map)).status,
        )
    }

    @Test
    fun `바깥 wrapper의 레이드 타이머는 실제 fixture 전까지 실행 가능으로 추정하지 않는다`() {
        val html = """
            <section class="raid-wrapper">
              <span>다음 전투까지 99초 남음</span>
              <div class="map-link"><a href="index.php?raid_common=raid001">Raid - 마을 시가지</a></div>
            </section>
        """.trimIndent()
        val maps = parser.parse("raid", "raid_common", html)

        val observation = parser.inspectRaidCooldown(html, maps)

        assertEquals(null, maps.single().cooldownRemainingSeconds)
        assertEquals(RaidCooldownAssociationStatus.AMBIGUOUS, observation.status)
        assertEquals(listOf(99L), observation.candidateSeconds)
        assertEquals(1, observation.mapCount)
    }

    @Test
    fun `레이드 쿨타임 광고의 숫자를 파싱하지 못하면 incomplete 근거를 만든다`() {
        val html = """
            <div><span>다음 전투까지 알 수 없는 초 남음</span></div>
            <div><a href="index.php?raid_common=raid001">Raid - 마을 시가지</a></div>
        """.trimIndent()
        val maps = parser.parse("raid", "raid_common", html)

        val observation = parser.inspectRaidCooldown(html, maps)

        assertEquals(RaidCooldownAssociationStatus.PARSE_FAILED, observation.status)
        assertEquals("RAID_COOLDOWN_PARSE_FAILED", observation.reasonCode)
        assertEquals(64, observation.responseShapeFingerprint.length)
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

    @Test
    fun `reads an image-only union link name from its card`() {
        val html = """
            <table>
              <tr>
                <td>
                  <div class="carpet_frame">
                    <div class="land"><a href="?union=0001"><img src="./image/char/GoblinTinkerLord.gif"></a></div>
                    <div class="bold dmg">고블린 침공군</div>
                    LvLimit:300
                  </div>
                </td>
              </tr>
            </table>
        """.trimIndent()

        val map = parser.parse("union", "union", html).single()

        assertEquals("0001", map.mapCode)
        assertEquals("고블린 침공군", map.name)
    }

    @Test
    fun `applies the page level union cooldown to every visible union map`() {
        val html = """
            <div id="mapgroup1">
              <a href="index.php?union=0003">도적소탕</a>
              <a href="index.php?union=0004">사막의 살인적</a>
            </div>
            <h4>UnionMonster</h4>
            <div>Time left to next battle : 8:32</div>
        """.trimIndent()

        val maps = parser.parse("union", "union", html)

        assertEquals(listOf(512L, 512L), maps.map { it.cooldownRemainingSeconds })
    }
}
