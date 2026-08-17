package app.spammy.hof.external.parser

import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CharacterPageParserFixtureTest {
    private val parser = CharacterDetailParser()

    @Test
    fun `six captured pages satisfy section contracts without executing javascript`() {
        FIXTURES.forEach { fixture ->
            val result = parser.parsePage("1683198503393759", readFixture(fixture))

            assertTrue(result.snapshot.name.isNotBlank(), fixture)
            assertTrue(result.snapshot.stats.hpBase != null, fixture)
            assertEquals(2, result.snapshot.positionGuard.positions.size, fixture)
            assertEquals(12, result.snapshot.equipment.size, fixture)
            assertTrue(result.snapshot.equipment.filter { it.name.isNotBlank() }.all { it.checked }, fixture)
            assertTrue(
                result.snapshot.equipmentCandidates.size > 400,
                "$fixture candidates=${result.snapshot.equipmentCandidates.size}",
            )
            assertTrue(result.snapshot.patternOptions.count { it.type == "CONDITION" } > 100, fixture)
            assertTrue(result.snapshot.patternOptions.count { it.type == "SKILL" } >= 5, fixture)
            result.sections.forEach { (section, sectionResult) ->
                assertIs<CharacterSectionParseResult.Success>(sectionResult, "$fixture / $section")
            }
        }
    }

    @Test
    fun `discovers all action rows instead of truncating at sixteen`() {
        val rows = (0..16).joinToString("") { index ->
            """<tr><td><select name="judge$index"><option value="1000">반드시</option></select></td>
                <td><input name="quantity$index" value="0"></td>
                <td><select name="skill$index"><option value="1000">Attack</option></select></td></tr>"""
        }
        val html = """
            <div class="carpet_frame">테스트<br>Lv.1 Fighter</div>
            <h4>Action Pattern</h4><table>$rows</table>
        """.trimIndent()

        assertEquals((0..16).toList(), parser.parse("id", html).actionPatterns.map { it.index })
    }

    @Test
    fun `parses real stats effects faith and skill details from captured page`() {
        val snapshot = parser.parse("1683198503393759", readFixture("Hall of Fame Ver ZeroHOF_skillPoint_not_exist.html"))

        assertEquals(null, snapshot.stats.statusPoints)
        assertEquals(50, snapshot.stats.skillPoints)
        assertEquals(5628, snapshot.stats.hpBase)
        assertEquals(5033, snapshot.stats.hpBonus)
        assertTrue(snapshot.statusEffects.any { it.valueText == "물리 강화 1%" })
        assertEquals("Marduk", snapshot.faith?.godName)
        assertEquals(380036, snapshot.faith?.current)
        assertEquals(380000, snapshot.faith?.max)
        val parrying = snapshot.learnedSkills.first { it.name == "Parrying" }
        assertEquals("self", parrying.targetText)
        assertEquals("individual", parrying.scopeText)
        assertEquals(0, parrying.spCost)
        assertTrue(parrying.description.contains("데미지 1회 무효화"))
        val mypod = snapshot.equipmentCandidates.first { it.value == "8801" }
        assertEquals(3, mypod.quantity)

        val withStatusPoints = parser.parse(
            "1683198503393759",
            readFixture("Hall of Fame Ver ZeroHOF_skillPoint_exist.html"),
        )
        assertEquals(236, withStatusPoints.stats.statusPoints)
        assertEquals(3, withStatusPoints.stats.skillPoints)
        assertTrue(withStatusPoints.patternOptions.any { it.type == "CLASS" && it.value == "523" && it.label == "Mathematician" })

        val usePage = parser.parse("1683198503393759", readFixture("Hall of Fame Ver ZeroHOF_use.html"))
        val resetCrystal = usePage.equipmentCandidates.first { it.typeCode == "resetitem" && it.value == "7510" }
        assertTrue(resetCrystal.name.startsWith("Reset Crystal(Lv 60"))
        assertFalse(resetCrystal.name.contains("x57"))
        assertEquals(57, resetCrystal.quantity)
    }

    @Test
    fun `failure diagnostics contain structure only`() {
        val secret = "secret-hidden-value"
        val result = parser.parsePage("id", "<html><input type=hidden value='$secret'></html>")
        val failure = assertIs<CharacterSectionParseResult.Failure>(result.sections.getValue(CharacterPageSection.STATS))
        val message = failure.safeMessage()

        assertTrue(message.contains("parser=character-v1"))
        assertTrue(message.contains("observed="))
        assertFalse(message.contains(secret))
        assertFalse(message.contains("<html"))
    }

    @Test
    fun `production relative image url is normalized independently from captured fixtures`() {
        val html = """
            <div class="carpet_frame"><img src="./image/char/sknight02.gif">소셜<br>Lv.60 Social Knight</div>
        """.trimIndent()

        assertEquals(
            "http://sic.zerosic.com/ZeroHOF/image/char/sknight02.gif",
            parser.parse("id", html).imageUrl,
        )

        val captured = parser.parse("id", readFixture("Hall of Fame Ver ZeroHOF_skillPoint_not_exist.html"))
        assertEquals("http://sic.zerosic.com/ZeroHOF/image/char/sknight02.gif", captured.imageUrl)
        assertTrue(captured.equipment.filter { it.name.isNotBlank() }.all { it.iconUrl.contains("/ZeroHOF/image/icon/") })
    }

    private fun readFixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/character/$name"))
            .use { input -> input.readBytes().toString(CP949) }

    companion object {
        private val CP949 = Charset.forName("MS949")
        private val FIXTURES = listOf(
            "Hall of Fame Ver ZeroHOF_skillPoint_not_exist.html",
            "Hall of Fame Ver ZeroHOF_skillPoint_exist.html",
            "Hall of Fame Ver ZeroHOF_changeName.html",
            "Hall of Fame Ver ZeroHOF_use.html",
            "Hall of Fame Ver ZeroHOF_kick.html",
            "Hall of Fame Ver ZeroHOF_knockback.html",
        )
    }
}
