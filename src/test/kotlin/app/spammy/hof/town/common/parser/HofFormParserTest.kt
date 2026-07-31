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

    private fun fixture(path: String): String = requireNotNull(javaClass.classLoader.getResource(path))
        .readText(StandardCharsets.UTF_8)
}
