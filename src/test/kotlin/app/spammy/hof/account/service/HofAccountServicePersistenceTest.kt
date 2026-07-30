package app.spammy.hof.account.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.persistence.QueryDslConfig
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.client.AccountHofGateway
import app.spammy.hof.external.client.HofGateway
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.client.HofRequestGovernor
import app.spammy.hof.external.client.HofRequestWaiter
import app.spammy.hof.external.config.HofRequestProperties
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.external.parser.LoginStateParser
import jakarta.persistence.EntityManager
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@DataJpaTest
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
    QueryDslConfig::class,
    AccountQueryRepository::class,
    CookieQueryRepository::class,
    HofRequestFactory::class,
    LoginStateParser::class,
    HofAccountIdentityCreator::class,
    HofAccountIdentityService::class,
    HofAccountService::class,
    HofAccountServicePersistenceTest.BoundaryConfig::class,
)
class HofAccountServicePersistenceTest {
    @Autowired
    private lateinit var service: HofAccountService

    @MockitoSpyBean
    private lateinit var accountQueryRepository: AccountQueryRepository

    @Autowired
    private lateinit var accountIdentityService: HofAccountIdentityService

    @MockitoSpyBean
    private lateinit var accountIdentityCreator: HofAccountIdentityCreator

    @Autowired
    private lateinit var cookieQueryRepository: CookieQueryRepository

    @Autowired
    private lateinit var gateway: GovernorGateway

    @Autowired
    private lateinit var credentialCipher: CredentialCipher

    @Autowired
    private lateinit var cookieCipher: HofCookieCipher

    @Autowired
    private lateinit var entityManager: EntityManager

    @BeforeTest
    fun resetGateway() {
        gateway.reset()
    }

    @Test
    fun firstLogin503KeepsIdentityAndImmediateRetryUsesItsCooldown() {
        gateway.failWithServiceUnavailable = true

        val firstFailure = assertFailsWith<ApiException> {
            service.authenticate("first-cooldown-user", "first-password")
        }
        assertEquals(ErrorCode.HOF_TEMPORARILY_UNAVAILABLE, firstFailure.errorCode)
        val durableIdentity = assertNotNull(accountQueryRepository.findByLoginId("first-cooldown-user"))

        val retryFailure = assertFailsWith<ApiException> {
            service.authenticate("first-cooldown-user", "second-password")
        }

        assertEquals(ErrorCode.HOF_TEMPORARILY_UNAVAILABLE, retryFailure.errorCode)
        assertEquals(listOf(durableIdentity.id), gateway.outboundAccountIds)
        val accountAfterRetry = assertNotNull(accountQueryRepository.findByLoginId("first-cooldown-user"))
        assertEquals(durableIdentity.id, accountAfterRetry.id)
        assertEquals(1L, countAccounts("first-cooldown-user"))
        assertEquals(
            "first-password",
            credentialCipher.decrypt(accountAfterRetry.encryptedPassword),
        )
    }

    @Test
    fun failedLoginPreservesExistingValidCredential() {
        service.authenticate("existing-failed-user", "valid-password")
        gateway.reset()
        gateway.rejectLogin = true

        val failure = assertFailsWith<ApiException> {
            service.authenticate("existing-failed-user", "wrong-password")
        }

        assertEquals(ErrorCode.HOF_LOGIN_FAILED, failure.errorCode)
        val stored = assertNotNull(accountQueryRepository.findByLoginId("existing-failed-user"))
        assertEquals("valid-password", credentialCipher.decrypt(stored.encryptedPassword))
    }

    @Test
    fun serviceUnavailablePreservesExistingValidCredential() {
        service.authenticate("existing-503-user", "valid-password")
        gateway.reset()
        gateway.failWithServiceUnavailable = true

        val failure = assertFailsWith<ApiException> {
            service.authenticate("existing-503-user", "wrong-password")
        }
        val cooldownFailure = assertFailsWith<ApiException> {
            service.authenticate("existing-503-user", "another-wrong-password")
        }

        assertEquals(ErrorCode.HOF_TEMPORARILY_UNAVAILABLE, failure.errorCode)
        assertEquals(ErrorCode.HOF_TEMPORARILY_UNAVAILABLE, cooldownFailure.errorCode)
        val stored = assertNotNull(accountQueryRepository.findByLoginId("existing-503-user"))
        assertEquals(listOf(stored.id), gateway.outboundAccountIds)
        assertEquals("valid-password", credentialCipher.decrypt(stored.encryptedPassword))
    }

    @Test
    fun successfulLoginUpdatesExistingCredential() {
        service.authenticate("existing-success-user", "old-password")
        gateway.reset()

        service.authenticate("existing-success-user", "new-password")

        val stored = assertNotNull(accountQueryRepository.findByLoginId("existing-success-user"))
        assertEquals("new-password", credentialCipher.decrypt(stored.encryptedPassword))
    }

    @Test
    fun concurrentFirstIdentityResolutionReturnsOneDurableAccount() {
        val loginId = "concurrent-identity-user"
        val initialLookupCount = AtomicInteger()
        val bothObservedMissing = CountDownLatch(2)
        val releaseCreators = CountDownLatch(1)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        Mockito.doAnswer { invocation ->
            val found = invocation.callRealMethod() as HofAccountEntity?
            if (initialLookupCount.incrementAndGet() <= 2) {
                assertNull(found)
                bothObservedMissing.countDown()
                check(releaseCreators.await(5, TimeUnit.SECONDS))
            }
            found
        }.`when`(accountQueryRepository).findByLoginId(loginId)
        try {
            val futures = (1..2).map { index ->
                executor.submit<Long> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    accountIdentityService.resolve(loginId, "password-$index").id
                }
            }
            check(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            check(bothObservedMissing.await(5, TimeUnit.SECONDS))
            releaseCreators.countDown()

            val ids = futures.map { future -> future.get(10, TimeUnit.SECONDS) }

            assertEquals(1, ids.toSet().size)
            assertEquals(1L, countAccounts(loginId))
            Mockito.verify(accountIdentityCreator).create(loginId, "password-1")
            Mockito.verify(accountIdentityCreator).create(loginId, "password-2")
        } finally {
            releaseCreators.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun concurrentSuccessfulLoginsPersistOneConsistentCredentialAndCookieSet() {
        service.authenticate("concurrent-login-user", "initial-password")
        gateway.reset()
        gateway.concurrentLoginBarrier = CountDownLatch(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf("password-a", "password-b").map { password ->
                executor.submit<Unit> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    service.authenticate("concurrent-login-user", password)
                }
            }
            check(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            futures.forEach { future -> future.get(10, TimeUnit.SECONDS) }

            val stored = assertNotNull(accountQueryRepository.findByLoginId("concurrent-login-user"))
            val storedPassword = credentialCipher.decrypt(stored.encryptedPassword)
            val cookies = cookieQueryRepository.findByAccountId(stored.id)
                .associate { cookie -> cookie.name to cookieCipher.decrypt(cookie.value) }
            assertEquals(storedPassword, cookies["NO"])
            assertEquals(setOf("NO", "PHPSESSID"), cookies.keys)
            assertEquals(2, cookies.size)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun firstSuccessfulLoginStoresCookiesAndLastLogin() {
        val authenticated = service.authenticate("first-success-user", "password")

        val stored = assertNotNull(accountQueryRepository.findByLoginId("first-success-user"))
        val cookies = cookieQueryRepository.findByAccountId(stored.id)
        assertEquals(authenticated.id, stored.id)
        assertEquals(NOW, stored.lastLoginAt)
        assertEquals(setOf("NO", "PHPSESSID"), cookies.map { cookie -> cookie.name }.toSet())
        assertEquals(
            setOf("42", "initial"),
            cookies.map { cookie -> cookieCipher.decrypt(cookie.value) }.toSet(),
        )
    }

    private fun countAccounts(loginId: String): Long =
        (entityManager.createNativeQuery("select count(*) from hof_accounts where login_id = :loginId")
            .setParameter("loginId", loginId)
            .singleResult as Number).toLong()

    @TestConfiguration
    class BoundaryConfig {
        @Bean
        fun timeProvider(): TimeProvider = TimeProvider { NOW }

        @Bean
        fun credentialCipher(): CredentialCipher = AesGcmCredentialCipher(
            Base64.getEncoder().encodeToString(ByteArray(32) { index -> (index + 1).toByte() }),
        )

        @Bean
        fun cookieCipher(): HofCookieCipher = HofCookieCipher(
            Base64.getEncoder().encodeToString(ByteArray(32) { index -> (index + 41).toByte() }),
        )

        @Bean
        fun gateway(timeProvider: TimeProvider): GovernorGateway = GovernorGateway(timeProvider)

        @Bean
        fun accountGateway(): AccountHofGateway = Mockito.mock(AccountHofGateway::class.java)
    }

    class GovernorGateway(timeProvider: TimeProvider) : HofGateway {
        private val governor = HofRequestGovernor(
            properties = HofRequestProperties(minimumInterval = Duration.ZERO),
            timeProvider = timeProvider,
            waiter = HofRequestWaiter { },
        )
        val outboundAccountIds = CopyOnWriteArrayList<Long>()
        @Volatile
        var failWithServiceUnavailable: Boolean = false
        @Volatile
        var rejectLogin: Boolean = false
        @Volatile
        var concurrentLoginBarrier: CountDownLatch? = null

        override fun execute(
            accountId: Long,
            request: HofRequest,
            cookies: Map<String, String>,
        ): HofHttpResponse {
            val barrier = concurrentLoginBarrier
            val outbound = {
                outboundAccountIds += accountId
                when {
                    failWithServiceUnavailable -> response(request, 503)
                    request.method == HofHttpMethod.GET -> response(
                        request,
                        200,
                        setCookies = mapOf("PHPSESSID" to "initial"),
                    )
                    rejectLogin -> response(
                        request,
                        200,
                        body = """<form><input name="id"><input name="pass"></form>""",
                    )
                    else -> response(
                        request,
                        200,
                        body = """<a href="?char=1683198503393759">소셜</a>""",
                        setCookies = mapOf(
                            "NO" to if (barrier == null) "42" else requireNotNull(request.formFields["pass"]),
                        ),
                    )
                }
            }
            if (barrier != null) {
                if (request.method == HofHttpMethod.POST) {
                    barrier.countDown()
                    check(barrier.await(5, TimeUnit.SECONDS))
                }
                return outbound()
            }
            return governor.execute(accountId, request.origin, outbound)
        }

        fun reset() {
            outboundAccountIds.clear()
            failWithServiceUnavailable = false
            rejectLogin = false
            concurrentLoginBarrier = null
        }

        private fun response(
            request: HofRequest,
            statusCode: Int,
            body: String = "<html></html>",
            setCookies: Map<String, String> = emptyMap(),
        ): HofHttpResponse = HofHttpResponse(statusCode, request.url, body, setCookies)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-30T02:00:00Z")
    }
}
