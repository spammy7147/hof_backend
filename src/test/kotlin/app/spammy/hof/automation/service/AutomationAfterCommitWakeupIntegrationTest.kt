package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.automation.kafka.KafkaAutomationWakeupAdapter
import app.spammy.hof.automation.outbox.AutomationOutboxQueryRepository
import app.spammy.hof.automation.outbox.AutomationOutboxService
import app.spammy.hof.automation.port.AutomationWakeupPort
import app.spammy.hof.common.persistence.QueryDslConfig
import app.spammy.hof.common.time.TimeProvider
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper

@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
    QueryDslConfig::class,
    AccountQueryRepository::class,
    AutomationOutboxQueryRepository::class,
    AutomationOutboxService::class,
    AutomationAfterCommitWakeupService::class,
    AutomationAfterCommitWakeupIntegrationTest.Config::class,
)
class AutomationAfterCommitWakeupIntegrationTest {
    @Autowired private lateinit var accountRepository: HofAccountRepository
    @Autowired private lateinit var queryRepository: AutomationOutboxQueryRepository
    @Autowired private lateinit var wakeupService: AutomationAfterCommitWakeupService
    @Autowired private lateinit var wakeupPort: AutomationWakeupPort
    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun requiresNewWakePersistsOutboxEvenWhenTheCallingTransactionRollsBack() {
        val accountId = TransactionTemplate(transactionManager).execute {
            accountRepository.save(
                HofAccountEntity(loginId = "requires-new-wakeup", encryptedPassword = "encrypted", createdAt = NOW),
            ).id
        }

        assertFailsWith<ForcedRollback> {
            TransactionTemplate(transactionManager).executeWithoutResult {
                wakeupService.wake(accountId, "MODULES_UPDATED")
                throw ForcedRollback()
            }
        }

        val events = queryRepository.findUnpublished(NOW.plusSeconds(1))
            .filter { it.account.id == accountId }
        assertEquals(1, events.size)
        assertEquals(accountId, events.single().account.id)
        assertEquals("hof.automation.wakeup", events.single().topic)
    }

    @Test
    fun directKafkaWakeRollsBackWithItsCallingTransaction() {
        val accountId = TransactionTemplate(transactionManager).execute {
            accountRepository.save(
                HofAccountEntity(loginId = "atomic-outbox", encryptedPassword = "encrypted", createdAt = NOW),
            ).id
        }

        assertFailsWith<ForcedRollback> {
            TransactionTemplate(transactionManager).executeWithoutResult {
                wakeupPort.wake(accountId, "DIRECT_WAKE")
                throw ForcedRollback()
            }
        }

        val accountEvents = queryRepository.findUnpublished(NOW.plusSeconds(1))
            .filter { it.account.id == accountId }
        assertEquals(emptyList(), accountEvents)
    }

    private class ForcedRollback : RuntimeException()

    @TestConfiguration(proxyBeanMethods = false)
    class Config {
        @Bean
        fun objectMapper(): ObjectMapper = jacksonObjectMapper()

        @Bean
        fun timeProvider(): TimeProvider = TimeProvider { NOW }

        @Bean
        fun automationWakeupPort(outboxService: AutomationOutboxService): AutomationWakeupPort =
            KafkaAutomationWakeupAdapter(outboxService)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-14T03:00:00Z")
    }
}
