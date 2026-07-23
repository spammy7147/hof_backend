package app.spammy.hof.automation.recovery

import app.spammy.hof.automation.entity.AutomationWorkType
import app.spammy.hof.automation.port.AutomationWakeupPort
import app.spammy.hof.automation.service.AutomationDueIndex
import app.spammy.hof.automation.service.AutomationDueTarget
import app.spammy.hof.common.time.TimeProvider
import java.time.Instant
import kotlin.test.Test
import org.mockito.Mockito

class AutomationSessionReconciliationSchedulerTest {
    @Test
    fun `due sessions wake each account once without calling HOF`() {
        val now = Instant.parse("2026-07-23T00:00:00Z")
        val due = Mockito.mock(AutomationDueIndex::class.java)
        val wakeups = Mockito.mock(AutomationWakeupPort::class.java)
        Mockito.`when`(due.dueAtOrBefore(now, 100)).thenReturn(
            listOf(
                AutomationDueTarget(7, 21, AutomationWorkType.QUEST, "quest-1"),
                AutomationDueTarget(7, 22, AutomationWorkType.ADVENTURE_MAP, "adventure/map"),
            ),
        )
        val scheduler = AutomationSessionReconciliationScheduler(due, wakeups, TimeProvider { now })

        scheduler.scanDue()

        Mockito.verify(wakeups).wake(7, "SESSION_RECONCILIATION_DUE")
    }
}
