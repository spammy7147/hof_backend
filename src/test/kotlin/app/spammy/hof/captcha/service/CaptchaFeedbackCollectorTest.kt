package app.spammy.hof.captcha.service

import app.spammy.hof.captcha.config.CaptchaAutoSolveProperties
import app.spammy.hof.external.parser.HofMainStatusParser
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.core.task.TaskExecutor

class CaptchaFeedbackCollectorTest {
    private val gateway = RecordingFeedbackGateway()
    private val collector = CaptchaFeedbackCollector(
        properties = CaptchaAutoSolveProperties(
            enabled = true,
            baseUrl = "http://ocr.test:8090",
            token = "x".repeat(64),
            ocrRetryDelay = Duration.ZERO,
        ),
        statusParser = HofMainStatusParser(),
        resultParser = CaptchaFeedbackResultParser(),
        gateway = gateway,
        taskExecutor = TaskExecutor(Runnable::run),
    )

    @Test
    fun `delivers only the image and verified captcha result without player identity`() {
        val image = byteArrayOf(1, 2, 3)
        val candidate = candidate(
            image = image,
            history = "-[《얼어붙은 손길》공민이] 검문 통과에 실패했다 (대답:u34Wc 정답:u34We)",
            expectedAccepted = false,
        )
        assertEquals("공민이", HofMainStatusParser().parse(candidate.responseHtml).playerName)
        assertEquals(
            CaptchaFeedbackLabel("u34We", false),
            CaptchaFeedbackResultParser().parse(candidate.responseHtml, "공민이", "u34Wc"),
        )

        collector.collectAfterCommit(candidate)
        image[0] = 9

        val feedback = gateway.feedback.single()
        assertEquals("u34Wc", feedback.predictedText)
        assertEquals("u34Wc", feedback.submittedText)
        assertEquals("u34We", feedback.correctText)
        assertEquals(false, feedback.accepted)
        assertEquals(CaptchaFeedbackSource.AUTOMATIC, feedback.source)
        assertEquals("2.1.1", feedback.engineVersion)
        assertContentEquals(byteArrayOf(1, 2, 3), feedback.image.bytes)
    }

    @Test
    fun `discards feedback when the page state and history outcome conflict`() {
        collector.collectAfterCommit(
            candidate(
                image = byteArrayOf(1),
                history = "-[《얼어붙은 손길》공민이] 검문 통과에 실패했다 (대답:u34Wc 정답:u34We)",
                expectedAccepted = true,
            ),
        )

        assertTrue(gateway.feedback.isEmpty())
    }

    private fun candidate(
        image: ByteArray,
        history: String,
        expectedAccepted: Boolean,
    ) = CaptchaFeedbackCandidate(
        accountId = 1L,
        challengeId = 7L,
        image = CaptchaImageResponse("image/png", image),
        responseHtml = """
            <html><body>
              <table id="menu2"><tr>
                <td>공민이</td>
                <td>Funds : ${'$'} 309,385,362<br>Work : Nothing</td>
                <td>Time : 6000/6000<br>Auction : item/funds</td>
              </tr></table>
              <div>$history</div>
            </body></html>
        """.trimIndent(),
        submittedText = "u34Wc",
        expectedAccepted = expectedAccepted,
        source = CaptchaFeedbackSource.AUTOMATIC,
        predictedText = "u34Wc",
        engineVersion = "2.1.1",
    )

    private class RecordingFeedbackGateway : CaptchaFeedbackGateway {
        val feedback = mutableListOf<CaptchaFeedback>()

        override fun submit(feedback: CaptchaFeedback) {
            this.feedback += feedback
        }
    }
}
