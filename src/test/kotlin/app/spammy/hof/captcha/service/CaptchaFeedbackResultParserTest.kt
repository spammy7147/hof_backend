package app.spammy.hof.captcha.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CaptchaFeedbackResultParserTest {
    private val parser = CaptchaFeedbackResultParser()

    @Test
    fun `finds the exact current player and submitted answer among other users`() {
        val html = history(
            """
            -[다른사람] 검문 통과에 실패했다 (대답:u34Wc 정답:abcde)
            -[《얼어붙은 손길》다른사람] 검문 통과에 성공했다 (대답:CPYNE 정답:CPYNE)
            -[《얼어붙은 손길》 공민이] 검문 통과에 실패했다 (대답:u34Wc 정답:u34We)
            """,
        )

        val result = parser.parse(html, "공민이", "u34Wc")

        assertEquals(CaptchaFeedbackLabel(correctText = "u34We", accepted = false), result)
    }

    @Test
    fun `accepts a verified success entry`() {
        val html = history(
            "-[《얼어붙은 손길》공민이] 검문 통과에 성공했다 (대답:CPYNE 정답:CPYNE)",
        )

        assertEquals(
            CaptchaFeedbackLabel(correctText = "CPYNE", accepted = true),
            parser.parse(html, "《얼어붙은 손길》공민이", "CPYNE"),
        )
    }

    @Test
    fun `does not use another players entry even when the submitted answer matches`() {
        val html = history(
            "-[다른사람] 검문 통과에 실패했다 (대답:u34Wc 정답:u34We)",
        )

        assertNull(parser.parse(html, "《얼어붙은 손길》공민이", "u34Wc"))
    }

    @Test
    fun `requires the submitted answer to match with exact casing`() {
        val html = history(
            "-[《얼어붙은 손길》공민이] 검문 통과에 실패했다 (대답:u34wc 정답:u34We)",
        )

        assertNull(parser.parse(html, "《얼어붙은 손길》공민이", "u34Wc"))
    }

    @Test
    fun `discards ambiguous duplicate entries instead of guessing`() {
        val html = history(
            """
            -[《얼어붙은 손길》공민이] 검문 통과에 실패했다 (대답:u34Wc 정답:u34We)
            -[《얼어붙은 손길》공민이] 검문 통과에 실패했다 (대답:u34Wc 정답:u34Wr)
            """,
        )

        assertNull(parser.parse(html, "《얼어붙은 손길》공민이", "u34Wc"))
    }

    @Test
    fun `discards an internally inconsistent result`() {
        val html = history(
            "-[《얼어붙은 손길》공민이] 검문 통과에 실패했다 (대답:CPYNE 정답:CPYNE)",
        )

        assertNull(parser.parse(html, "《얼어붙은 손길》공민이", "CPYNE"))
    }

    private fun history(text: String): String =
        "<html><body><div>${text.trimIndent()}</div></body></html>"
}
