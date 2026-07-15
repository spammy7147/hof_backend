package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.automation.repository.AdventureDailyPreflightQueryRepository
import app.spammy.hof.battle.service.BattleMapService
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.persistence.QueryDslConfig
import app.spammy.hof.common.time.TimeProvider
import jakarta.persistence.EntityManager
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
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
    AdventureDailyPreflightQueryRepository::class,
    AutomationDailyPreflight::class,
    AutomationDailyPreflightTest.Config::class,
)
class AutomationDailyPreflightTest {
    @Autowired private lateinit var service: AutomationDailyPreflight
    @Autowired private lateinit var battleMapService: BattleMapService
    @Autowired private lateinit var accountRepository: HofAccountRepository
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var entityManager: EntityManager
    @Autowired private lateinit var timeProvider: MutableTimeProvider

    @BeforeTest
    fun reset() {
        Mockito.reset(battleMapService)
        timeProvider.current.set(KOREA_MIDNIGHT_AFTER)
    }

    @Test
    fun `first evaluation after Korea midnight refreshes and same-date evaluation reuses success`() {
        val accountId = savedAccount("preflight-first")

        assertIs<AutomationDailyPreflight.Result.Ready>(service.ensureReady(accountId))
        assertIs<AutomationDailyPreflight.Result.Ready>(service.ensureReady(accountId))

        Mockito.verify(battleMapService, Mockito.times(1)).refreshAdventureMaps(accountId)
        assertEquals(listOf("2026-07-15"), refreshDates(accountId))
    }

    @Test
    fun `Korea date changes at fifteen hundred UTC`() {
        val accountId = savedAccount("preflight-boundary")
        timeProvider.current.set(Instant.parse("2026-07-14T14:59:59Z"))
        assertIs<AutomationDailyPreflight.Result.Ready>(service.ensureReady(accountId))

        timeProvider.current.set(Instant.parse("2026-07-14T15:00:01Z"))
        assertIs<AutomationDailyPreflight.Result.Ready>(service.ensureReady(accountId))

        Mockito.verify(battleMapService, Mockito.times(2)).refreshAdventureMaps(accountId)
        assertEquals(listOf("2026-07-14", "2026-07-15"), refreshDates(accountId))
    }

    @Test
    fun `network failure schedules exact retries without persisting success or calling early`() {
        val accountId = savedAccount("preflight-retries")
        Mockito.doThrow(networkFailure()).`when`(battleMapService).refreshAdventureMaps(accountId)

        assertRetry(service.ensureReady(accountId), 1, KOREA_MIDNIGHT_AFTER.plusSeconds(10))
        assertEquals(emptyList(), refreshDates(accountId))

        timeProvider.current.set(KOREA_MIDNIGHT_AFTER.plusSeconds(9))
        assertRetry(service.ensureReady(accountId), 1, KOREA_MIDNIGHT_AFTER.plusSeconds(10))
        Mockito.verify(battleMapService, Mockito.times(1)).refreshAdventureMaps(accountId)

        timeProvider.current.set(KOREA_MIDNIGHT_AFTER.plusSeconds(10))
        assertRetry(service.ensureReady(accountId), 2, KOREA_MIDNIGHT_AFTER.plusSeconds(40))
        timeProvider.current.set(KOREA_MIDNIGHT_AFTER.plusSeconds(40))
        assertRetry(service.ensureReady(accountId), 3, KOREA_MIDNIGHT_AFTER.plusSeconds(100))
        Mockito.verify(battleMapService, Mockito.times(3)).refreshAdventureMaps(accountId)
    }

    @Test
    fun `raw gateway IO failure is classified as retryable network failure`() {
        val accountId = savedAccount("preflight-io-failure")
        Mockito.doAnswer { throw IOException("connection reset") }
            .`when`(battleMapService).refreshAdventureMaps(accountId)

        assertRetry(service.ensureReady(accountId), 1, KOREA_MIDNIGHT_AFTER.plusSeconds(10))
        assertEquals(emptyList(), refreshDates(accountId))
    }

    @Test
    fun `four network failures stop permanently until explicit resume`() {
        val accountId = savedAccount("preflight-stop")
        Mockito.doThrow(networkFailure()).`when`(battleMapService).refreshAdventureMaps(accountId)

        assertIs<AutomationDailyPreflight.Result.RetryScheduled>(service.ensureReady(accountId))
        timeProvider.current.set(KOREA_MIDNIGHT_AFTER.plusSeconds(10))
        assertIs<AutomationDailyPreflight.Result.RetryScheduled>(service.ensureReady(accountId))
        timeProvider.current.set(KOREA_MIDNIGHT_AFTER.plusSeconds(40))
        assertIs<AutomationDailyPreflight.Result.RetryScheduled>(service.ensureReady(accountId))
        timeProvider.current.set(KOREA_MIDNIGHT_AFTER.plusSeconds(100))
        assertEquals(
            AutomationDailyPreflight.Result.Stopped(AutomationDailyPreflight.StopReason.NETWORK),
            service.ensureReady(accountId),
        )

        timeProvider.current.set(KOREA_MIDNIGHT_AFTER.plusSeconds(3_600))
        assertEquals(
            AutomationDailyPreflight.Result.Stopped(AutomationDailyPreflight.StopReason.NETWORK),
            service.ensureReady(accountId),
        )
        Mockito.verify(battleMapService, Mockito.times(4)).refreshAdventureMaps(accountId)

        service.resume(accountId)
        assertIs<AutomationDailyPreflight.Result.RetryScheduled>(service.ensureReady(accountId))
        Mockito.verify(battleMapService, Mockito.times(5)).refreshAdventureMaps(accountId)
    }

    @Test
    fun `fatal failure stops without retrying`() {
        val accountId = savedAccount("preflight-fatal")
        Mockito.doThrow(ApiException(ErrorCode.HOF_SESSION_EXPIRED, "expired"))
            .`when`(battleMapService).refreshAdventureMaps(accountId)

        assertEquals(
            AutomationDailyPreflight.Result.Stopped(AutomationDailyPreflight.StopReason.FATAL),
            service.ensureReady(accountId),
        )
        timeProvider.current.set(KOREA_MIDNIGHT_AFTER.plusSeconds(600))
        assertEquals(
            AutomationDailyPreflight.Result.Stopped(AutomationDailyPreflight.StopReason.FATAL),
            service.ensureReady(accountId),
        )
        Mockito.verify(battleMapService, Mockito.times(1)).refreshAdventureMaps(accountId)
    }

    @Test
    fun `concurrent evaluations serialize by account and refresh only once`() {
        val accountId = savedAccount("preflight-concurrent")
        val refreshStarted = CountDownLatch(1)
        val releaseRefresh = CountDownLatch(1)
        Mockito.doAnswer {
            refreshStarted.countDown()
            check(releaseRefresh.await(10, TimeUnit.SECONDS))
            emptyList<Any>()
        }.`when`(battleMapService).refreshAdventureMaps(accountId)
        val executor = Executors.newFixedThreadPool(2)
        val secondStarted = CountDownLatch(1)

        try {
            val first = executor.submit<AutomationDailyPreflight.Result> { service.ensureReady(accountId) }
            check(refreshStarted.await(10, TimeUnit.SECONDS))
            val second = executor.submit<AutomationDailyPreflight.Result> {
                secondStarted.countDown()
                service.ensureReady(accountId)
            }
            check(secondStarted.await(10, TimeUnit.SECONDS))
            releaseRefresh.countDown()

            assertIs<AutomationDailyPreflight.Result.Ready>(first.get(10, TimeUnit.SECONDS))
            assertIs<AutomationDailyPreflight.Result.Ready>(second.get(10, TimeUnit.SECONDS))
            Mockito.verify(battleMapService, Mockito.times(1)).refreshAdventureMaps(accountId)
            assertEquals(listOf("2026-07-15"), refreshDates(accountId))
        } finally {
            releaseRefresh.countDown()
            executor.shutdownNow()
        }
    }

    private fun savedAccount(loginId: String): Long = TransactionTemplate(transactionManager).execute {
        accountRepository.save(
            HofAccountEntity(loginId = loginId, encryptedPassword = "encrypted", createdAt = KOREA_MIDNIGHT_AFTER),
        ).also { accountRepository.flush() }.id
    }

    private fun refreshDates(accountId: Long): List<String> = TransactionTemplate(transactionManager).execute {
        entityManager.createNativeQuery(
            "select refresh_date from adventure_daily_refresh where account_id = :accountId order by refresh_date",
        ).setParameter("accountId", accountId).resultList.map { it.toString() }
    }

    private fun assertRetry(result: AutomationDailyPreflight.Result, retryAttempt: Int, next: Instant) {
        val scheduled = assertIs<AutomationDailyPreflight.Result.RetryScheduled>(result)
        assertEquals(retryAttempt, scheduled.retryAttempt)
        assertEquals(next, scheduled.nextAttemptAt)
    }

    private fun networkFailure() = ApiException(ErrorCode.HOF_REQUEST_FAILED, "network")

    @TestConfiguration(proxyBeanMethods = false)
    class Config {
        @Bean fun timeProvider(): MutableTimeProvider = MutableTimeProvider(KOREA_MIDNIGHT_AFTER)
        @Bean fun battleMapService(): BattleMapService = Mockito.mock(BattleMapService::class.java)
    }

    class MutableTimeProvider(initial: Instant) : TimeProvider {
        val current = AtomicReference(initial)
        override fun now(): Instant = current.get()
    }

    private companion object {
        val KOREA_MIDNIGHT_AFTER: Instant = Instant.parse("2026-07-14T15:00:01Z")
    }
}
