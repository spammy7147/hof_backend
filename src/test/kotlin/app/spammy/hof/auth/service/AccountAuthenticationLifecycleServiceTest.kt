package app.spammy.hof.auth.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.auth.entity.AccountAuthExecutionStateEntity
import app.spammy.hof.auth.repository.AccountAuthExecutionStateCommandRepository
import app.spammy.hof.auth.repository.AccountAuthExecutionStateQueryRepository
import app.spammy.hof.auth.repository.RefreshTokenQueryRepository
import app.spammy.hof.automation.service.TypedAutomationLifecycleBridge
import app.spammy.hof.captcha.service.CaptchaPassMaintenanceService
import app.spammy.hof.common.time.TimeProvider
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.mockito.Mockito

class AccountAuthenticationLifecycleServiceTest {
    private val accounts = Mockito.mock(AccountQueryRepository::class.java)
    private val states = Mockito.mock(AccountAuthExecutionStateQueryRepository::class.java)
    private val commands = Mockito.mock(AccountAuthExecutionStateCommandRepository::class.java)
    private val refreshTokens = Mockito.mock(RefreshTokenQueryRepository::class.java)
    private val automation = Mockito.mock(TypedAutomationLifecycleBridge::class.java)
    private val passes = Mockito.mock(CaptchaPassMaintenanceService::class.java)
    private val account = HofAccountEntity(ACCOUNT_ID, "login", "encrypted", NOW)
    private val service = AccountAuthenticationLifecycleService(
        accounts,
        states,
        commands,
        refreshTokens,
        automation,
        passes,
        TimeProvider { NOW },
    )

    @Test
    fun `last active refresh family suspends automation and pass maintenance`() {
        val state = AccountAuthExecutionStateEntity(ACCOUNT_ID, account, suspended = false, updatedAt = NOW)
        Mockito.`when`(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(account)
        Mockito.`when`(states.findByAccountId(ACCOUNT_ID)).thenReturn(state)
        Mockito.`when`(refreshTokens.countActiveByAccountId(ACCOUNT_ID, NOW)).thenReturn(0)

        assertTrue(service.suspendIfNoActiveSessions(ACCOUNT_ID))

        assertTrue(state.suspended)
        Mockito.verify(automation).suspendForAuthentication(ACCOUNT_ID, "LAST_APP_SESSION_ENDED")
        Mockito.verify(passes).suspendForAuthentication(ACCOUNT_ID)
    }

    @Test
    fun `another active refresh family keeps account execution running`() {
        Mockito.`when`(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(account)
        Mockito.`when`(refreshTokens.countActiveByAccountId(ACCOUNT_ID, NOW)).thenReturn(1)

        assertFalse(service.suspendIfNoActiveSessions(ACCOUNT_ID))

        Mockito.verifyNoInteractions(automation, passes, commands)
    }

    @Test
    fun `new login clears authentication suspension and restores each domain by its own marker`() {
        val state = AccountAuthExecutionStateEntity(ACCOUNT_ID, account, suspended = true, suspendedAt = NOW, updatedAt = NOW)
        Mockito.`when`(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(account)
        Mockito.`when`(states.findByAccountId(ACCOUNT_ID)).thenReturn(state)

        service.activate(ACCOUNT_ID, newLoginFamily = true)

        assertFalse(state.suspended)
        Mockito.verify(automation).resumeAfterAuthentication(ACCOUNT_ID, "APP_SESSION_ACTIVATED")
        Mockito.verify(passes).resumeAfterAuthentication(ACCOUNT_ID)
    }

    @Test
    fun `refresh rotation in an already active session does not restart parked domain work`() {
        val state = AccountAuthExecutionStateEntity(ACCOUNT_ID, account, suspended = false, updatedAt = NOW)
        Mockito.`when`(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(account)
        Mockito.`when`(states.findByAccountId(ACCOUNT_ID)).thenReturn(state)

        service.activate(ACCOUNT_ID, newLoginFamily = false)

        assertFalse(state.suspended)
        Mockito.verifyNoInteractions(automation, passes)
    }

    @Test
    fun `a new login family refreshes pass observation without restarting active automation`() {
        val state = AccountAuthExecutionStateEntity(ACCOUNT_ID, account, suspended = false, updatedAt = NOW)
        Mockito.`when`(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(account)
        Mockito.`when`(states.findByAccountId(ACCOUNT_ID)).thenReturn(state)

        service.activate(ACCOUNT_ID, newLoginFamily = true)

        Mockito.verifyNoInteractions(automation)
        Mockito.verify(passes).resumeAfterAuthentication(ACCOUNT_ID)
    }

    private companion object {
        const val ACCOUNT_ID = 42L
        val NOW: Instant = Instant.parse("2026-08-26T10:00:00Z")
    }
}
