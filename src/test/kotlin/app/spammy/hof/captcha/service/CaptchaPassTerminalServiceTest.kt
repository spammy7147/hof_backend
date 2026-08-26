package app.spammy.hof.captcha.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.push.service.PushOutboxService
import java.time.Instant
import kotlin.test.Test
import org.mockito.Mockito

class CaptchaPassTerminalServiceTest {
    private val accounts = Mockito.mock(AccountQueryRepository::class.java)
    private val maintenance = Mockito.mock(CaptchaPassMaintenanceService::class.java)
    private val outbox = Mockito.mock(PushOutboxService::class.java)
    private val service = CaptchaPassTerminalService(accounts, maintenance, outbox)
    private val account = HofAccountEntity(7L, "login", "encrypted", Instant.parse("2026-08-26T00:00:00Z"))

    @Test
    fun `manual terminal state and one deterministic push event are written together`() {
        Mockito.`when`(accounts.findById(7L)).thenReturn(account)
        Mockito.`when`(maintenance.finishManualRequired(7L, "lease-1", 91L)).thenReturn(true)

        service.finishManualRequired(7L, "lease-1", 91L)

        Mockito.verify(outbox).enqueueCaptchaRequired(account, 91L, "pass-manual-lease-1")
    }

    @Test
    fun `a stale lease cannot emit a duplicate login-required event`() {
        Mockito.`when`(accounts.findById(7L)).thenReturn(account)
        Mockito.`when`(maintenance.finishLoginRequired(7L, "stale-lease")).thenReturn(false)

        service.finishLoginRequired(7L, "stale-lease")

        Mockito.verifyNoInteractions(outbox)
    }
}
