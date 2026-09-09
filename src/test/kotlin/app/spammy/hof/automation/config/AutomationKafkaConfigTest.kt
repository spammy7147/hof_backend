package app.spammy.hof.automation.config

import app.spammy.hof.automation.outbox.AutomationOutboxService
import app.spammy.hof.push.service.PushOutboxService
import kotlin.test.assertEquals
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.DefaultKafkaConsumerFactory

class AutomationKafkaConfigTest {
    @Test
    fun `wake 소비량만 제한하고 공유 factory의 push 설정은 보존한다`() {
        val property = ConsumerConfig.MAX_POLL_RECORDS_CONFIG
        val consumerFactory = DefaultKafkaConsumerFactory<String, String>(mapOf(property to 37))
        val factory = AutomationKafkaConfig().automationKafkaListenerFactory(consumerFactory)
        val wake = factory.createContainer(AutomationOutboxService.WAKEUP_TOPIC)
        val push = factory.createContainer(PushOutboxService.PUSH_TOPIC)

        assertEquals("1", wake.containerProperties.kafkaConsumerProperties.getProperty(property))
        assertEquals(37, push.containerProperties.kafkaConsumerProperties[property] ?: consumerFactory.configurationProperties[property])
        assertEquals(37, consumerFactory.configurationProperties[property])
    }
}
