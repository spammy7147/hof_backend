package app.spammy.hof.external.parser

import app.spammy.hof.battle.model.BattleMapKeyMode
import app.spammy.hof.external.model.HofBattleMap
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import org.slf4j.LoggerFactory

class BattleMapParserTest {
    private val parser = BattleMapParser()

    @Test
    fun detectsThreeBattleCapabilityOnlyFromTheActualSubmitControl() {
        val threeBattle = parser.parse(
            categoryId = "battle_map",
            queryName = "common",
            html = """
                <form method="post" action="index.php?common=snow22">
                  <a href="index.php?common=snow22">Map</a>
                  <input type="submit" name="monster_battle_10" value="Battle !">
                </form>
            """.trimIndent(),
        )
        val singleBattle = parser.parse(
            categoryId = "battle_map",
            queryName = "common",
            html = """
                <form method="post" action="index.php?common=snow22">
                  <a href="index.php?common=snow22">Map</a>
                  <button type="submit" name="monster_battle">Battle !</button>
                </form>
            """.trimIndent(),
        )
        val unrelatedThree = parser.parse(
            categoryId = "battle_map",
            queryName = "common",
            html = "<a href='index.php?common=snow22'>Map: 3 battles available</a>",
        )

        assertEquals(true, threeBattle.single().supportsThreeBattles)
        assertEquals(false, singleBattle.single().supportsThreeBattles)
        assertNull(unrelatedThree.single().supportsThreeBattles)
    }

    @Test
    fun scopesThreeBattleCapabilityToEachMapsAssociatedExecutableForm() {
        val maps = parser.parse(
            categoryId = "battle_map",
            queryName = "common",
            html = """
                <form id="triple" action="index.php?common=triple">
                  <a href="index.php?common=triple">Triple map</a>
                  <button name="monster_battle_10">Battle three</button>
                </form>
                <form id="single" action="index.php?common=single">
                  <a href="index.php?common=single">Single map</a>
                  <input type="submit" name="monster_battle" value="Battle one">
                </form>
                <form id="disabled" action="index.php?common=disabled">
                  <a href="index.php?common=disabled">Disabled map</a>
                  <input type="submit" name="monster_battle_10" disabled value="Battle three">
                </form>
                <form id="wrong-type" action="index.php?common=wrong">
                  <a href="index.php?common=wrong">Wrong type map</a>
                  <button type="button" name="monster_battle_10">Not submit</button>
                </form>
                <input type="image" name="monster_battle_10" value="Detached">
                <button form="single" name="monster_battle_10" disabled>Disabled association</button>
            """.trimIndent(),
        )

        assertEquals(
            mapOf<String?, Boolean>("triple" to true, "single" to false, "disabled" to false, "wrong" to false),
            maps.associate { it.mapCode to it.supportsThreeBattles },
        )
    }

    @Test
    fun honorsValidExplicitHtmlFormAssociationForTheSameMap() {
        val map = parser.parse(
            categoryId = "battle_map",
            queryName = "common",
            html = """
                <form id="map-form" action="index.php?common=associated">
                  <a href="index.php?common=associated">Associated map</a>
                </form>
                <input form="map-form" type="submit" name="monster_battle_10" value="Battle three">
            """.trimIndent(),
        ).single()

        assertEquals(true, map.supportsThreeBattles)
    }

    @Test
    fun detailCapabilityObservationIsTriStateAndRequiresTheMapsExecutionForm() {
        val triple = parser.observeThreeBattleCapability(
            "common", "detail", """
                <form id="detail-form" action="index.php?common=detail">
                  <button name="monster_battle_10">Battle three</button>
                </form>
            """.trimIndent(),
        )
        val single = parser.observeThreeBattleCapability(
            "common", "detail", """
                <form action="index.php?common=detail">
                  <button name="monster_battle">Battle one</button>
                </form>
            """.trimIndent(),
        )
        val unrelated = parser.observeThreeBattleCapability(
            "common", "detail", """
                <form action="index.php?common=other">
                  <button name="monster_battle_10">Battle three</button>
                </form>
            """.trimIndent(),
        )

        assertEquals(true, triple)
        assertEquals(false, single)
        assertNull(unrelated)
    }

    @Test
    fun detailEmptyActionUsesSuppliedAuthenticatedCurrentMapContext() {
        val currentMap = parser.observeThreeBattleCapability(
            queryName = "common",
            mapCode = "detail",
            html = "<form action=''><button name='monster_battle_10'>Battle three</button></form>",
            authoritativeCurrentMapCode = "detail",
        )
        val wrongCurrentMap = parser.observeThreeBattleCapability(
            queryName = "common",
            mapCode = "detail",
            html = "<form action=''><button name='monster_battle_10'>Battle three</button></form>",
            authoritativeCurrentMapCode = "other",
        )
        val explicitlyOther = parser.observeThreeBattleCapability(
            queryName = "common",
            mapCode = "detail",
            html = "<form action='index.php?common=other'><button name='monster_battle_10'>Battle three</button></form>",
            authoritativeCurrentMapCode = "detail",
        )

        assertEquals(true, currentMap)
        assertNull(wrongCurrentMap)
        assertNull(explicitlyOther)
    }

    @Test
    fun imageSubmitDoesNotProveThreeBattleCapability() {
        val observed = parser.observeThreeBattleCapability(
            "common",
            "detail",
            """
                <form action="index.php?common=detail">
                  <input type="image" name="monster_battle_10" value="Battle three">
                </form>
            """.trimIndent(),
        )

        assertEquals(false, observed)
    }

    @Test
    fun parsesBattleMapsFromMatchingLinksAndKeepsDisplayOrder() {
        val maps = parser.parse(
            categoryId = "battle_map",
            queryName = "common",
            html = """
                <html>
                  <body>
                    <div>
                      <a href="index.php?common=snow22">Frosty Mountain - 대충산</a>
                      <span>Time : 100</span>
                    </div>
                    <div>
                      <div id="mapgroup99">
                        <a href="/ZeroHOF/index.php?sp_common=sea001">다른 카테고리</a>
                      </div>
                    </div>
                    <div>
                      <a href="index.php?common=snow22">중복 링크</a>
                    </div>
                    <div>
                      <a href="index.php?common=arena01">Arena - 결투장 3 가능</a>
                    </div>
                  </body>
                </html>
            """.trimIndent(),
        )

        assertEquals(listOf("snow22", "arena01"), maps.map { it.mapCode })
        assertEquals("Frosty Mountain - 대충산", maps[0].name)
        assertEquals(100, maps[0].requiredTime)
        assertEquals(3, maps[1].availableCount)
        assertTrue(maps.all { it.enabled })
    }

    @Test
    fun usesLaterVisibleNameWhenScenarioImageLinkAppearsFirst() {
        val maps = parser.parse(
            categoryId = "scenario_ocean",
            queryName = "common",
            html = """
                <html>
                  <body>
                    <a href="index.php?common=Sink01"><img src="coral.gif"></a>
                    <a href="index.php?common=Sink01"></a>
                    <a href="index.php?common=Sink01">산호 숲</a>

                    <a href="index.php?common=Sink03"><img src="sea.gif"></a>
                    <a href="index.php?common=Sink03"></a>
                    <a href="index.php?common=Sink03">바다 평원</a>

                    <a href="index.php?common=Sink02"><img src="rune.gif"></a>
                    <a href="index.php?common=Sink02"></a>
                    <a href="index.php?common=Sink02">불길한 바닥</a>

                    <a href="index.php?common=Sink04"><img src="ship.gif"></a>
                    <a href="index.php?common=Sink04"></a>
                    <a href="index.php?common=Sink04">선실 내부</a>

                    <a href="index.php?common=Sink05"><img src="ship.gif"></a>
                    <a href="index.php?common=Sink05"></a>
                    <a href="index.php?common=Sink05">침수 구역</a>

                    <a href="index.php?common=Sink06"><img src="ship.gif"></a>
                    <a href="index.php?common=Sink06"></a>
                    <a href="index.php?common=Sink06">하부 갑판</a>
                  </body>
                </html>
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                "Sink01" to "산호 숲",
                "Sink03" to "바다 평원",
                "Sink02" to "불길한 바닥",
                "Sink04" to "선실 내부",
                "Sink05" to "침수 구역",
                "Sink06" to "하부 갑판",
            ),
            maps.map { map -> map.mapCode to map.name },
        )
    }

    @Test
    fun parsesSavedBattleMapFixtureWithGroups() {
        val maps = parser.parse(
            categoryId = "battle_map",
            queryName = "common",
            html = fixtureHtml("전투맵.html"),
        )

        assertEquals(88, maps.size)

        val snow22 = maps.first { it.mapCode == "snow22" }
        assertEquals("Frosty Mountain- 대충산(마도사의 은신처)", snow22.name)
        assertEquals("대충산 위험지역", snow22.groupName)
        assertEquals("40-60", snow22.recommendedLevel)
        assertEquals(7, snow22.groupOrder)
        assertEquals(4, snow22.mapOrder)
        assertEquals("http://sic.zerosic.com/ZeroHOF/index.php?common=snow22", snow22.rawHref)
    }

    @Test
    fun parsesSavedAdventureMapFixtureWithRequiredTimeAndDeduplication() {
        val maps = parser.parse(
            categoryId = "adventure_map",
            queryName = "sp_common",
            html = fixtureHtml("모험맵.html"),
        )

        assertEquals(103, maps.size)
        assertEquals(1, maps.count { it.mapCode == "min11" })

        val herb = maps.first { it.mapCode == "HerbS01" }
        assertEquals("Wandai- 완다이 산맥(빅풋의 영역)", herb.name)
        assertEquals(114, herb.keyCount)
        assertEquals("특수 채집 구역", herb.groupName)
        assertEquals("??", herb.recommendedLevel)
        assertEquals(100, herb.requiredTime)
    }

    @Test
    fun separatesRequiredKeyCountFromMapName() {
        val maps = parser.parse(
            categoryId = "adventure_map",
            queryName = "sp_common",
            html = """
                <html>
                  <body>
                    <div>
                      <a href="index.php?sp_common=warehouse">
                        Frosty Mountain- 대충산(리치의 창고) ( x9 )
                      </a>
                    </div>
                    <div>
                      <a href="index.php?sp_common=plain">Frosty Mountain- 대충산(고원)</a>
                    </div>
                  </body>
                </html>
            """.trimIndent(),
        )

        val keyedMap = maps.first { it.mapCode == "warehouse" }
        assertEquals("Frosty Mountain- 대충산(리치의 창고)", keyedMap.name)
        assertEquals(9, keyedMap.keyCount)
        assertEquals(null, maps.first { it.mapCode == "plain" }.keyCount)
    }

    @Test
    fun coalescesSplitPermanentKeyMapLinksWithoutWarning() {
        lateinit var maps: List<HofBattleMap>
        val warnings = captureWarnings {
            maps = parser.parse(
                categoryId = "adventure_map",
                queryName = "sp_common",
                html = """
                    <div id="mapgroup1">
                      <p>
                        <a href="index.php?sp_common=min08">Dead Pit- 지각 내부 (B4) Tuls의 문(</a><a href="index.php?sp_common=min08">x )</a>
                        (타임 소모 : 100)
                      </p>
                    </div>
                """.trimIndent(),
            )
        }

        val map = maps.single()
        assertEquals("min08", map.mapCode)
        assertEquals("Dead Pit- 지각 내부 (B4) Tuls의 문", map.name)
        assertEquals(BattleMapKeyMode.UNLIMITED, map.keyMode)
        assertNull(map.keyCount)
        assertEquals(100, map.requiredTime)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun coalescesSameParentMapFragmentsAcrossInterveningElementsAndMapLinks() {
        lateinit var maps: List<HofBattleMap>
        val warnings = captureWarnings {
            maps = parser.parse(
                categoryId = "adventure_map",
                queryName = "sp_common",
                html = """
                    <div id="mapgroup1">
                      <p>
                        <a href="index.php?sp_common=min08">Dead Pit- 지각 내부 (B4) Tuls의 문(</a>
                        <span class="decoration"></span>
                        <a href="index.php?sp_common=other">Other Map</a>
                        <em></em>
                        <a href="index.php?sp_common=min08">x )</a>
                        (타임 소모 : 100)
                      </p>
                    </div>
                """.trimIndent(),
            )
        }

        assertEquals(listOf("min08", "other"), maps.map { it.mapCode })
        val permanent = maps.first()
        assertEquals("Dead Pit- 지각 내부 (B4) Tuls의 문", permanent.name)
        assertEquals(BattleMapKeyMode.UNLIMITED, permanent.keyMode)
        assertNull(permanent.keyCount)
        assertEquals(100, permanent.requiredTime)
        assertEquals("Other Map", maps.last().name)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun distinguishesFiniteAbsentAndMalformedKeyAdvertisements() {
        lateinit var maps: List<HofBattleMap>
        val warnings = captureWarnings {
            maps = parser.parse(
                categoryId = "adventure_map",
                queryName = "sp_common",
                html = """
                    <p><a href="index.php?sp_common=finite">Finite ( x12 )</a></p>
                    <p><a href="index.php?sp_common=plain">Plain</a></p>
                    <p><a href="index.php?sp_common=broken">Broken ( xunknown )</a></p>
                """.trimIndent(),
            )
        }

        val finite = maps.first { it.mapCode == "finite" }
        assertEquals("Finite", finite.name)
        assertEquals(BattleMapKeyMode.LIMITED, finite.keyMode)
        assertEquals(12, finite.keyCount)

        val plain = maps.first { it.mapCode == "plain" }
        assertEquals("Plain", plain.name)
        assertEquals(BattleMapKeyMode.NOT_REQUIRED, plain.keyMode)
        assertNull(plain.keyCount)

        val broken = maps.first { it.mapCode == "broken" }
        assertEquals("Broken", broken.name)
        assertEquals(BattleMapKeyMode.UNKNOWN, broken.keyMode)
        assertNull(broken.keyCount)
        assertEquals(1, warnings.size)
        assertTrue("keyCount" in warnings.single())
    }

    @Test
    fun rejectsMalformedAndOverflowingFiniteKeyCountsButPreservesValidCommas() {
        lateinit var maps: List<HofBattleMap>
        val warnings = captureWarnings {
            maps = parser.parse(
                categoryId = "adventure_map",
                queryName = "sp_common",
                html = """
                    <p><a href="index.php?sp_common=valid">Valid ( x1,234 )</a></p>
                    <p><a href="index.php?sp_common=plain-digits">Plain digits ( x42 )</a></p>
                    <p><a href="index.php?sp_common=comma-only">Comma only ( x, )</a></p>
                    <p><a href="index.php?sp_common=bad-comma">Bad comma ( x1,,2 )</a></p>
                    <p><a href="index.php?sp_common=short-group">Short group ( x12,34 )</a></p>
                    <p><a href="index.php?sp_common=uneven-groups">Uneven groups ( x1,23,4 )</a></p>
                    <p><a href="index.php?sp_common=overflow">Overflow ( x2,147,483,648 )</a></p>
                """.trimIndent(),
            )
        }

        val valid = maps.first { it.mapCode == "valid" }
        assertEquals("Valid", valid.name)
        assertEquals(BattleMapKeyMode.LIMITED, valid.keyMode)
        assertEquals(1_234, valid.keyCount)

        val plainDigits = maps.first { it.mapCode == "plain-digits" }
        assertEquals("Plain digits", plainDigits.name)
        assertEquals(BattleMapKeyMode.LIMITED, plainDigits.keyMode)
        assertEquals(42, plainDigits.keyCount)

        maps.filterNot { it.mapCode == "valid" || it.mapCode == "plain-digits" }.forEach { malformed ->
            assertEquals(BattleMapKeyMode.UNKNOWN, malformed.keyMode)
            assertNull(malformed.keyCount)
        }
        assertEquals("Comma only", maps.first { it.mapCode == "comma-only" }.name)
        assertEquals("Bad comma", maps.first { it.mapCode == "bad-comma" }.name)
        assertEquals("Short group", maps.first { it.mapCode == "short-group" }.name)
        assertEquals("Uneven groups", maps.first { it.mapCode == "uneven-groups" }.name)
        assertEquals("Overflow", maps.first { it.mapCode == "overflow" }.name)
        assertEquals(5, warnings.count { "keyCount" in it })
    }

    @Test
    fun parsesAdventureMapAttemptWinLimitsAndCooldownSeconds() {
        val maps = parser.parse(
            categoryId = "adventure_map",
            queryName = "sp_common",
            html = """
                <html>
                  <body>
                    <div>
                      <a href="index.php?sp_common=Noble103">
                        Noble's Mansion- 저택 동관(동관 안뜰)( x37 )
                      </a>
                      ( 도전 15회 , 승리 5회 ) 남음 (타임 소모 : 0) ★
                    </div>
                    <div>
                      <a href="index.php?sp_common=GoblinColosseum">Goblin- 고블린 콜로세움</a>
                      ( 3시간 0분 ) 남음 (타임 소모 : 100)
                    </div>
                  </body>
                </html>
            """.trimIndent(),
        )

        val limitedMap = maps.first { it.mapCode == "Noble103" }
        assertEquals("Noble's Mansion- 저택 동관(동관 안뜰)", limitedMap.name)
        assertEquals(37, limitedMap.keyCount)
        assertEquals(15, limitedMap.attemptCount)
        assertEquals(5, limitedMap.winCount)
        assertEquals(0, limitedMap.requiredTime)

        val cooldownMap = maps.first { it.mapCode == "GoblinColosseum" }
        assertEquals(10_800L, cooldownMap.cooldownRemainingSeconds)
        assertEquals("3시간 0분", cooldownMap.cooldownRemainingText)
        assertEquals(100, cooldownMap.requiredTime)
    }

    @Test
    fun keepsAdventurePlaceholderAsUnresolvedObservationWithoutHardcodedMapCode() {
        val maps = parser.parse(
            categoryId = "adventure_map",
            queryName = "sp_common",
            html = """
                <html>
                  <body>
                    <a href="javascript:void(0);" onclick="toggle_show('mapgroup3');">
                      고블린 부락 (적정 레벨 : 1-20)( 1 )
                    </a>
                    <div id="mapgroup3">
                      <p>
                        <a href="index.php?sp_hunt#">Goblin- 고블린 콜로세움</a>
                        ( 2시간 58분 ) 남음 (타임 소모 : 100)
                      </p>
                    </div>
                  </body>
                </html>
            """.trimIndent(),
        )

        assertEquals(1, maps.size)
        assertNull(maps.single().mapCode)
        assertFalse(maps.single().resolved)
        assertEquals("Goblin- 고블린 콜로세움", maps.single().name)
        assertEquals("고블린 부락", maps.single().groupName)
        assertEquals("1-20", maps.single().recommendedLevel)
        assertEquals(10_680L, maps.single().cooldownRemainingSeconds)
        assertEquals("2시간 58분", maps.single().cooldownRemainingText)
        assertEquals(100, maps.single().requiredTime)
    }

    @Test
    fun keepsZeroLimitAndClearedPlaceholdersEvenWithoutCooldownText() {
        val maps = parser.parse(
            categoryId = "adventure_map",
            queryName = "sp_common",
            html = """
                <html>
                  <body>
                    <div>이벤트 ( 적정 레벨 : 50 ) (4)</div>
                    <div id="mapgroup9">
                      <p><a href="index.php?sp_hunt#">Festival- 키 없는 맵 ( x0 )</a></p>
                      <p><a href="index.php?sp_hunt#">Festival- 횟수 없는 맵</a> ( 도전 0회 , 승리 0회 ) 남음</p>
                      <p><a href="index.php?sp_hunt#">Festival- 가능 횟수 0</a> 0 가능</p>
                      <p><a href="index.php?sp_hunt#">Festival- 이미 클리어</a> Clear</p>
                    </div>
                  </body>
                </html>
            """.trimIndent(),
        )

        assertEquals(4, maps.size)
        assertTrue(maps.all { it.mapCode == null })
        assertEquals(0, maps[0].keyCount)
        assertEquals(0, maps[1].attemptCount)
        assertEquals(0, maps[1].winCount)
        assertEquals(0, maps[2].availableCount)
        assertNull(maps[3].cooldownRemainingSeconds)
    }

    @Test
    fun deduplicatesDirectCodesAndUnresolvedGroupNameIdentitiesSeparately() {
        val maps = parser.parse(
            categoryId = "adventure_map",
            queryName = "sp_common",
            html = """
                <html>
                  <body>
                    <div>첫 그룹 (2)</div>
                    <div id="mapgroup1">
                      <a href="index.php?sp_common=future01">Future- 직접 맵</a>
                      <a href="index.php?sp_common=future01">직접 중복</a>
                      <a href="index.php?sp_hunt#">Future-   알 수 없음</a>
                      <a href="index.php?sp_hunt#">Future- 알 수 없음</a>
                    </div>
                    <div>둘째 그룹 (1)</div>
                    <div id="mapgroup2">
                      <a href="index.php?sp_hunt#">Future- 알 수 없음</a>
                    </div>
                  </body>
                </html>
            """.trimIndent(),
        )

        assertEquals(listOf("future01", null, null), maps.map { it.mapCode })
        assertEquals("Future- 직접 맵", maps.first().name)
        assertEquals(listOf(1, 1, 2), maps.map { it.groupOrder })
    }

    @Test
    fun ignoresCodeLessAnchorsThatDoNotNameTheRequestedPlaceholderAction() {
        val maps = parser.parse(
            categoryId = "adventure_map",
            queryName = "sp_common",
            html = """
                <div>미지 지역 (6)</div>
                <div id="mapgroup1">
                  <a href="#">단순 앵커</a>
                  <a href="javascript:void(0);">스크립트</a>
                  <a href="index.php?town#">다른 액션</a>
                  <a href="index.php?menu=police">네비게이션</a>
                  <a href="index.php?hunt#">다른 카테고리 placeholder</a>
                  <a href="index.php?sp_hunt#">Future- 실제 placeholder</a>
                </div>
            """.trimIndent(),
        )

        assertEquals(listOf("Future- 실제 placeholder"), maps.map { it.name })
        assertNull(maps.single().mapCode)
    }

    @Test
    fun parsesHoursOnlyMinutesOnlyAndCombinedCooldownsIntoSeconds() {
        val maps = parser.parse(
            categoryId = "adventure_map",
            queryName = "sp_common",
            html = """
                <html>
                  <body>
                    <div id="mapgroup1">
                      <p><a href="index.php?sp_hunt#">A</a> (3시간) 남음</p>
                      <p><a href="index.php?sp_hunt#">B</a> (45분) 남음</p>
                      <p><a href="index.php?sp_hunt#">C</a> (3시간 0분) 남음</p>
                    </div>
                  </body>
                </html>
            """.trimIndent(),
        )

        assertEquals(listOf(10_800L, 2_700L, 10_800L), maps.map { it.cooldownRemainingSeconds })
    }

    @Test
    fun derivesCompatibilityCooldownTextFromRemainingSeconds() {
        val map = HofBattleMap(
            categoryId = "adventure_map",
            mapCode = null,
            name = "Future- 쿨타임",
            cooldownRemainingSeconds = 5_400L,
            rawHref = "index.php?sp_hunt#",
        )

        assertEquals("1시간 30분", map.cooldownRemainingText)
    }

    @Test
    fun defaultsResolvedFromWhetherDirectMapCodeExists() {
        val direct = HofBattleMap(
            categoryId = "battle_map",
            mapCode = "future01",
            name = "Future",
            rawHref = "index.php?common=future01",
        )
        val placeholder = HofBattleMap(
            categoryId = "battle_map",
            mapCode = null,
            name = "Future",
            rawHref = "index.php?hunt#",
        )

        assertTrue(direct.resolved)
        assertFalse(placeholder.resolved)
    }

    @Test
    fun warnsWhenAdvertisedDynamicFieldsCannotBeParsedAndKeepsThemNull() {
        lateinit var maps: List<HofBattleMap>
        val warnings = captureWarnings {
            maps = parser.parse(
                categoryId = "adventure_map",
                queryName = "sp_common",
                html = """
                    <div>미지 지역 (1)</div>
                    <div id="mapgroup1">
                      <p>
                        <a href="index.php?sp_hunt#">Broken- 파싱 실패 ( x없음 )</a>
                        ( 도전 여러 회 , 승리 없음 회 ) 남음 (곧 3시간?) 남음
                      </p>
                    </div>
                """.trimIndent(),
            )
        }

        val map = maps.single()
        assertNull(map.cooldownRemainingSeconds)
        assertNull(map.attemptCount)
        assertNull(map.winCount)
        assertNull(map.keyCount)
        assertEquals(4, warnings.size)
        assertTrue(warnings.any { "cooldownRemainingSeconds" in it })
        assertTrue(warnings.any { "attemptCount" in it })
        assertTrue(warnings.any { "winCount" in it })
        assertTrue(warnings.any { "keyCount" in it })
        assertTrue(warnings.all { "categoryId=adventure_map" in it && "Broken-" in it })
    }

    @Test
    fun doesNotWarnWhenDynamicFieldsAreAbsent() {
        val warnings = captureWarnings {
            parser.parse(
                categoryId = "battle_map",
                queryName = "common",
                html = """<a href="index.php?common=plain01">Plain Map</a>""",
            )
        }

        assertTrue(warnings.isEmpty())
    }

    private fun captureWarnings(block: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(BattleMapParser::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        return try {
            block()
            appender.list
                .filter { event -> event.level == Level.WARN }
                .map(ILoggingEvent::getFormattedMessage)
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    private fun fixtureHtml(fileName: String): String {
        val fixturePath = listOf(
            Path.of("..", "example", "호프정보", fileName),
            Path.of("example", "호프정보", fileName),
        ).first(Files::exists)

        return Files.readString(fixturePath, Charset.forName("MS949"))
    }
}
