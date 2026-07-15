package app.spammy.hof.automation.outbox

import app.spammy.hof.common.time.TimeProvider
import java.util.concurrent.TimeUnit
import org.springframework.context.annotation.Profile
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

interface AutomationOutboxTransport {
    /** Null means every outbox topic (Kafka); local mode selects only account wake rows. */
    val supportedTopics: Set<String>?
    fun publish(row: AutomationOutboxEntity)
}

fun interface LocalAutomationWakeExecutor {
    fun execute(accountId: Long, reason: String)
}

@Component
@Profile("!docker & !kafka")
class LocalAutomationOutboxTransport(
    private val executor: LocalAutomationWakeExecutor,
    private val objectMapper: ObjectMapper,
) : AutomationOutboxTransport {
    override val supportedTopics = setOf(AutomationOutboxService.WAKEUP_TOPIC)

    override fun publish(row: AutomationOutboxEntity) {
        val event = objectMapper.readValue(row.payload, AutomationWakeupEvent::class.java)
        executor.execute(event.accountId, event.reason)
    }
}

@Component
@Profile("docker | kafka")
class KafkaAutomationOutboxTransport(
    private val kafkaTemplate: KafkaTemplate<String, String>,
) : AutomationOutboxTransport {
    override val supportedTopics: Set<String>? = null

    override fun publish(row: AutomationOutboxEntity) {
        kafkaTemplate.send(row.topic, row.eventKey, row.payload).get(10, TimeUnit.SECONDS)
    }
}

/** Polling exists in every profile; only the transport changes between local execution and Kafka. */
@Component
class AutomationOutboxPublisher(
    private val queryRepository: AutomationOutboxQueryRepository,
    private val marker: AutomationOutboxPublishMarker,
    private val transport: AutomationOutboxTransport,
    private val timeProvider: TimeProvider,
) {
    @Scheduled(fixedDelayString = "\${hof.automation.outbox-delay-ms:500}")
    fun publishBatch() {
        queryRepository.findUnpublished(timeProvider.now(), topics = transport.supportedTopics).forEach { row ->
            transport.publish(row)
            marker.markPublished(row.id)
        }
    }
}

@Component
class AutomationOutboxPublishMarker(
    private val queryRepository: AutomationOutboxQueryRepository,
    private val timeProvider: TimeProvider,
) {
    @Transactional
    fun markPublished(id: Long) {
        queryRepository.findById(id)?.publishedAt = timeProvider.now()
    }
}
