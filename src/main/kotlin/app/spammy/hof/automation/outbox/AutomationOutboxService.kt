package app.spammy.hof.automation.outbox

import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

data class AutomationWakeupEvent(
    val eventId: String,
    val accountId: Long,
    val reason: String,
    val createdAt: String,
)

@Service
class AutomationOutboxService(
    private val accountQueryRepository: AccountQueryRepository,
    private val repository: AutomationOutboxRepository,
    private val objectMapper: ObjectMapper,
    private val timeProvider: TimeProvider,
) {
    @Transactional
    fun enqueue(
        accountId: Long,
        reason: String,
        availableAt: Instant? = null,
    ): AutomationOutboxEntity {
        val account = accountQueryRepository.findById(accountId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "HOF 계정을 찾지 못했습니다.")
        val now = timeProvider.now()
        val event = AutomationWakeupEvent(UUID.randomUUID().toString(), accountId, reason, now.toString())
        return repository.save(
            AutomationOutboxEntity(
                eventId = event.eventId,
                account = account,
                topic = WAKEUP_TOPIC,
                eventKey = accountId.toString(),
                payload = objectMapper.writeValueAsString(event),
                createdAt = now,
                // Kotlin 기본 인자에서 빈 필드를 읽으면 CGLIB 프록시 경로에서 NPE가 날 수 있어 본문에서 계산한다.
                availableAt = availableAt ?: now,
            ),
        )
    }

    companion object {
        const val WAKEUP_TOPIC = "hof.automation.wakeup"
    }
}
