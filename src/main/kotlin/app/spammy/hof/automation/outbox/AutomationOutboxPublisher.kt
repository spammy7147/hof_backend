package app.spammy.hof.automation.outbox

import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.automation.history.AutomationDecisionJournal
import app.spammy.hof.automation.history.AUTOMATION_HISTORY_OUTBOX_TOPIC
import java.util.concurrent.TimeUnit
import org.springframework.context.annotation.Profile
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.slf4j.LoggerFactory
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
@Profile("test")
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
@Profile("dev | prod")
class KafkaAutomationOutboxTransport(
    private val kafkaTemplate: KafkaTemplate<String, String>,
) : AutomationOutboxTransport {
    override val supportedTopics: Set<String>? = null

    override fun publish(row: AutomationOutboxEntity) {
        kafkaTemplate.send(row.topic, row.eventKey, row.payload).get(10, TimeUnit.SECONDS)
    }
}

/** Publishes one due batch. Scheduling is a production concern kept outside test replay contexts. */
@Component
class AutomationOutboxPublisher(
    private val queryRepository: AutomationOutboxQueryRepository,
    private val marker: AutomationOutboxPublishMarker,
    private val transport: AutomationOutboxTransport,
    private val timeProvider: TimeProvider,
    private val journal: AutomationDecisionJournal? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun publishBatch() {
        try {
            queryRepository.findUnpublished(timeProvider.now(), topics = transport.supportedTopics,
                excludedTopics = setOf(AUTOMATION_HISTORY_OUTBOX_TOPIC)).forEach { row ->
                transport.publish(row)
                marker.markPublished(row.id)
            }
        } finally {
            // 로컬 이력은 Kafka 장애와 독립적으로 복구한다. 상세 문맥을 전송하거나 wake 조회 예산을 쓰지 않는다.
            try {
                journal?.publishDeferredResults()
            } catch (error: RuntimeException) {
                log.warn("Deferred history poll unavailable errorType={}", error.javaClass.name)
            }
        }
    }
}

@Component
@Profile("dev | prod")
class AutomationOutboxPollingScheduler(
    private val publisher: AutomationOutboxPublisher,
) {
    @Scheduled(fixedDelayString = "\${hof.automation.outbox-delay-ms:500}")
    fun publishDueBatch() = publisher.publishBatch()
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
