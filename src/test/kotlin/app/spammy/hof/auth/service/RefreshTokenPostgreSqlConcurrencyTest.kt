package app.spammy.hof.auth.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.auth.repository.RefreshTokenQueryRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.external.client.HofGateway
import jakarta.persistence.EntityManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/** 별도 로컬 PostgreSQL에서 실제 회전·로그아웃 transaction의 완료 상태를 검증한다. */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "HOF_TEST_POSTGRES_URL", matches = "jdbc:postgresql:.*")
class RefreshTokenPostgreSqlConcurrencyTest {
    @Autowired private lateinit var tokens: RefreshTokenService
    @Autowired private lateinit var auth: AuthService
    @Autowired private lateinit var authorization: AccountExecutionAuthorizationReader
    @Autowired private lateinit var executionGate: AccountExecutionSubmissionGate
    @Autowired private lateinit var transactions: PlatformTransactionManager
    @Autowired private lateinit var entityManager: EntityManager
    @MockitoSpyBean private lateinit var accounts: AccountQueryRepository
    @Autowired private lateinit var query: RefreshTokenQueryRepository
    @MockitoBean private lateinit var hof: HofGateway

    @ParameterizedTest
    @CsvSource("false,false", "true,false", "false,true", "true,true")
    fun `이전 토큰의 family 폐기는 동시에 회전된 자식을 포함하고 다른 family는 보존한다`(otherFamily: Boolean, reuse: Boolean) {
        val account = newAccount()
        val original = tokens.issue(account, "NATIVE")
        val current = tokens.rotate(original.value)
        val other = if (otherFamily) tokens.issue(account, "WEB") else null
        if (reuse) {
            TransactionTemplate(transactions).executeWithoutResult {
                query.findByTokenHashForUpdate(RefreshTokenService.hash(original.value))!!.rotatedAt = Instant.now().minusSeconds(20)
            }
        }
        val accountLocked = CountDownLatch(1)
        val logoutAccountRequested = CountDownLatch(1)
        val allowChildInsert = CountDownLatch(1)
        val paused = AtomicBoolean(false)
        Mockito.doAnswer { invocation ->
            if (Thread.currentThread().name == "auth-logout") logoutAccountRequested.countDown()
            val locked = invocation.callRealMethod()
            if (Thread.currentThread().name == "auth-refresh" && paused.compareAndSet(false, true)) {
                accountLocked.countDown()
                check(allowChildInsert.await(15, TimeUnit.SECONDS))
            }
            locked
        }.`when`(accounts).findByIdForUpdate(account.id)

        val executor = Executors.newFixedThreadPool(2)
        try {
            val rotation = CompletableFuture.supplyAsync({
                Thread.currentThread().name = "auth-refresh"
                tokens.rotate(current.value)
            }, executor)
            assertTrue(accountLocked.await(15, TimeUnit.SECONDS), "회전의 계정 잠금 도달")
            val logout = CompletableFuture.runAsync({
                Thread.currentThread().name = "auth-logout"
                if (reuse) {
                    assertEquals(ErrorCode.REFRESH_TOKEN_REUSED, assertFailsWith<ApiException> { auth.refresh(original.value) }.errorCode)
                } else {
                    auth.logout(original.value)
                }
            }, executor)
            try {
                assertTrue(logoutAccountRequested.await(15, TimeUnit.SECONDS), "로그아웃의 계정 잠금 요청 도달")
            } finally { allowChildInsert.countDown() }
            rotation.get(15, TimeUnit.SECONDS)
            logout.get(15, TimeUnit.SECONDS)

            val active = TransactionTemplate(transactions).execute {
                query.findByFamilyId(original.familyId).count { it.revokedAt == null && it.rotatedAt == null && it.expiresAt.isAfter(Instant.now()) }
            }
            assertEquals(0, active, "폐기 완료 이후 같은 family의 활성 토큰")
            assertEquals(otherFamily, authorization.isExecutionAllowed(account.id))
            var submitted = false
            assertEquals(otherFamily, executionGate.executeIfAuthorized(account.id, Runnable { submitted = true }))
            assertEquals(otherFamily, submitted)
            if (other != null) {
                assertEquals(other.familyId, tokens.rotate(other.value).familyId)
            }
        } finally {
            allowChildInsert.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `폐기 transaction을 기다린 회전은 잠금 뒤 토큰을 다시 읽어 거부한다`() {
        val account = newAccount()
        val original = tokens.issue(account, "NATIVE")
        val current = tokens.rotate(original.value)
        val logoutLocked = CountDownLatch(1)
        val refreshRequested = CountDownLatch(1)
        val allowRevocation = CountDownLatch(1)
        val paused = AtomicBoolean(false)
        Mockito.doAnswer { invocation ->
            if (Thread.currentThread().name == "auth-refresh") refreshRequested.countDown()
            val locked = invocation.callRealMethod()
            if (Thread.currentThread().name == "auth-logout" && paused.compareAndSet(false, true)) {
                logoutLocked.countDown()
                check(allowRevocation.await(15, TimeUnit.SECONDS))
            }
            locked
        }.`when`(accounts).findByIdForUpdate(account.id)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val logout = CompletableFuture.runAsync({
                Thread.currentThread().name = "auth-logout"
                auth.logout(original.value)
            }, executor)
            assertTrue(logoutLocked.await(15, TimeUnit.SECONDS))
            val rotation = CompletableFuture.supplyAsync({
                Thread.currentThread().name = "auth-refresh"
                assertFailsWith<ApiException> { tokens.rotate(current.value) }.errorCode
            }, executor)
            try { assertTrue(refreshRequested.await(15, TimeUnit.SECONDS)) }
            finally { allowRevocation.countDown() }
            logout.get(15, TimeUnit.SECONDS)
            assertEquals(ErrorCode.AUTH_TOKEN_INVALID, rotation.get(15, TimeUnit.SECONDS))
            assertEquals(false, authorization.isExecutionAllowed(account.id))
        } finally {
            allowRevocation.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `동일 토큰의 동시 회전은 한 번 성공하고 다른 요청은 409 재시도를 요구한다`() {
        val account = newAccount()
        val original = tokens.issue(account, "NATIVE")
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val requests = List(2) {
                CompletableFuture.supplyAsync({
                    check(start.await(15, TimeUnit.SECONDS))
                    runCatching { tokens.rotate(original.value) }
                }, executor)
            }
            start.countDown()
            val results = requests.map { it.get(15, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it.isSuccess })
            val error = results.single { it.isFailure }.exceptionOrNull()
            assertTrue(error is ApiException)
            assertEquals(ErrorCode.REFRESH_RETRY_REQUIRED, error.errorCode)
            assertTrue(authorization.isExecutionAllowed(account.id))
            val remaining = TransactionTemplate(transactions).execute { query.countActiveByAccountId(account.id, Instant.now()) }
            assertEquals(1L, remaining)
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS))
        }
    }

    private fun newAccount() = TransactionTemplate(transactions).execute {
        HofAccountEntity(loginId = "auth-race-${UUID.randomUUID()}", encryptedPassword = "fixture", createdAt = Instant.now())
            .also(entityManager::persist)
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun postgres(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { System.getenv("HOF_TEST_POSTGRES_URL") }
            registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
            registry.add("spring.datasource.username") { "hof_test" }
            registry.add("spring.datasource.password") { "local-fixture-only" }
        }
    }
}
