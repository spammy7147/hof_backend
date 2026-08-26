package app.spammy.hof.captcha.service

import app.spammy.hof.account.service.HofAccountService
import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.captcha.dto.CaptchaPassMaintenanceLifecycleState
import app.spammy.hof.captcha.dto.CaptchaPassMaintenanceResponse
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.status.service.HofStatusService
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.mockito.Mockito

class CaptchaPassStatusRefresherTest {
    private val accounts = Mockito.mock(HofAccountService::class.java)
    private val status = Mockito.mock(HofStatusService::class.java)
    private val maintenance = Mockito.mock(CaptchaPassMaintenanceService::class.java)
    private val service = CaptchaPassStatusRefresher(
        HofSessionRecoveryService(accounts),
        status,
        maintenance,
        TimeProvider { NOW },
    )

    @Test
    fun `successful GET is rejected when no fresh pass observation was committed`() {
        Mockito.`when`(maintenance.get(ACCOUNT_ID)).thenReturn(pass(observedAt = NOW.minusSeconds(1)))

        assertFailsWith<IllegalStateException> { service.refresh(ACCOUNT_ID) }
    }

    @Test
    fun `a fresh but already expired countdown is not accepted as valid`() {
        Mockito.`when`(maintenance.get(ACCOUNT_ID)).thenReturn(pass(observedAt = NOW, remainingSeconds = 0))

        assertFailsWith<IllegalStateException> { service.refresh(ACCOUNT_ID) }
    }

    @Test
    fun `a fresh positive countdown completes status refresh`() {
        Mockito.`when`(maintenance.get(ACCOUNT_ID)).thenReturn(pass(observedAt = NOW, remainingSeconds = 1_799))

        service.refresh(ACCOUNT_ID)

        Mockito.verify(status).fetch(
            ACCOUNT_ID,
            app.spammy.hof.external.model.HofRequestOrigin.AUTOMATION,
        )
    }

    private fun pass(observedAt: Instant, remainingSeconds: Int = 1_799) = CaptchaPassMaintenanceResponse(
        enabled = true,
        authSuspended = false,
        passState = "VALID",
        remainingSeconds = remainingSeconds,
        validUntil = NOW.plusSeconds(remainingSeconds.toLong()),
        observedAt = observedAt,
        nextRefreshAt = null,
        lastAttemptAt = NOW,
        lastResult = null,
        manualChallengeId = null,
        lifecycleState = CaptchaPassMaintenanceLifecycleState.VALID,
        userActionRequired = false,
    )

    private companion object {
        const val ACCOUNT_ID = 7L
        val NOW: Instant = Instant.parse("2026-08-26T10:00:00Z")
    }
}
