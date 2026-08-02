package app.spammy.hof.external.config

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("hof.request")
data class HofRequestProperties(
    val interactiveMinimumInterval: Duration = Duration.ofMillis(250),
    val automationMinimumInterval: Duration = Duration.ofSeconds(3),
    val shortCooldown: Duration = Duration.ofSeconds(1),
    val longCooldown: Duration = Duration.ofSeconds(60),
    val longCooldownThreshold: Int = 5,
) {
    init {
        require(!interactiveMinimumInterval.isNegative) { "interactiveMinimumInterval must not be negative" }
        require(!automationMinimumInterval.isNegative) { "automationMinimumInterval must not be negative" }
        require(!shortCooldown.isNegative) { "shortCooldown must not be negative" }
        require(!longCooldown.isNegative) { "longCooldown must not be negative" }
        require(longCooldownThreshold >= 1) { "longCooldownThreshold must be at least 1" }
    }
}
