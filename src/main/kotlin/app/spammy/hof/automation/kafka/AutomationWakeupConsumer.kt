package app.spammy.hof.automation.kafka

import app.spammy.hof.automation.lease.AccountAutomationLeaseService
import app.spammy.hof.automation.outbox.AutomationConsumedEventService
import app.spammy.hof.automation.outbox.AutomationOutboxService
import app.spammy.hof.automation.outbox.AutomationWakeupEvent
import app.spammy.hof.automation.service.UnifiedAutomationRunner
import app.spammy.hof.common.time.TimeProvider
import java.net.InetAddress
import java.time.Duration
import java.util.UUID
import org.springframework.context.annotation.Profile
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
@Profile("docker")
class AutomationWakeupConsumer(
    private val objectMapper: ObjectMapper,
    private val consumedEventService: AutomationConsumedEventService,
    private val leaseService: AccountAutomationLeaseService,
    private val runner: UnifiedAutomationRunner,
    private val timeProvider: TimeProvider,
) {
    private val workerId = "${runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("worker")}-${UUID.randomUUID()}"

    @KafkaListener(
        topics = [AutomationOutboxService.WAKEUP_TOPIC],
        groupId = "hof-automation",
        containerFactory = "automationKafkaListenerFactory",
    )
    fun consume(
        payload: String,
        acknowledgment: Acknowledgment,
    ) {
        val event = objectMapper.readValue(payload, AutomationWakeupEvent::class.java)
        if (consumedEventService.wasConsumed(event.eventId)) {
            acknowledgment.acknowledge()
            return
        }
        if (!leaseService.tryAcquire(event.accountId, workerId, timeProvider.now(), LEASE_DURATION)) return
        try {
            runner.runOne(event.accountId)
            consumedEventService.record(event.eventId)
            acknowledgment.acknowledge()
        } finally {
            leaseService.release(event.accountId, workerId)
        }
    }

    private companion object {
        val LEASE_DURATION: Duration = Duration.ofMinutes(5)
    }
}
