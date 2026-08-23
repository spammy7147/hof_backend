package app.spammy.hof.automation.config

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("hof.automation.raid")
data class RaidAutomationProperties(
    val fallbackCooldown: Duration = Duration.ofSeconds(120),
    val fallbackEnforcementEnabled: Boolean = true,
    val fixtureProbeEnabled: Boolean = false,
) {
    init {
        require(!fallbackCooldown.isZero && !fallbackCooldown.isNegative) {
            "fallbackCooldown must be positive"
        }
    }
}
