package app.spammy.hof.captcha.dto

import app.spammy.hof.captcha.entity.CaptchaPassMaintenanceEntity
import java.time.Instant

enum class CaptchaPassMaintenanceLifecycleState {
    DISABLED,
    AUTH_SUSPENDED,
    UNKNOWN,
    VALID,
    CHECK_SCHEDULED,
    CHECKING,
    AUTO_RECOGNIZING,
    MANUAL_INPUT_REQUIRED,
    OCR_CONFIGURATION_REQUIRED,
    HOF_LOGIN_REQUIRED,
    CONNECTION_RETRY_WAIT,
}

enum class CaptchaPassMaintenanceLastResult {
    VALID_CONFIRMED,
    DISABLED,
    RENEWED,
    OCR_CONFIGURATION_REQUIRED,
    MANUAL_REQUIRED,
    HOF_LOGIN_REQUIRED,
    RETRY_SCHEDULED,
    AUTH_SUSPENDED,
}

/** 앱 설정과 홈 경고가 공유하는 계정 전역 통행증 자동 갱신 상태다. */
data class CaptchaPassMaintenanceResponse(
    val enabled: Boolean,
    val authSuspended: Boolean,
    val passState: String,
    val remainingSeconds: Int?,
    val validUntil: Instant?,
    val observedAt: Instant?,
    val nextRefreshAt: Instant?,
    val lastAttemptAt: Instant?,
    val lastResult: CaptchaPassMaintenanceLastResult?,
    val manualChallengeId: Long?,
    val lifecycleState: CaptchaPassMaintenanceLifecycleState,
    val userActionRequired: Boolean,
) {
    companion object {
        fun from(entity: CaptchaPassMaintenanceEntity): CaptchaPassMaintenanceResponse =
            CaptchaPassMaintenanceResponse(
                enabled = entity.enabled,
                authSuspended = entity.authSuspended,
                passState = entity.passState,
                remainingSeconds = entity.remainingSeconds,
                validUntil = entity.validUntil,
                observedAt = entity.observedAt,
                nextRefreshAt = entity.nextRefreshAt,
                lastAttemptAt = entity.lastAttemptAt,
                lastResult = entity.lastResult?.let(CaptchaPassMaintenanceLastResult::valueOf),
                manualChallengeId = entity.manualChallengeId,
                lifecycleState = entity.lifecycleState(),
                userActionRequired = entity.lastResult in USER_ACTION_RESULTS,
            )

        private fun CaptchaPassMaintenanceEntity.lifecycleState(): CaptchaPassMaintenanceLifecycleState = when {
            !enabled -> CaptchaPassMaintenanceLifecycleState.DISABLED
            authSuspended -> CaptchaPassMaintenanceLifecycleState.AUTH_SUSPENDED
            lastResult == "MANUAL_REQUIRED" -> CaptchaPassMaintenanceLifecycleState.MANUAL_INPUT_REQUIRED
            lastResult == "OCR_CONFIGURATION_REQUIRED" ->
                CaptchaPassMaintenanceLifecycleState.OCR_CONFIGURATION_REQUIRED
            lastResult == "HOF_LOGIN_REQUIRED" -> CaptchaPassMaintenanceLifecycleState.HOF_LOGIN_REQUIRED
            runPhase == "RECOGNIZING" -> CaptchaPassMaintenanceLifecycleState.AUTO_RECOGNIZING
            runPhase in setOf("PREPARING", "SUBMITTING", "RECONCILING") ->
                CaptchaPassMaintenanceLifecycleState.CHECKING
            lastResult == "RETRY_SCHEDULED" -> CaptchaPassMaintenanceLifecycleState.CONNECTION_RETRY_WAIT
            passState == CaptchaPassMaintenanceEntity.PASS_VALID -> CaptchaPassMaintenanceLifecycleState.VALID
            nextRefreshAt != null -> CaptchaPassMaintenanceLifecycleState.CHECK_SCHEDULED
            else -> CaptchaPassMaintenanceLifecycleState.UNKNOWN
        }

        private val USER_ACTION_RESULTS = setOf(
            "MANUAL_REQUIRED",
            "OCR_CONFIGURATION_REQUIRED",
            "HOF_LOGIN_REQUIRED",
        )
    }
}
