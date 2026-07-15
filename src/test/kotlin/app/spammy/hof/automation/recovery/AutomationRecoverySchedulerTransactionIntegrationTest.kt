package app.spammy.hof.automation.recovery

import app.spammy.hof.automation.port.AutomationWakeupPort
import app.spammy.hof.automation.repository.AutomationJobQueryRepository
import app.spammy.hof.automation.repository.TypedAutomationQueryRepository
import app.spammy.hof.common.time.TimeProvider
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager

@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(AutomationRecoveryDueAccountQuery::class, AutomationRecoverySchedulerTransactionIntegrationTest.Config::class)
class AutomationRecoverySchedulerTransactionIntegrationTest {
    @Autowired private lateinit var due: AutomationRecoveryDueAccountQuery
    @Autowired private lateinit var jobs: AutomationJobQueryRepository
    @Autowired private lateinit var typed: TypedAutomationQueryRepository
    @Autowired private lateinit var wakeups: AutomationWakeupPort

    @Test
    fun `proxied query transaction closes before deduplicated wake delivery`() {
        Mockito.`when`(jobs.findRecoverableAccountIds(NOW)).thenAnswer {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
            listOf(7L, 8L)
        }
        Mockito.`when`(typed.findRecoverableRuntimeAccountIds(NOW)).thenAnswer {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
            listOf(7L, 9L)
        }
        Mockito.doAnswer {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
            null
        }.`when`(wakeups).wake(Mockito.anyLong(), Mockito.anyString() ?: "")
        val scheduler = AutomationRecoveryScheduler(due, wakeups, TimeProvider { NOW })

        scheduler.enqueueDue("RECOVERY")

        Mockito.verify(wakeups).wake(7, "RECOVERY")
        Mockito.verify(wakeups).wake(8, "RECOVERY")
        Mockito.verify(wakeups).wake(9, "RECOVERY")
        Mockito.verifyNoMoreInteractions(wakeups)
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Config {
        @Bean fun jobs(): AutomationJobQueryRepository = Mockito.mock(AutomationJobQueryRepository::class.java)
        @Bean fun typed(): TypedAutomationQueryRepository = Mockito.mock(TypedAutomationQueryRepository::class.java)
        @Bean fun wakeups(): AutomationWakeupPort = Mockito.mock(AutomationWakeupPort::class.java)
    }

    private companion object { val NOW: Instant = Instant.parse("2026-07-16T00:00:00Z") }
}
