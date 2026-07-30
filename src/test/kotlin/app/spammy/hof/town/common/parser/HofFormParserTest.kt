package app.spammy.hof.town.common.parser

import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import tools.jackson.module.kotlin.jacksonObjectMapper

class HofFormParserTest {
    private val parser = HofFormParser()

    @Test
    fun `only controls owned by selected form become candidates`() {
        val page = parser.parse(fixture("fixtures/town/common/form-boundaries.html"))

        assertEquals(2, page.forms.size)
        assertEquals(listOf("item-1"), page.forms.first().candidates.map { it.id })
        assertFalse(page.forms.first().rows.single { it.label.contains("표시 전용") }.selectable)
        assertNotEquals(page.forms[0].actionId, page.forms[1].actionId)
    }

    @Test
    fun `hidden and submit fields are retained only inside server model`() {
        val form = parser.parse(fixture("fixtures/town/common/form-boundaries.html")).forms.first()

        assertEquals(mapOf("csrf" to "server-only"), form.hiddenFields)
        assertEquals(mapOf("Create" to "교환"), form.submitFields)
    }

    @Test
    fun `serialized parsed form exposes no server form fields`() {
        val form = parser.parse(fixture("fixtures/town/common/form-boundaries.html")).forms.first()
        val json = jacksonObjectMapper().writeValueAsString(form)

        assertFalse(json.contains("server-only"))
        assertFalse(json.contains("inputName"))
        assertFalse(json.contains("inputValue"))
        assertFalse(json.contains("quantityFieldName"))
        assertTrue(json.contains("item-1"))
    }

    private fun fixture(path: String): String = requireNotNull(javaClass.classLoader.getResource(path))
        .readText(StandardCharsets.UTF_8)
}
