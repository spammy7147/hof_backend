package app.spammy.hof.town.common.parser

import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import tools.jackson.module.kotlin.jacksonObjectMapper

class HofFormParserTest {
    private val parser = HofFormParser()

    @Test
    fun `table 때문에 DOM에서 떨어진 컨트롤은 원래 폼 소유권과 순서를 유지한다`() {
        val html = """
            <table><form method="post" action="?menu=rank">
            <tr><td>A<input type="checkbox" name="char_101" value="1" checked></td>
            <td>B<input type="checkbox" name="char_102" value="1"></td></tr></table>
            <input type="submit" value="SetTeam"><input type="hidden" name="SetRankTeam" value="observed"></form>
            <form action="?menu=other"><input type="checkbox" name="other" value="9"><input type="submit" name="Other" value="Other"></form>
        """.trimIndent()
        val page = parser.parse(html)
        val team = page.forms.single { it.submitLabel == "SetTeam" }
        assertEquals(listOf("char_101", "char_102"), team.candidates.map { it.inputName })
        assertFalse(team.candidates.any { it.inputName == "other" })
        val submitted = app.spammy.hof.town.common.service.TownActionGuard().guard(page,
            app.spammy.hof.town.common.model.TownActionRequest(team.actionId,
                listOf(app.spammy.hof.town.common.model.TownActionSelection(team.candidates.first().id))))
        assertEquals(listOf("char_101" to "1", "SetRankTeam" to "observed"), submitted.formEntries.map { it.name to it.value })
    }

    @Test
    fun `only controls owned by selected form become candidates`() {
        val page = parser.parse(fixture("fixtures/town/common/form-boundaries.html"))

        assertEquals(4, page.forms.size)
        assertEquals(listOf("item-1"), page.forms.first().candidates.map { it.id })
        assertFalse(page.forms.first().rows.single { it.label.contains("표시 전용") }.selectable)
        assertNotEquals(page.forms[0].actionId, page.forms[1].actionId)
    }

    @Test
    fun `hidden and submit fields are retained only inside server model`() {
        val form = parser.parse(fixture("fixtures/town/common/form-boundaries.html")).forms.first()

        assertEquals(listOf("csrf" to "server-only"), form.hiddenFields.map { it.name to it.value })
        assertEquals(listOf("Create" to "교환"), form.submitFields.map { it.name to it.value })
    }

    @Test
    fun `serialized parsed form exposes no server form fields`() {
        val form = parser.parse(fixture("fixtures/town/common/form-boundaries.html")).forms.first()
        val json = jacksonObjectMapper().writeValueAsString(form)

        assertFalse(json.contains("server-only"))
        assertFalse(json.contains("inputName"))
        assertFalse(json.contains("inputValue"))
        assertFalse(json.contains("quantityFieldName"))
        assertFalse(json.contains("actionUrl"))
        assertFalse(json.contains("sic.zerosic.com"))
        assertFalse(json.contains("HofHttpMethod"))
        assertFalse(json.contains("POST"))
        assertTrue(json.contains("item-1"))
    }

    @Test
    fun `structurally identical forms receive unique opaque action ids`() {
        val forms = parser.parse(fixture("fixtures/town/common/form-boundaries.html")).forms

        assertEquals(forms.size, forms.map { it.actionId }.distinct().size)
        assertTrue(forms.all { it.actionId.matches(Regex("[0-9a-f]{64}")) })
    }

    @Test
    fun `action id follows hidden target when forms reorder`() {
        fun page(targets: List<String>) = targets.joinToString(prefix = "<html><body>", postfix = "</body></html>") { target ->
            """
                <form action="index.php?menu=shop" method="post">
                  <input type="hidden" name="target" value="$target">
                  <input type="radio" name="item" value="same-item">
                  <button type="submit" name="Create" value="교환">Create</button>
                </form>
            """.trimIndent()
        }
        fun idsByTarget(html: String) = parser.parse(html).forms.associate { form ->
            form.hiddenFields.single { it.name == "target" }.value to form.actionId
        }

        val original = idsByTarget(page(listOf("alpha", "beta")))
        val reordered = idsByTarget(page(listOf("beta", "alpha")))

        assertEquals(original, reordered)
        assertNotEquals(original.getValue("alpha"), original.getValue("beta"))
    }

    @Test
    fun `query only form action preserves the HOF entry filename`() {
        val form = parser.parse(
            "<form method='post' action='?menu=create'><input name='item' type='radio' value='1'><button name='Create'>Create</button></form>",
            "https://hof.zerosic.com/index.php?menu=town",
        ).forms.single()

        assertEquals("https://hof.zerosic.com/index.php?menu=create", form.actionUrl)
    }

    @Test
    fun `unrelated text input is not inferred as quantity and repeated fields are preserved`() {
        val form = parser.parse(fixture("fixtures/town/common/form-boundaries.html")).forms[2]
        assertFails {
            app.spammy.hof.town.common.service.TownActionGuard().guard(
                app.spammy.hof.town.common.model.ParsedTownPage(listOf(form)),
                app.spammy.hof.town.common.model.TownActionRequest(
                    form.actionId,
                    listOf(app.spammy.hof.town.common.model.TownActionSelection("item-2", 3)),
                ),
            )
        }
        val guarded = app.spammy.hof.town.common.service.TownActionGuard().guard(
            app.spammy.hof.town.common.model.ParsedTownPage(listOf(form)),
            app.spammy.hof.town.common.model.TownActionRequest(
                form.actionId,
                listOf(app.spammy.hof.town.common.model.TownActionSelection("item-2", 1)),
            ),
        )

        assertEquals(listOf("token", "token", "item", "Create"), guarded.formEntries.map { it.name })
        assertEquals(listOf("first", "second"), guarded.formEntries.filter { it.name == "token" }.map { it.value })
        assertFalse(guarded.formEntries.any { it.name == "search" })
    }

    @Test
    fun `multiple radio cards in one table row remain distinct candidates`() {
        val form = parser.parse(
            """
                <form action='?menu=recruit' method='post'>
                  <table><tr>
                    <td><label><input type='radio' name='Job' value='opaque-a'>Warrior ${'$'}2,000</label></td>
                    <td><label><input type='radio' name='Job' value='opaque-b'>Monk ${'$'}10,000</label></td>
                  </tr></table>
                  <input type='submit' name='Recruit' value='Recruit'>
                </form>
            """.trimIndent(),
            "https://hof.zerosic.com/index.php?menu=town",
        ).forms.single()

        assertEquals(listOf("Warrior ${'$'}2,000", "Monk ${'$'}10,000"), form.candidates.map { it.label })
        assertEquals(listOf("opaque-a", "opaque-b"), form.candidates.map { it.inputValue })
    }

    @Test
    fun `unnamed submit remains an executable observed action without inventing a field`() {
        val form = parser.parse(
            """
                <form action='?char=123' method='post'>
                  <select name='guard'><option value='none' selected>호위하지 않는다</option></select>
                  <input type='submit' value='Set'>
                </form>
            """.trimIndent(),
        ).forms.single()

        assertEquals("Set", form.submitLabel)
        assertEquals("unnamed-submit", form.submitSource)
        assertTrue(form.submitFields.isEmpty())
        assertTrue(form.candidates.single().selected)
        val guarded = app.spammy.hof.town.common.service.TownActionGuard().guard(
            app.spammy.hof.town.common.model.ParsedTownPage(listOf(form)),
            app.spammy.hof.town.common.model.TownActionRequest(
                form.actionId,
                selections = listOf(app.spammy.hof.town.common.model.TownActionSelection(form.candidates.single().id)),
            ),
        )
        assertEquals(listOf("guard"), guarded.formEntries.map { it.name })
    }

    @Test
    fun `text and number inputs are exposed as safe editable fields`() {
        val form = parser.parse(
            """
                <form action='?char=123' method='post'>
                  <input type='text' name='newname' value='old' maxlength='16'>
                  <input type='number' name='quantity0' value='40'>
                  <input type='submit' name='rename2' value='Change'>
                </form>
            """.trimIndent(),
        ).forms.single()

        assertEquals(listOf("old", "40"), form.editableFields.map { it.value })
        assertEquals(listOf(16, null), form.editableFields.map { it.maxLength })
        assertEquals("rename2", form.submitSource)
    }

    private fun fixture(path: String): String = requireNotNull(javaClass.classLoader.getResource(path))
        .readText(StandardCharsets.UTF_8)
}
