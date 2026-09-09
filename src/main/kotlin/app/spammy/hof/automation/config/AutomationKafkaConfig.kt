package app.spammy.hof.automation.config

import app.spammy.hof.automation.outbox.AutomationOutboxService
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.listener.ContainerProperties

@Configuration
@Profile("dev | prod")
class AutomationKafkaConfig {
    @Bean
    fun automationKafkaListenerFactory(
        consumerFactory: ConsumerFactory<String, String>,
    ): ConcurrentKafkaListenerContainerFactory<String, String> =
        ConcurrentKafkaListenerContainerFactory<String, String>().apply {
            setConsumerFactory(consumerFactory)
            containerProperties.ackMode = ContainerProperties.AckMode.MANUAL_IMMEDIATE
            // 각 wake는 느린 HOF 판단까지 동기로 수행한다. 다음 wake 전에 poll해 누적 timeout을 막는다.
            // 같은 factory를 사용하는 push consumer의 처리량은 유지한다.
            setContainerCustomizer { container ->
                if (container.containerProperties.topics?.singleOrNull() == AutomationOutboxService.WAKEUP_TOPIC) {
                    container.containerProperties.kafkaConsumerProperties.setProperty(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "1")
                }
            }
            setConcurrency(4)
        }
}
