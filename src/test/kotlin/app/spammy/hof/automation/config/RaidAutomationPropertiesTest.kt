package app.spammy.hof.automation.config

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RaidAutomationPropertiesTest {
    @Test
    fun `레이드 fallback은 기본 120초이며 enforcement가 활성화된다`() {
        val properties = RaidAutomationProperties()

        assertEquals(Duration.ofSeconds(120), properties.fallbackCooldown)
        assertEquals(true, properties.fallbackEnforcementEnabled)
        assertEquals(false, properties.fixtureProbeEnabled)
    }

    @Test
    fun `0 이하 fallback은 거절한다`() {
        assertFailsWith<IllegalArgumentException> {
            RaidAutomationProperties(fallbackCooldown = Duration.ZERO)
        }
        assertFailsWith<IllegalArgumentException> {
            RaidAutomationProperties(fallbackCooldown = Duration.ofSeconds(-1))
        }
    }
}
