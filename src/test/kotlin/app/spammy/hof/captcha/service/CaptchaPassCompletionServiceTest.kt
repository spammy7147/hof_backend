package app.spammy.hof.captcha.service

import app.spammy.hof.captcha.dto.CaptchaPassMaintenanceLifecycleState
import app.spammy.hof.captcha.dto.CaptchaPassMaintenanceResponse
import java.time.Instant
import kotlin.test.Test
import org.mockito.Mockito

class CaptchaPassCompletionServiceTest {
    private val refresher = Mockito.mock(CaptchaPassStatusRefresher::class.java)
    private val maintenance = Mockito.mock(CaptchaPassMaintenanceService::class.java)
    private val service = CaptchaPassCompletionService(refresher, maintenance)

    @Test
    fun `successful status GET still retries when the pass countdown was not confirmed`() {
        Mockito.`when`(maintenance.get(ACCOUNT_ID)).thenReturn(response("REQUIRED"))

        service.confirmAfterAnswer(ACCOUNT_ID)

        Mockito.verify(refresher).refresh(ACCOUNT_ID)
        Mockito.verify(maintenance).scheduleConfirmationRetry(ACCOUNT_ID, "pass-not-confirmed")
    }

    @Test
    fun `confirmed pass countdown completes without a retry`() {
        Mockito.`when`(maintenance.get(ACCOUNT_ID)).thenReturn(response("VALID"))

        service.confirmAfterAnswer(ACCOUNT_ID)

        Mockito.verify(refresher).refresh(ACCOUNT_ID)
        Mockito.verify(maintenance, Mockito.never()).scheduleConfirmationRetry(
            Mockito.eq(ACCOUNT_ID),
            Mockito.anyString(),
        )
    }

    private fun response(passState: String) = CaptchaPassMaintenanceResponse(
        enabled = true,
        authSuspended = false,
        passState = passState,
        remainingSeconds = null,
        validUntil = null,
        observedAt = NOW,
        nextRefreshAt = null,
        lastAttemptAt = NOW,
        lastResult = null,
        manualChallengeId = null,
        lifecycleState = CaptchaPassMaintenanceLifecycleState.CHECKING,
        userActionRequired = false,
    )

    private companion object {
        const val ACCOUNT_ID = 7L
        val NOW: Instant = Instant.parse("2026-08-26T10:00:00Z")
    }
}
