package app.spammy.hof.automation.config

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("hof.automation-session")
data class AutomationSessionProperties(
    val redisEnabled: Boolean = false,
    val reconciliationInterval: Duration = Duration.ofMinutes(30),
    val reconciliationJitter: Duration = Duration.ofMinutes(2),
    val duePollDelay: Duration = Duration.ofSeconds(5),
)
