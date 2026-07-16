package app.spammy.hof.automation.recovery

import app.spammy.hof.automation.port.AutomationWakeupPort
import app.spammy.hof.common.time.TimeProvider
import java.time.Instant
import kotlin.test.Test
import org.mockito.Mockito

class AutomationRecoverySchedulerTest {
    private val query = Mockito.mock(AutomationRecoveryDueAccountQuery::class.java)
    private val wakeup = Mockito.mock(AutomationWakeupPort::class.java)
    private val now = Instant.parse("2026-07-13T00:00:00Z")
    private val scheduler = AutomationRecoveryScheduler(query, wakeup, TimeProvider { now })

    @Test
    fun onlyRowsReturnedByTheExplicitRecoverableQueryAreWoken() {
        Mockito.`when`(query.findDueAccountIds(now)).thenReturn(listOf(1L, 2L, 3L))

        scheduler.enqueueDue("STARTUP_RECOVERY")

        Mockito.verify(wakeup).wake(1L, "STARTUP_RECOVERY")
        Mockito.verify(wakeup).wake(2L, "STARTUP_RECOVERY")
        Mockito.verify(wakeup).wake(3L, "STARTUP_RECOVERY")
        Mockito.verifyNoMoreInteractions(wakeup)
    }

}
