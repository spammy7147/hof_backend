package app.spammy.hof.automation.port

import java.time.Instant

interface AutomationWakeupPort {
    fun wake(accountId: Long, reason: String)
    fun schedule(accountId: Long, at: Instant, reason: String)
}
