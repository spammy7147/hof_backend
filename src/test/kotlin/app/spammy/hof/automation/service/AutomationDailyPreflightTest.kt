package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.automation.repository.AdventureDailyPreflightQueryRepository
import app.spammy.hof.battle.service.AdventureMapRefreshException
import app.spammy.hof.battle.service.AdventureMapSnapshot
import app.spammy.hof.battle.service.BattleMapService
import app.spammy.hof.battle.service.BattleMapCatalogService
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
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
    @Autowired private lateinit var accountQueryRepository: AccountQueryRepository
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var entityManager: EntityManager
    @Autowired private lateinit var timeProvider: MutableTimeProvider

    @BeforeTest
    fun reset() {
        Mockito.reset(battleMapService)
        Mockito.doAnswer { invocation ->
            val accountId = invocation.getArgument<Long>(0)
            battleMapService.refreshAdventureMaps(accountId)
            AdventureMapSnapshot(accountId, emptyList())
        }.`when`(battleMapService).fetchAdventureMapSnapshot(anyLong())
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
    fun `retry backoff starts from failure completion time`() {
        val accountId = savedAccount("preflight-completion-backoff")
        val completedAt = KOREA_MIDNIGHT_AFTER.plusSeconds(120)
        Mockito.doAnswer {
            timeProvider.current.set(completedAt)
            throw networkFailure()
        }.`when`(battleMapService).refreshAdventureMaps(accountId)

        assertRetry(service.ensureReady(accountId), 1, completedAt.plusSeconds(10))
    }

    @Test
    fun `retry backoff remains based on completion while finalize waits for account lock`() {
        val accountId = savedAccount("preflight-finalize-lock-backoff")
        val requestStarted = CountDownLatch(1)
        val releaseRequest = CountDownLatch(1)
        val finalizeLockHeld = CountDownLatch(1)
        val releaseFinalizeLock = CountDownLatch(1)
        val completionTimeRead = CountDownLatch(1)
        val completedAt = KOREA_MIDNIGHT_AFTER.plusSeconds(120)
        Mockito.doAnswer {
            requestStarted.countDown()
            check(releaseRequest.await(10, TimeUnit.SECONDS))
            timeProvider.current.set(completedAt)
            throw networkFailure()
        }.`when`(battleMapService).refreshAdventureMaps(accountId)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val preflight = executor.submit<AutomationDailyPreflight.Result> { service.ensureReady(accountId) }
            check(requestStarted.await(10, TimeUnit.SECONDS))
            val lockHolder = executor.submit {
                TransactionTemplate(transactionManager).executeWithoutResult {
                    accountQueryRepository.findByIdForUpdate(accountId)
                    finalizeLockHeld.countDown()
                    check(releaseFinalizeLock.await(10, TimeUnit.SECONDS))
                }
            }
            check(finalizeLockHeld.await(10, TimeUnit.SECONDS))
            timeProvider.nextReadSignal.set(completionTimeRead)
            releaseRequest.countDown()
            assertTrue(completionTimeRead.await(10, TimeUnit.SECONDS))
            timeProvider.current.set(completedAt.plusSeconds(100))
            releaseFinalizeLock.countDown()

            assertRetry(preflight.get(10, TimeUnit.SECONDS), 1, completedAt.plusSeconds(10))
            lockHolder.get(10, TimeUnit.SECONDS)
        } finally {
            releaseRequest.countDown()
            releaseFinalizeLock.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `request crossing Korea midnight does not mark either date and next call refreshes new day`() {
        val accountId = savedAccount("preflight-request-midnight")
        val beforeMidnight = Instant.parse("2026-07-14T14:59:59Z")
        val afterMidnight = Instant.parse("2026-07-14T15:00:01Z")
        timeProvider.current.set(beforeMidnight)
        Mockito.doAnswer {
            timeProvider.current.set(afterMidnight)
            emptyList<Any>()
        }.`when`(battleMapService).refreshAdventureMaps(accountId)

        val crossed = assertIs<AutomationDailyPreflight.Result.Busy>(service.ensureReady(accountId))
        assertEquals(afterMidnight, crossed.retryAt)
        assertEquals(emptyList(), refreshDates(accountId))

        assertIs<AutomationDailyPreflight.Result.Ready>(service.ensureReady(accountId))
        Mockito.verify(battleMapService, Mockito.times(2)).refreshAdventureMaps(accountId)
        assertEquals(listOf("2026-07-15"), refreshDates(accountId))
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
    fun `generic HOF request failure is fatal rather than assumed retryable`() {
        val accountId = savedAccount("preflight-generic-request-failure")
        Mockito.doThrow(ApiException(ErrorCode.HOF_REQUEST_FAILED, "unparseable response"))
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
    fun `fatal typed failure takes precedence over nested IO cause`() {
        val accountId = savedAccount("preflight-fatal-wrapped-io")
        Mockito.doThrow(AdventureMapRefreshException.Fatal("fatal parse", IOException("nested")))
            .`when`(battleMapService).refreshAdventureMaps(accountId)

        assertEquals(
            AutomationDailyPreflight.Result.Stopped(AutomationDailyPreflight.StopReason.FATAL),
            service.ensureReady(accountId),
        )
    }

    @Test
    fun `interruption restores interrupt flag and stops without network retry`() {
        val accountId = savedAccount("preflight-interrupted")
        Mockito.doAnswer { throw InterruptedException("cancelled") }
            .`when`(battleMapService).refreshAdventureMaps(accountId)

        try {
            assertEquals(
                AutomationDailyPreflight.Result.Stopped(AutomationDailyPreflight.StopReason.FATAL),
                service.ensureReady(accountId),
            )
            assertEquals(true, Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `refresh transaction rollback still persists fatal stop in separate finalize transaction`() {
        val accountId = savedAccount("preflight-refresh-rollback")
        Mockito.doAnswer {
            TransactionTemplate(transactionManager).executeWithoutResult {
                throw IllegalStateException("map sync persistence failed")
            }
        }.`when`(battleMapService).synchronizeAdventureMapSnapshot(AdventureMapSnapshot(accountId, emptyList()))

        assertEquals(
            AutomationDailyPreflight.Result.Stopped(AutomationDailyPreflight.StopReason.FATAL),
            service.ensureReady(accountId),
        )
        assertEquals(
            AutomationDailyPreflight.Result.Stopped(AutomationDailyPreflight.StopReason.FATAL),
            service.ensureReady(accountId),
        )
        Mockito.verify(battleMapService, Mockito.times(1)).fetchAdventureMapSnapshot(accountId)
    }

    @Test
    fun `claim time is captured after waiting for account lock across Korea midnight`() {
        val accountId = savedAccount("preflight-lock-midnight")
        timeProvider.current.set(Instant.parse("2026-07-14T14:59:59Z"))
        val lockHeld = CountDownLatch(1)
        val releaseLock = CountDownLatch(1)
        val workerStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val holder = executor.submit {
                TransactionTemplate(transactionManager).executeWithoutResult {
                    accountQueryRepository.findByIdForUpdate(accountId)
                    lockHeld.countDown()
                    check(releaseLock.await(10, TimeUnit.SECONDS))
                }
            }
            check(lockHeld.await(10, TimeUnit.SECONDS))
            val worker = executor.submit<AutomationDailyPreflight.Result> {
                workerStarted.countDown()
                service.ensureReady(accountId)
            }
            check(workerStarted.await(10, TimeUnit.SECONDS))
            assertFalse(worker.isDone)

            timeProvider.current.set(Instant.parse("2026-07-14T15:00:01Z"))
            releaseLock.countDown()

            assertIs<AutomationDailyPreflight.Result.Ready>(worker.get(10, TimeUnit.SECONDS))
            holder.get(10, TimeUnit.SECONDS)
            assertEquals(listOf("2026-07-15"), refreshDates(accountId))
        } finally {
            releaseLock.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `concurrent evaluation observes durable busy claim while first network call is in flight`() {
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
            val busy = assertIs<AutomationDailyPreflight.Result.Busy>(second.get(10, TimeUnit.SECONDS))
            assertEquals(KOREA_MIDNIGHT_AFTER.plusSeconds(45), busy.retryAt)
            assertFalse(first.isDone)
            Mockito.verify(battleMapService, Mockito.times(1)).refreshAdventureMaps(accountId)

            releaseRefresh.countDown()
            assertIs<AutomationDailyPreflight.Result.Ready>(first.get(10, TimeUnit.SECONDS))
            assertEquals(listOf("2026-07-15"), refreshDates(accountId))
        } finally {
            releaseRefresh.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `expired claim is reclaimed and stale worker cannot overwrite new success`() {
        val accountId = savedAccount("preflight-expired-claim")
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val calls = AtomicInteger()
        Mockito.doAnswer {
            if (calls.incrementAndGet() == 1) {
                firstStarted.countDown()
                check(releaseFirst.await(10, TimeUnit.SECONDS))
            }
            emptyList<Any>()
        }.`when`(battleMapService).refreshAdventureMaps(accountId)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val stale = executor.submit<AutomationDailyPreflight.Result> { service.ensureReady(accountId) }
            check(firstStarted.await(10, TimeUnit.SECONDS))
            timeProvider.current.set(KOREA_MIDNIGHT_AFTER.plusSeconds(46))

            assertIs<AutomationDailyPreflight.Result.Ready>(service.ensureReady(accountId))
            releaseFirst.countDown()
            assertIs<AutomationDailyPreflight.Result.Ready>(stale.get(10, TimeUnit.SECONDS))
            assertEquals(2, calls.get())
            assertEquals(listOf("2026-07-15"), refreshDates(accountId))
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `clean ambient transaction is rejected before claim or network`() {
        val accountId = savedAccount("preflight-clean-ambient")

        val error = assertFailsWith<IllegalStateException> {
            TransactionTemplate(transactionManager).executeWithoutResult {
                service.ensureReady(accountId)
            }
        }

        assertEquals(AutomationDailyPreflight.TRANSACTIONAL_CALLER_MESSAGE, error.message)
        Mockito.verify(battleMapService, Mockito.never()).fetchAdventureMapSnapshot(accountId)
        assertEquals(0L, preflightStateCount(accountId))
    }

    @Test
    fun `ambient transaction already holding account row is rejected immediately without mutation`() {
        val accountId = savedAccount("preflight-locked-ambient")

        TransactionTemplate(transactionManager).executeWithoutResult {
            accountQueryRepository.findByIdForUpdate(accountId)
            val error = assertFailsWith<IllegalStateException> { service.ensureReady(accountId) }
            assertEquals(AutomationDailyPreflight.TRANSACTIONAL_CALLER_MESSAGE, error.message)
        }

        Mockito.verify(battleMapService, Mockito.never()).fetchAdventureMapSnapshot(accountId)
        assertEquals(0L, preflightStateCount(accountId))
    }

    @Test
    fun `success finalize waits for global catalog fence before taking account lock`() {
        val accountId = savedAccount("preflight-lock-order")
        val fenceHeld = CountDownLatch(1)
        val releaseFence = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(3)

        try {
            val holder = executor.submit {
                BattleMapCatalogService.withSynchronizationFence {
                    fenceHeld.countDown()
                    check(releaseFence.await(10, TimeUnit.SECONDS))
                }
            }
            check(fenceHeld.await(10, TimeUnit.SECONDS))
            val preflight = executor.submit<AutomationDailyPreflight.Result> { service.ensureReady(accountId) }
            val opposingAccountLock = executor.submit {
                TransactionTemplate(transactionManager).executeWithoutResult {
                    accountQueryRepository.findByIdForUpdate(accountId)
                }
            }

            opposingAccountLock.get(3, TimeUnit.SECONDS)
            assertFalse(preflight.isDone)
            releaseFence.countDown()
            assertIs<AutomationDailyPreflight.Result.Ready>(preflight.get(10, TimeUnit.SECONDS))
            holder.get(10, TimeUnit.SECONDS)
        } finally {
            releaseFence.countDown()
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

    private fun preflightStateCount(accountId: Long): Long = TransactionTemplate(transactionManager).execute {
        (entityManager.createNativeQuery(
            "select count(*) from adventure_daily_preflight_states where account_id = :accountId",
        ).setParameter("accountId", accountId).singleResult as Number).toLong()
    }

    private fun preflightStopReason(accountId: Long): String? = TransactionTemplate(transactionManager).execute {
        entityManager.createNativeQuery(
            "select stop_reason from adventure_daily_preflight_states where account_id = :accountId",
        ).setParameter("accountId", accountId).singleResult as String?
    }

    private fun assertRetry(result: AutomationDailyPreflight.Result, retryAttempt: Int, next: Instant) {
        val scheduled = assertIs<AutomationDailyPreflight.Result.RetryScheduled>(result)
        assertEquals(retryAttempt, scheduled.retryAttempt)
        assertEquals(next, scheduled.nextAttemptAt)
    }

    private fun networkFailure() = AdventureMapRefreshException.Retryable("transient upstream failure")

    @TestConfiguration(proxyBeanMethods = false)
    class Config {
        @Bean fun timeProvider(): MutableTimeProvider = MutableTimeProvider(KOREA_MIDNIGHT_AFTER)
        @Bean fun battleMapService(): BattleMapService = Mockito.mock(BattleMapService::class.java)
    }

    class MutableTimeProvider(initial: Instant) : TimeProvider {
        val current = AtomicReference(initial)
        val nextReadSignal = AtomicReference<CountDownLatch?>()
        override fun now(): Instant = current.get().also { nextReadSignal.getAndSet(null)?.countDown() }
    }

    private companion object {
        val KOREA_MIDNIGHT_AFTER: Instant = Instant.parse("2026-07-14T15:00:01Z")
    }
}
