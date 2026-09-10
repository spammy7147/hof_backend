package app.spammy.hof.push.kafka

import app.spammy.hof.automation.outbox.AutomationConsumedEventService
import app.spammy.hof.push.service.AndroidPushService
import app.spammy.hof.push.service.PushOutboxService
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
@Profile("prod")
class PushRequestConsumer(
    private val objectMapper: ObjectMapper,
    private val consumedEventService: AutomationConsumedEventService,
    private val pushService: AndroidPushService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = [PushOutboxService.PUSH_TOPIC],
        groupId = "hof-push",
        containerFactory = "automationKafkaListenerFactory",
    )
    fun consume(payload: String, acknowledgment: Acknowledgment) {
        val event = objectMapper.readValue(payload, PushRequestEvent::class.java)
        if (consumedEventService.wasConsumed(event.eventId)) {
            log.debug("Skipping consumed push event eventId={} type={}", event.eventId, event.type)
            acknowledgment.acknowledge()
            return
        }
        log.info(
            "Consuming push event eventId={} accountId={} challengeId={} type={}",
            event.eventId,
            event.accountId,
            event.challengeId,
            event.type,
        )
        when (event.type) {
            "CAPTCHA_REQUIRED" -> pushService.sendCaptchaRequired(event.accountId, requireNotNull(event.challengeId), event.eventId)
            "LOGIN_REQUIRED" -> pushService.sendLoginRequired(event.accountId, event.eventId)
            else -> throw IllegalArgumentException("지원하지 않는 push type입니다: ${event.type}")
        }
        consumedEventService.record(event.eventId)
        acknowledgment.acknowledge()
        log.info("Completed push event eventId={} type={}", event.eventId, event.type)
    }
}
