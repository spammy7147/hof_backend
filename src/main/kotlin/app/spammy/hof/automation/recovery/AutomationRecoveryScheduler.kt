package app.spammy.hof.automation.recovery

import app.spammy.hof.automation.port.AutomationWakeupPort
import app.spammy.hof.automation.repository.AutomationJobQueryRepository
import app.spammy.hof.automation.repository.TypedAutomationQueryRepository
import app.spammy.hof.common.time.TimeProvider
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
@Profile("docker | kafka")
class AutomationRecoveryScheduler(
    private val queryRepository: AutomationJobQueryRepository,
    private val wakeupPort: AutomationWakeupPort,
    private val timeProvider: TimeProvider,
    private val typedQueryRepository: TypedAutomationQueryRepository? = null,
) {
    @EventListener(ApplicationReadyEvent::class)
    fun recoverOnStartup() = enqueueDue("STARTUP_RECOVERY")

    @Scheduled(fixedDelayString = "\${hof.automation.recovery-delay-ms:5000}")
    fun recoverDueJobs() = enqueueDue("DUE_RECOVERY")

    @Transactional(readOnly = true)
    fun enqueueDue(reason: String) {
        queryRepository.findRecoverable(timeProvider.now()).forEach { job ->
            wakeupPort.wake(job.account.id, reason)
        }
        typedQueryRepository?.findRecoverableRuntimeAccountIds(timeProvider.now())?.forEach { accountId ->
            wakeupPort.wake(accountId, "TYPED_$reason")
        }
    }
}
