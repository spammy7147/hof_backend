package app.spammy.hof.captcha.service

import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.captcha.dto.CaptchaPassMaintenanceResponse
import app.spammy.hof.captcha.dto.CaptchaPassMaintenanceLifecycleState
import app.spammy.hof.captcha.entity.CaptchaPassMaintenanceEntity
import app.spammy.hof.captcha.repository.CaptchaPassMaintenanceCommandRepository
import app.spammy.hof.captcha.repository.CaptchaPassMaintenanceQueryRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.jsoup.Jsoup
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** 모든 HOF 응답에서 계정 전역 통행증 상태를 관측하고 자동 갱신 정책 상태를 소유한다. */
@Service
class CaptchaPassMaintenanceService(
    private val accounts: AccountQueryRepository,
    private val queries: CaptchaPassMaintenanceQueryRepository,
    private val commands: CaptchaPassMaintenanceCommandRepository,
    private val parser: CaptchaChallengeParser,
    private val timeProvider: TimeProvider,
    private val events: ApplicationEventPublisher,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun observe(
        accountId: Long,
        html: String,
        requestStartedAt: Instant,
        responseObservedAt: Instant,
    ): Boolean {
        val document = Jsoup.parse(html)
        if (document.selectFirst("#menu") == null) return false
        val observed = parser.parseVigilantePassState(document)
        if (!observed.required && observed.remainingSeconds == null) return false

        val account = accounts.findByIdForUpdate(accountId) ?: return false
        val existing = queries.findByAccountId(accountId)
        if (existing?.observedAt?.isAfter(requestStartedAt) == true) return false

        val state = existing ?: CaptchaPassMaintenanceEntity(
            account = account,
            updatedAt = responseObservedAt,
        )
        state.observedAt = requestStartedAt
        state.updatedAt = responseObservedAt
        if (observed.required) {
            state.passState = CaptchaPassMaintenanceEntity.PASS_REQUIRED
            state.remainingSeconds = null
            state.validUntil = null
            if (
                state.enabled &&
                !state.authSuspended &&
                state.lastResult !in PARKED_RESULTS
            ) {
                state.nextRefreshAt = responseObservedAt
            }
        } else {
            val remaining = requireNotNull(observed.remainingSeconds)
            val validUntil = responseObservedAt.plusSeconds(remaining.toLong())
            state.passState = CaptchaPassMaintenanceEntity.PASS_VALID
            state.remainingSeconds = remaining
            state.validUntil = validUntil
            if (state.enabled && !state.authSuspended) state.nextRefreshAt = validUntil.plusSeconds(1)
            state.retryCount = 0
            state.lastResult = if (state.lastAttemptAt == null) state.lastResult else RESULT_VALID_CONFIRMED
            state.manualChallengeId = null
            state.notificationKey = null
        }
        commands.save(state)
        if (!observed.required) events.publishEvent(CaptchaPassValidObservedEvent(accountId))
        return true
    }

    @Transactional(readOnly = true)
    fun get(accountId: Long): CaptchaPassMaintenanceResponse {
        accounts.findById(accountId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "HOF 계정을 찾지 못했습니다.")
        return queries.findByAccountId(accountId)
            ?.let { state -> state.toResponse(timeProvider.now()) }
            ?: CaptchaPassMaintenanceResponse(
                enabled = true,
                authSuspended = false,
                passState = CaptchaPassMaintenanceEntity.PASS_UNKNOWN,
                remainingSeconds = null,
                validUntil = null,
                observedAt = null,
                nextRefreshAt = null,
                lastAttemptAt = null,
                lastResult = null,
                manualChallengeId = null,
                lifecycleState = CaptchaPassMaintenanceLifecycleState.UNKNOWN,
                userActionRequired = false,
            )
    }

    @Transactional
    fun setEnabled(accountId: Long, enabled: Boolean): CaptchaPassMaintenanceResponse {
        val now = timeProvider.now()
        val account = accounts.findByIdForUpdate(accountId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "HOF 계정을 찾지 못했습니다.")
        val state = queries.findByAccountId(accountId) ?: CaptchaPassMaintenanceEntity(
            account = account,
            updatedAt = now,
        )
        state.enabled = enabled
        state.updatedAt = now
        if (enabled) {
            state.nextRefreshAt = now
            if (state.lastResult == RESULT_DISABLED) state.lastResult = null
        } else {
            state.nextRefreshAt = null
            if (state.runPhase !in setOf(RUN_SUBMITTING, RUN_RECONCILING)) clearLease(state, now)
            if (state.lastResult != RESULT_MANUAL_REQUIRED) state.lastResult = RESULT_DISABLED
        }
        return commands.save(state).toResponse(now)
    }

    /** 실행 직전 계정 row와 상태 row를 잠그고 동일 계정의 만료 갱신을 한 작업자에게만 임대한다. */
    @Transactional
    fun claimDue(accountId: Long): CaptchaPassMaintenanceClaim? {
        val now = timeProvider.now()
        val account = accounts.findByIdForUpdate(accountId) ?: return null
        val state = queries.findByAccountId(accountId) ?: CaptchaPassMaintenanceEntity(
            account = account,
            nextRefreshAt = now.plusSeconds(bootstrapJitterSeconds(accountId)),
            updatedAt = now,
        ).also(commands::save)
        if (!state.enabled || state.authSuspended) return null

        if (state.nextRefreshAt == null) {
            state.nextRefreshAt = now.plusSeconds(bootstrapJitterSeconds(accountId))
            state.updatedAt = now
            commands.save(state)
            return null
        }
        if (state.nextRefreshAt?.isAfter(now) == true || state.leaseUntil?.isAfter(now) == true) return null

        val token = UUID.randomUUID().toString()
        state.leaseToken = token
        state.leaseUntil = now.plusSeconds(LEASE_SECONDS)
        state.runPhase = RUN_PREPARING
        state.lastAttemptAt = now
        state.updatedAt = now
        commands.save(state)
        return CaptchaPassMaintenanceClaim(accountId, token)
    }

    @Transactional
    fun finishValid(accountId: Long, token: String, renewed: Boolean) {
        withLease(accountId, token) { state, now ->
            state.retryCount = 0
            state.lastResult = if (renewed) RESULT_RENEWED else RESULT_VALID_CONFIRMED
            state.nextRefreshAt = state.validUntil?.plusSeconds(1)
                ?.takeIf { state.enabled && !state.authSuspended }
            clearLease(state, now)
        }
    }

    @Transactional
    fun finishOcrConfigurationRequired(accountId: Long, token: String) {
        withLease(accountId, token) { state, now ->
            state.lastResult = RESULT_OCR_CONFIGURATION_REQUIRED
            state.nextRefreshAt = null
            clearLease(state, now)
        }
    }

    @Transactional
    fun finishManualRequired(accountId: Long, token: String, challengeId: Long): Boolean =
        withLease(accountId, token) { state, now ->
            state.lastResult = RESULT_MANUAL_REQUIRED
            state.manualChallengeId = challengeId
            state.nextRefreshAt = null
            clearLease(state, now)
        }

    @Transactional
    fun finishLoginRequired(accountId: Long, token: String): Boolean =
        withLease(accountId, token) { state, now ->
            state.lastResult = RESULT_HOF_LOGIN_REQUIRED
            state.nextRefreshAt = null
            clearLease(state, now)
        }

    @Transactional
    fun scheduleRetry(accountId: Long, token: String, reason: String) {
        withLease(accountId, token) { state, now ->
            val delay = RETRY_DELAYS_SECONDS.getOrElse(state.retryCount) { RETRY_DELAYS_SECONDS.last() }
            state.retryCount += 1
            state.lastResult = RESULT_RETRY_SCHEDULED
            state.nextRefreshAt = now.plusSeconds(delay).takeIf { state.enabled && !state.authSuspended }
            state.notificationKey = reason.take(100)
            clearLease(state, now)
        }
    }

    @Transactional(readOnly = true)
    fun findDueAccountIds(now: Instant): List<Long> = queries.findDueAccountIds(now)

    @Transactional
    fun beginSubmission(accountId: Long, token: String): Boolean = authorizeSubmission(accountId, token)

    /** challenge 준비를 마치고 OCR 단계에 진입했음을 별도 상태로 노출한다. */
    @Transactional
    fun beginRecognition(accountId: Long, token: String): Boolean {
        accounts.findByIdForUpdate(accountId) ?: return false
        val state = queries.findByAccountId(accountId)
            ?.takeIf {
                it.leaseToken == token &&
                    it.runPhase == RUN_PREPARING &&
                    it.enabled &&
                    !it.authSuspended
            }
            ?: return false
        state.runPhase = RUN_RECOGNIZING
        state.updatedAt = timeProvider.now()
        commands.save(state)
        return true
    }

    /** OCR이 만든 답안을 원격 제출하기 직전마다 설정과 인증 권한을 다시 검사한다. */
    @Transactional
    fun authorizeSubmission(accountId: Long, token: String): Boolean {
        accounts.findByIdForUpdate(accountId) ?: return false
        val state = queries.findByAccountId(accountId)
            ?.takeIf {
                it.leaseToken == token &&
                    it.runPhase in setOf(RUN_PREPARING, RUN_RECOGNIZING, RUN_SUBMITTING) &&
                    it.enabled &&
                    !it.authSuspended
            }
            ?: return false
        if (state.runPhase in setOf(RUN_PREPARING, RUN_RECOGNIZING)) {
            state.runPhase = RUN_SUBMITTING
            state.updatedAt = timeProvider.now()
            commands.save(state)
        }
        return true
    }

    @Transactional
    fun beginReconciliation(accountId: Long, token: String): Boolean {
        accounts.findByIdForUpdate(accountId) ?: return false
        val state = queries.findByAccountId(accountId)
            ?.takeIf {
                it.leaseToken == token &&
                    (
                        it.runPhase == RUN_SUBMITTING ||
                            (it.runPhase in setOf(RUN_PREPARING, RUN_RECOGNIZING) &&
                                it.enabled && !it.authSuspended)
                    )
            }
            ?: return false
        state.runPhase = RUN_RECONCILING
        state.updatedAt = timeProvider.now()
        commands.save(state)
        return true
    }

    /** 제출 직전 권한이 사라졌으면 원격 변경 없이 현재 정책 상태로 임대를 닫는다. */
    @Transactional
    fun finishCancelled(accountId: Long, token: String) {
        withLease(accountId, token) { state, now ->
            state.nextRefreshAt = null
            when {
                state.authSuspended && state.lastResult != RESULT_MANUAL_REQUIRED ->
                    state.lastResult = RESULT_AUTH_SUSPENDED
                !state.enabled && state.lastResult != RESULT_MANUAL_REQUIRED ->
                    state.lastResult = RESULT_DISABLED
            }
            clearLease(state, now)
        }
    }

    @Transactional
    fun finishSuspended(accountId: Long, token: String) {
        withLease(accountId, token) { state, now ->
            if (state.lastResult != RESULT_MANUAL_REQUIRED) state.lastResult = RESULT_AUTH_SUSPENDED
            state.nextRefreshAt = null
            clearLease(state, now)
        }
    }

    @Transactional
    fun suspendForAuthentication(accountId: Long) {
        val now = timeProvider.now()
        val account = accounts.findByIdForUpdate(accountId) ?: return
        val state = queries.findByAccountId(accountId) ?: CaptchaPassMaintenanceEntity(
            account = account,
            updatedAt = now,
        )
        state.authSuspended = true
        state.nextRefreshAt = null
        if (state.lastResult != RESULT_MANUAL_REQUIRED) state.lastResult = RESULT_AUTH_SUSPENDED
        if (state.runPhase !in setOf(RUN_SUBMITTING, RUN_RECONCILING)) clearLease(state, now)
        state.updatedAt = now
        commands.save(state)
    }

    @Transactional
    fun resumeAfterAuthentication(accountId: Long) {
        val now = timeProvider.now()
        val account = accounts.findByIdForUpdate(accountId) ?: return
        val state = queries.findByAccountId(accountId) ?: CaptchaPassMaintenanceEntity(
            account = account,
            updatedAt = now,
        )
        state.authSuspended = false
        if (state.enabled && state.runPhase == null) {
            state.nextRefreshAt = now
        }
        if (state.lastResult == RESULT_AUTH_SUSPENDED) state.lastResult = null
        state.updatedAt = now
        commands.save(state)
    }

    /** 수동 답안은 성공했지만 사후 상태 GET이 실패했을 때 다음 실행도 반드시 상태 조회부터 시작하게 한다. */
    @Transactional
    fun scheduleConfirmationRetry(accountId: Long, reason: String) {
        val now = timeProvider.now()
        val account = accounts.findByIdForUpdate(accountId) ?: return
        val state = queries.findByAccountId(accountId) ?: CaptchaPassMaintenanceEntity(
            account = account,
            updatedAt = now,
        )
        state.manualChallengeId = null
        state.retryCount = 1
        state.lastResult = RESULT_RETRY_SCHEDULED
        state.notificationKey = reason.take(100)
        state.nextRefreshAt = now.plusSeconds(RETRY_DELAYS_SECONDS.first())
            .takeIf { state.enabled && !state.authSuspended }
        // A common answer-completion hook can run while proactive renewal still owns the lease.
        // Its fresh GET is authoritative, so hand the run back to the scheduler instead of
        // letting the coordinator apply a second retry decision to the same failure.
        clearLease(state, now)
        commands.save(state)
    }

    private fun withLease(
        accountId: Long,
        token: String,
        update: (CaptchaPassMaintenanceEntity, Instant) -> Unit,
    ): Boolean {
        accounts.findByIdForUpdate(accountId) ?: return false
        val state = queries.findByAccountId(accountId)?.takeIf { it.leaseToken == token } ?: return false
        update(state, timeProvider.now())
        commands.save(state)
        return true
    }

    private fun clearLease(state: CaptchaPassMaintenanceEntity, now: Instant) {
        state.leaseToken = null
        state.leaseUntil = null
        state.runPhase = null
        state.updatedAt = now
    }

    private fun bootstrapJitterSeconds(accountId: Long): Long =
        Math.floorMod(accountId, BOOTSTRAP_WINDOW_SECONDS).toLong()

    private fun CaptchaPassMaintenanceEntity.toResponse(now: Instant): CaptchaPassMaintenanceResponse {
        val currentRemaining = validUntil?.let { until ->
            Duration.between(now, until).seconds.coerceAtLeast(0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        return CaptchaPassMaintenanceResponse.from(this).copy(
            remainingSeconds = if (passState == CaptchaPassMaintenanceEntity.PASS_VALID) {
                currentRemaining
            } else {
                null
            },
        )
    }

    companion object {
        const val RESULT_VALID_CONFIRMED = "VALID_CONFIRMED"
        const val RESULT_DISABLED = "DISABLED"
        const val RESULT_RENEWED = "RENEWED"
        const val RESULT_OCR_CONFIGURATION_REQUIRED = "OCR_CONFIGURATION_REQUIRED"
        const val RESULT_MANUAL_REQUIRED = "MANUAL_REQUIRED"
        const val RESULT_HOF_LOGIN_REQUIRED = "HOF_LOGIN_REQUIRED"
        const val RESULT_RETRY_SCHEDULED = "RETRY_SCHEDULED"
        const val RESULT_AUTH_SUSPENDED = "AUTH_SUSPENDED"
        const val RUN_PREPARING = "PREPARING"
        const val RUN_RECOGNIZING = "RECOGNIZING"
        const val RUN_SUBMITTING = "SUBMITTING"
        const val RUN_RECONCILING = "RECONCILING"
        private const val LEASE_SECONDS = 120L
        private const val BOOTSTRAP_WINDOW_SECONDS = 300
        private val RETRY_DELAYS_SECONDS = listOf(60L, 300L, 900L)
        private val PARKED_RESULTS = setOf(
            RESULT_MANUAL_REQUIRED,
            RESULT_OCR_CONFIGURATION_REQUIRED,
            RESULT_HOF_LOGIN_REQUIRED,
            RESULT_RETRY_SCHEDULED,
        )
    }
}

data class CaptchaPassMaintenanceClaim(
    val accountId: Long,
    val token: String,
)

data class CaptchaPassValidObservedEvent(val accountId: Long)
