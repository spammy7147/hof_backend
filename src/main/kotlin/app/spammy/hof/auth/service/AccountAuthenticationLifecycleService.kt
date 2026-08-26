package app.spammy.hof.auth.service

import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.auth.entity.AccountAuthExecutionStateEntity
import app.spammy.hof.auth.repository.AccountAuthExecutionStateCommandRepository
import app.spammy.hof.auth.repository.AccountAuthExecutionStateQueryRepository
import app.spammy.hof.auth.repository.RefreshTokenQueryRepository
import app.spammy.hof.automation.service.TypedAutomationLifecycleBridge
import app.spammy.hof.captcha.service.CaptchaPassMaintenanceService
import app.spammy.hof.common.time.TimeProvider
import java.time.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 앱 로그인 세션을 계정 전역 자동화와 통행증 실행 권한으로 투영한다. */
@Service
class AccountAuthenticationLifecycleService(
    private val accounts: AccountQueryRepository,
    private val states: AccountAuthExecutionStateQueryRepository,
    private val commands: AccountAuthExecutionStateCommandRepository,
    private val refreshTokens: RefreshTokenQueryRepository,
    private val automation: TypedAutomationLifecycleBridge,
    private val passes: CaptchaPassMaintenanceService,
    private val timeProvider: TimeProvider,
) {
    @Transactional
    fun suspendIfNoActiveSessions(accountId: Long): Boolean {
        val now = timeProvider.now()
        val account = accounts.findByIdForUpdate(accountId) ?: return false
        if (refreshTokens.countActiveByAccountId(accountId, now) > 0) return false
        val state = states.findByAccountId(accountId) ?: AccountAuthExecutionStateEntity(
            accountId = accountId,
            account = account,
            updatedAt = now,
        )
        if (state.suspended) return false
        state.suspended = true
        state.suspendedAt = now
        state.updatedAt = now
        commands.save(state)
        automation.suspendForAuthentication(accountId, LAST_SESSION_REASON)
        passes.suspendForAuthentication(accountId)
        return true
    }

    @Transactional
    fun activate(accountId: Long, newLoginFamily: Boolean) {
        val now = timeProvider.now()
        val account = accounts.findByIdForUpdate(accountId) ?: return
        val existing = states.findByAccountId(accountId)
        val shouldResumeDomains = existing == null || existing.suspended
        val state = existing ?: AccountAuthExecutionStateEntity(
            accountId = accountId,
            account = account,
            suspended = false,
            updatedAt = now,
        )
        state.suspended = false
        state.suspendedAt = null
        state.updatedAt = now
        commands.save(state)
        if (shouldResumeDomains) {
            automation.resumeAfterAuthentication(accountId, SESSION_ACTIVATED_REASON)
        }
        if (shouldResumeDomains || newLoginFamily) {
            passes.resumeAfterAuthentication(accountId)
        }
    }

    @Transactional(readOnly = true)
    fun findNaturallyExpiredAccountIds(now: Instant): List<Long> =
        states.findUnsuspendedAccountIdsWithoutActiveSession(now)

    companion object {
        const val LAST_SESSION_REASON = "LAST_APP_SESSION_ENDED"
        const val SESSION_ACTIVATED_REASON = "APP_SESSION_ACTIVATED"
    }
}
