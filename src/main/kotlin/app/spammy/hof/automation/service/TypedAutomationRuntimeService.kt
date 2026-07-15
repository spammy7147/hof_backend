package app.spammy.hof.automation.service

import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.automation.entity.*
import app.spammy.hof.automation.repository.*
import app.spammy.hof.common.time.TimeProvider
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

sealed interface TypedRuntimeClaim {
    data object Inactive : TypedRuntimeClaim
    data object Busy : TypedRuntimeClaim
    data class Acquired(val token: String, val preparedAction: TypedAutomationActionRunEntity? = null) : TypedRuntimeClaim
    data class AmbiguousRecovered(val message: String) : TypedRuntimeClaim
}

@Service
class TypedAutomationRuntimeService(
    private val accountQueryRepository: AccountQueryRepository,
    private val queryRepository: TypedAutomationQueryRepository,
    private val stateRepository: TypedAutomationRuntimeStateCommandRepository,
    private val actionRepository: TypedAutomationActionRunCommandRepository,
    private val codec: StoredTypedAutomationActionCodec,
    private val timeProvider: TimeProvider,
    private val dailyPreflight: AutomationDailyPreflight,
    private val wakeups: AutomationAfterCommitWakeupService,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun start(accountId: Long): Boolean {
        val now = timeProvider.now()
        val state = queryRepository.lockRuntimeState(accountId)
        if (state == null) {
            val account = accountQueryRepository.findByIdForUpdate(accountId) ?: error("Account $accountId does not exist.")
            stateRepository.save(TypedAutomationRuntimeStateEntity(accountId, account, TypedAutomationLifecycle.RUNNING, createdAt = now, updatedAt = now))
        } else {
            if (state.lifecycleStatus == TypedAutomationLifecycle.STOPPED) return false
            state.lifecycleStatus = TypedAutomationLifecycle.RUNNING
            state.stopReason = null
            state.retryAttempt = 0
            state.nextAttemptAt = null
            state.updatedAt = now
        }
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = wakeups.wake(accountId, "TYPED_AUTOMATION_STARTED")
        })
        return true
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun resume(accountId: Long) {
        dailyPreflight.resume(accountId)
        resumeState(accountId)
        wakeups.wake(accountId, "TYPED_AUTOMATION_RESUMED")
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun resumeState(accountId: Long) {
        val state = queryRepository.lockRuntimeState(accountId)
        if (state == null) {
            val now = timeProvider.now()
            val account = accountQueryRepository.findByIdForUpdate(accountId) ?: error("Account $accountId does not exist.")
            stateRepository.save(TypedAutomationRuntimeStateEntity(accountId, account, TypedAutomationLifecycle.RUNNING, createdAt = now, updatedAt = now))
            return
        }
        val now = timeProvider.now()
        state.lifecycleStatus = TypedAutomationLifecycle.RUNNING; state.stopReason = null
        state.retryAttempt = 0; state.nextAttemptAt = null; state.leaseToken = null; state.leaseUntil = null; state.updatedAt = now
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun pause(accountId: Long) {
        queryRepository.lockRuntimeState(accountId)?.let {
            it.lifecycleStatus = TypedAutomationLifecycle.PAUSED
            it.stopReason = null
            it.leaseToken = null
            it.leaseUntil = null
            it.updatedAt = timeProvider.now()
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun stop(accountId: Long, reason: AutomationStopReason) {
        queryRepository.lockRuntimeState(accountId)?.let { stopState(it, reason, timeProvider.now()) }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun claim(accountId: Long): TypedRuntimeClaim {
        val state = queryRepository.lockRuntimeState(accountId) ?: return TypedRuntimeClaim.Inactive
        if (state.lifecycleStatus != TypedAutomationLifecycle.RUNNING) return TypedRuntimeClaim.Inactive
        val now = timeProvider.now()
        if (state.nextAttemptAt?.isAfter(now) == true) return TypedRuntimeClaim.Busy
        if (state.leaseUntil?.isAfter(now) == true) return TypedRuntimeClaim.Busy
        val active = queryRepository.findActiveTypedAction(accountId)
        if (active?.status == TypedAutomationActionStatus.SUBMITTING) {
            active.status = TypedAutomationActionStatus.AMBIGUOUS
            active.finishedAt = now
            active.updatedAt = now
            stopState(state, AutomationStopReason.NETWORK, now)
            return TypedRuntimeClaim.AmbiguousRecovered("A submitted action lost its lease; outcome is ambiguous.")
        }
        val token = UUID.randomUUID().toString()
        state.leaseToken = token
        state.leaseUntil = now.plus(LEASE_DURATION)
        state.updatedAt = now
        active?.leaseToken = token
        active?.updatedAt = now
        return TypedRuntimeClaim.Acquired(token, active)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun prepare(accountId: Long, token: String, action: StoredTypedAutomationActionV1): TypedAutomationActionRunEntity? {
        val state = fencedState(accountId, token) ?: return null
        queryRepository.findActiveTypedAction(accountId)?.let { return it }
        val entry = queryRepository.findEntry(accountId, action.entryId) ?: return null
        val encoded = codec.encode(action)
        val now = timeProvider.now()
        state.updatedAt = now
        return actionRepository.save(
            TypedAutomationActionRunEntity(
                account = entry.account, entry = entry, executionIdentity = action.executionIdentity,
                actionKind = action.payload.kind(), schemaVersion = 1, payloadJson = encoded.json,
                actionFingerprint = encoded.fingerprint, status = TypedAutomationActionStatus.PREPARED,
                leaseToken = token, createdAt = now, updatedAt = now,
            ),
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markSubmitting(accountId: Long, token: String, actionId: Long): Boolean {
        fencedState(accountId, token) ?: return false
        val action = queryRepository.lockTypedAction(actionId) ?: return false
        if (action.account.id != accountId || action.leaseToken != token || action.status != TypedAutomationActionStatus.PREPARED) return false
        val now = timeProvider.now()
        action.status = TypedAutomationActionStatus.SUBMITTING
        action.submittedAt = now
        action.updatedAt = now
        return true
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun succeed(accountId: Long, token: String, actionId: Long): Boolean = finish(accountId, token, actionId, TypedAutomationActionStatus.SUCCEEDED, null)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun stop(accountId: Long, token: String, actionId: Long?, reason: AutomationStopReason, message: String): Boolean {
        val state = fencedState(accountId, token) ?: return false
        val now = timeProvider.now()
        actionId?.let { id -> queryRepository.lockTypedAction(id)?.takeIf { it.leaseToken == token }?.apply {
            status = if (status == TypedAutomationActionStatus.SUBMITTING) TypedAutomationActionStatus.AMBIGUOUS else TypedAutomationActionStatus.FAILED
            lastError = message.take(2000); finishedAt = now; updatedAt = now
        } }
        stopState(state, reason, now)
        return true
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun scheduleSafeRetry(accountId: Long, token: String, message: String): Instant? {
        val state = fencedState(accountId, token) ?: return null
        val now = timeProvider.now()
        state.retryAttempt += 1
        state.leaseToken = null; state.leaseUntil = null; state.updatedAt = now
        if (state.retryAttempt >= 4) {
            stopState(state, AutomationStopReason.NETWORK, now)
            return null
        }
        return now.plusSeconds(RETRY_SECONDS[state.retryAttempt - 1]).also { state.nextAttemptAt = it }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun release(accountId: Long, token: String, nextRunAt: Instant? = null): Boolean {
        val state = fencedState(accountId, token) ?: return false
        state.leaseToken = null; state.leaseUntil = null; state.nextAttemptAt = nextRunAt; state.updatedAt = timeProvider.now()
        return true
    }

    private fun finish(accountId: Long, token: String, actionId: Long, status: TypedAutomationActionStatus, error: String?): Boolean {
        val state = fencedState(accountId, token) ?: return false
        val action = queryRepository.lockTypedAction(actionId) ?: return false
        if (action.account.id != accountId || action.leaseToken != token || action.status != TypedAutomationActionStatus.SUBMITTING) return false
        val now = timeProvider.now()
        action.status = status; action.lastError = error; action.finishedAt = now; action.updatedAt = now
        state.retryAttempt = 0; state.nextAttemptAt = null; state.leaseToken = null; state.leaseUntil = null; state.updatedAt = now
        return true
    }

    private fun fencedState(accountId: Long, token: String) = queryRepository.lockRuntimeState(accountId)
        ?.takeIf { it.lifecycleStatus == TypedAutomationLifecycle.RUNNING && it.leaseToken == token }

    private fun stopState(state: TypedAutomationRuntimeStateEntity, reason: AutomationStopReason, now: Instant) {
        state.lifecycleStatus = TypedAutomationLifecycle.STOPPED; state.stopReason = reason.name
        state.nextAttemptAt = null; state.leaseToken = null; state.leaseUntil = null; state.updatedAt = now
    }

    private fun StoredTypedActionPayload.kind(): String = when (this) {
        is StoredTypedActionPayload.QuestClaim -> "QUEST_CLAIM"
        is StoredTypedActionPayload.QuestAccept -> "QUEST_ACCEPT"
        is StoredTypedActionPayload.QuestBattle -> "QUEST_BATTLE"
        is StoredTypedActionPayload.BattleMap -> "BATTLE_MAP"
        is StoredTypedActionPayload.AdventureMap -> "ADVENTURE_MAP"
    }

    companion object {
        private val LEASE_DURATION = Duration.ofMinutes(5)
        private val RETRY_SECONDS = listOf(10L, 30L, 60L)
    }
}
