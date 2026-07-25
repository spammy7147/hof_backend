package app.spammy.hof.external.parser

import app.spammy.hof.external.model.HofBattleLoot
import app.spammy.hof.external.model.HofBattleOutcome
import kotlin.test.Test
import kotlin.test.assertEquals

class BattleResultParserTest {
    private val parser = BattleResultParser()

    @Test
    fun parsesVictoryResultWhenAllyNameWins() {
        val result = parser.parse(
            html = """
                <html><body>
                  <h2>Show Detail( 36 turns. )</h2>
                  <h1>《얼어붙은 손길》공민이은(는) 승리했다!</h1>
                  <fieldset>[ 퀘스트 정보 갱신 ] 마도사의 은신처 조사 지원 - ( 3 / 10 )</fieldset>
                  <div class="enemy">
                    남은 HP : 0/51930<br>
                    생존자 : 0/7<br>
                    총 데미지 : 3044
                  </div>
                  <div class="ally">
                    남은 HP : 25955/26197<br>
                    생존자 : 5/5<br>
                    총 데미지 : 365247<br>
                    턴 : 36/100<br>
                    획득 경험치 : 10590<br>
                    획득 Funds : $ 3,660<br>
                    <b>전리품</b><br>
                    <img src="silver.png"> Silver Ingot x 1<br>
                    <img src="bone.png"> Bone x 1<br>
                    소셜 신앙심 변동 :+12
                  </div>
                </body></html>
            """.trimIndent(),
            playerName = "《얼어붙은 손길》공민이",
        )

        assertEquals(HofBattleOutcome.VICTORY, result.outcome)
        assertEquals("《얼어붙은 손길》공민이은(는) 승리했다!", result.title)
        assertEquals(36, result.turns)
        assertEquals(3660, result.funds)
        assertEquals(10590, result.experience)
        assertEquals(
            listOf(
                HofBattleLoot(name = "Silver Ingot", quantity = 1, rawText = "Silver Ingot x 1"),
                HofBattleLoot(name = "Bone", quantity = 1, rawText = "Bone x 1"),
            ),
            result.loots,
        )
        assertEquals("[ 퀘스트 정보 갱신 ] 마도사의 은신처 조사 지원 - ( 3 / 10 )", result.quest)
        assertEquals(0, result.enemySide.hpCurrent)
        assertEquals(51930, result.enemySide.hpMax)
        assertEquals(0, result.enemySide.survivorsAlive)
        assertEquals(7, result.enemySide.survivorsMax)
        assertEquals(25955, result.allySide.hpCurrent)
        assertEquals(26197, result.allySide.hpMax)
        assertEquals(5, result.allySide.survivorsAlive)
        assertEquals(5, result.allySide.survivorsMax)
    }

    @Test
    fun parsesTitleAfterLogCopyLinkWithoutIncludingLinkText() {
        val result = parser.parse(
            html = """
                <html><body>
                  <a href="http://sic.zerosic.com/ZeroHOF/index.php?common=gb0#">로그 주소 복사</a>
                  《얼어붙은 손길》공민이은(는) 승리했다!
                  <div>
                    남은 HP : 0/780
                    생존자 : 0/5
                    총 데미지 : 0
                  </div>
                  <div>
                    남은 HP : 26087/26087
                    생존자 : 5/5
                    총 데미지 : 123243
                    턴 : 6/100
                    획득 경험치 : 100
                    획득 Funds : $ 200
                    전리품
                    Steel Ingot x 1
                    BlueRing x 1
                  </div>
                </body></html>
            """.trimIndent(),
            playerName = "《얼어붙은 손길》공민이",
        )

        assertEquals(HofBattleOutcome.VICTORY, result.outcome)
        assertEquals("《얼어붙은 손길》공민이은(는) 승리했다!", result.title)
        assertEquals("http://sic.zerosic.com/ZeroHOF/index.php?common=gb0#", result.rawLogUrl)
        assertEquals(0, result.enemySide.hpCurrent)
        assertEquals(780, result.enemySide.hpMax)
        assertEquals(26087, result.allySide.hpCurrent)
        assertEquals(26087, result.allySide.hpMax)
        assertEquals(5, result.allySide.survivorsAlive)
        assertEquals(5, result.allySide.survivorsMax)
        assertEquals(123243, result.allySide.totalDamage)
        assertEquals(6, result.allySide.turnCurrent)
        assertEquals(100, result.allySide.turnMax)
        assertEquals(100, result.experience)
        assertEquals(200, result.funds)
        assertEquals(listOf("Steel Ingot", "BlueRing"), result.loots.map { it.name })
    }

    @Test
    fun parsesVictoryWhenPlayerNameAppearsInTitle() {
        val result = parser.parse(
            html = """
                <html><body>
                  <a href="http://sic.zerosic.com/ZeroHOF/index.php?common=gb0#">로그 주소 복사</a>
                  《얼어붙은 손길》공민이은(는) 승리했다!
                  <div>
                    남은 HP : 0/860
                    생존자 : 0/5
                    총 데미지 : 0
                  </div>
                  <div>
                    남은 HP : 37276/51086
                    생존자 : 5/5
                    총 데미지 : 592824
                    턴 : 4/100
                    획득 경험치 : 100
                    획득 Funds : $ 200
                    전리품
                    Steel Ingot x 1
                    Stone x 1
                  </div>
                </body></html>
            """.trimIndent(),
            playerName = "《얼어붙은 손길》공민이",
        )

        assertEquals(HofBattleOutcome.VICTORY, result.outcome)
        assertEquals("《얼어붙은 손길》공민이은(는) 승리했다!", result.title)
        assertEquals(0, result.enemySide.survivorsAlive)
        assertEquals(5, result.allySide.survivorsAlive)
    }

    @Test
    fun parsesPlayerVictoryFromTitleWithOnlyOneSideBlock() {
        val result = parser.parse(
            html = """
                <html><body>
                  <h1>《얼어붙은 손길》공민이은(는) 승리했다!</h1>
                  <div>
                    남은 HP : 25955/26197<br>
                    생존자 : 5/5<br>
                    총 데미지 : 365247
                  </div>
                </body></html>
            """.trimIndent(),
            playerName = " \n 《얼어붙은   손길》공민이 \t",
        )

        assertEquals(HofBattleOutcome.VICTORY, result.outcome)
    }

    @Test
    fun parsesNonPlayerVictoryAsDefeatRegardlessOfSideBlocks() {
        val result = parser.parse(
            html = """
                <html><body>
                  <h1>Frosty Mountain- 대충산(마도사의 은신처)은(는) 승리했다!</h1>
                  <div>
                    남은 HP : 0/41070<br>
                    생존자 : 0/2<br>
                    총 데미지 : 3676
                  </div>
                  <div>
                    남은 HP : 2071/2071<br>
                    생존자 : 1/1<br>
                    총 데미지 : 100
                  </div>
                </body></html>
            """.trimIndent(),
            playerName = "《얼어붙은 손길》공민이",
        )

        assertEquals(HofBattleOutcome.DEFEAT, result.outcome)
    }

    @Test
    fun givesDrawTitlePrecedenceOverVictoryPhrase() {
        val result = parser.parse(
            html = "<h1>무승부 결과로 《얼어붙은 손길》공민이은(는) 승리했다!</h1>",
            playerName = "《얼어붙은 손길》공민이",
        )

        assertEquals(HofBattleOutcome.DRAW, result.outcome)
    }

    @Test
    fun parsesVictoryTitleWithNullPlayerNameAsUnknown() {
        val result = parser.parse(
            html = "<h1>《얼어붙은 손길》공민이은(는) 승리했다!</h1>",
            playerName = null,
        )

        assertEquals(HofBattleOutcome.UNKNOWN, result.outcome)
    }

    @Test
    fun parsesVictoryTitleWithUnknownPlayerNameAsUnknown() {
        val result = parser.parse(
            html = "<h1>《얼어붙은 손길》공민이은(는) 승리했다!</h1>",
            playerName = "Unknown",
        )

        assertEquals(HofBattleOutcome.UNKNOWN, result.outcome)
    }

    @Test
    fun parsesVictoryTitleWithBlankPlayerNameAsUnknown() {
        listOf("", " \n\t ").forEach { playerName ->
            val result = parser.parse(
                html = "<h1>《얼어붙은 손길》공민이은(는) 승리했다!</h1>",
                playerName = playerName,
            )

            assertEquals(HofBattleOutcome.UNKNOWN, result.outcome)
        }
    }

    @Test
    fun parsesVictoryTitleWithPaddedMixedCaseUnknownPlayerNameAsUnknown() {
        val result = parser.parse(
            html = "<h1>《얼어붙은 손길》공민이은(는) 승리했다!</h1>",
            playerName = "  uNkNoWn  ",
        )

        assertEquals(HofBattleOutcome.UNKNOWN, result.outcome)
    }

    @Test
    fun parsesEquivalentPlayerAndTitleNamesAcrossUnicodeSeparatorWhitespace() {
        val result = parser.parse(
            html = "<h1>《얼어붙은&nbsp;손길》공민이은(는) 승리했다!</h1>",
            playerName = "《얼어붙은\u2003손길》공민이",
        )

        assertEquals(HofBattleOutcome.VICTORY, result.outcome)
    }

    @Test
    fun parsesTitleWithoutTerminalPhraseAsUnknown() {
        val result = parser.parse(
            html = "<h1>《얼어붙은 손길》공민이의 전투 결과</h1>",
            playerName = "《얼어붙은 손길》공민이",
        )

        assertEquals(HofBattleOutcome.UNKNOWN, result.outcome)
    }

    @Test
    fun parsesEachBattleRoundFromThreeBattleResponse() {
        val results = parser.parseAll(
            html = """
                <html><body>
                  <h2>Show Detail( 6 turns. )</h2>
                  <a href="http://sic.zerosic.com/ZeroHOF/index.php?common=round1#">로그 주소 복사</a>
                  《얼어붙은 손길》공민이은(는) 승리했다!
                  <div>
                    남은 HP : 0/820
                    생존자 : 0/5
                    총 데미지 : 0
                  </div>
                  <div>
                    남은 HP : 26087/26087
                    생존자 : 5/5
                    총 데미지 : 102450
                    턴 : 6/100
                    획득 경험치 : 100
                    획득 Funds : $ 200
                  </div>
                  <h2>Show Detail( 6 turns. )</h2>
                  <a href="http://sic.zerosic.com/ZeroHOF/index.php?common=round2#">로그 주소 복사</a>
                  《얼어붙은 손길》공민이은(는) 승리했다!
                  <div>
                    남은 HP : 0/780
                    생존자 : 0/5
                    총 데미지 : 0
                  </div>
                  <div>
                    남은 HP : 26087/26087
                    생존자 : 5/5
                    총 데미지 : 90686
                    턴 : 6/100
                    획득 경험치 : 100
                    획득 Funds : $ 200
                  </div>
                  <h2>Show Detail( 6 turns. )</h2>
                  <a href="http://sic.zerosic.com/ZeroHOF/index.php?common=round3#">로그 주소 복사</a>
                  《얼어붙은 손길》공민이은(는) 승리했다!
                  <div>
                    남은 HP : 0/820
                    생존자 : 0/5
                    총 데미지 : 0
                  </div>
                  <div>
                    남은 HP : 26087/26087
                    생존자 : 5/5
                    총 데미지 : 93937
                    턴 : 6/100
                    획득 경험치 : 100
                    획득 Funds : $ 200
                    전리품
                    Stone x 2
                  </div>
                </body></html>
            """.trimIndent(),
            playerName = "《얼어붙은 손길》공민이",
        )

        assertEquals(3, results.size)
        assertEquals(listOf(HofBattleOutcome.VICTORY, HofBattleOutcome.VICTORY, HofBattleOutcome.VICTORY), results.map { it.outcome })
        assertEquals(listOf(102450, 90686, 93937), results.map { it.allySide.totalDamage })
        assertEquals(
            listOf(
                "http://sic.zerosic.com/ZeroHOF/index.php?common=round1#",
                "http://sic.zerosic.com/ZeroHOF/index.php?common=round2#",
                "http://sic.zerosic.com/ZeroHOF/index.php?common=round3#",
            ),
            results.map { it.rawLogUrl },
        )
        assertEquals(listOf(emptyList(), emptyList(), listOf("Stone")), results.map { result -> result.loots.map { it.name } })
        assertEquals(2, results.last().loots.single().quantity)
        assertEquals("Stone x 2", results.last().loots.single().rawText)
    }

    @Test
    fun parsesCaseInsensitiveTrailingQuantityAndFallsBackForUnsuffixedLoot() {
        val quantified = parser.parse(
            html = victoryHtmlWithLoot("Steel Ingot X 2"),
            playerName = "소셜",
        )
        val unsuffixed = parser.parse(
            html = victoryHtmlWithLoot("Mysterious Relic"),
            playerName = "소셜",
        )

        assertEquals(
            HofBattleLoot(name = "Steel Ingot", quantity = 2, rawText = "Steel Ingot X 2"),
            quantified.loots.single(),
        )
        assertEquals(
            HofBattleLoot(name = "Mysterious Relic", quantity = 1, rawText = "Mysterious Relic"),
            unsuffixed.loots.single(),
        )
    }

    @Test
    fun preservesMixedLootLinesInSourceDisplayOrder() {
        val result = parser.parse(
            html = """
                <html><body>
                  <h2>Show Detail( 1 turn. )</h2>
                  <h1>소셜은(는) 승리했다!</h1>
                  <div>
                    남은 HP : 0/100<br>
                    생존자 : 0/1<br>
                    총 데미지 : 0
                  </div>
                  <div>
                    남은 HP : 100/100<br>
                    생존자 : 1/1<br>
                    총 데미지 : 100<br>
                    <b>전리품</b><br>
                    <img src="steel.png"> Steel Ingot x 2<br>
                    <span>Mysterious Relic</span><br>
                    <img src="bone.png"> Bone X 3<br>
                    소셜 신앙심 변동 :+1
                  </div>
                </body></html>
            """.trimIndent(),
            playerName = "소셜",
        )

        assertEquals(
            listOf(
                HofBattleLoot(name = "Steel Ingot", quantity = 2, rawText = "Steel Ingot x 2"),
                HofBattleLoot(name = "Mysterious Relic", quantity = 1, rawText = "Mysterious Relic"),
                HofBattleLoot(name = "Bone", quantity = 3, rawText = "Bone X 3"),
            ),
            result.loots,
        )
    }

    @Test
    fun parsesDefeatResultWhenEnemyNameWins() {
        val result = parser.parse(
            html = """
                <html><body>
                  <h2>Show Detail( 4 turns. )</h2>
                  <h1>Frosty Mountain- 대충산(마도사의 은신처)은(는) 승리했다!</h1>
                  <div>
                    남은 HP : 41070/41070
                    생존자 : 2/2
                    총 데미지 : 3676
                  </div>
                  <div>
                    남은 HP : 0/2071
                    생존자 : 0/1
                    총 데미지 : 0
                    턴 : 4/100
                  </div>
                </body></html>
            """.trimIndent(),
            playerName = "소셜",
        )

        assertEquals(HofBattleOutcome.DEFEAT, result.outcome)
        assertEquals(4, result.turns)
        assertEquals(null, result.funds)
        assertEquals(emptyList(), result.loots)
        assertEquals(41070, result.enemySide.hpCurrent)
        assertEquals(0, result.allySide.hpCurrent)
    }

    @Test
    fun parsesDrawResultWithoutRewards() {
        val result = parser.parse(
            html = """
                <html><body>
                  <h2>Show Detail( 100 turns. )</h2>
                  <h1>무승부!</h1>
                  <div>
                    남은 HP : 13801/55920
                    생존자 : 6/6
                    총 데미지 : 24581
                  </div>
                  <div>
                    남은 HP : 23125/26087
                    생존자 : 5/5
                    총 데미지 : 1637
                    턴 : 100/100
                  </div>
                </body></html>
            """.trimIndent(),
            playerName = "소셜",
        )

        assertEquals(HofBattleOutcome.DRAW, result.outcome)
        assertEquals("무승부!", result.title)
        assertEquals(100, result.turns)
        assertEquals(null, result.experience)
        assertEquals(null, result.quest)
        assertEquals(6, result.enemySide.survivorsAlive)
        assertEquals(5, result.allySide.survivorsAlive)
    }

    private fun victoryHtmlWithLoot(loot: String): String =
        """
            <html><body>
              <h2>Show Detail( 1 turn. )</h2>
              <h1>소셜은(는) 승리했다!</h1>
              <div>
                남은 HP : 0/100<br>
                생존자 : 0/1<br>
                총 데미지 : 0
              </div>
              <div>
                남은 HP : 100/100<br>
                생존자 : 1/1<br>
                총 데미지 : 100<br>
                전리품<br>
                $loot<br>
                소셜 신앙심 변동 :+1
              </div>
            </body></html>
        """.trimIndent()
}
