package app.spammy.hof.automation.service

import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.automation.entity.TypedAutomationLifecycle
import app.spammy.hof.automation.entity.TypedAutomationActionStatus
import app.spammy.hof.automation.entity.TypedAutomationRuntimeStateEntity
import app.spammy.hof.automation.outbox.AutomationOutboxService
import app.spammy.hof.automation.repository.AdventureDailyPreflightQueryRepository
import app.spammy.hof.automation.repository.AdventureDailyPreflightStateCommandRepository
import app.spammy.hof.automation.repository.TypedAutomationQueryRepository
import app.spammy.hof.automation.repository.TypedAutomationRuntimeStateCommandRepository
import app.spammy.hof.common.time.TimeProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Core typed lifecycle transition. Every mutation joins the caller's transaction so typed runtime,
 * preflight reset, and durable wake outbox are one atomic commit.
 */
@Service
class TypedAutomationLifecycleBridge(
    private val accounts: AccountQueryRepository,
    private val typed: TypedAutomationQueryRepository,
    private val states: TypedAutomationRuntimeStateCommandRepository,
    private val preflight: AdventureDailyPreflightQueryRepository,
    private val preflightStates: AdventureDailyPreflightStateCommandRepository,
    private val outbox: AutomationOutboxService,
    private val timeProvider: TimeProvider,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun start(accountId: Long, wakeReason: String): Boolean {
        val account = accounts.findByIdForUpdate(accountId) ?: error("Account $accountId does not exist.")
        if (!typed.hasTypedAutomation(accountId)) {
            outbox.enqueue(accountId, wakeReason)
            return true
        }
        val now = timeProvider.now()
        val state = typed.lockRuntimeState(accountId)
        if (state?.lifecycleStatus == TypedAutomationLifecycle.STOPPED) return false
        if (state == null) {
            states.save(TypedAutomationRuntimeStateEntity(accountId, account, TypedAutomationLifecycle.RUNNING, createdAt = now, updatedAt = now))
        } else {
            state.lifecycleStatus = TypedAutomationLifecycle.RUNNING
            clearRuntime(state, now)
        }
        outbox.enqueue(accountId, wakeReason)
        return true
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun pause(accountId: Long, wakeReason: String) {
        val account = accounts.findByIdForUpdate(accountId) ?: error("Account $accountId does not exist.")
        val state = typed.lockRuntimeState(accountId)
        if (state == null && typed.hasTypedAutomation(accountId)) {
            val now = timeProvider.now()
            states.save(TypedAutomationRuntimeStateEntity(accountId, account, TypedAutomationLifecycle.PAUSED, createdAt = now, updatedAt = now))
        } else state?.let {
            if (it.lifecycleStatus != TypedAutomationLifecycle.STOPPED) {
                if (typed.findOpenRaidCycle(accountId) != null) {
                    it.lifecycleStatus = TypedAutomationLifecycle.DRAINING
                    it.requestedLifecycle = TypedAutomationLifecycle.PAUSED
                    it.updatedAt = timeProvider.now()
                    outbox.enqueue(accountId, wakeReason)
                    return
                }
                it.lifecycleStatus = TypedAutomationLifecycle.PAUSED
                it.requestedLifecycle = null
                it.stopReason = null
                it.stopActionId = null
                it.nextAttemptAt = null
                it.waitReason = null
                it.leaseToken = null
                it.leaseUntil = null
                it.updatedAt = timeProvider.now()
            }
        }
        outbox.enqueue(accountId, wakeReason)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun resume(accountId: Long, wakeReason: String) {
        val account = accounts.findByIdForUpdate(accountId) ?: error("Account $accountId does not exist.")
        val now = timeProvider.now()
        if (typed.hasTypedAutomation(accountId)) {
            val state = typed.lockRuntimeState(accountId)
            if (state == null) {
                states.save(TypedAutomationRuntimeStateEntity(accountId, account, TypedAutomationLifecycle.RUNNING, createdAt = now, updatedAt = now))
            } else {
                state.lifecycleStatus = TypedAutomationLifecycle.RUNNING
                clearRuntime(state, now)
            }
            clearPreflight(accountId, now)
        }
        outbox.enqueue(accountId, wakeReason)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun resumeIfStoppedForCaptcha(accountId: Long, wakeReason: String): Boolean {
        accounts.findByIdForUpdate(accountId) ?: error("Account $accountId does not exist.")
        val state = typed.lockRuntimeState(accountId) ?: return false
        if (state.lifecycleStatus != TypedAutomationLifecycle.STOPPED ||
            state.stopReason != AutomationStopReason.CAPTCHA.name
        ) {
            return false
        }
        val now = timeProvider.now()
        state.lifecycleStatus = TypedAutomationLifecycle.RUNNING
        clearRuntime(state, now)
        clearPreflight(accountId, now)
        outbox.enqueue(accountId, wakeReason)
        return true
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun stop(accountId: Long, reason: AutomationStopReason, wakeReason: String) {
        require(reason == AutomationStopReason.MANUAL_STOP) {
            "Only an explicit user request may stop typed automation."
        }
        val account = accounts.findByIdForUpdate(accountId) ?: error("Account $accountId does not exist.")
        val now = timeProvider.now()
        val state = typed.lockRuntimeState(accountId)
        if (state?.lifecycleStatus == TypedAutomationLifecycle.STOPPED && state.stopReason == reason.name) return
        if (state == null && typed.hasTypedAutomation(accountId)) {
            states.save(TypedAutomationRuntimeStateEntity(accountId, account, TypedAutomationLifecycle.STOPPED, reason.name, createdAt = now, updatedAt = now))
        } else state?.let {
            if (typed.findOpenRaidCycle(accountId) != null) {
                it.lifecycleStatus = TypedAutomationLifecycle.DRAINING
                it.requestedLifecycle = TypedAutomationLifecycle.STOPPED
                it.stopReason = null
                it.updatedAt = now
                outbox.enqueue(accountId, wakeReason)
                return
            }
            discardActiveActionForFreshRestart(accountId, now)
            it.lifecycleStatus = TypedAutomationLifecycle.STOPPED
            it.requestedLifecycle = null
            it.stopReason = reason.name
            it.stopActionId = null
            it.nextAttemptAt = null
            it.waitReason = null
            it.leaseToken = null
            it.leaseUntil = null
            it.updatedAt = now
        }
        outbox.enqueue(accountId, wakeReason)
    }

    /**
     * 수동 정지는 일시정지와 달리 이전 실행을 이어받지 않는다. 제출 여부가 불명확한 작업은 이력상
     * AMBIGUOUS로 보존하고, 아직 제출하지 않은 작업은 FAILED로 닫아 다음 재개가 최신 화면부터 판단하게 한다.
     */
    private fun discardActiveActionForFreshRestart(accountId: Long, now: java.time.Instant) {
        val active = typed.findActiveTypedAction(accountId) ?: return
        val action = typed.lockTypedAction(active.id)
            ?.takeIf { it.account.id == accountId }
            ?: return
        action.status = when (action.status) {
            TypedAutomationActionStatus.PREPARED -> TypedAutomationActionStatus.FAILED
            TypedAutomationActionStatus.SUBMITTING,
            TypedAutomationActionStatus.RECONCILING,
            -> TypedAutomationActionStatus.AMBIGUOUS
            else -> return
        }
        action.nextAttemptAt = null
        action.lastError = "사용자 정지로 이전 작업을 종료했습니다. 다음 시작에서 최신 상태를 다시 판단합니다."
        action.finishedAt = now
        action.updatedAt = now
    }

    private fun clearRuntime(state: TypedAutomationRuntimeStateEntity, now: java.time.Instant) {
        state.stopReason = null
        state.stopActionId = null
        state.retryAttempt = 0
        state.nextAttemptAt = null
        state.waitReason = null
        state.leaseToken = null
        state.leaseUntil = null
        state.warningText = null
        state.lastError = null
        state.requestedLifecycle = null
        state.updatedAt = now
    }

    private fun clearPreflight(accountId: Long, now: java.time.Instant) {
        preflight.findState(accountId)?.let { value ->
            value.failedAttempts = 0
            value.nextAttemptAt = null
            value.stopReason = null
            value.inFlightToken = null
            value.inFlightUntil = null
            value.updatedAt = now
            preflightStates.save(value)
        }
    }
}
