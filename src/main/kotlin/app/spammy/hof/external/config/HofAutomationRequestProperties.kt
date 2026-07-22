package app.spammy.hof.external.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("hof.automation-request")
data class HofAutomationRequestProperties(
    val minimumInterval: Duration = Duration.ofMillis(500),
    val shortCooldown: Duration = Duration.ofSeconds(30),
    val longCooldown: Duration = Duration.ofMinutes(3),
    val longCooldownThreshold: Int = 3,
) {
    init {
        require(!minimumInterval.isNegative) { "minimumInterval must not be negative" }
        require(!shortCooldown.isNegative) { "shortCooldown must not be negative" }
        require(!longCooldown.isNegative) { "longCooldown must not be negative" }
        require(longCooldownThreshold >= 1) { "longCooldownThreshold must be at least 1" }
    }
}
