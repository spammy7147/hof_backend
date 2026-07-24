package app.spammy.hof.push.kafka

import app.spammy.hof.automation.outbox.AutomationConsumedEventService
import app.spammy.hof.push.service.AndroidPushService
import app.spammy.hof.push.service.PushOutboxService
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
    @KafkaListener(
        topics = [PushOutboxService.PUSH_TOPIC],
        groupId = "hof-push",
        containerFactory = "automationKafkaListenerFactory",
    )
    fun consume(payload: String, acknowledgment: Acknowledgment) {
        val event = objectMapper.readValue(payload, PushRequestEvent::class.java)
        if (consumedEventService.wasConsumed(event.eventId)) {
            acknowledgment.acknowledge()
            return
        }
        when (event.type) {
            "CAPTCHA_REQUIRED" -> pushService.sendCaptchaRequired(event.accountId, requireNotNull(event.challengeId))
            "LOGIN_REQUIRED" -> pushService.sendLoginRequired(event.accountId)
            else -> throw IllegalArgumentException("지원하지 않는 push type입니다: ${event.type}")
        }
        consumedEventService.record(event.eventId)
        acknowledgment.acknowledge()
    }
}
