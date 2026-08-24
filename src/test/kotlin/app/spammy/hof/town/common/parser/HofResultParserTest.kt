package app.spammy.hof.town.common.parser

import app.spammy.hof.town.fishing.dto.TownActionResultResponse
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

    @Test
    fun `레이드 전역 상태 충돌 문구를 명시적 실패로 보존한다`() {
        val result = parser.parse(
            """
            <html><body>
              <p>이미 전투 중입니다. 퇴치/보상 확인/상태 갱신을 해주세요.</p>
              <form method="post"><input type="submit" value="파티에 등록한다"></form>
            </body></html>
            """.trimIndent(),
        )

        assertEquals(
            listOf("이미 전투 중입니다. 퇴치/보상 확인/상태 갱신을 해주세요."),
            result.messages,
        )
        assertEquals("FAILURE", TownActionResultResponse.from(result).status)
    }

    private fun fixture(path: String): String = requireNotNull(javaClass.classLoader.getResource(path))
        .readText(StandardCharsets.UTF_8)
}
