package app.spammy.hof.captcha.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import java.time.Instant
import kotlin.test.Test
import org.mockito.Mockito

class CaptchaPassTerminalServiceTest {
    private val accounts = Mockito.mock(AccountQueryRepository::class.java)
    private val maintenance = Mockito.mock(CaptchaPassMaintenanceService::class.java)
    private val captcha = Mockito.mock(CaptchaService::class.java)
    private val notifications = Mockito.mock(CaptchaNotificationGateway::class.java)
    private val service = CaptchaPassTerminalService(accounts, maintenance, captcha, notifications)
    private val account = HofAccountEntity(7L, "login", "encrypted", Instant.parse("2026-08-26T00:00:00Z"))

    @Test
    fun `manual terminal state and one deterministic push event are written together`() {
        Mockito.`when`(accounts.findById(7L)).thenReturn(account)
        Mockito.`when`(maintenance.finishManualRequired(7L, "lease-1", 91L)).thenReturn(true)

        service.finishManualRequired(7L, "lease-1", 91L, automaticAttemptCount = 3)

        Mockito.verify(captcha).markManualInputRequired(7L, 91L, 3)
        Mockito.verify(notifications).captchaRequired(account, 91L, "pass-manual-lease-1")
    }

    @Test
    fun `a stale renewal lease cannot change the challenge prompt or emit a push event`() {
        Mockito.`when`(accounts.findById(7L)).thenReturn(account)
        Mockito.`when`(maintenance.finishManualRequired(7L, "stale-lease", 91L)).thenReturn(false)

        service.finishManualRequired(7L, "stale-lease", 91L, automaticAttemptCount = 3)

        Mockito.verifyNoInteractions(captcha, notifications)
    }

    @Test
    fun `a stale lease cannot emit a duplicate login-required event`() {
        Mockito.`when`(accounts.findById(7L)).thenReturn(account)
        Mockito.`when`(maintenance.finishLoginRequired(7L, "stale-lease")).thenReturn(false)

        service.finishLoginRequired(7L, "stale-lease")

        Mockito.verifyNoInteractions(notifications)
    }

    @Test
    fun `login terminal state emits one deterministic profile-aware event`() {
        Mockito.`when`(accounts.findById(7L)).thenReturn(account)
        Mockito.`when`(maintenance.finishLoginRequired(7L, "lease-2")).thenReturn(true)

        service.finishLoginRequired(7L, "lease-2")

        Mockito.verify(notifications).loginRequired(account, "pass-login-lease-2")
    }
}
