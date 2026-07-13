package app.spammy.hof.automation.outbox

import app.spammy.hof.common.time.TimeProvider
import java.util.concurrent.TimeUnit
import org.springframework.context.annotation.Profile
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
@Profile("docker")
class AutomationOutboxPublisher(
    private val queryRepository: AutomationOutboxQueryRepository,
    private val marker: AutomationOutboxPublishMarker,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val timeProvider: TimeProvider,
) {
    @Scheduled(fixedDelayString = "\${hof.automation.outbox-delay-ms:500}")
    fun publishBatch() {
        queryRepository.findUnpublished(timeProvider.now()).forEach { row ->
            kafkaTemplate.send(row.topic, row.eventKey, row.payload).get(10, TimeUnit.SECONDS)
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
