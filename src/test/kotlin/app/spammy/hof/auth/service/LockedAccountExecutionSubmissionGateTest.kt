package app.spammy.hof.auth.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.auth.repository.RefreshTokenQueryRepository
import app.spammy.hof.common.time.TimeProvider
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.mockito.Mockito

class LockedAccountExecutionSubmissionGateTest {
    private val accounts = Mockito.mock(AccountQueryRepository::class.java)
    private val refreshTokens = Mockito.mock(RefreshTokenQueryRepository::class.java)
    private val timeProvider = Mockito.mock(TimeProvider::class.java)
    private val service = LockedAccountExecutionSubmissionGate(accounts, refreshTokens, timeProvider)
    private val now = Instant.parse("2026-08-26T00:00:00Z")
    private val account = HofAccountEntity(7L, "login", "encrypted", now)

    @Test
    fun `runs the remote submission only while the locked account still has an active app session`() {
        var submitted = false
        Mockito.`when`(accounts.findByIdForUpdate(7L)).thenReturn(account)
        Mockito.`when`(timeProvider.now()).thenReturn(now)
        Mockito.`when`(refreshTokens.countActiveByAccountId(7L, now)).thenReturn(1L)

        val authorized = service.executeIfAuthorized(7L, Runnable { submitted = true })

        assertTrue(authorized)
        assertTrue(submitted)
        val order = Mockito.inOrder(accounts, refreshTokens)
        order.verify(accounts).findByIdForUpdate(7L)
        order.verify(refreshTokens).countActiveByAccountId(7L, now)
    }

    @Test
    fun `does not start a remote submission after logout removed the final active session`() {
        var submitted = false
        Mockito.`when`(accounts.findByIdForUpdate(7L)).thenReturn(account)
        Mockito.`when`(timeProvider.now()).thenReturn(now)
        Mockito.`when`(refreshTokens.countActiveByAccountId(7L, now)).thenReturn(0L)

        val authorized = service.executeIfAuthorized(7L, Runnable { submitted = true })

        assertFalse(authorized)
        assertFalse(submitted)
    }
}
