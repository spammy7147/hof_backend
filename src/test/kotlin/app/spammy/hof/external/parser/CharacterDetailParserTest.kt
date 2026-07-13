package app.spammy.hof.external.parser

import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CharacterDetailParserTest {
    private val parser = CharacterDetailParser()

    @Test
    fun parsesNameLevelJobAndPatternSlots() {
        val html = """
            <div class="carpet_frame">소셜 Lv.51 Sage</div>
            <form>
              <input name="patternno" value="0">
              <input type="button" value="첫 번째 패턴">
              <input name="loadpattern" value="LOAD">
            </form>
            <form>
              <input name="patternno" value="1">
              <button type="button">두 번째 패턴</button>
            </form>
        """.trimIndent()

        val character = parser.parse(characterId = "1683881378538406", html = html)

        assertEquals("1683881378538406", character.id)
        assertEquals("소셜", character.name)
        assertEquals(51, character.level)
        assertEquals("Sage", character.job)
        assertEquals(listOf("0", "1"), character.patternSlots.map { it.slot })
        assertEquals("첫 번째 패턴", character.patternSlots[0].label)
        assertTrue(character.patternSlots[0].canLoad)
    }

    @Test
    fun parsesDetailedStatusPatternEquipmentAndSkills() {
        val html = """
            <div class="carpet_frame">
              <img src="/ZeroHOF/image/social-knight.png">
              소셜 Lv.60 Social Knight
            </div>

            <h3>Character Status</h3>
            <div>HP : 12345/23456</div>
            <div>Atk : 116</div>
            <div>Matk : 5</div>
            <div>Def : 141 + 1649</div>
            <div>Mdef : 111 + 1089</div>
            <div>handle : 25 / 25</div>
            <div>cost : 0 / 10</div>

            <form>
              <input name="PatternNumber" value="0" checked>
              <select name="judge0"><option value="1" selected>아군 HP 낮음</option></select>
              <select name="quantity0"><option value="50" selected>50%</option></select>
              <select name="skill0"><option value="guard" selected>Shield Guard</option></select>
              <select name="judge1"><option value="2" selected>항상</option></select>
              <select name="quantity1"><option value="100" selected>100%</option></select>
              <select name="skill1"><option value="attack" selected>Attack</option></select>
            </form>

            <form>
              <input name="position" value="front" checked> Front
              <input name="position" value="back"> Back
              <select name="guard">
                <option value="none">None</option>
                <option value="mage" selected>마법사 호위</option>
              </select>
            </form>

            <h3>Current Equip's</h3>
            <table>
              <tr>
                <td class="align-right">Weapon :</td>
                <td>
                  <input name="spot" value="weapon" checked>
                  <img src="/ZeroHOF/item/sword.png">
                  +8 Mirage Arch
                  <span class="dmg">Atk:306</span>
                  <span style="font-size:10px">Bow / Matk:194</span>
                </td>
              </tr>
            </table>

            <h3>Stock & Allowed to Equip</h3>
            <div><input name="item_no" value="999"> Candidate Blade</div>

            <h3>Current Skill</h3>
            <ul>
              <li>Shield Bash - learned skill line</li>
              <li>Holy Shield - learned skill line</li>
            </ul>

            <h3>Skill</h3>
            <form>
              <table>
                <tr>
                  <td><input name="newskill" value="200"></td>
                  <td><img src="/ZeroHOF/skill/holy.png"> Holy Guard / can learn line</td>
                </tr>
              </table>
            </form>
        """.trimIndent()

        val character = parser.parse(characterId = "1683900404093018", html = html)

        assertEquals("소셜", character.name)
        assertEquals(60, character.level)
        assertEquals("Social Knight", character.job)
        assertEquals("http://sic.zerosic.com/ZeroHOF/image/social-knight.png", character.imageUrl)
        assertEquals(116, character.stats.atk)
        assertEquals(5, character.stats.matk)
        assertEquals(141, character.stats.defBase)
        assertEquals(1649, character.stats.defBonus)
        assertEquals(111, character.stats.mdefBase)
        assertEquals(1089, character.stats.mdefBonus)
        assertEquals(25, character.stats.handleUsed)
        assertEquals(25, character.stats.handleMax)
        assertEquals(0, character.stats.costUsed)
        assertEquals(10, character.stats.costMax)
        assertEquals("0", character.selectedPatternNumber)
        assertEquals(listOf("아군 HP 낮음", "항상"), character.actionPatterns.map { it.judgeText })
        assertEquals("front", character.positionGuard.selectedPosition)
        assertEquals("mage", character.positionGuard.guardValue)
        assertEquals("마법사 호위", character.positionGuard.guardText)
        assertEquals(1, character.equipment.size)
        assertEquals("weapon", character.equipment[0].slot)
        assertEquals("Weapon", character.equipment[0].part)
        assertEquals("+8 Mirage Arch", character.equipment[0].name)
        assertEquals("Bow / Matk:194", character.equipment[0].description)
        assertEquals(listOf("Shield Bash - learned skill line", "Holy Shield - learned skill line"), character.learnedSkills.map { it.name })
        assertEquals(listOf("Holy Guard / can learn line"), character.learnableSkills.map { it.name })
    }

    @Test
    fun parsesSavedSocialKnightCharacterPage() {
        val character = parser.parse(
            characterId = "1683198503393759",
            html = readSocialKnightFixture(),
        )

        assertEquals("소셜", character.name)
        assertEquals(60, character.level)
        assertEquals("Social Knight", character.job)
        assertTrue(character.imageUrl.endsWith("sknight02.gif"))
        assertEquals(8, character.patternSlots.size)
        assertEquals(listOf("범용", "대회랑", "산중", "1탑", "2탑", "목요", "빈슬롯", "토요"), character.patternSlots.map { it.label })
        assertEquals(116, character.stats.atk)
        assertEquals(5, character.stats.matk)
        assertEquals(141, character.stats.defBase)
        assertEquals(1649, character.stats.defBonus)
        assertEquals(111, character.stats.mdefBase)
        assertEquals(1089, character.stats.mdefBonus)
        assertEquals(25, character.stats.handleUsed)
        assertEquals(25, character.stats.handleMax)
        assertEquals(0, character.stats.costUsed)
        assertEquals(10, character.stats.costMax)
        assertEquals(10, character.actionPatterns.size)
        assertEquals("자신이 후방", character.actionPatterns[1].judgeText)
        assertEquals("Stance Restore(Self) - (SP:0)", character.actionPatterns[1].skillText)
        assertEquals("front", character.positionGuard.selectedPosition)
        assertEquals("always", character.positionGuard.guardValue)
        assertEquals("반드시 지킨다", character.positionGuard.guardText)
        assertEquals(11, character.equipment.size)
        assertTrue(character.equipment.any { it.slot == "weapon" && it.name == "Soulcollector's Sword Breaker" })
        assertTrue(character.equipment.any { it.slot == "head" && it.name.contains("Aurelia's Iron Curtain") })
        assertTrue(character.learnedSkills.any { it.name.startsWith("Attack /") })
        assertTrue(character.learnedSkills.any { it.name.startsWith("Marduk's Blade Wall /") })
        assertTrue(character.learnedSkills.any { it.name.startsWith("Job Master : Social Knight /") })
        assertEquals("1014", character.learnableSkills.first().value)
        assertTrue(character.learnableSkills.first().name.startsWith("Double Quick Slash /"))
    }

    private fun readSocialKnightFixture(): String {
        val fixture = Files.walk(Path.of("..", "example"))
            .use { paths ->
                paths
                    .filter { path -> Files.isRegularFile(path) }
                    .filter { path -> path.fileName.toString() == "소셜캐릭터html.html" }
                    .findFirst()
                    .orElseThrow()
            }
        val decoder = Charset.forName("EUC-KR")
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)

        return Files.newInputStream(fixture).use { input ->
            InputStreamReader(input, decoder).use { reader -> reader.readText() }
        }
    }
}
