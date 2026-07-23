package app.spammy.hof.automation.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutomationLootSignalServiceTest {
    private val signals = AutomationLootSignalService()

    @Test
    fun `NFKC whitespace and latin case normalization match material names`() {
        assertTrue(signals.matches("  Steel   Ingot ", "ＳＴＥＥＬ ingot"))
    }

    @Test
    fun `partial and similar names do not trigger a quest refresh`() {
        assertFalse(signals.matches("Steel Ingot", "Steel Ingot Fragment"))
        assertFalse(signals.matches("Steel Ingot", "Steel-Ingot"))
    }
}
