package app.spammy.hof.captcha.config

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CaptchaAutoSolvePropertiesTest {
    @Test
    fun `defaults to three disabled HTTP OCR attempts`() {
        val properties = CaptchaAutoSolveProperties()

        assertEquals(false, properties.enabled)
        assertEquals(3, properties.maxAttempts)
        assertEquals(3, properties.maxOcrFailures)
        assertEquals(Duration.ofSeconds(10), properties.timeout)
        assertEquals(Duration.ofMillis(500), properties.ocrRetryDelay)
    }

    @Test
    fun `rejects unsafe retry and timeout settings`() {
        assertFailsWith<IllegalArgumentException> { CaptchaAutoSolveProperties(maxAttempts = 0) }
        assertFailsWith<IllegalArgumentException> { CaptchaAutoSolveProperties(maxOcrFailures = 0) }
        assertFailsWith<IllegalArgumentException> { CaptchaAutoSolveProperties(timeout = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { CaptchaAutoSolveProperties(ocrRetryDelay = Duration.ofMillis(-1)) }
    }

    @Test
    fun `enabled HTTP OCR requires a valid URL and sufficiently long token`() {
        assertFailsWith<IllegalArgumentException> {
            CaptchaAutoSolveProperties(enabled = true, baseUrl = "", token = "x".repeat(32))
        }
        assertFailsWith<IllegalArgumentException> {
            CaptchaAutoSolveProperties(enabled = true, baseUrl = "http://ocr.test:8090", token = "short")
        }

        val properties = CaptchaAutoSolveProperties(
            enabled = true,
            baseUrl = "http://ocr.test:8090",
            token = "x".repeat(64),
        )
        assertEquals("http://ocr.test:8090", properties.baseUrl)
    }
}
