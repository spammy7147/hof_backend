package app.spammy.hof.automation.outbox

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.automation.port.AutomationWakeupPort
import app.spammy.hof.automation.entity.AutomationEntryEntity
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.repository.AdventureDailyPreflightQueryRepository
import app.spammy.hof.automation.repository.AutomationEntryCommandRepository
import app.spammy.hof.automation.repository.AutomationWorkSessionQueryRepository
import app.spammy.hof.automation.repository.TypedAutomationQueryRepository
import app.spammy.hof.automation.service.TypedAutomationLifecycleBridge
import app.spammy.hof.common.persistence.QueryDslConfig
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.auth.service.AccountExecutionAuthorizationReader
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.scheduling.config.ScheduledTaskHolder
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper

@DataJpaTest
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
    QueryDslConfig::class,
    AccountQueryRepository::class,
    AutomationOutboxQueryRepository::class,
    AutomationOutboxService::class,
    TypedAutomationQueryRepository::class,
    AdventureDailyPreflightQueryRepository::class,
    AutomationWorkSessionQueryRepository::class,
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
    @Autowired private lateinit var localExecutor: LocalAutomationWakeExecutor
    @Autowired private lateinit var wakeups: AutomationWakeupPort
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var lifecycle: TypedAutomationLifecycleBridge
    @Autowired private lateinit var scheduledTasks: ScheduledTaskHolder

    @Test
    fun `test transport has no background outbox poll competing with explicit replay`() {
        assertTrue(
            scheduledTasks.scheduledTasks.none { it.toString().contains("publishBatch") },
            "The test outbox publisher must only run when the test invokes it.",
        )
    }

    @Test
    fun `no-profile publisher marks a wake only after synchronous local execution completes`() {
        val (accountId, rowId) = enqueueWake("local-success")
        var completed = false
        Mockito.doAnswer {
            assertNull(query.findById(rowId)?.publishedAt)
            completed = true
            null
        }.`when`(localExecutor).execute(accountId, "CRASH_RECOVERY")

        publisher.publishBatch()

        assertTrue(completed)
        assertNotNull(query.findById(rowId)?.publishedAt)
        Mockito.verify(localExecutor).execute(accountId, "CRASH_RECOVERY")
        Mockito.verifyNoInteractions(wakeups)
    }

    @Test
    fun `failed local execution leaves wake unpublished and next poll retries it once`() {
        val (accountId, rowId) = enqueueWake("local-retry")
        Mockito.doThrow(IllegalStateException("runner crashed"))
            .doAnswer { null }
            .`when`(localExecutor).execute(accountId, "CRASH_RECOVERY")

        assertFailsWith<IllegalStateException> { publisher.publishBatch() }
        assertNull(query.findById(rowId)?.publishedAt)

        publisher.publishBatch()

        assertNotNull(query.findById(rowId)?.publishedAt)
        Mockito.verify(localExecutor, Mockito.times(2)).execute(accountId, "CRASH_RECOVERY")
        Mockito.verifyNoInteractions(wakeups)
    }

    private fun enqueueWake(loginId: String): Pair<Long, Long> {
        val accountId = TransactionTemplate(transactionManager).execute {
            val account = accounts.save(HofAccountEntity(loginId = loginId, encryptedPassword = "encrypted", createdAt = NOW))
            entries.save(AutomationEntryEntity(account = account, type = AutomationType.QUEST, priority = 0, enabled = true, createdAt = NOW, updatedAt = NOW))
            account.id
        }
        TransactionTemplate(transactionManager).execute { lifecycle.start(accountId, "CRASH_RECOVERY") }
        val rowId = query.findUnpublished(NOW.plusSeconds(1)).single { it.account.id == accountId }.id
        return accountId to rowId
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Config {
        @Bean fun objectMapper(): ObjectMapper = jacksonObjectMapper()
        @Bean fun timeProvider(): TimeProvider = TimeProvider { NOW }
        @Bean fun localExecutor(): LocalAutomationWakeExecutor = Mockito.mock(LocalAutomationWakeExecutor::class.java)
        @Bean fun wakeups(): AutomationWakeupPort = Mockito.mock(AutomationWakeupPort::class.java)
        @Bean
        fun executionAuthorization(): AccountExecutionAuthorizationReader =
            Mockito.mock(AccountExecutionAuthorizationReader::class.java).also { reader ->
                Mockito.`when`(reader.isExecutionAllowed(Mockito.anyLong())).thenReturn(true)
            }
    }

    private companion object { val NOW: Instant = Instant.parse("2026-07-16T00:00:00Z") }
}
