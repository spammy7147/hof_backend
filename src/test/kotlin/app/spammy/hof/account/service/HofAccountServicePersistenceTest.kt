package app.spammy.hof.account.service

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
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
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
    HofAccountIdentityService::class,
    HofAccountService::class,
    HofAccountServicePersistenceTest.BoundaryConfig::class,
)
class HofAccountServicePersistenceTest {
    @Autowired
    private lateinit var service: HofAccountService

    @Autowired
    private lateinit var accountQueryRepository: AccountQueryRepository

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
            "second-password",
            credentialCipher.decrypt(accountAfterRetry.encryptedPassword),
        )
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

        override fun execute(
            accountId: Long,
            request: HofRequest,
            cookies: Map<String, String>,
        ): HofHttpResponse = governor.execute(accountId, request.origin) {
            outboundAccountIds += accountId
            when {
                failWithServiceUnavailable -> response(request, 503)
                request.method == HofHttpMethod.GET -> response(
                    request,
                    200,
                    setCookies = mapOf("PHPSESSID" to "initial"),
                )
                else -> response(
                    request,
                    200,
                    body = """<a href="?char=1683198503393759">소셜</a>""",
                    setCookies = mapOf("NO" to "42"),
                )
            }
        }

        fun reset() {
            outboundAccountIds.clear()
            failWithServiceUnavailable = false
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
