package app.spammy.hof.automation.service

import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.automation.entity.TypedAutomationLifecycle
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
