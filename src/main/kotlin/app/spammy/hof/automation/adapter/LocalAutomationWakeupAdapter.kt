package app.spammy.hof.automation.adapter

import app.spammy.hof.automation.port.AutomationWakeupPort
import app.spammy.hof.automation.service.UnifiedAutomationRunner
import jakarta.annotation.PreDestroy
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

@Component
class LocalAutomationWakeupAdapter(
    private val runnerProvider: ObjectProvider<UnifiedAutomationRunner>,
) : AutomationWakeupPort {
    private val executors = ConcurrentHashMap<Long, ScheduledExecutorService>()

    override fun wake(accountId: Long, reason: String) {
        executor(accountId).schedule({ runnerProvider.ifAvailable?.runOne(accountId) }, 50, TimeUnit.MILLISECONDS)
    }

    override fun schedule(accountId: Long, at: Instant, reason: String) {
        val delay = Duration.between(Instant.now(), at).toMillis().coerceAtLeast(0)
        executor(accountId).schedule({ runnerProvider.ifAvailable?.runOne(accountId) }, delay, TimeUnit.MILLISECONDS)
    }

    private fun executor(accountId: Long): ScheduledExecutorService = executors.computeIfAbsent(accountId) {
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "automation-account-$accountId").apply { isDaemon = true }
        }
    }

    @PreDestroy
    fun close() {
        executors.values.forEach(ScheduledExecutorService::shutdownNow)
    }
}
