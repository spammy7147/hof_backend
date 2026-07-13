package app.spammy.hof.auth.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.service.HofAccountService
import app.spammy.hof.auth.dto.AuthClientType
import org.mockito.Mockito
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthServiceTest {
    private val accountService = Mockito.mock(HofAccountService::class.java)
    private val jwtTokenService = Mockito.mock(JwtTokenService::class.java)
    private val refreshTokenService = Mockito.mock(RefreshTokenService::class.java)
    private val service = AuthService(accountService, jwtTokenService, refreshTokenService)

    @Test
    fun successfulHofLoginIssuesTokenPairForSavedAccount() {
        Mockito.`when`(accountService.authenticate("hof-id", "hof-password"))
            .thenReturn(ACCOUNT)
        Mockito.`when`(jwtTokenService.issue(42L)).thenReturn(IssuedAccessToken("access", ACCESS_EXPIRES_AT))
        Mockito.`when`(refreshTokenService.issue(ACCOUNT, "NATIVE"))
            .thenReturn(IssuedRefreshToken("refresh", REFRESH_EXPIRES_AT, "family", 42L, "NATIVE"))

        val result = service.login("hof-id", "hof-password", AuthClientType.NATIVE)

        assertEquals("access", result.accessToken.value)
        assertEquals("refresh", result.refreshToken.value)
        assertEquals(AuthClientType.NATIVE, result.clientType)
    }

    @Test
    fun refreshRotatesRefreshTokenAndIssuesNewAccessToken() {
        Mockito.`when`(refreshTokenService.rotate("old-refresh"))
            .thenReturn(IssuedRefreshToken("new-refresh", REFRESH_EXPIRES_AT, "family", 42L, "WEB"))
        Mockito.`when`(jwtTokenService.issue(42L)).thenReturn(IssuedAccessToken("new-access", ACCESS_EXPIRES_AT))

        val result = service.refresh("old-refresh")

        assertEquals("new-access", result.accessToken.value)
        assertEquals("new-refresh", result.refreshToken.value)
        assertEquals(AuthClientType.WEB, result.clientType)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-13T00:00:00Z")
        val ACCESS_EXPIRES_AT: Instant = NOW.plusSeconds(1_800)
        val REFRESH_EXPIRES_AT: Instant = NOW.plusSeconds(2_592_000)
        val ACCOUNT = HofAccountEntity(42L, "hof-id", "encrypted", NOW)
    }
}
