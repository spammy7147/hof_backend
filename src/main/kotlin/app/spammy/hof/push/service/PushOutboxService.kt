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
    fun enqueueCaptchaRequired(account: HofAccountEntity, challengeId: Long) {
        enqueue(account, PushRequestEvent(UUID.randomUUID().toString(), account.id, "CAPTCHA_REQUIRED", challengeId))
    }

    @Transactional
    fun enqueueLoginRequired(account: HofAccountEntity) {
        enqueue(account, PushRequestEvent(UUID.randomUUID().toString(), account.id, "LOGIN_REQUIRED"))
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
