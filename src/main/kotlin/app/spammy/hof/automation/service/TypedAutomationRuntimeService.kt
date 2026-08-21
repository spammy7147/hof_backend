package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.*
import app.spammy.hof.automation.repository.*
import app.spammy.hof.automation.outbox.AutomationOutboxService
import app.spammy.hof.common.time.TimeProvider
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

sealed interface TypedRuntimeClaim {
    data object Inactive : TypedRuntimeClaim
    data object Busy : TypedRuntimeClaim
    data class Acquired(val token: String, val preparedAction: TypedAutomationActionRunEntity? = null) : TypedRuntimeClaim
    data class AmbiguousRecovered(val message: String) : TypedRuntimeClaim
}

@Service
class TypedAutomationRuntimeService(
    private val queryRepository: TypedAutomationQueryRepository,
    private val actionRepository: TypedAutomationActionRunCommandRepository,
    private val codec: StoredTypedAutomationActionCodec,
    private val timeProvider: TimeProvider,
    private val lifecycleBridge: TypedAutomationLifecycleBridge,
    private val outbox: AutomationOutboxService,
) {
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    fun isRunning(accountId: Long): Boolean =
        queryRepository.findRuntimeState(accountId)?.lifecycleStatus in setOf(TypedAutomationLifecycle.RUNNING, TypedAutomationLifecycle.DRAINING)

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    fun isCompletingCurrentAction(accountId: Long): Boolean =
        queryRepository.findRuntimeState(accountId)?.let { state ->
            state.lifecycleStatus == TypedAutomationLifecycle.DRAINING &&
                state.requestedLifecycle == TypedAutomationLifecycle.PAUSED
        } == true

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun start(accountId: Long): Boolean {
        return lifecycleBridge.start(accountId, "TYPED_AUTOMATION_STARTED")
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun resume(accountId: Long) {
        lifecycleBridge.resume(accountId, "TYPED_AUTOMATION_RESUMED")
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun pause(accountId: Long) {
        lifecycleBridge.pause(accountId, "TYPED_AUTOMATION_PAUSED")
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun stop(accountId: Long, reason: AutomationStopReason) {
        require(reason == AutomationStopReason.MANUAL_STOP) {
            "Only an explicit user request may stop typed automation."
        }
        lifecycleBridge.stop(accountId, reason, "TYPED_AUTOMATION_STOPPED")
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun acquire(accountId: Long): TypedRuntimeAcquisition {
        val state = queryRepository.lockRuntimeState(accountId) ?: return TypedRuntimeAcquisition.Inactive
        if (state.lifecycleStatus !in setOf(TypedAutomationLifecycle.RUNNING, TypedAutomationLifecycle.DRAINING)) {
            return TypedRuntimeAcquisition.Inactive
        }
        val now = timeProvider.now()
        if (state.nextAttemptAt?.isAfter(now) == true || state.leaseUntil?.isAfter(now) == true) {
            return TypedRuntimeAcquisition.Busy
        }
        val active = queryRepository.findActiveTypedAction(accountId)
        if (active?.status == TypedAutomationActionStatus.SUBMITTING) {
            active.status = TypedAutomationActionStatus.RECONCILING
            active.finishedAt = null
            active.lastError = "A submitted action lost its lease; verify its authoritative state."
            active.updatedAt = now
        }
        state.nextAttemptAt = null
        state.waitReason = null
        state.stopReason = null
        state.stopActionId = null
        val token = UUID.randomUUID().toString()
        state.leaseToken = token
        state.leaseUntil = now.plus(LEASE_DURATION)
        state.updatedAt = now
        active?.leaseToken = token
        active?.updatedAt = now

        val checkpoint = active?.let { row ->
            val stored = try {
                codec.verifyPersisted(row, accountId)
            } catch (_: RuntimeException) {
                row.status = when (row.status) {
                    TypedAutomationActionStatus.PREPARED -> TypedAutomationActionStatus.FAILED
                    else -> TypedAutomationActionStatus.AMBIGUOUS
                }
                row.lastError = "Stored typed action integrity check failed."
                row.finishedAt = now
                row.updatedAt = now
                return TypedRuntimeAcquisition.RetryScheduled(
                    scheduleAutomaticRetry(state, AutomationStopReason.FATAL, row.lastError!!),
                )
            }
            TypedRuntimeCheckpoint(
                storedAction = stored,
                phase = when (row.status) {
                    TypedAutomationActionStatus.PREPARED -> TypedRuntimeCheckpointPhase.PREPARED
                    TypedAutomationActionStatus.RECONCILING -> TypedRuntimeCheckpointPhase.RECONCILING
                    else -> error("Active action ${row.id} has unsupported checkpoint status ${row.status}.")
                },
                submittedAt = row.submittedAt,
                diagnostic = row.lastError,
            )
        }
        return TypedRuntimeAcquisition.Acquired(
            PersistedTypedRuntimeExecutionRight(accountId, token, active?.id, checkpoint),
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun persistPrepared(
        execution: TypedRuntimeExecutionRight,
        action: StoredTypedAutomationAction,
        warnings: List<String> = emptyList(),
    ): TypedRuntimePreparation {
        val right = execution.persistedRight()
        val state = fencedState(right.accountId, right.leaseToken) ?: return TypedRuntimePreparation.Invalidated
        if (right.checkpoint != null || right.actionId != null) return TypedRuntimePreparation.Ready(right)
        val existing = queryRepository.findActiveTypedAction(right.accountId)
        if (existing != null) return TypedRuntimePreparation.Invalidated
        val entry = queryRepository.findEntry(right.accountId, action.entryId) ?: return TypedRuntimePreparation.Invalidated
        val encoded = codec.encode(action)
        val now = timeProvider.now()
        state.warningText = warnings.takeIf { it.isNotEmpty() }?.joinToString("\n") { sanitizeDiagnostic(it) }
        state.updatedAt = now
        val saved = actionRepository.save(
            TypedAutomationActionRunEntity(
                account = entry.account,
                entry = entry,
                executionIdentity = action.executionIdentity,
                actionKind = action.payload.kind(),
                payloadJson = encoded.json,
                actionFingerprint = encoded.fingerprint,
                status = TypedAutomationActionStatus.PREPARED,
                leaseToken = right.leaseToken,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return TypedRuntimePreparation.Ready(
            PersistedTypedRuntimeExecutionRight(
                right.accountId,
                right.leaseToken,
                saved.id,
                TypedRuntimeCheckpoint(action, TypedRuntimeCheckpointPhase.PREPARED, null, null),
            ),
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun beginSubmission(execution: TypedRuntimeExecutionRight): TypedRuntimeSubmission {
        val right = execution.persistedRight()
        fencedState(right.accountId, right.leaseToken) ?: return TypedRuntimeSubmission.Invalidated
        val actionId = right.actionId ?: return TypedRuntimeSubmission.Invalidated
        val action = queryRepository.lockTypedAction(actionId) ?: return TypedRuntimeSubmission.Invalidated
        if (
            action.account.id != right.accountId ||
            action.leaseToken != right.leaseToken ||
            action.status != TypedAutomationActionStatus.PREPARED
        ) return TypedRuntimeSubmission.Invalidated
        val now = timeProvider.now()
        action.status = TypedAutomationActionStatus.SUBMITTING
        action.nextAttemptAt = null
        action.lastError = null
        action.submittedAt = now
        action.updatedAt = now
        return TypedRuntimeSubmission.Started(now)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun claim(accountId: Long): TypedRuntimeClaim {
        val state = queryRepository.lockRuntimeState(accountId) ?: return TypedRuntimeClaim.Inactive
        if (state.lifecycleStatus !in setOf(TypedAutomationLifecycle.RUNNING, TypedAutomationLifecycle.DRAINING)) return TypedRuntimeClaim.Inactive
        val now = timeProvider.now()
        if (state.nextAttemptAt?.isAfter(now) == true) return TypedRuntimeClaim.Busy
        if (state.leaseUntil?.isAfter(now) == true) return TypedRuntimeClaim.Busy
        val active = queryRepository.findActiveTypedAction(accountId)
        if (active?.status == TypedAutomationActionStatus.SUBMITTING) {
            active.status = TypedAutomationActionStatus.RECONCILING
            active.finishedAt = null
            active.lastError = "A submitted action lost its lease; verify its authoritative state."
            active.updatedAt = now
        }
        state.nextAttemptAt = null
        state.waitReason = null
        state.stopReason = null
        state.stopActionId = null
        val token = UUID.randomUUID().toString()
        state.leaseToken = token
        state.leaseUntil = now.plus(LEASE_DURATION)
        state.updatedAt = now
        active?.leaseToken = token
        active?.updatedAt = now
        return TypedRuntimeClaim.Acquired(token, active)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun prepare(accountId: Long, token: String, action: StoredTypedAutomationAction): TypedAutomationActionRunEntity? {
        val state = fencedState(accountId, token) ?: return null
        queryRepository.findActiveTypedAction(accountId)?.let { return it }
        val entry = queryRepository.findEntry(accountId, action.entryId) ?: return null
        val encoded = codec.encode(action)
        val now = timeProvider.now()
        state.updatedAt = now
        return actionRepository.save(
            TypedAutomationActionRunEntity(
                account = entry.account, entry = entry, executionIdentity = action.executionIdentity,
                actionKind = action.payload.kind(), payloadJson = encoded.json,
                actionFingerprint = encoded.fingerprint, status = TypedAutomationActionStatus.PREPARED,
                leaseToken = token, createdAt = now, updatedAt = now,
            ),
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markSubmitting(accountId: Long, token: String, actionId: Long): Instant? {
        fencedState(accountId, token) ?: return null
        val action = queryRepository.lockTypedAction(actionId) ?: return null
        if (action.account.id != accountId || action.leaseToken != token || action.status != TypedAutomationActionStatus.PREPARED) return null
        val now = timeProvider.now()
        action.status = TypedAutomationActionStatus.SUBMITTING
        action.nextAttemptAt = null
        action.lastError = null
        action.submittedAt = now
        action.updatedAt = now
        return now
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun deferSubmittedAction(
        accountId: Long,
        token: String,
        actionId: Long,
        retryAt: Instant,
        message: String,
    ): Boolean {
        val state = fencedState(accountId, token) ?: return false
        val action = queryRepository.lockTypedAction(actionId) ?: return false
        if (
            action.account.id != accountId ||
            action.leaseToken != token ||
            action.status != TypedAutomationActionStatus.SUBMITTING
        ) return false

        val now = timeProvider.now()
        val diagnostic = sanitizeDiagnostic(message)
        action.status = TypedAutomationActionStatus.PREPARED
        action.retryAttempt += 1
        action.nextAttemptAt = retryAt
        action.submittedAt = null
        action.finishedAt = null
        action.lastError = diagnostic
        action.updatedAt = now

        state.nextAttemptAt = retryAt
        state.waitReason = AutomationWaitReason.HOF_CONNECTION
        state.leaseToken = null
        state.leaseUntil = null
        state.lastError = diagnostic
        state.updatedAt = now
        return true
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markReconcilingAndEnqueueWake(
        accountId: Long,
        token: String,
        actionId: Long,
        message: String,
    ): Boolean {
        val state = fencedState(accountId, token) ?: return false
        val action = queryRepository.lockTypedAction(actionId) ?: return false
        if (
            action.account.id != accountId ||
            action.leaseToken != token ||
            action.status != TypedAutomationActionStatus.SUBMITTING
        ) return false
        val now = timeProvider.now()
        val diagnostic = sanitizeDiagnostic(message)
        action.status = TypedAutomationActionStatus.RECONCILING
        action.nextAttemptAt = null
        action.finishedAt = null
        action.lastError = diagnostic
        action.updatedAt = now
        state.leaseToken = null
        state.leaseUntil = null
        state.nextAttemptAt = null
        state.waitReason = null
        state.lastError = diagnostic
        state.updatedAt = now
        outbox.enqueue(accountId, "TYPED_AMBIGUOUS_RECONCILE")
        return true
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun handoffAmbiguousAction(
        accountId: Long,
        token: String,
        actionId: Long,
        warning: String,
        wakeReason: String,
    ): Boolean {
        val state = fencedState(accountId, token) ?: return false
        val action = queryRepository.lockTypedAction(actionId) ?: return false
        if (
            action.account.id != accountId ||
            action.leaseToken != token ||
            action.status !in setOf(
                TypedAutomationActionStatus.SUBMITTING,
                TypedAutomationActionStatus.RECONCILING,
            )
        ) return false
        val now = timeProvider.now()
        val diagnostic = sanitizeDiagnostic(warning)
        action.status = TypedAutomationActionStatus.AMBIGUOUS
        action.nextAttemptAt = null
        action.finishedAt = now
        action.lastError = diagnostic
        action.updatedAt = now
        state.retryAttempt = 0
        state.nextAttemptAt = null
        state.waitReason = null
        state.leaseToken = null
        state.leaseUntil = null
        state.warningText = diagnostic
        state.lastError = null
        state.stopReason = null
        state.stopActionId = null
        state.updatedAt = now
        val paused = completeRequestedLifecycle(state, now)
        if (!paused && state.lifecycleStatus == TypedAutomationLifecycle.RUNNING) {
            outbox.enqueue(accountId, wakeReason)
        }
        return true
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun retryReconciledSubmission(
        accountId: Long,
        token: String,
        actionId: Long,
        reason: String,
    ): Boolean {
        val state = fencedState(accountId, token) ?: return false
        val action = queryRepository.lockTypedAction(actionId) ?: return false
        if (
            action.account.id != accountId ||
            action.leaseToken != token ||
            action.status != TypedAutomationActionStatus.RECONCILING
        ) return false
        val now = timeProvider.now()
        action.status = TypedAutomationActionStatus.PREPARED
        action.retryAttempt += 1
        action.nextAttemptAt = null
        action.submittedAt = null
        action.finishedAt = null
        action.lastError = null
        action.updatedAt = now
        state.leaseToken = null
        state.leaseUntil = null
        state.nextAttemptAt = null
        state.waitReason = null
        state.lastError = null
        state.updatedAt = now
        outbox.enqueue(accountId, reason)
        return true
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun deferReconciliation(
        accountId: Long,
        token: String,
        actionId: Long,
        retryAt: Instant,
        reason: String,
    ): Boolean {
        val state = fencedState(accountId, token) ?: return false
        val action = queryRepository.lockTypedAction(actionId) ?: return false
        if (
            action.account.id != accountId ||
            action.leaseToken != token ||
            action.status != TypedAutomationActionStatus.RECONCILING
        ) return false
        val now = timeProvider.now()
        val diagnostic = sanitizeDiagnostic(reason)
        action.retryAttempt += 1
        action.nextAttemptAt = retryAt
        action.lastError = diagnostic
        action.updatedAt = now
        state.leaseToken = null
        state.leaseUntil = null
        state.nextAttemptAt = retryAt
        state.waitReason = AutomationWaitReason.HOF_CONNECTION
        state.lastError = diagnostic
        state.updatedAt = now
        return true
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun succeedReconciliation(
        accountId: Long,
        token: String,
        actionId: Long,
        reason: String,
    ): Boolean {
        val state = fencedState(accountId, token) ?: return false
        val action = queryRepository.lockTypedAction(actionId) ?: return false
        if (
            action.account.id != accountId ||
            action.leaseToken != token ||
            action.status != TypedAutomationActionStatus.RECONCILING
        ) return false
        val now = timeProvider.now()
        action.status = TypedAutomationActionStatus.SUCCEEDED
        action.lastError = null
        action.finishedAt = now
        action.updatedAt = now
        state.retryAttempt = 0
        state.nextAttemptAt = null
        state.waitReason = null
        state.leaseToken = null
        state.leaseUntil = null
        state.lastError = null
        state.stopActionId = null
        state.updatedAt = now
        val paused = completeRequestedLifecycle(state, now)
        if (!paused) outbox.enqueue(accountId, reason)
        return true
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun succeed(accountId: Long, token: String, actionId: Long): Boolean = finish(accountId, token, actionId, TypedAutomationActionStatus.SUCCEEDED, null)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun succeedAndEnqueueWake(
        accountId: Long,
        token: String,
        actionId: Long,
        reason: String,
        warnings: List<String>? = emptyList(),
    ): Boolean {
        val succeeded = finish(accountId, token, actionId, TypedAutomationActionStatus.SUCCEEDED, null, warnings)
        if (succeeded && queryRepository.findRuntimeState(accountId)?.lifecycleStatus == TypedAutomationLifecycle.RUNNING) {
            outbox.enqueue(accountId, reason)
        }
        return succeeded
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun stop(accountId: Long, token: String, actionId: Long?, reason: AutomationStopReason, message: String): Boolean {
        require(reason == AutomationStopReason.MANUAL_STOP) {
            "Only an explicit user request may stop typed automation."
        }
        val state = fencedState(accountId, token) ?: return false
        val now = timeProvider.now()
        val stoppedAction = actionId?.let { id ->
            queryRepository.lockTypedAction(id)?.takeIf {
                it.account.id == accountId &&
                    it.leaseToken == token &&
                    it.status in setOf(TypedAutomationActionStatus.PREPARED, TypedAutomationActionStatus.SUBMITTING)
            }
        }
        stoppedAction?.apply {
            status = if (
                status == TypedAutomationActionStatus.SUBMITTING &&
                reason in setOf(AutomationStopReason.NETWORK, AutomationStopReason.CAPTCHA)
            ) {
                TypedAutomationActionStatus.RECONCILING
            } else {
                TypedAutomationActionStatus.FAILED
            }
            lastError = message.take(2000)
            finishedAt = now.takeUnless { status == TypedAutomationActionStatus.RECONCILING }
            updatedAt = now
        }
        state.lastError = sanitizeDiagnostic(message)
        stopState(state, reason, now, stoppedAction?.id)
        return true
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun isolateIntegrityFailureForRetry(accountId: Long, token: String, actionId: Long, message: String): Instant? {
        val state = fencedState(accountId, token) ?: return null
        val action = queryRepository.lockTypedAction(actionId) ?: return null
        if (action.account.id != accountId || action.leaseToken != token) return null
        val terminalStatus = when (action.status) {
            TypedAutomationActionStatus.PREPARED -> TypedAutomationActionStatus.FAILED
            TypedAutomationActionStatus.RECONCILING -> TypedAutomationActionStatus.AMBIGUOUS
            else -> return null
        }
        val now = timeProvider.now()
        action.status = terminalStatus
        action.lastError = message.take(2000)
        action.finishedAt = now
        action.updatedAt = now
        return scheduleAutomaticRetry(state, AutomationStopReason.FATAL, message)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun scheduleSafeRetry(accountId: Long, token: String, message: String): Instant? {
        val state = fencedState(accountId, token) ?: return null
        return scheduleAutomaticRetry(state, AutomationStopReason.NETWORK, message)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun scheduleAutomaticRetry(accountId: Long, reason: AutomationStopReason, message: String): Instant? {
        require(reason != AutomationStopReason.MANUAL_STOP) { "Manual stop cannot be scheduled for retry." }
        val state = queryRepository.lockRuntimeState(accountId)
            ?.takeIf { it.lifecycleStatus in setOf(TypedAutomationLifecycle.RUNNING, TypedAutomationLifecycle.DRAINING) }
            ?: return null
        return scheduleAutomaticRetry(state, reason, message)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun scheduleAutomaticRetry(
        accountId: Long,
        token: String,
        actionId: Long?,
        reason: AutomationStopReason,
        message: String,
    ): Instant? {
        require(reason != AutomationStopReason.MANUAL_STOP) { "Manual stop cannot be scheduled for retry." }
        val state = fencedState(accountId, token) ?: return null
        val action = actionId?.let { id ->
            queryRepository.lockTypedAction(id)?.takeIf {
                it.account.id == accountId &&
                    it.leaseToken == token &&
                    it.status in setOf(
                        TypedAutomationActionStatus.PREPARED,
                        TypedAutomationActionStatus.SUBMITTING,
                        TypedAutomationActionStatus.RECONCILING,
                    )
            }
        }
        val retryAt = scheduleAutomaticRetry(state, reason, message)
        action?.apply {
            if (status == TypedAutomationActionStatus.SUBMITTING) {
                status = TypedAutomationActionStatus.RECONCILING
                submittedAt = submittedAt ?: timeProvider.now()
            }
            retryAttempt = (retryAttempt + 1).coerceAtMost(MAX_RETRY_ATTEMPT)
            nextAttemptAt = retryAt
            lastError = sanitizeDiagnostic(message)
            finishedAt = null
            updatedAt = timeProvider.now()
        }
        return retryAt
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun deferUntil(
        accountId: Long,
        retryAt: Instant,
        reason: AutomationWaitReason,
    ): Boolean {
        val state = queryRepository.lockRuntimeState(accountId)
            ?.takeIf { it.lifecycleStatus in setOf(TypedAutomationLifecycle.RUNNING, TypedAutomationLifecycle.DRAINING) }
            ?: return false
        state.nextAttemptAt = retryAt
        state.waitReason = reason
        state.updatedAt = timeProvider.now()
        return true
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun release(
        accountId: Long,
        token: String,
        nextRunAt: Instant? = null,
        waitReason: AutomationWaitReason? = null,
    ): Boolean {
        return releaseCore(accountId, token, nextRunAt, waitReason, emptyList())
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun releaseWithDiagnostics(accountId: Long, token: String, nextRunAt: Instant?, warnings: List<String>): Boolean {
        return releaseCore(
            accountId,
            token,
            nextRunAt,
            nextRunAt?.let { AutomationWaitReason.SCHEDULED },
            warnings,
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun releaseAndEnqueueWake(accountId: Long, token: String, reason: String): Boolean {
        val released = releaseCore(accountId, token, null, null, emptyList())
        if (released && queryRepository.findRuntimeState(accountId)?.lifecycleStatus == TypedAutomationLifecycle.RUNNING) {
            outbox.enqueue(accountId, reason)
        }
        return released
    }

    private fun releaseCore(
        accountId: Long,
        token: String,
        nextRunAt: Instant?,
        waitReason: AutomationWaitReason?,
        warnings: List<String>,
    ): Boolean {
        require((nextRunAt == null) == (waitReason == null)) {
            "nextRunAt and waitReason must either both be null or both be present."
        }
        val state = fencedState(accountId, token) ?: return false
        val now = timeProvider.now()
        state.leaseToken = null; state.leaseUntil = null; state.nextAttemptAt = nextRunAt; state.waitReason = waitReason
        state.updatedAt = now
        state.warningText = warnings.takeIf { it.isNotEmpty() }?.joinToString("\n") { sanitizeDiagnostic(it) }
        state.lastError = null
        state.retryAttempt = 0
        state.stopReason = null
        state.stopActionId = null
        completeRequestedLifecycle(state, now)
        return true
    }

    private fun completeRequestedLifecycle(state: TypedAutomationRuntimeStateEntity, now: Instant): Boolean {
        val requested = state.requestedLifecycle ?: return false
        if (state.lifecycleStatus != TypedAutomationLifecycle.DRAINING) return false
        state.lifecycleStatus = requested
        state.requestedLifecycle = null
        state.nextAttemptAt = null
        state.waitReason = null
        state.leaseToken = null
        state.leaseUntil = null
        if (requested == TypedAutomationLifecycle.STOPPED) {
            state.stopReason = AutomationStopReason.MANUAL_STOP.name
        }
        state.updatedAt = now
        return true
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun deferForConfiguration(accountId: Long, token: String, warnings: List<String>): Instant? {
        val state = fencedState(accountId, token) ?: return null
        val next = timeProvider.now().plus(CONFIG_RECHECK)
        state.leaseToken = null; state.leaseUntil = null; state.nextAttemptAt = next
        state.waitReason = AutomationWaitReason.SCHEDULED; state.updatedAt = timeProvider.now()
        state.warningText = warnings.joinToString("\n") { sanitizeDiagnostic(it) }
        state.lastError = null
        return next
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordWarnings(accountId: Long, token: String, warnings: List<String>): Boolean {
        val state = fencedState(accountId, token) ?: return false
        state.warningText = warnings.takeIf { it.isNotEmpty() }?.joinToString("\n") { sanitizeDiagnostic(it) }
        state.updatedAt = timeProvider.now()
        return true
    }

    private fun finish(
        accountId: Long,
        token: String,
        actionId: Long,
        status: TypedAutomationActionStatus,
        error: String?,
        warnings: List<String>? = emptyList(),
    ): Boolean {
        val state = fencedState(accountId, token) ?: return false
        val action = queryRepository.lockTypedAction(actionId) ?: return false
        if (action.account.id != accountId || action.leaseToken != token || action.status != TypedAutomationActionStatus.SUBMITTING) return false
        val now = timeProvider.now()
        action.status = status; action.lastError = error; action.finishedAt = now; action.updatedAt = now
        state.retryAttempt = 0; state.nextAttemptAt = null; state.waitReason = null
        state.leaseToken = null; state.leaseUntil = null; state.updatedAt = now
        if (warnings != null) {
            state.warningText = warnings.takeIf { it.isNotEmpty() }?.joinToString("\n") { sanitizeDiagnostic(it) }
        }
        state.lastError = null
        state.stopReason = null
        state.stopActionId = null
        completeRequestedLifecycle(state, now)
        return true
    }

    private fun fencedState(accountId: Long, token: String) = queryRepository.lockRuntimeState(accountId)
        ?.takeIf { it.lifecycleStatus in setOf(TypedAutomationLifecycle.RUNNING, TypedAutomationLifecycle.DRAINING) && it.leaseToken == token }

    private fun stopState(
        state: TypedAutomationRuntimeStateEntity,
        reason: AutomationStopReason,
        now: Instant,
        stopActionId: Long? = null,
    ) {
        state.lifecycleStatus = TypedAutomationLifecycle.STOPPED; state.stopReason = reason.name
        state.stopActionId = stopActionId
        state.nextAttemptAt = null; state.waitReason = null
        state.leaseToken = null; state.leaseUntil = null; state.updatedAt = now
    }

    private fun scheduleAutomaticRetry(
        state: TypedAutomationRuntimeStateEntity,
        reason: AutomationStopReason,
        message: String,
    ): Instant {
        val now = timeProvider.now()
        state.retryAttempt = (state.retryAttempt + 1).coerceAtMost(MAX_RETRY_ATTEMPT)
        val delayIndex = (state.retryAttempt - 1).coerceAtMost(RETRY_SECONDS.lastIndex)
        val retryAt = now.plusSeconds(RETRY_SECONDS[delayIndex])
        state.stopReason = reason.name
        state.stopActionId = null
        state.nextAttemptAt = retryAt
        state.waitReason = AutomationWaitReason.HOF_CONNECTION
        state.leaseToken = null
        state.leaseUntil = null
        state.lastError = sanitizeDiagnostic(message)
        state.updatedAt = now
        return retryAt
    }

    private fun sanitizeDiagnostic(value: String): String = value
        .replace(Regex("(?i)(password|token|cookie|authorization)\\s*[=:]\\s*[^\\s,;]+"), "$1=[redacted]")
        .replace(Regex("[\\r\\n\\t]+"), " ")
        .take(2000)

    private fun TypedRuntimeExecutionRight.persistedRight(): PersistedTypedRuntimeExecutionRight =
        this as? PersistedTypedRuntimeExecutionRight
            ?: throw IllegalArgumentException("Execution right was not issued by this runtime.")

    companion object {
        private val LEASE_DURATION = Duration.ofMinutes(5)
        private val CONFIG_RECHECK = Duration.ofMinutes(5)
        private val RETRY_SECONDS = listOf(10L, 30L, 60L, 300L)
        private const val MAX_RETRY_ATTEMPT = 4
    }
}
