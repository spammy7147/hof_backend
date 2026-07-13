package app.spammy.hof.automation.outbox

import app.spammy.hof.common.time.TimeProvider
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AutomationConsumedEventService(
    private val repository: AutomationConsumedEventRepository,
    private val queryRepository: AutomationOutboxQueryRepository,
    private val timeProvider: TimeProvider,
) {
    @Transactional(readOnly = true)
    fun wasConsumed(eventId: String): Boolean = queryRepository.consumed(eventId)

    @Transactional
    fun record(eventId: String) {
        try {
            repository.save(AutomationConsumedEventEntity(eventId, timeProvider.now()))
            repository.flush()
        } catch (_: DataIntegrityViolationException) {
            // At-least-once delivery can race on the same event; the primary key makes the second record harmless.
        }
    }
}
