package app.spammy.hof.auth.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.auth.config.AuthProperties
import app.spammy.hof.auth.entity.RefreshTokenEntity
import app.spammy.hof.auth.repository.RefreshTokenQueryRepository
import app.spammy.hof.auth.repository.RefreshTokenRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class RefreshTokenServiceTest {
    private val repository = Mockito.mock(RefreshTokenRepository::class.java)
    private val queryRepository = Mockito.mock(RefreshTokenQueryRepository::class.java)
    private val rateLimiter = Mockito.mock(AuthRateLimiter::class.java)
    private val accounts = Mockito.mock(AccountQueryRepository::class.java)
    private val accountLifecycle = Mockito.mock(AccountAuthenticationLifecycleService::class.java)
    private var now = CREATED_AT
    private val service = RefreshTokenService(
        repository = repository,
        queryRepository = queryRepository,
        rateLimiter = rateLimiter,
        properties = properties(),
        timeProvider = TimeProvider { now },
        accounts = accounts,
        accountLifecycle = accountLifecycle,
    )

    init {
        Mockito.`when`(accounts.findByIdForUpdate(ACCOUNT.id)).thenReturn(ACCOUNT)
    }

    @Test
    fun issuesOpaqueTokenAndStoresOnlyItsHashWithThirtyDayExpiry() {
        Mockito.`when`(repository.save(anyToken())).thenAnswer { it.arguments[0] }

        val issued = service.issue(ACCOUNT, "NATIVE")

        val captor = ArgumentCaptor.forClass(RefreshTokenEntity::class.java)
        Mockito.verify(repository).save(capture(captor))
        assertNotEquals(issued.value, captor.value.tokenHash)
        assertEquals(64, captor.value.tokenHash.length)
        assertEquals(issued.familyId, captor.value.familyId)
        assertEquals("NATIVE", captor.value.clientType)
        assertEquals(CREATED_AT.plus(Duration.ofDays(30)), captor.value.expiresAt)
        assertEquals(captor.value.expiresAt, issued.expiresAt)
        assertNull(captor.value.rotatedAt)
        assertNull(captor.value.revokedAt)
        Mockito.verify(accountLifecycle).activate(ACCOUNT.id, newLoginFamily = true)
    }

    @Test
    fun rotatesActiveTokenAndSlidesExpiryFromRotationTime() {
        val original = token(tokenHash = RefreshTokenService.hash(TOKEN))
        Mockito.`when`(queryRepository.findByTokenHashForUpdate(original.tokenHash)).thenReturn(original)
        Mockito.`when`(repository.save(anyToken())).thenAnswer { it.arguments[0] }
        now = CREATED_AT.plus(Duration.ofDays(5))

        val rotated = service.rotate(TOKEN)

        assertEquals(now, original.rotatedAt)
        assertEquals(FAMILY_ID, rotated.familyId)
        assertEquals(now.plus(Duration.ofDays(30)), rotated.expiresAt)
        assertNotEquals(TOKEN, rotated.value)
        Mockito.verify(rateLimiter).checkRefresh(FAMILY_ID, ACCOUNT.id)
        Mockito.verify(accountLifecycle).activate(ACCOUNT.id, newLoginFamily = false)
    }

    @Test
    fun duplicateRotationInsideGraceReturnsRetryConflictWithoutRevokingFamily() {
        val original = token(
            tokenHash = RefreshTokenService.hash(TOKEN),
            rotatedAt = CREATED_AT.plusSeconds(2),
        )
        Mockito.`when`(queryRepository.findByTokenHashForUpdate(original.tokenHash)).thenReturn(original)
        now = CREATED_AT.plusSeconds(10)

        val error = assertFailsWith<ApiException> { service.rotate(TOKEN) }

        assertEquals(ErrorCode.REFRESH_RETRY_REQUIRED, error.errorCode)
        Mockito.verify(queryRepository, Mockito.never()).findByFamilyId(FAMILY_ID)
        Mockito.verifyNoInteractions(rateLimiter)
    }

    @Test
    fun reusedRotatedTokenAfterGraceRevokesWholeFamily() {
        val original = token(
            tokenHash = RefreshTokenService.hash(TOKEN),
            rotatedAt = CREATED_AT.plusSeconds(2),
        )
        val current = token(tokenHash = "f".repeat(64), createdAt = CREATED_AT.plusSeconds(2))
        Mockito.`when`(queryRepository.findByTokenHashForUpdate(original.tokenHash)).thenReturn(original)
        Mockito.`when`(queryRepository.findByFamilyId(FAMILY_ID)).thenReturn(listOf(original, current))
        now = CREATED_AT.plusSeconds(13)

        val error = assertFailsWith<ApiException> { service.rotate(TOKEN) }

        assertEquals(ErrorCode.REFRESH_TOKEN_REUSED, error.errorCode)
        assertEquals(now, original.revokedAt)
        assertEquals(now, current.revokedAt)
        Mockito.verifyNoInteractions(rateLimiter)
    }

    @Test
    fun rejectsUnknownExpiredOrRevokedToken() {
        Mockito.`when`(queryRepository.findByTokenHashForUpdate(RefreshTokenService.hash("unknown"))).thenReturn(null)
        assertEquals(
            ErrorCode.AUTH_TOKEN_INVALID,
            assertFailsWith<ApiException> { service.rotate("unknown") }.errorCode,
        )

        val expired = token(tokenHash = RefreshTokenService.hash("expired"), expiresAt = CREATED_AT.minusSeconds(1))
        Mockito.`when`(queryRepository.findByTokenHashForUpdate(expired.tokenHash)).thenReturn(expired)
        assertEquals(
            ErrorCode.AUTH_TOKEN_INVALID,
            assertFailsWith<ApiException> { service.rotate("expired") }.errorCode,
        )

        val revoked = token(tokenHash = RefreshTokenService.hash("revoked"), revokedAt = CREATED_AT.minusSeconds(1))
        Mockito.`when`(queryRepository.findByTokenHashForUpdate(revoked.tokenHash)).thenReturn(revoked)
        assertEquals(
            ErrorCode.AUTH_TOKEN_INVALID,
            assertFailsWith<ApiException> { service.rotate("revoked") }.errorCode,
        )
        Mockito.verifyNoInteractions(rateLimiter)
    }

    @Test
    fun logoutRevokesEveryTokenInSubmittedTokensFamily() {
        val submitted = token(tokenHash = RefreshTokenService.hash(TOKEN))
        val sibling = token(tokenHash = "e".repeat(64))
        Mockito.`when`(queryRepository.findByTokenHashForUpdate(submitted.tokenHash)).thenReturn(submitted)
        Mockito.`when`(queryRepository.findByFamilyId(FAMILY_ID)).thenReturn(listOf(submitted, sibling))

        service.logout(TOKEN)

        assertEquals(CREATED_AT, submitted.revokedAt)
        assertEquals(CREATED_AT, sibling.revokedAt)
        Mockito.verify(accountLifecycle).suspendIfNoActiveSessions(ACCOUNT.id)
    }

    private fun token(
        tokenHash: String,
        createdAt: Instant = CREATED_AT,
        expiresAt: Instant = CREATED_AT.plus(Duration.ofDays(30)),
        rotatedAt: Instant? = null,
        revokedAt: Instant? = null,
    ) = RefreshTokenEntity(
        account = ACCOUNT,
        tokenHash = tokenHash,
        familyId = FAMILY_ID,
        clientType = "NATIVE",
        createdAt = createdAt,
        expiresAt = expiresAt,
        rotatedAt = rotatedAt,
        revokedAt = revokedAt,
    )

    private fun properties() = AuthProperties(
        jwtSecret = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=",
        credentialEncryptionKey = "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8=",
        cookieEncryptionKey = "KSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj9AQUJDREVGR0g=",
        refreshTokenTtl = Duration.ofDays(30),
        refreshReuseGrace = Duration.ofSeconds(10),
    )

    private fun anyToken(): RefreshTokenEntity =
        Mockito.any(RefreshTokenEntity::class.java) ?: token("0".repeat(64))

    private fun capture(captor: ArgumentCaptor<RefreshTokenEntity>): RefreshTokenEntity =
        captor.capture() ?: token("0".repeat(64))

    private companion object {
        const val TOKEN = "submitted-refresh-token"
        const val FAMILY_ID = "11111111-1111-1111-1111-111111111111"
        val CREATED_AT: Instant = Instant.parse("2026-07-13T00:00:00Z")
        val ACCOUNT = HofAccountEntity(
            id = 42L,
            loginId = "auth-user",
            encryptedPassword = "encrypted",
            createdAt = CREATED_AT,
        )
    }
}
