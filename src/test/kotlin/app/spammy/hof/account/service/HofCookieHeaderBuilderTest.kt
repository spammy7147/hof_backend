package app.spammy.hof.account.service

import kotlin.test.Test
import kotlin.test.assertEquals

class HofCookieHeaderBuilderTest {
    @Test
    fun buildsCookieHeaderFromNameValues() {
        val header = HofCookieHeaderBuilder().build(
            mapOf(
                "PHPSESSID" to "abc",
                "NO" to "123",
            ),
        )

        assertEquals("PHPSESSID=abc; NO=123", header)
    }
}
