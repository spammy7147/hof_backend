package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.entity.HofCookieEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.account.repository.HofCookieRepository
import app.spammy.hof.account.service.HofCookieCipher
import app.spammy.hof.account.service.HofAccountService
import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.automation.repository.AdventureDailyPreflightQueryRepository
import app.spammy.hof.battle.repository.BattleMapQueryRepository
import app.spammy.hof.battle.service.BattleMapCatalogService
import app.spammy.hof.battle.service.BattleMapCatalogTransactionService
import app.spammy.hof.battle.service.BattleMapIdentityResolver
import app.spammy.hof.battle.service.BattleMapService
import app.spammy.hof.common.persistence.QueryDslConfig
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.captcha.service.CaptchaService
import app.spammy.hof.external.client.HofGateway
import app.spammy.hof.external.client.AccountHofGateway
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.external.parser.BattleMapParser
import app.spammy.hof.external.parser.LoginStateParser
import app.spammy.hof.status.service.HofStatusSnapshotService
import jakarta.persistence.EntityManager
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.mockito.Mockito
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
    CookieQueryRepository::class,
    BattleMapQueryRepository::class,
    BattleMapIdentityResolver::class,
    BattleMapCatalogService::class,
    BattleMapCatalogTransactionService::class,
    BattleMapService::class,
    BattleMapParser::class,
    HofRequestFactory::class,
    AdventureDailyPreflightQueryRepository::class,
    AutomationDailyPreflight::class,
    AutomationDailyPreflightMapSyncTest.Config::class,
)
class AutomationDailyPreflightMapSyncTest {
    @Autowired private lateinit var service: AutomationDailyPreflight
    @Autowired private lateinit var gateway: BlockingAdventureGateway
    @Autowired private lateinit var timeProvider: MutableTimeProvider
    @Autowired private lateinit var accountRepository: HofAccountRepository
    @Autowired private lateinit var cookieRepository: HofCookieRepository
    @Autowired private lateinit var mapQueryRepository: BattleMapQueryRepository
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var entityManager: EntityManager

    @BeforeTest
    fun reset() {
        gateway.reset()
        timeProvider.current.set(NOW)
    }

    @Test
    fun `active snapshot synchronizes map state before writing daily marker`() {
        val accountId = savedAccount("preflight-real-sync")

        assertIs<AutomationDailyPreflight.Result.Ready>(service.ensureReady(accountId))

        assertEquals(listOf("replacement01"), visibleCodes(accountId))
        assertEquals(1L, markerCount(accountId))
    }

    @Test
    fun `expired stale snapshot is discarded without overwriting replacement map state`() {
        val accountId = savedAccount("preflight-real-stale-sync")
        gateway.blockFirst = true
        val executor = Executors.newSingleThreadExecutor()

        try {
            val stale = executor.submit<AutomationDailyPreflight.Result> { service.ensureReady(accountId) }
            check(gateway.firstStarted.await(10, TimeUnit.SECONDS))
            timeProvider.current.set(NOW.plusSeconds(46))

            assertIs<AutomationDailyPreflight.Result.Ready>(service.ensureReady(accountId))
            assertEquals(listOf("replacement01"), visibleCodes(accountId))
            assertEquals(1L, markerCount(accountId))

            gateway.releaseFirst.countDown()
            assertIs<AutomationDailyPreflight.Result.Ready>(stale.get(10, TimeUnit.SECONDS))
            assertEquals(listOf("replacement01"), visibleCodes(accountId))
            assertEquals(1L, markerCount(accountId))
        } finally {
            gateway.releaseFirst.countDown()
            executor.shutdownNow()
        }
    }

    private fun savedAccount(loginId: String): Long = TransactionTemplate(transactionManager).execute {
        val account = accountRepository.save(
            HofAccountEntity(loginId = loginId, encryptedPassword = "encrypted", createdAt = NOW),
        )
        cookieRepository.save(HofCookieEntity(account = account, name = "PHPSESSID", value = "abc", updatedAt = NOW))
        cookieRepository.flush()
        account.id
    }

    private fun visibleCodes(accountId: Long): List<String> = TransactionTemplate(transactionManager).execute {
        mapQueryRepository.findVisibleStatesByAccountIdAndCategoryId(accountId, "adventure_map")
            .map { it.battleMap.mapCode }
    }

    private fun markerCount(accountId: Long): Long = TransactionTemplate(transactionManager).execute {
        (entityManager.createNativeQuery(
            "select count(*) from adventure_daily_refresh where account_id = :accountId",
        ).setParameter("accountId", accountId).singleResult as Number).toLong()
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Config {
        @Bean fun timeProvider(): MutableTimeProvider = MutableTimeProvider(NOW)
        @Bean fun gateway(): BlockingAdventureGateway = BlockingAdventureGateway()
        @Bean
        fun accountGateway(
            gateway: BlockingAdventureGateway,
            timeProvider: MutableTimeProvider,
        ): AccountHofGateway =
            AccountHofGateway(
                gateway,
                Mockito.mock(HofStatusSnapshotService::class.java),
                Mockito.mock(app.spammy.hof.character.service.CharacterRosterObservationService::class.java),
                timeProvider,
            )
        @Bean fun loginStateParser(): LoginStateParser = LoginStateParser()
        @Bean fun captchaService(): CaptchaService = Mockito.mock(CaptchaService::class.java)
        @Bean fun hofAccountService(): HofAccountService = Mockito.mock(HofAccountService::class.java)
        @Bean
        fun sessionRecovery(hofAccountService: HofAccountService): HofSessionRecoveryExecutor =
            HofSessionRecoveryExecutor(HofSessionRecoveryService(hofAccountService))
        @Bean fun cipher(): HofCookieCipher =
            HofCookieCipher(Base64.getEncoder().encodeToString(ByteArray(32) { 11 }))
    }

    class MutableTimeProvider(initial: Instant) : TimeProvider {
        val current = AtomicReference(initial)
        override fun now(): Instant = current.get()
    }

    class BlockingAdventureGateway : HofGateway {
        val calls = AtomicInteger()
        var blockFirst: Boolean = false
        var firstStarted = CountDownLatch(1)
        var releaseFirst = CountDownLatch(1)

        override fun execute(
            accountId: Long,
            request: HofRequest,
            cookies: Map<String, String>,
        ): HofHttpResponse {
            val call = calls.incrementAndGet()
            if (blockFirst && call == 1) {
                firstStarted.countDown()
                check(releaseFirst.await(10, TimeUnit.SECONDS))
            }
            val code = if (call == 1 && blockFirst) "stale01" else "replacement01"
            return HofHttpResponse(
                statusCode = 200,
                finalUrl = request.url,
                body = """
                    <div>공유 지역 (1)</div>
                    <div id="mapgroup1"><a href="index.php?sp_common=$code">Shared- $code</a> 1 가능</div>
                """.trimIndent(),
                setCookies = emptyMap(),
            )
        }

        fun reset() {
            calls.set(0)
            blockFirst = false
            firstStarted = CountDownLatch(1)
            releaseFirst = CountDownLatch(1)
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-14T15:00:01Z")
    }
}
