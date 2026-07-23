package app.spammy.hof.automation.recovery

import app.spammy.hof.automation.port.AutomationWakeupPort
import app.spammy.hof.automation.service.AutomationDueIndex
import app.spammy.hof.common.time.TimeProvider
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Polls only the local due index. HOF is contacted later by the normal runner and only for
 * accounts whose persistent session is due.
 */
@Component
@Profile("docker | kafka")
class AutomationSessionReconciliationScheduler(
    private val dueIndex: AutomationDueIndex,
    private val wakeups: AutomationWakeupPort,
    private val timeProvider: TimeProvider,
) {
    @EventListener(ApplicationReadyEvent::class)
    fun recoverOnStartup() = scanDue()

    @Scheduled(fixedDelayString = "\${hof.automation-session.due-poll-delay:5000}")
    fun scanDue() {
        dueIndex.dueAtOrBefore(timeProvider.now(), 100)
            .asSequence()
            .map { it.accountId }
            .distinct()
            .forEach { wakeups.wake(it, DUE_REASON) }
    }

    private companion object {
        const val DUE_REASON = "SESSION_RECONCILIATION_DUE"
    }
}
