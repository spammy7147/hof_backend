package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.automation.repository.UnifiedAutomationQueryRepository
import app.spammy.hof.battle.repository.BattleMapQueryRepository
import app.spammy.hof.common.persistence.QueryDslConfig
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.party.repository.PartyPresetQueryRepository
import jakarta.persistence.EntityManager
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

@DataJpaTest
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
    QueryDslConfig::class,
    AccountQueryRepository::class,
    UnifiedAutomationQueryRepository::class,
    BattleMapQueryRepository::class,
    PartyPresetQueryRepository::class,
    AutomationModuleReadinessEvaluator::class,
    UnifiedAutomationService::class,
    UnifiedAutomationProfileConcurrencyTest.Config::class,
)
class UnifiedAutomationProfileConcurrencyTest {
    @Autowired private lateinit var accountRepository: HofAccountRepository
    @Autowired private lateinit var service: UnifiedAutomationService
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var entityManager: EntityManager

    @Test
    fun concurrentFirstGetsCreateOnlyOneUnifiedProfile() {
        val accountId = TransactionTemplate(transactionManager).execute {
            accountRepository.save(
                HofAccountEntity(
                    loginId = "concurrent-unified-profile",
                    encryptedPassword = "encrypted",
                    createdAt = NOW,
                ),
            ).also { accountRepository.flush() }.id
        }
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val futures = List(2) {
                executor.submit<Long> {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    service.get(accountId).profileId
                }
            }
            check(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            val profileIds = futures.map { future -> future.get(10, TimeUnit.SECONDS) }
            val profileCount = TransactionTemplate(transactionManager).execute<Long> {
                entityManager.createQuery(
                    "select count(p) from AutomationProfileEntity p where p.account.id = :accountId and p.mode = :mode",
                )
                    .setParameter("accountId", accountId)
                    .setParameter("mode", "UNIFIED")
                    .singleResult
                    .let { it as Number }
                    .toLong()
            }

            assertEquals(1, profileIds.toSet().size)
            assertEquals(1L, profileCount)
        } finally {
            executor.shutdownNow()
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Config {
        @Bean
        fun timeProvider(): TimeProvider = TimeProvider { NOW }

        @Bean
        fun afterCommitWakeupService(): AutomationAfterCommitWakeupService =
            Mockito.mock(AutomationAfterCommitWakeupService::class.java)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-14T04:00:00Z")
    }
}
