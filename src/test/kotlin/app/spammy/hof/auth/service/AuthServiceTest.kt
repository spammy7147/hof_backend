package app.spammy.hof.auth.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.service.HofAccountService
import app.spammy.hof.auth.dto.AuthClientType
import app.spammy.hof.push.service.DevicePushTargetService
import org.mockito.Mockito
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthServiceTest {
    private val accountService = Mockito.mock(HofAccountService::class.java)
    private val jwtTokenService = Mockito.mock(JwtTokenService::class.java)
    private val refreshTokenService = Mockito.mock(RefreshTokenService::class.java)
    private val executionGate = Mockito.mock(AccountExecutionSubmissionGate::class.java)
    private val pushTargets = Mockito.mock(DevicePushTargetService::class.java)
    private val service = AuthService(accountService, jwtTokenService, refreshTokenService, executionGate, pushTargets)

    init {
        Mockito.doAnswer { invocation ->
            (invocation.arguments[1] as Runnable).run()
            null
        }.`when`(executionGate).executeLogout(Mockito.anyLong(), anyRunnable())
        Mockito.doAnswer { invocation ->
            invocation.getArgument<(Long) -> Unit>(1).invoke(42L)
            42L
        }.`when`(refreshTokenService).logout(Mockito.eq("refresh"), anyAfterRevocation())
    }

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

    @Test
    fun logoutDeactivatesOnlyTheSubmittedDevicesOwnedPushTarget() {
        Mockito.`when`(refreshTokenService.findLogoutAccountId("refresh")).thenReturn(42L)

        service.logout("refresh", 7L)

        Mockito.verify(pushTargets).deactivate(42L, 7L)
    }

    @Test
    fun `logout after an app restart deactivates the current device by durable installation id`() {
        Mockito.`when`(refreshTokenService.findLogoutAccountId("refresh")).thenReturn(42L)

        service.logout("refresh", pushInstallationId = "install-7")

        Mockito.verify(pushTargets).deactivateByInstallation(42L, "install-7")
    }

    private fun anyRunnable(): Runnable = Mockito.any(Runnable::class.java) ?: Runnable {}

    private fun anyAfterRevocation(): (Long) -> Unit = Mockito.any() ?: {}

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-13T00:00:00Z")
        val ACCESS_EXPIRES_AT: Instant = NOW.plusSeconds(1_800)
        val REFRESH_EXPIRES_AT: Instant = NOW.plusSeconds(2_592_000)
        val ACCOUNT = HofAccountEntity(42L, "hof-id", "encrypted", NOW)
    }
}
