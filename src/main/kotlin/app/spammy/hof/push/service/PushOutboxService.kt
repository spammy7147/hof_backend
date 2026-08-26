package app.spammy.hof.push.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.automation.outbox.AutomationOutboxEntity
import app.spammy.hof.automation.outbox.AutomationOutboxRepository
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.push.kafka.PushRequestEvent
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

@Service
class PushOutboxService(
    private val repository: AutomationOutboxRepository,
    private val objectMapper: ObjectMapper,
    private val timeProvider: TimeProvider,
) {
    @Transactional
    fun enqueueCaptchaRequired(
        account: HofAccountEntity,
        challengeId: Long,
        eventId: String = UUID.randomUUID().toString(),
    ) {
        enqueue(account, PushRequestEvent(eventId, account.id, "CAPTCHA_REQUIRED", challengeId))
    }

    @Transactional
    fun enqueueLoginRequired(
        account: HofAccountEntity,
        eventId: String = UUID.randomUUID().toString(),
    ) {
        enqueue(account, PushRequestEvent(eventId, account.id, "LOGIN_REQUIRED"))
    }

    private fun enqueue(account: HofAccountEntity, event: PushRequestEvent) {
        val now = timeProvider.now()
        repository.save(
            AutomationOutboxEntity(
                eventId = event.eventId,
                account = account,
                topic = PUSH_TOPIC,
                eventKey = account.id.toString(),
                payload = objectMapper.writeValueAsString(event),
                createdAt = now,
                availableAt = now,
            ),
        )
    }

    companion object {
        const val PUSH_TOPIC = "hof.push.requests"
    }
}
