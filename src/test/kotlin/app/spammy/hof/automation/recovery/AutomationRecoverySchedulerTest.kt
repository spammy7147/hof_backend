package app.spammy.hof.automation.recovery

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.automation.entity.AutomationJobEntity
import app.spammy.hof.automation.entity.AutomationProfileEntity
import app.spammy.hof.automation.port.AutomationWakeupPort
import app.spammy.hof.automation.repository.AutomationJobQueryRepository
import app.spammy.hof.common.time.TimeProvider
import java.time.Instant
import kotlin.test.Test
import org.mockito.Mockito

class AutomationRecoverySchedulerTest {
    private val query = Mockito.mock(AutomationJobQueryRepository::class.java)
    private val wakeup = Mockito.mock(AutomationWakeupPort::class.java)
    private val now = Instant.parse("2026-07-13T00:00:00Z")
    private val scheduler = AutomationRecoveryScheduler(query, wakeup, TimeProvider { now })

    @Test
    fun onlyRowsReturnedByTheExplicitRecoverableQueryAreWoken() {
        val first = job(1L)
        val second = job(2L)
        Mockito.`when`(query.findRecoverable(now)).thenReturn(listOf(first, second))

        scheduler.enqueueDue("STARTUP_RECOVERY")

        Mockito.verify(wakeup).wake(1L, "STARTUP_RECOVERY")
        Mockito.verify(wakeup).wake(2L, "STARTUP_RECOVERY")
        Mockito.verifyNoMoreInteractions(wakeup)
    }

    private fun job(accountId: Long): AutomationJobEntity {
        val account = HofAccountEntity(accountId, "account-$accountId", "encrypted", now)
        val profile = AutomationProfileEntity(accountId, account, "통합 자동화", "UNIFIED", true, now, now)
        return AutomationJobEntity(
            id = accountId,
            account = account,
            profile = profile,
            status = "RUNNING",
            currentStepIndex = 0,
            message = null,
            createdAt = now,
            startedAt = now,
            updatedAt = now,
            finishedAt = null,
        )
    }
}
