package app.spammy.hof.automation.service

import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.automation.entity.TypedAutomationLifecycle
import app.spammy.hof.automation.entity.TypedAutomationActionStatus
import app.spammy.hof.automation.entity.TypedAutomationRuntimeStateEntity
import app.spammy.hof.automation.entity.AutomationWorkStatus
import app.spammy.hof.automation.outbox.AutomationOutboxService
import app.spammy.hof.automation.outbox.AutomationOutboxQueryRepository
import app.spammy.hof.automation.repository.AdventureDailyPreflightQueryRepository
import app.spammy.hof.automation.repository.AdventureDailyPreflightStateCommandRepository
import app.spammy.hof.automation.repository.AutomationWorkSessionCommandRepository
import app.spammy.hof.automation.repository.AutomationWorkSessionQueryRepository
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
    private val outboxQuery: AutomationOutboxQueryRepository,
    private val workSessions: AutomationWorkSessionQueryRepository,
    private val workSessionCommands: AutomationWorkSessionCommandRepository,
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
        if (state == null) {
            states.save(TypedAutomationRuntimeStateEntity(accountId, account, TypedAutomationLifecycle.RUNNING, createdAt = now, updatedAt = now))
        } else {
            state.lifecycleStatus = TypedAutomationLifecycle.RUNNING
            clearRuntime(state, now)
        }
        clearPreflight(accountId, now)
        makeParkedRaidCheckDue(accountId, now)
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
                val active = typed.findActiveTypedAction(accountId)
                if (active?.status in setOf(TypedAutomationActionStatus.SUBMITTING, TypedAutomationActionStatus.RECONCILING)) {
                    it.lifecycleStatus = TypedAutomationLifecycle.DRAINING
                    it.requestedLifecycle = TypedAutomationLifecycle.PAUSED
                    it.updatedAt = timeProvider.now()
                    outbox.enqueue(accountId, wakeReason)
                    return
                }
                discardPreparedActionForPause(accountId, timeProvider.now())
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
            makeParkedRaidCheckDue(accountId, now)
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
        makeParkedRaidCheckDue(accountId, now)
        outbox.enqueue(accountId, wakeReason)
        return true
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun wakeFreshAfterCaptcha(accountId: Long, wakeReason: String): Boolean {
        accounts.findByIdForUpdate(accountId) ?: error("Account $accountId does not exist.")
        val state = typed.lockRuntimeState(accountId) ?: return false
        if (state.lifecycleStatus != TypedAutomationLifecycle.RUNNING) return false
        val now = timeProvider.now()
        when (discardActiveBattleActionAfterCaptcha(accountId, now)) {
            CaptchaActionDisposition.BATTLE_DISCARDED -> clearRuntime(state, now)
            CaptchaActionDisposition.NO_ACTIVE_ACTION -> {
                state.nextAttemptAt = null
                state.waitReason = null
                state.updatedAt = now
            }
            CaptchaActionDisposition.NON_BATTLE_PRESERVED -> Unit
        }
        clearPreflight(accountId, now)
        makeParkedRaidCheckDue(accountId, now)
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
        if (state == null && typed.hasTypedAutomation(accountId)) {
            states.save(TypedAutomationRuntimeStateEntity(accountId, account, TypedAutomationLifecycle.STOPPED, reason.name, createdAt = now, updatedAt = now))
        } else state?.let {
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
        clearPreflight(accountId, now)
        typed.findOpenRaidCycle(accountId)?.apply {
            clearBattleRecovery()
            updatedAt = now
        }
        stopOpenWorkSessions(accountId, now)
        outboxQuery.deleteUnpublishedForAccount(accountId)
    }

    private fun discardPreparedActionForPause(accountId: Long, now: java.time.Instant) {
        val active = typed.findActiveTypedAction(accountId)
            ?.takeIf { it.status == TypedAutomationActionStatus.PREPARED }
            ?: return
        val action = typed.lockTypedAction(active.id)
            ?.takeIf { it.account.id == accountId && it.status == TypedAutomationActionStatus.PREPARED }
            ?: return
        action.status = TypedAutomationActionStatus.FAILED
        action.nextAttemptAt = null
        action.lastError = "일시정지 전에 대기 중이던 작업을 종료했습니다. 실행 시 최신 상태를 다시 판단합니다."
        action.finishedAt = now
        action.updatedAt = now
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

    private fun discardActiveBattleActionAfterCaptcha(
        accountId: Long,
        now: java.time.Instant,
    ): CaptchaActionDisposition {
        val active = typed.findActiveTypedAction(accountId)
            ?: return CaptchaActionDisposition.NO_ACTIVE_ACTION
        if (active.actionKind !in BATTLE_ACTION_KINDS) {
            return CaptchaActionDisposition.NON_BATTLE_PRESERVED
        }
        val action = typed.lockTypedAction(active.id)
            ?.takeIf {
                it.account.id == accountId &&
                    it.actionKind in BATTLE_ACTION_KINDS &&
                    it.status in setOf(
                        TypedAutomationActionStatus.PREPARED,
                        TypedAutomationActionStatus.SUBMITTING,
                        TypedAutomationActionStatus.RECONCILING,
                    )
            }
            ?: return CaptchaActionDisposition.NO_ACTIVE_ACTION
        action.status = TypedAutomationActionStatus.FAILED
        action.nextAttemptAt = null
        action.lastError = "캡차 해소 뒤 저장된 전투를 종료했습니다. 최신 HOF 상태에서 다시 판단합니다."
        action.finishedAt = now
        action.updatedAt = now
        return CaptchaActionDisposition.BATTLE_DISCARDED
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

    private fun stopOpenWorkSessions(accountId: Long, now: java.time.Instant) {
        workSessions.lockOpen(accountId).forEach { session ->
            session.transitionTo(AutomationWorkStatus.STOPPED)
            session.nextCheckAt = null
            session.finishedAt = now
            session.updatedAt = now
            workSessionCommands.save(session)
        }
    }

    private fun makeParkedRaidCheckDue(accountId: Long, now: java.time.Instant) {
        typed.findOpenRaidCycle(accountId)?.takeIf { it.battleRecoveryChainId != null }?.let { cycle ->
            cycle.battleRecoveryNextCheckAt = now
            cycle.updatedAt = now
        }
        workSessions.lockOpen(accountId)
            .filter { session ->
                session.workType == app.spammy.hof.automation.entity.AutomationWorkType.RAID &&
                    session.status in setOf(
                        AutomationWorkStatus.WAITING_COOLDOWN,
                        AutomationWorkStatus.WAITING_RESOURCE,
                    )
            }
            .forEach { session ->
                session.nextCheckAt = now
                session.updatedAt = now
                workSessionCommands.save(session)
            }
    }

    private companion object {
        enum class CaptchaActionDisposition {
            BATTLE_DISCARDED,
            NON_BATTLE_PRESERVED,
            NO_ACTIVE_ACTION,
        }

        val BATTLE_ACTION_KINDS = setOf("QUEST_BATTLE", "BATTLE_MAP", "ADVENTURE_MAP")
    }
}
