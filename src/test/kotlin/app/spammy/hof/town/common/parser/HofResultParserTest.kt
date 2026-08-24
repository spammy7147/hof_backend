package app.spammy.hof.town.common.parser

import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HofResultParserTest {
    private val parser = HofResultParser()

    @Test
    fun `result parser never returns page html or footer`() {
        val result = parser.parse(fixture("fixtures/town/common/result-with-footer.html"))
        val visible = (result.messages + result.items.map { it.label }).joinToString(" ")

        assertTrue(result.messages.contains("교환에 성공했습니다."))
        assertEquals(listOf("Emerald Silk x 2"), result.items.map { it.label })
        assertFalse(visible.contains("<html", ignoreCase = true))
        assertFalse(visible.contains("Copy Right", ignoreCase = true))
        assertFalse(visible.contains("Funds:", ignoreCase = true))
    }

    private fun fixture(path: String): String = requireNotNull(javaClass.classLoader.getResource(path))
        .readText(StandardCharsets.UTF_8)
}
