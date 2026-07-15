package app.spammy.hof.automation.outbox

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.automation.port.AutomationWakeupPort
import app.spammy.hof.automation.entity.AutomationEntryEntity
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.repository.AdventureDailyPreflightQueryRepository
import app.spammy.hof.automation.repository.AutomationEntryCommandRepository
import app.spammy.hof.automation.repository.TypedAutomationQueryRepository
import app.spammy.hof.automation.service.TypedAutomationLifecycleBridge
import app.spammy.hof.common.persistence.QueryDslConfig
import app.spammy.hof.common.time.TimeProvider
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertNotNull
import org.mockito.Mockito
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
    TypedAutomationQueryRepository::class,
    AdventureDailyPreflightQueryRepository::class,
    TypedAutomationLifecycleBridge::class,
    AutomationOutboxPublishMarker::class,
    AutomationOutboxPublisher::class,
    LocalAutomationOutboxTransport::class,
    AutomationOutboxLocalReplayIntegrationTest.Config::class,
)
class AutomationOutboxLocalReplayIntegrationTest {
    @Autowired private lateinit var accounts: HofAccountRepository
    @Autowired private lateinit var entries: AutomationEntryCommandRepository
    @Autowired private lateinit var query: AutomationOutboxQueryRepository
    @Autowired private lateinit var publisher: AutomationOutboxPublisher
    @Autowired private lateinit var wakeups: AutomationWakeupPort
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var lifecycle: TypedAutomationLifecycleBridge

    @Test
    fun `no-profile publisher replays committed wake row locally and marks it published`() {
        val accountId = TransactionTemplate(transactionManager).execute {
            val account = accounts.save(HofAccountEntity(loginId = "local-replay", encryptedPassword = "encrypted", createdAt = NOW))
            entries.save(AutomationEntryEntity(account = account, type = AutomationType.QUEST, priority = 0, enabled = true, createdAt = NOW, updatedAt = NOW))
            account.id
        }
        TransactionTemplate(transactionManager).execute { lifecycle.start(accountId, "CRASH_RECOVERY") }
        val rowId = query.findUnpublished(NOW.plusSeconds(1)).single { it.account.id == accountId }.id

        publisher.publishBatch()

        Mockito.verify(wakeups).wake(accountId, "CRASH_RECOVERY")
        assertNotNull(query.findById(rowId)?.publishedAt)
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Config {
        @Bean fun objectMapper(): ObjectMapper = jacksonObjectMapper()
        @Bean fun timeProvider(): TimeProvider = TimeProvider { NOW }
        @Bean fun wakeups(): AutomationWakeupPort = Mockito.mock(AutomationWakeupPort::class.java)
    }

    private companion object { val NOW: Instant = Instant.parse("2026-07-16T00:00:00Z") }
}
