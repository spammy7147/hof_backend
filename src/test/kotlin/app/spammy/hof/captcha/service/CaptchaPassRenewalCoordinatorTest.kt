package app.spammy.hof.captcha.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.captcha.config.CaptchaAutoSolveProperties
import app.spammy.hof.captcha.dto.CaptchaChallengeResponse
import app.spammy.hof.captcha.dto.CaptchaPassMaintenanceResponse
import app.spammy.hof.captcha.dto.CaptchaPassMaintenanceLifecycleState
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.external.client.HofRequestFactory
import java.time.Instant
import kotlin.test.Test
import org.mockito.Mockito

class CaptchaPassRenewalCoordinatorTest {
    private val maintenance = Mockito.mock(CaptchaPassMaintenanceService::class.java)
    private val refresher = Mockito.mock(CaptchaPassStatusRefresher::class.java)
    private val accounts = Mockito.mock(AccountQueryRepository::class.java)
    private val captcha = Mockito.mock(CaptchaService::class.java)
    private val solver = Mockito.mock(CaptchaAutoSolveCoordinator::class.java)
    private val terminal = Mockito.mock(CaptchaPassTerminalService::class.java)
    private val account = HofAccountEntity(ACCOUNT_ID, "login", "encrypted", NOW)

    @Test
    fun `missing OCR configuration parks maintenance without creating a challenge`() {
        val coordinator = coordinator(enabled = false)
        Mockito.`when`(maintenance.claimDue(ACCOUNT_ID)).thenReturn(CLAIM)
        Mockito.`when`(maintenance.get(ACCOUNT_ID)).thenReturn(pass("REQUIRED"))

        coordinator.runDue(ACCOUNT_ID)

        Mockito.verify(refresher).refresh(ACCOUNT_ID)
        Mockito.verify(maintenance).finishOcrConfigurationRequired(ACCOUNT_ID, CLAIM.token)
        Mockito.verifyNoInteractions(captcha, solver)
    }

    @Test
    fun `disabled OCR still accepts a fresh valid pass observation`() {
        val coordinator = coordinator(enabled = false)
        Mockito.`when`(maintenance.claimDue(ACCOUNT_ID)).thenReturn(CLAIM)
        Mockito.`when`(maintenance.get(ACCOUNT_ID)).thenReturn(pass("VALID"))

        coordinator.runDue(ACCOUNT_ID)

        Mockito.verify(refresher).refresh(ACCOUNT_ID)
        Mockito.verify(maintenance).finishValid(ACCOUNT_ID, CLAIM.token, renewed = false)
        Mockito.verify(maintenance, Mockito.never()).finishOcrConfigurationRequired(ACCOUNT_ID, CLAIM.token)
    }

    @Test
    fun `setting disabled during the status read stops before challenge creation`() {
        val coordinator = coordinator()
        Mockito.`when`(maintenance.claimDue(ACCOUNT_ID)).thenReturn(CLAIM)
        Mockito.`when`(maintenance.get(ACCOUNT_ID)).thenReturn(pass("REQUIRED", enabled = false))

        coordinator.runDue(ACCOUNT_ID)

        Mockito.verify(refresher).refresh(ACCOUNT_ID)
        Mockito.verify(maintenance).finishCancelled(ACCOUNT_ID, CLAIM.token)
        Mockito.verifyNoInteractions(captcha, solver)
    }

    @Test
    fun `fresh valid status completes without issuing a captcha`() {
        val coordinator = coordinator()
        Mockito.`when`(maintenance.claimDue(ACCOUNT_ID)).thenReturn(CLAIM)
        Mockito.`when`(maintenance.get(ACCOUNT_ID)).thenReturn(pass("VALID"))

        coordinator.runDue(ACCOUNT_ID)

        Mockito.verify(refresher).refresh(ACCOUNT_ID)
        Mockito.verify(maintenance).finishValid(ACCOUNT_ID, CLAIM.token, renewed = false)
        Mockito.verifyNoInteractions(captcha, solver)
    }

    @Test
    fun `required status reuses the account challenge solves it and confirms a fresh countdown`() {
        val coordinator = coordinator()
        Mockito.`when`(maintenance.claimDue(ACCOUNT_ID)).thenReturn(CLAIM)
        Mockito.`when`(maintenance.get(ACCOUNT_ID)).thenReturn(pass("REQUIRED"), pass("VALID"))
        Mockito.`when`(maintenance.beginRecognition(ACCOUNT_ID, CLAIM.token)).thenReturn(true)
        Mockito.`when`(maintenance.beginReconciliation(ACCOUNT_ID, CLAIM.token)).thenReturn(true)
        Mockito.`when`(accounts.findById(ACCOUNT_ID)).thenReturn(account)
        Mockito.`when`(
            captcha.detectAndRecord(
                account,
                CaptchaPassRenewalCoordinator.REQUIRED_PASS_HTML,
                HofRequestFactory().home().url,
                false,
            ),
        ).thenReturn(challenge())
        stubSolve(CaptchaAutoSolveOutcome.SOLVED)

        coordinator.runDue(ACCOUNT_ID)

        Mockito.verify(refresher).refresh(ACCOUNT_ID)
        Mockito.verify(maintenance).finishValid(ACCOUNT_ID, CLAIM.token, renewed = true)
    }

    @Test
    fun `OCR exhaustion keeps one manual challenge and sends one nonblocking notification`() {
        val coordinator = coordinator()
        Mockito.`when`(maintenance.claimDue(ACCOUNT_ID)).thenReturn(CLAIM)
        Mockito.`when`(maintenance.get(ACCOUNT_ID)).thenReturn(pass("REQUIRED"))
        Mockito.`when`(maintenance.beginRecognition(ACCOUNT_ID, CLAIM.token)).thenReturn(true)
        Mockito.`when`(accounts.findById(ACCOUNT_ID)).thenReturn(account)
        Mockito.`when`(
            captcha.detectAndRecord(
                account,
                CaptchaPassRenewalCoordinator.REQUIRED_PASS_HTML,
                HofRequestFactory().home().url,
                false,
            ),
        ).thenReturn(challenge())
        stubSolve(CaptchaAutoSolveOutcome.MANUAL_INPUT_REQUIRED)

        coordinator.runDue(ACCOUNT_ID)

        Mockito.verify(terminal).finishManualRequired(ACCOUNT_ID, CLAIM.token, CHALLENGE_ID)
    }

    @Test
    fun `authentication loss during OCR prevents the first remote answer submission`() {
        val coordinator = coordinator()
        Mockito.`when`(maintenance.claimDue(ACCOUNT_ID)).thenReturn(CLAIM)
        Mockito.`when`(maintenance.get(ACCOUNT_ID)).thenReturn(pass("REQUIRED"))
        Mockito.`when`(maintenance.beginRecognition(ACCOUNT_ID, CLAIM.token)).thenReturn(true)
        Mockito.`when`(accounts.findById(ACCOUNT_ID)).thenReturn(account)
        Mockito.`when`(
            captcha.detectAndRecord(
                account,
                CaptchaPassRenewalCoordinator.REQUIRED_PASS_HTML,
                HofRequestFactory().home().url,
                false,
            ),
        ).thenReturn(challenge())
        Mockito.`when`(maintenance.authorizeSubmission(ACCOUNT_ID, CLAIM.token)).thenReturn(false)
        Mockito.doAnswer { invocation ->
            val authorize = invocation.getArgument<() -> Boolean>(2)
            if (authorize()) CaptchaAutoSolveOutcome.SOLVED else CaptchaAutoSolveOutcome.CANCELLED
        }.`when`(solver).solve(
            Mockito.eq(ACCOUNT_ID),
            Mockito.eq(CHALLENGE_ID),
            Mockito.any(),
        )

        coordinator.runDue(ACCOUNT_ID)

        Mockito.verify(maintenance).finishCancelled(ACCOUNT_ID, CLAIM.token)
        Mockito.verify(maintenance, Mockito.never()).beginReconciliation(ACCOUNT_ID, CLAIM.token)
        Mockito.verifyNoInteractions(terminal)
    }

    @Test
    fun `failed saved credential recovery parks maintenance and requests login once`() {
        val coordinator = coordinator()
        Mockito.`when`(maintenance.claimDue(ACCOUNT_ID)).thenReturn(CLAIM)
        Mockito.`when`(accounts.findById(ACCOUNT_ID)).thenReturn(account)
        Mockito.doThrow(ApiException(ErrorCode.HOF_SESSION_EXPIRED, "expired"))
            .`when`(refresher).refresh(ACCOUNT_ID)

        coordinator.runDue(ACCOUNT_ID)

        Mockito.verify(terminal).finishLoginRequired(ACCOUNT_ID, CLAIM.token)
    }

    @Test
    fun `session expiry in police flow reauthenticates through a fresh status read before retrying`() {
        val coordinator = coordinator()
        Mockito.`when`(maintenance.claimDue(ACCOUNT_ID)).thenReturn(CLAIM)
        Mockito.`when`(maintenance.get(ACCOUNT_ID)).thenReturn(
            pass("REQUIRED"),
            pass("REQUIRED"),
            pass("VALID"),
        )
        Mockito.`when`(maintenance.beginRecognition(ACCOUNT_ID, CLAIM.token)).thenReturn(true)
        Mockito.`when`(maintenance.beginReconciliation(ACCOUNT_ID, CLAIM.token)).thenReturn(true)
        Mockito.`when`(accounts.findById(ACCOUNT_ID)).thenReturn(account)
        Mockito.`when`(
            captcha.detectAndRecord(
                account,
                CaptchaPassRenewalCoordinator.REQUIRED_PASS_HTML,
                HofRequestFactory().home().url,
                false,
            ),
        ).thenReturn(challenge())
        Mockito.doThrow(ApiException(ErrorCode.HOF_SESSION_EXPIRED, "expired"))
            .doReturn(CaptchaAutoSolveOutcome.SOLVED)
            .`when`(solver).solve(
                Mockito.eq(ACCOUNT_ID),
                Mockito.eq(CHALLENGE_ID),
                Mockito.any(),
            )

        coordinator.runDue(ACCOUNT_ID)

        Mockito.verify(captcha).invalidatePreparation(ACCOUNT_ID, CHALLENGE_ID)
        Mockito.verify(refresher, Mockito.times(2)).refresh(ACCOUNT_ID)
        Mockito.verify(maintenance).finishValid(ACCOUNT_ID, CLAIM.token, renewed = true)
        Mockito.verify(terminal, Mockito.never()).finishLoginRequired(ACCOUNT_ID, CLAIM.token)
    }

    @Test
    fun `network failure schedules a retry whose next run starts with another status read`() {
        val coordinator = coordinator()
        Mockito.`when`(maintenance.claimDue(ACCOUNT_ID)).thenReturn(CLAIM)
        Mockito.doThrow(IllegalStateException("network"))
            .`when`(refresher).refresh(ACCOUNT_ID)

        coordinator.runDue(ACCOUNT_ID)

        Mockito.verify(maintenance).scheduleRetry(ACCOUNT_ID, CLAIM.token, "network")
        Mockito.verifyNoInteractions(captcha)
        Mockito.verifyNoInteractions(solver)
    }

    @Test
    fun `ambiguous answer failure invalidates the stored form before status-first retry`() {
        val coordinator = coordinator()
        Mockito.`when`(maintenance.claimDue(ACCOUNT_ID)).thenReturn(CLAIM)
        Mockito.`when`(maintenance.get(ACCOUNT_ID)).thenReturn(pass("REQUIRED"))
        Mockito.`when`(maintenance.beginRecognition(ACCOUNT_ID, CLAIM.token)).thenReturn(true)
        Mockito.`when`(accounts.findById(ACCOUNT_ID)).thenReturn(account)
        Mockito.`when`(
            captcha.detectAndRecord(
                account,
                CaptchaPassRenewalCoordinator.REQUIRED_PASS_HTML,
                HofRequestFactory().home().url,
                false,
            ),
        ).thenReturn(challenge())
        Mockito.doThrow(ApiException(ErrorCode.HOF_REQUEST_FAILED, "ambiguous"))
            .`when`(solver).solve(
                Mockito.eq(ACCOUNT_ID),
                Mockito.eq(CHALLENGE_ID),
                Mockito.any(),
            )

        coordinator.runDue(ACCOUNT_ID)

        Mockito.verify(captcha).invalidatePreparation(ACCOUNT_ID, CHALLENGE_ID)
        Mockito.verify(maintenance).scheduleRetry(ACCOUNT_ID, CLAIM.token, "ambiguous")
    }

    private fun coordinator(enabled: Boolean = true): CaptchaPassRenewalCoordinator =
        CaptchaPassRenewalCoordinator(
            maintenance = maintenance,
            refresher = refresher,
            accounts = accounts,
            captcha = captcha,
            solver = solver,
            autoSolveProperties = CaptchaAutoSolveProperties(
                enabled = enabled,
                baseUrl = if (enabled) "https://ocr.example.com" else "",
                token = if (enabled) "x".repeat(32) else "",
            ),
            requestFactory = HofRequestFactory(),
            terminal = terminal,
        )

    private fun stubSolve(outcome: CaptchaAutoSolveOutcome) {
        Mockito.doReturn(outcome).`when`(solver).solve(
            Mockito.eq(ACCOUNT_ID),
            Mockito.eq(CHALLENGE_ID),
            Mockito.any(),
        )
    }

    private fun pass(state: String, enabled: Boolean = true) = CaptchaPassMaintenanceResponse(
        enabled = enabled,
        authSuspended = false,
        passState = state,
        remainingSeconds = null,
        validUntil = null,
        observedAt = NOW,
        nextRefreshAt = NOW,
        lastAttemptAt = NOW,
        lastResult = null,
        manualChallengeId = null,
        lifecycleState = CaptchaPassMaintenanceLifecycleState.CHECK_SCHEDULED,
        userActionRequired = false,
    )

    private fun challenge() = CaptchaChallengeResponse(
        id = CHALLENGE_ID,
        accountId = ACCOUNT_ID,
        status = "DETECTED",
        prompt = "통행증",
        imageUrl = null,
        sourceUrl = HofRequestFactory().home().url,
        preparationVersion = 0,
        createdAt = NOW.toString(),
        answeredAt = null,
    )

    private companion object {
        const val ACCOUNT_ID = 7L
        const val CHALLENGE_ID = 91L
        val NOW: Instant = Instant.parse("2026-08-26T10:00:00Z")
        val CLAIM = CaptchaPassMaintenanceClaim(ACCOUNT_ID, "lease-token")
    }
}
