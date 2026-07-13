package app.spammy.hof.common.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogSanitizerTest {
    @Test
    fun sanitizeBodyMasksPasswordFields() {
        val sanitized = LogSanitizer.sanitizeBody(
            """{"loginId":"abcd12","password":"qwer12","pass":"secret"}""",
        )

        assertTrue(sanitized.contains(""""loginId":"abcd12""""))
        assertTrue(sanitized.contains(""""password":"***""""))
        assertTrue(sanitized.contains(""""pass":"***""""))
        assertFalse(sanitized.contains("qwer12"))
        assertFalse(sanitized.contains("secret"))
    }

    @Test
    fun sanitizeHeaderMasksSensitiveHeaders() {
        assertEquals("Authorization=<masked>", LogSanitizer.sanitizeHeader("Authorization", "Bearer token"))
        assertEquals("Cookie=<masked>", LogSanitizer.sanitizeHeader("Cookie", "PHPSESSID=abc"))
        assertEquals("User-Agent=Spammy", LogSanitizer.sanitizeHeader("User-Agent", "Spammy"))
    }

    @Test
    fun previewTruncatesLongValues() {
        val preview = LogSanitizer.preview("abcdef", maxLength = 4)

        assertEquals("abcd...(6 chars)", preview)
    }
}
