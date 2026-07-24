package app.spammy.hof.automation.recovery

import app.spammy.hof.automation.port.AutomationWakeupPort
import app.spammy.hof.automation.repository.TypedAutomationQueryRepository
import app.spammy.hof.common.time.TimeProvider
import java.time.Instant
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AutomationRecoveryDueAccountQuery(
    private val typed: TypedAutomationQueryRepository,
) {
    @Transactional(readOnly = true)
    fun findDueAccountIds(now: Instant): List<Long> =
        typed.findRecoverableRuntimeAccountIds(now).distinct().sorted()
}

@Component
@Profile("dev | prod")
class AutomationRecoveryScheduler(
    private val dueAccounts: AutomationRecoveryDueAccountQuery,
    private val wakeupPort: AutomationWakeupPort,
    private val timeProvider: TimeProvider,
) {
    @EventListener(ApplicationReadyEvent::class)
    fun recoverOnStartup() = enqueueDue("STARTUP_RECOVERY")

    @Scheduled(fixedDelayString = "\${hof.automation.recovery-delay-ms:5000}")
    fun recoverDueJobs() = enqueueDue("DUE_RECOVERY")

    /** Query proxy returns detached scalar IDs; wake/outbox delivery starts only after its transaction closes. */
    fun enqueueDue(reason: String) {
        dueAccounts.findDueAccountIds(timeProvider.now()).forEach { accountId -> wakeupPort.wake(accountId, reason) }
    }
}
