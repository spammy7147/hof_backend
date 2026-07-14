package app.spammy.hof.automation.kafka

import app.spammy.hof.automation.outbox.AutomationOutboxService
import app.spammy.hof.automation.port.AutomationWakeupPort
import java.time.Instant
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

@Component
@Primary
@Profile("docker | kafka")
class KafkaAutomationWakeupAdapter(
    private val outboxService: AutomationOutboxService,
) : AutomationWakeupPort {
    override fun wake(accountId: Long, reason: String) {
        outboxService.enqueue(accountId, reason)
    }

    override fun schedule(accountId: Long, at: Instant, reason: String) {
        outboxService.enqueue(accountId, reason, at)
    }
}
