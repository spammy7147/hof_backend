package app.spammy.hof.account.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.entity.HofCookieEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.account.repository.HofCookieRepository
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.client.HofGateway
import app.spammy.hof.external.client.AccountHofGateway
import app.spammy.hof.external.client.AccountHofResponseObserver
import app.spammy.hof.status.service.HofStatusSnapshotService
import app.spammy.hof.character.service.CharacterRosterObservationService
import app.spammy.hof.captcha.service.CaptchaPassMaintenanceService
import org.springframework.transaction.support.TransactionSynchronizationManager
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.external.parser.LoginStateParser
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import java.time.Instant
import java.util.Base64
import kotlin.test.assertEquals

class HofAccountServiceTest {
    private val now = Instant.parse("2026-07-07T00:00:00Z")
    private val credentialCipher = AesGcmCredentialCipher(
        Base64.getEncoder().encodeToString(ByteArray(32) { index -> (index + 1).toByte() }),
    )
    private val cookieCipher = HofCookieCipher(
        Base64.getEncoder().encodeToString(ByteArray(32) { index -> (index + 41).toByte() }),
    )
    private val accountRepository = Mockito.mock(HofAccountRepository::class.java)
    private val cookieRepository = Mockito.mock(HofCookieRepository::class.java)
    private val accountQueryRepository = Mockito.mock(AccountQueryRepository::class.java)
    private val cookieQueryRepository = Mockito.mock(CookieQueryRepository::class.java)
    private val accountIdentityCreator = HofAccountIdentityCreator(
        accountRepository = accountRepository,
        credentialCipher = credentialCipher,
        timeProvider = TimeProvider { now },
    )
    private val accountIdentityService = HofAccountIdentityService(
        accountQueryRepository = accountQueryRepository,
        accountIdentityCreator = accountIdentityCreator,
    )
    private val gateway = FakeHofGateway()
    private val accountGateway = Mockito.mock(AccountHofGateway::class.java)
    private val service = HofAccountService(
        accountRepository = accountRepository,
        cookieRepository = cookieRepository,
        accountQueryRepository = accountQueryRepository,
        cookieQueryRepository = cookieQueryRepository,
        accountIdentityService = accountIdentityService,
        credentialCipher = credentialCipher,
        cookieCipher = cookieCipher,
        requestFactory = HofRequestFactory(),
        gateway = gateway,
        accountGateway = accountGateway,
        loginStateParser = LoginStateParser(),
        timeProvider = TimeProvider { now },
    )

    @Test
    fun authenticateSendsHofRequestsAndStoresCookies() {
        val account = HofAccountEntity(
            id = 1L,
            loginId = "abcd12",
            encryptedPassword = credentialCipher.encrypt("qwer12"),
            createdAt = now,
        )
        val existingCookie = HofCookieEntity(
            id = 9L,
            account = account,
            name = "OLD_SESSION",
            value = "old-value",
            updatedAt = now,
        )
        Mockito.`when`(accountQueryRepository.findByLoginId("abcd12")).thenReturn(account)
        Mockito.`when`(accountQueryRepository.findByIdForUpdate(1L)).thenReturn(account)
        Mockito.`when`(cookieQueryRepository.findByAccountId(1L)).thenReturn(listOf(existingCookie))
        Mockito.`when`(accountRepository.save(anyAccount()))
            .thenAnswer { invocation -> invocation.arguments[0] }
        Mockito.`when`(cookieRepository.save(anyCookie()))
            .thenAnswer { invocation -> invocation.arguments[0] }

        val response = service.authenticate("abcd12", "qwer12")

        assertEquals(1L, response.id)
        assertEquals(listOf(HofHttpMethod.GET, HofHttpMethod.POST), gateway.requests.map { it.method })
        assertEquals(listOf(1L, 1L), gateway.accountIds)

        val cookieCaptor = ArgumentCaptor.forClass(HofCookieEntity::class.java)
        Mockito.verify(cookieRepository).deleteAll(listOf(existingCookie))
        Mockito.verify(cookieRepository).flush()
        Mockito.verify(cookieRepository, Mockito.times(2)).save(capture(cookieCaptor, existingCookie))
        assertEquals(setOf("PHPSESSID", "NO"), cookieCaptor.allValues.map { it.name }.toSet())
        assertEquals(
            setOf("initial", "42"),
            cookieCaptor.allValues.map { cookie -> cookieCipher.decrypt(cookie.value) }.toSet(),
        )
        assertEquals(now, account.lastLoginAt)
    }

    @Test
    fun authenticateCreatesAccountAndLogsIn() {
        val savedAccount = HofAccountEntity(
            id = 42L,
            loginId = "abcd12",
            encryptedPassword = credentialCipher.encrypt("qwer12"),
            createdAt = now,
        )
        Mockito.`when`(accountQueryRepository.findByLoginId("abcd12")).thenReturn(null)
        Mockito.`when`(accountRepository.save(anyAccount()))
            .thenAnswer { invocation ->
                val account = invocation.arguments[0] as HofAccountEntity
                if (account.id == 0L) savedAccount else account
            }
        Mockito.`when`(accountQueryRepository.findByIdForUpdate(42L)).thenReturn(savedAccount)
        Mockito.`when`(cookieRepository.save(anyCookie()))
            .thenAnswer { invocation -> invocation.arguments[0] }

        val response = service.authenticate("  abcd12  ", "qwer12")

        assertEquals(42L, response.id)
        assertEquals(listOf(HofHttpMethod.GET, HofHttpMethod.POST), gateway.requests.map { it.method })
        assertEquals(listOf(42L, 42L), gateway.accountIds)
        assertEquals("abcd12", gateway.requests.last().formFields["id"])
        assertEquals("qwer12", gateway.requests.last().formFields["pass"])
    }

    @Test
    fun authenticateUsesSubmittedPasswordAndStoresItAfterLoginSuccess() {
        val account = HofAccountEntity(
            id = 7L,
            loginId = "abcd12",
            encryptedPassword = credentialCipher.encrypt("old-password"),
            createdAt = now,
        )
        Mockito.`when`(accountQueryRepository.findByLoginId("abcd12")).thenReturn(account)
        Mockito.`when`(accountQueryRepository.findByIdForUpdate(7L)).thenReturn(account)
        Mockito.`when`(accountRepository.save(anyAccount()))
            .thenAnswer { invocation -> invocation.arguments[0] }
        Mockito.`when`(cookieRepository.save(anyCookie()))
            .thenAnswer { invocation -> invocation.arguments[0] }

        val response = service.authenticate("abcd12", "new-password")

        assertEquals(7L, response.id)
        assertEquals("new-password", credentialCipher.decrypt(account.encryptedPassword))
        assertEquals("new-password", gateway.requests.last().formFields["pass"])
    }

    @Test
    fun reauthenticateSendsBothLoginRequestsWithTheAccountId() {
        val account = HofAccountEntity(
            id = 23L,
            loginId = "abcd12",
            encryptedPassword = credentialCipher.encrypt("qwer12"),
            createdAt = now,
        )
        Mockito.`when`(accountQueryRepository.findById(23L)).thenReturn(account)
        Mockito.`when`(accountQueryRepository.findByIdForUpdate(23L)).thenReturn(account)
        Mockito.`when`(cookieQueryRepository.findByAccountId(23L)).thenReturn(emptyList())
        Mockito.`when`(accountRepository.save(anyAccount()))
            .thenAnswer { invocation -> invocation.arguments[0] }
        Mockito.`when`(cookieRepository.save(anyCookie()))
            .thenAnswer { invocation -> invocation.arguments[0] }

        val response = service.reauthenticate(23L)

        assertEquals(23L, response.id)
        assertEquals(listOf(HofHttpMethod.GET, HofHttpMethod.POST), gateway.requests.map { it.method })
        assertEquals(listOf(23L, 23L), gateway.accountIds)
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource("false,-1", "false,0", "false,1", "true,-1", "true,0", "true,1")
    fun `로그인과 재인증 관측은 지연된 commit rollback에서도 원래 응답 시각을 전달한다`(reauthenticate: Boolean, completion: Int) {
        val responseAt = now.plusSeconds(2)
        var currentTime = now
        val clock = TimeProvider { currentTime }
        val snapshots = Mockito.mock(HofStatusSnapshotService::class.java)
        val rosters = Mockito.mock(CharacterRosterObservationService::class.java)
        val pass = Mockito.mock(CaptchaPassMaintenanceService::class.java)
        val observingGateway = AccountHofGateway(gateway, AccountHofResponseObserver(snapshots, rosters, pass), clock,
            app.spammy.hof.character.service.SessionPatternLoadTracker())
        val observedService = HofAccountService(
            accountRepository, cookieRepository, accountQueryRepository, cookieQueryRepository,
            accountIdentityService, credentialCipher, cookieCipher, HofRequestFactory(), gateway,
            observingGateway, LoginStateParser(), clock,
        )
        val account = HofAccountEntity(7L, "observed-user", credentialCipher.encrypt("password"), now)
        Mockito.`when`(accountQueryRepository.findByLoginId("observed-user")).thenReturn(account)
        Mockito.`when`(accountQueryRepository.findById(7L)).thenReturn(account)
        Mockito.`when`(accountQueryRepository.findByIdForUpdate(7L)).thenAnswer {
            currentTime = now.plusSeconds(10)
            account
        }
        Mockito.`when`(accountRepository.save(anyAccount())).thenAnswer { it.arguments[0] }
        Mockito.`when`(cookieRepository.save(anyCookie())).thenAnswer { it.arguments[0] }
        gateway.loginReturned = { currentTime = responseAt }
        if (completion >= 0) TransactionSynchronizationManager.initSynchronization()
        try {
            if (reauthenticate) observedService.reauthenticate(7L)
            else observedService.authenticate("observed-user", "password")
            assertEquals(listOf(HofHttpMethod.GET, HofHttpMethod.POST), gateway.requests.map { it.method })
            if (completion >= 0) {
                Mockito.verifyNoInteractions(snapshots, pass, rosters)
                currentTime = now.plusSeconds(60)
                TransactionSynchronizationManager.getSynchronizations().single().afterCompletion(completion)
            }

            Mockito.verify(pass).observe(7L, gateway.loginBody, now, responseAt)
            Mockito.verify(snapshots).observe(7L, gateway.loginBody, now)
            assertEquals(2, gateway.requests.size)
        } finally {
            if (completion >= 0) TransactionSynchronizationManager.clearSynchronization()
        }
    }

    private fun anyAccount(): HofAccountEntity =
        Mockito.any(HofAccountEntity::class.java) ?: account()

    private fun anyCookie(): HofCookieEntity =
        Mockito.any(HofCookieEntity::class.java) ?: HofCookieEntity(
            account = account(),
            name = "matcher-cookie",
            value = "matcher-value",
            updatedAt = now,
        )

    private fun account(): HofAccountEntity =
        HofAccountEntity(
            loginId = "matcher-account",
            encryptedPassword = "matcher-password",
            createdAt = now,
        )

    private fun <T : Any> capture(
        captor: ArgumentCaptor<T>,
        fallback: T,
    ): T = captor.capture() ?: fallback

    private class FakeHofGateway : HofGateway {
        val accountIds = mutableListOf<Long>()
        val requests = mutableListOf<HofRequest>()
        val loginBody = """<a href="?char=1683198503393759">소셜</a>"""
        var loginReturned: () -> Unit = {}

        override fun execute(
            accountId: Long,
            request: HofRequest,
            cookies: Map<String, String>,
        ): HofHttpResponse {
            accountIds += accountId
            requests += request
            return if (request.method == HofHttpMethod.GET) {
                HofHttpResponse(
                    statusCode = 200,
                    finalUrl = request.url,
                    body = "<html></html>",
                    setCookies = mapOf("PHPSESSID" to "initial"),
                )
            } else {
                loginReturned()
                HofHttpResponse(
                    statusCode = 200,
                    finalUrl = request.url,
                    body = loginBody,
                    setCookies = mapOf("NO" to "42"),
                )
            }
        }
    }
}
