package app.spammy.hof.captcha.service

import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.captcha.config.CaptchaAutoSolveProperties
import app.spammy.hof.captcha.dto.CaptchaPassMaintenanceLastResult
import app.spammy.hof.captcha.entity.CaptchaPassMaintenanceEntity
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.external.client.HofRequestFactory
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** 계정 전역 통행증 만료 작업의 상태 재확인, challenge 수렴, OCR, 사후 확인을 조율한다. */
@Service
class CaptchaPassRenewalCoordinator(
    private val maintenance: CaptchaPassMaintenanceService,
    private val refresher: CaptchaPassStatusRefresher,
    private val accounts: AccountQueryRepository,
    private val captcha: CaptchaService,
    private val solver: CaptchaAutoSolveCoordinator,
    private val autoSolveProperties: CaptchaAutoSolveProperties,
    private val requestFactory: HofRequestFactory,
    private val terminal: CaptchaPassTerminalService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun runDue(accountId: Long) {
        val claim = maintenance.claimDue(accountId) ?: return
        var freshStatusCompleted = false
        var touchedChallengeId: Long? = null

        try {
            refresher.refresh(accountId)
            freshStatusCompleted = true
            continueFromFreshStatus(accountId, claim) { touchedChallengeId = it }
        } catch (error: Throwable) {
            touchedChallengeId?.let { challengeId ->
                runCatching { captcha.invalidatePreparation(accountId, challengeId) }
                    .onFailure { cleanup -> error.addSuppressed(cleanup) }
            }
            if (error.isHofSessionExpired() && freshStatusCompleted) {
                recoverSessionAndContinue(accountId, claim)
            } else if (error.isHofSessionExpired()) {
                terminal.finishLoginRequired(accountId, claim.token)
            } else {
                scheduleFailure(accountId, claim, error)
            }
        }
    }

    private fun continueFromFreshStatus(
        accountId: Long,
        claim: CaptchaPassMaintenanceClaim,
        onChallenge: (Long) -> Unit,
    ) {
        val current = maintenance.get(accountId)
        if (current.authSuspended) {
            maintenance.finishSuspended(accountId, claim.token)
            return
        }
        if (!current.enabled) {
            maintenance.finishCancelled(accountId, claim.token)
            return
        }
        when (current.passState) {
            CaptchaPassMaintenanceEntity.PASS_VALID ->
                maintenance.finishValid(accountId, claim.token, renewed = false)
            CaptchaPassMaintenanceEntity.PASS_REQUIRED -> when {
                current.lastResult == CaptchaPassMaintenanceLastResult.MANUAL_REQUIRED &&
                    current.manualChallengeId != null ->
                    maintenance.finishManualRequired(accountId, claim.token, current.manualChallengeId)
                !autoSolveProperties.enabled ->
                    maintenance.finishOcrConfigurationRequired(accountId, claim.token)
                else -> renewRequiredPass(accountId, claim, onChallenge)
            }
            else -> maintenance.scheduleRetry(accountId, claim.token, UNKNOWN_STATE_REASON)
        }
    }

    private fun renewRequiredPass(
        accountId: Long,
        claim: CaptchaPassMaintenanceClaim,
        onChallenge: (Long) -> Unit,
    ) {
        val account = accounts.findById(accountId)
        if (account == null) {
            maintenance.scheduleRetry(accountId, claim.token, "account-not-found")
            return
        }
        val challenge = captcha.detectAndRecord(
            account = account,
            html = REQUIRED_PASS_HTML,
            sourceUrl = requestFactory.home().url,
            notifyAutomation = false,
        )
        if (challenge == null) {
            maintenance.scheduleRetry(accountId, claim.token, "challenge-not-detected")
            return
        }
        onChallenge(challenge.id)
        if (!maintenance.beginRecognition(accountId, claim.token)) {
            maintenance.finishCancelled(accountId, claim.token)
            return
        }

        when (
            solver.solve(
                accountId = accountId,
                challengeId = challenge.id,
                authorizeSubmission = { maintenance.authorizeSubmission(accountId, claim.token) },
                manualInputRequired = { attemptCount ->
                    terminal.finishManualRequired(accountId, claim.token, challenge.id, attemptCount)
                },
            )
        ) {
            CaptchaAutoSolveOutcome.MANUAL_INPUT_REQUIRED -> Unit
            CaptchaAutoSolveOutcome.SOLVED,
            CaptchaAutoSolveOutcome.NO_PENDING_CHALLENGE,
            -> {
                if (maintenance.beginReconciliation(accountId, claim.token)) convergeAfterSolve(accountId, claim)
            }
            CaptchaAutoSolveOutcome.CANCELLED -> maintenance.finishCancelled(accountId, claim.token)
        }
    }

    /** CaptchaAutomationHook의 공통 답안 완료가 fresh GET을 소유하므로 여기서는 결과만 lease에 수렴한다. */
    private fun convergeAfterSolve(accountId: Long, claim: CaptchaPassMaintenanceClaim) {
        if (maintenance.get(accountId).passState == CaptchaPassMaintenanceEntity.PASS_VALID) {
            maintenance.finishValid(accountId, claim.token, renewed = true)
        } else {
            maintenance.scheduleRetry(accountId, claim.token, "pass-not-confirmed")
        }
    }

    /** 경찰서 흐름 중 HOF 세션이 끊기면 공통 복구를 한 번 수행한 뒤 상태 GET부터 다시 판단한다. */
    private fun recoverSessionAndContinue(accountId: Long, claim: CaptchaPassMaintenanceClaim) {
        var retryChallengeId: Long? = null
        try {
            refresher.refresh(accountId)
            continueFromFreshStatus(accountId, claim) { retryChallengeId = it }
        } catch (recoveryError: Throwable) {
            retryChallengeId?.let { challengeId ->
                runCatching { captcha.invalidatePreparation(accountId, challengeId) }
                    .onFailure { cleanup -> recoveryError.addSuppressed(cleanup) }
            }
            if (recoveryError.isHofSessionExpired()) {
                terminal.finishLoginRequired(accountId, claim.token)
            } else {
                scheduleFailure(accountId, claim, recoveryError)
            }
        }
    }

    private fun scheduleFailure(accountId: Long, claim: CaptchaPassMaintenanceClaim, error: Throwable) {
        val reason = error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName
        maintenance.scheduleRetry(accountId, claim.token, reason)
        log.warn("Pass maintenance failed accountId={} errorType={}", accountId, error.javaClass.name, error)
    }

    private fun Throwable.isHofSessionExpired(): Boolean =
        generateSequence(this) { it.cause }
            .filterIsInstance<ApiException>()
            .any { it.errorCode == ErrorCode.HOF_SESSION_EXPIRED }

    companion object {
        const val REQUIRED_PASS_HTML = "<div id='menu'><font color='red'>통행증</font></div>"
        private const val UNKNOWN_STATE_REASON = "pass-state-unknown"
    }
}
