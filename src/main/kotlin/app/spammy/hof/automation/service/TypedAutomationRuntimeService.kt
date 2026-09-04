package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.*
import app.spammy.hof.automation.repository.*
import app.spammy.hof.automation.outbox.AutomationOutboxService
import app.spammy.hof.common.time.TimeProvider
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

private data class PersistedTypedRuntimeExecutionRight(
    val accountId: Long,
    val leaseToken: String,
    val actionId: Long?,
    override val checkpoint: TypedRuntimeCheckpoint?,
) : TypedRuntimeExecutionRight

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
        queryRepository.findRuntimeState(accountId)?.let { state ->
            state.lifecycleStatus == TypedAutomationLifecycle.DRAINING ||
                (!state.authSuspended && state.lifecycleStatus == TypedAutomationLifecycle.RUNNING)
        } == true

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
        val active = queryRepository.findActiveTypedAction(accountId)
        if (
            state.authSuspended &&
            !(
                state.lifecycleStatus == TypedAutomationLifecycle.DRAINING &&
                    active?.status in setOf(
                        TypedAutomationActionStatus.SUBMITTING,
                        TypedAutomationActionStatus.RECONCILING,
                    )
            )
        ) {
            return TypedRuntimeAcquisition.Inactive
        }
        val now = timeProvider.now()
        if (state.nextAttemptAt?.isAfter(now) == true || state.leaseUntil?.isAfter(now) == true) {
            return TypedRuntimeAcquisition.Busy
        }
        if (active?.status == TypedAutomationActionStatus.SUBMITTING) {
            active.status = TypedAutomationActionStatus.RECONCILING
            active.reconciliationObservationCount = 0
            active.reconciliationFirstPendingAt = active.submittedAt ?: now
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
            val decoded = try {
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
            val legacySuppressionEpoch = if (row.status == TypedAutomationActionStatus.RECONCILING) {
                decoded.legacySuppressionEpoch(accountId, row)
            } else {
                null
            }
            val stored = decoded.withLegacySuppressionEpoch(legacySuppressionEpoch)
            TypedRuntimeCheckpoint(
                storedAction = stored,
                phase = when (row.status) {
                    TypedAutomationActionStatus.PREPARED -> TypedRuntimeCheckpointPhase.PREPARED
                    TypedAutomationActionStatus.RECONCILING -> TypedRuntimeCheckpointPhase.RECONCILING
                    else -> error("Active action ${row.id} has unsupported checkpoint status ${row.status}.")
                },
                submittedAt = row.submittedAt,
                diagnostic = row.lastError,
                successfulObservationCount = row.reconciliationObservationCount,
                firstPendingAt = row.reconciliationFirstPendingAt,
                legacySuppressionEpoch = legacySuppressionEpoch,
                deferredSubmissionRetry =
                    row.status == TypedAutomationActionStatus.PREPARED &&
                        row.submittedAt == null &&
                        row.retryAttempt > 0 &&
                        row.nextAttemptAt != null,
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
        val entry = (
            queryRepository.findEntryForUpdate(right.accountId, action.entryId)
                ?: queryRepository.findEntry(right.accountId, action.entryId)
            ) ?: return TypedRuntimePreparation.Invalidated
        val encoded = codec.encode(action)
        val now = timeProvider.now()
        state.warningText = warnings.takeIf { it.isNotEmpty() }?.joinToString("\n") { sanitizeDiagnostic(it) }
        state.updatedAt = now
        val saved = actionRepository.save(
            TypedAutomationActionRunEntity(
                account = entry.account,
                entry = entry,
                entryDisplayName = automationEntryDisplayNames(
                    queryRepository.findEntries(right.accountId),
                )[entry.id],
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
        val state = fencedState(right.accountId, right.leaseToken) ?: return TypedRuntimeSubmission.Invalidated
        val actionId = right.actionId ?: return TypedRuntimeSubmission.Invalidated
        val action = queryRepository.lockTypedAction(actionId) ?: return TypedRuntimeSubmission.Invalidated
        if (
            action.account.id != right.accountId ||
            action.leaseToken != right.leaseToken ||
            action.status != TypedAutomationActionStatus.PREPARED
        ) return TypedRuntimeSubmission.Invalidated
        val now = timeProvider.now()
        if (blocksNewSubmission(state)) {
            discardPreparedForLifecycle(state, action, now)
            return TypedRuntimeSubmission.Invalidated
        }
        action.status = TypedAutomationActionStatus.SUBMITTING
        action.nextAttemptAt = null
        action.lastError = null
        action.submittedAt = now
        action.updatedAt = now
        return TypedRuntimeSubmission.Started(now)
    }

    /** START 직접 응답을 확인한 뒤 START 종결과 CATCH 준비를 같은 DB 전이로 보존한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun advanceAppliedActionToPreparedFollowup(
        execution: TypedRuntimeExecutionRight,
        followup: StoredTypedAutomationAction,
    ): TypedRuntimePreparation = advanceActionToPreparedFollowup(
        execution,
        followup,
        TypedAutomationActionStatus.SUCCEEDED,
        null,
    )

    /** 최신 직접 응답이 현재 행동을 대체했을 때 그 행동을 실패로 닫고 복구 행동을 같은 lease에 연결한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun advanceSupersededActionToPreparedFollowup(
        execution: TypedRuntimeExecutionRight,
        followup: StoredTypedAutomationAction,
        reason: String,
    ): TypedRuntimePreparation = advanceActionToPreparedFollowup(
        execution,
        followup,
        TypedAutomationActionStatus.FAILED,
        sanitizeDiagnostic(reason),
    )

    private fun advanceActionToPreparedFollowup(
        execution: TypedRuntimeExecutionRight,
        followup: StoredTypedAutomationAction,
        completedStatus: TypedAutomationActionStatus,
        diagnostic: String?,
    ): TypedRuntimePreparation {
        val right = execution.persistedRight()
        val state = fencedState(right.accountId, right.leaseToken)
            ?: return TypedRuntimePreparation.Invalidated
        val actionId = right.actionId ?: return TypedRuntimePreparation.Invalidated
        val current = queryRepository.lockTypedAction(actionId)
            ?: return TypedRuntimePreparation.Invalidated
        if (
            current.account.id != right.accountId ||
            current.leaseToken != right.leaseToken ||
            current.status != TypedAutomationActionStatus.SUBMITTING ||
            followup.entryId != current.entry?.id
        ) return TypedRuntimePreparation.Invalidated
        val now = timeProvider.now()
        if (blocksNewSubmission(state)) {
            current.status = completedStatus
            current.lastError = diagnostic
            current.finishedAt = now
            current.updatedAt = now
            state.leaseToken = null
            state.leaseUntil = null
            state.nextAttemptAt = null
            state.waitReason = null
            state.updatedAt = now
            completeRequestedLifecycle(state, now)
            return TypedRuntimePreparation.Invalidated
        }
        val entry = queryRepository.findEntry(right.accountId, followup.entryId)
            ?: return TypedRuntimePreparation.Invalidated
        val encoded = codec.encode(followup)
        current.status = completedStatus
        current.lastError = diagnostic
        current.finishedAt = now
        current.updatedAt = now
        val saved = actionRepository.save(
            TypedAutomationActionRunEntity(
                account = entry.account,
                entry = entry,
                executionIdentity = followup.executionIdentity,
                actionKind = followup.payload.kind(),
                payloadJson = encoded.json,
                actionFingerprint = encoded.fingerprint,
                status = TypedAutomationActionStatus.PREPARED,
                leaseToken = right.leaseToken,
                createdAt = now,
                updatedAt = now,
            ),
        )
        state.updatedAt = now
        return TypedRuntimePreparation.Ready(
            PersistedTypedRuntimeExecutionRight(
                right.accountId,
                right.leaseToken,
                saved.id,
                TypedRuntimeCheckpoint(followup, TypedRuntimeCheckpointPhase.PREPARED, null, null),
            ),
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun complete(
        execution: TypedRuntimeExecutionRight,
        outcome: TypedRuntimeOutcome,
    ): TypedRuntimeProjection {
        val right = execution.persistedRight()
        return when (outcome) {
            TypedRuntimeOutcome.Idle -> releaseCore(
                right.accountId,
                right.leaseToken,
                null,
                null,
                emptyList(),
            ).projection()
            is TypedRuntimeOutcome.SelectionChanged -> releaseAndEnqueueWake(
                right.accountId,
                right.leaseToken,
                outcome.wakeReason,
            ).projection()
            is TypedRuntimeOutcome.ScheduledWait -> releaseCore(
                right.accountId,
                right.leaseToken,
                outcome.nextRunAt,
                outcome.waitReason,
                outcome.warnings,
            ).projection(outcome.nextRunAt)
            is TypedRuntimeOutcome.ConfigurationWait -> {
                val next = timeProvider.now().plus(CONFIG_RECHECK)
                releaseCore(
                    right.accountId,
                    right.leaseToken,
                    next,
                    AutomationWaitReason.SCHEDULED,
                    outcome.warnings,
                ).projection(next)
            }
            is TypedRuntimeOutcome.SafeRetry -> {
                val retryAt = scheduleSafeRetry(right.accountId, right.leaseToken, outcome.message)
                TypedRuntimeProjection(retryAt != null, retryAt)
            }
            is TypedRuntimeOutcome.RetryableFailure -> {
                outcome.warnings?.let { recordWarnings(right.accountId, right.leaseToken, it) }
                val retryAt = scheduleAutomaticRetry(
                    right.accountId,
                    right.leaseToken,
                    right.actionId,
                    outcome.reason,
                    outcome.message,
                )
                TypedRuntimeProjection(retryAt != null, retryAt)
            }
            is TypedRuntimeOutcome.ActionSucceeded -> succeedAndEnqueueWake(
                right.accountId,
                right.leaseToken,
                right.requireActionId(),
                outcome.wakeReason,
                outcome.warnings,
            ).projection()
            is TypedRuntimeOutcome.SharedCooldownHandled -> succeedAndEnqueueWake(
                right.accountId,
                right.leaseToken,
                right.requireActionId(),
                outcome.wakeReason,
                outcome.warnings,
            ).projection()
            is TypedRuntimeOutcome.BattleGateBlocked -> supersedeAction(
                right.accountId,
                right.leaseToken,
                right.requireActionId(),
                outcome.warning,
                outcome.wakeReason,
            ).projection()
            is TypedRuntimeOutcome.SubmissionDeferred -> deferSubmittedAction(
                right.accountId,
                right.leaseToken,
                right.requireActionId(),
                outcome.retryAt,
                outcome.message,
            ).projection(outcome.retryAt)
            is TypedRuntimeOutcome.UnsubmittedFailure -> failUnsubmittedAction(right, outcome.message)
            is TypedRuntimeOutcome.SubmissionAmbiguous -> markReconcilingAndEnqueueWake(
                right.accountId,
                right.leaseToken,
                right.requireActionId(),
                outcome.message,
            ).projection()
            is TypedRuntimeOutcome.ReconciliationApplied -> succeedReconciliation(
                right.accountId,
                right.leaseToken,
                right.requireActionId(),
                outcome.wakeReason,
            ).projection()
            is TypedRuntimeOutcome.ReconciliationDeferred -> deferReconciliation(
                right.accountId,
                right.leaseToken,
                right.requireActionId(),
                outcome.retryAt,
                outcome.reason,
                outcome.successfulObservation,
            ).projection(outcome.retryAt)
            is TypedRuntimeOutcome.AmbiguousHandoff -> handoffAmbiguousAction(
                right.accountId,
                right.leaseToken,
                right.requireActionId(),
                outcome.warning,
                outcome.wakeReason,
                outcome.successfulObservationCount,
            ).projection()
            is TypedRuntimeOutcome.ActionSuperseded -> supersedeAction(
                right.accountId,
                right.leaseToken,
                right.requireActionId(),
                outcome.warning,
                outcome.wakeReason,
            ).projection()
            is TypedRuntimeOutcome.PreparedDiscarded -> discardPreparedAction(
                right.accountId,
                right.leaseToken,
                right.requireActionId(),
                outcome.warning,
                outcome.wakeReason,
            ).projection()
            is TypedRuntimeOutcome.IntegrityFailure -> {
                val retryAt = isolateIntegrityFailureForRetry(
                    right.accountId,
                    right.leaseToken,
                    right.requireActionId(),
                    outcome.message,
                )
                TypedRuntimeProjection(retryAt != null, retryAt)
            }
        }
    }

    private fun failUnsubmittedAction(
        right: PersistedTypedRuntimeExecutionRight,
        message: String,
    ): TypedRuntimeProjection {
        val state = fencedState(right.accountId, right.leaseToken) ?: return TypedRuntimeProjection(false)
        val action = queryRepository.lockTypedAction(right.requireActionId()) ?: return TypedRuntimeProjection(false)
        if (action.account.id != right.accountId || action.leaseToken != right.leaseToken ||
            action.status != TypedAutomationActionStatus.SUBMITTING
        ) return TypedRuntimeProjection(false)

        val now = timeProvider.now()
        action.status = TypedAutomationActionStatus.FAILED
        action.submittedAt = null
        action.nextAttemptAt = null
        action.finishedAt = now
        action.updatedAt = now
        action.lastError = sanitizeDiagnostic(message)
        if (blocksNewSubmission(state)) {
            return releaseCore(right.accountId, right.leaseToken, null, null, emptyList()).projection()
        }
        val retryAt = scheduleAutomaticRetry(state, AutomationStopReason.NETWORK, message)
        action.entry?.let { lifecycleBridge.parkUnsubmittedWork(right.accountId, it.id, retryAt) }
        return TypedRuntimeProjection(true, retryAt)
    }

    private fun deferSubmittedAction(
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
        if (blocksNewSubmission(state)) {
            action.status = TypedAutomationActionStatus.FAILED
            action.nextAttemptAt = null
            action.submittedAt = null
            action.finishedAt = now
            action.lastError = diagnostic
            action.updatedAt = now
            state.nextAttemptAt = null
            state.waitReason = null
            state.leaseToken = null
            state.leaseUntil = null
            state.lastError = null
            state.updatedAt = now
            completeRequestedLifecycle(state, now)
            return true
        }
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

    private fun markReconcilingAndEnqueueWake(
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
        action.reconciliationObservationCount = 0
        action.reconciliationFirstPendingAt = now
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

    private fun handoffAmbiguousAction(
        accountId: Long,
        token: String,
        actionId: Long,
        warning: String,
        wakeReason: String,
        successfulObservationCount: Int?,
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
        successfulObservationCount?.let { action.reconciliationObservationCount = it }
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

    private fun discardPreparedAction(
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
            action.status != TypedAutomationActionStatus.PREPARED
        ) return false
        val now = timeProvider.now()
        val diagnostic = sanitizeDiagnostic(warning)
        action.status = TypedAutomationActionStatus.FAILED
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

    private fun supersedeAction(
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
                TypedAutomationActionStatus.PREPARED,
                TypedAutomationActionStatus.SUBMITTING,
                TypedAutomationActionStatus.RECONCILING,
            )
        ) return false
        val now = timeProvider.now()
        val diagnostic = sanitizeDiagnostic(warning)
        action.status = TypedAutomationActionStatus.FAILED
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

    private fun deferReconciliation(
        accountId: Long,
        token: String,
        actionId: Long,
        retryAt: Instant,
        reason: String,
        successfulObservation: Boolean,
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
        if (action.reconciliationFirstPendingAt == null) {
            action.reconciliationFirstPendingAt = now
        }
        if (successfulObservation) {
            action.reconciliationObservationCount += 1
        }
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

    private fun succeedReconciliation(
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

    private fun succeedAndEnqueueWake(
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

    private fun isolateIntegrityFailureForRetry(accountId: Long, token: String, actionId: Long, message: String): Instant? {
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

    private fun scheduleSafeRetry(accountId: Long, token: String, message: String): Instant? {
        val state = fencedState(accountId, token) ?: return null
        return scheduleAutomaticRetry(state, AutomationStopReason.NETWORK, message)
    }

    private fun scheduleAutomaticRetry(
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
            if (status == TypedAutomationActionStatus.RECONCILING && reconciliationFirstPendingAt == null) {
                reconciliationObservationCount = 0
                reconciliationFirstPendingAt = submittedAt ?: timeProvider.now()
            }
            retryAttempt = (retryAttempt + 1).coerceAtMost(MAX_RETRY_ATTEMPT)
            nextAttemptAt = retryAt
            lastError = sanitizeDiagnostic(message)
            finishedAt = null
            updatedAt = timeProvider.now()
        }
        return retryAt
    }

    private fun releaseAndEnqueueWake(accountId: Long, token: String, reason: String): Boolean {
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
        state.lifecycleStatus = if (
            requested == TypedAutomationLifecycle.PAUSED &&
            state.resumeAfterAuth &&
            !state.authSuspended
        ) {
            state.resumeAfterAuth = false
            TypedAutomationLifecycle.RUNNING
        } else {
            requested
        }
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

    private fun blocksNewSubmission(state: TypedAutomationRuntimeStateEntity): Boolean =
        state.authSuspended ||
            state.lifecycleStatus != TypedAutomationLifecycle.RUNNING ||
            state.requestedLifecycle != null

    private fun discardPreparedForLifecycle(
        state: TypedAutomationRuntimeStateEntity,
        action: TypedAutomationActionRunEntity,
        now: Instant,
    ) {
        action.status = TypedAutomationActionStatus.FAILED
        action.nextAttemptAt = null
        action.finishedAt = now
        action.lastError = "인증 또는 사용자 수명주기 전환 전에 제출되지 않은 행동을 폐기했습니다."
        action.updatedAt = now
        state.nextAttemptAt = null
        state.waitReason = null
        state.leaseToken = null
        state.leaseUntil = null
        state.updatedAt = now
        completeRequestedLifecycle(state, now)
    }

    private fun recordWarnings(accountId: Long, token: String, warnings: List<String>): Boolean {
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

    private fun PersistedTypedRuntimeExecutionRight.requireActionId(): Long =
        actionId ?: throw IllegalStateException("This runtime outcome requires a durable action checkpoint.")

    private fun Boolean.projection(nextAttemptAt: Instant? = null): TypedRuntimeProjection =
        TypedRuntimeProjection(this, nextAttemptAt.takeIf { this })

    private fun StoredTypedAutomationAction.legacySuppressionEpoch(
        accountId: Long,
        row: TypedAutomationActionRunEntity,
    ): String? = when (val storedPayload = payload) {
        is StoredTypedActionPayload.QuestAccept -> storedPayload.questCycle ?: queryRepository
            .findQuestCycle(accountId, storedPayload.questKey)
            ?.currentCycle
            ?.toString()
            ?: "0"
        is StoredTypedActionPayload.QuestClaim -> storedPayload.questCycle ?: queryRepository
            .findQuestCycle(accountId, storedPayload.questKey)
            ?.currentCycle
            ?.toString()
            ?: "0"
        is StoredTypedActionPayload.FishingTown -> storedPayload.progressDate?.toString()
            ?: (row.submittedAt ?: row.createdAt)
                .atZone(LEGACY_SUPPRESSION_ZONE)
                .toLocalDate()
                .toString()
        else -> null
    }

    private fun StoredTypedAutomationAction.withLegacySuppressionEpoch(epoch: String?): StoredTypedAutomationAction {
        if (epoch == null) return this
        val enrichedPayload = when (val storedPayload = payload) {
            is StoredTypedActionPayload.QuestAccept -> if (storedPayload.questCycle == null) {
                storedPayload.copy(questCycle = epoch)
            } else {
                storedPayload
            }
            is StoredTypedActionPayload.QuestClaim -> if (storedPayload.questCycle == null) {
                storedPayload.copy(questCycle = epoch)
            } else {
                storedPayload
            }
            is StoredTypedActionPayload.FishingTown -> if (storedPayload.progressDate == null) {
                storedPayload.copy(progressDate = java.time.LocalDate.parse(epoch))
            } else {
                storedPayload
            }
            else -> storedPayload
        }
        return if (enrichedPayload === payload) this else copy(payload = enrichedPayload)
    }

    companion object {
        private val LEGACY_SUPPRESSION_ZONE = ZoneId.of("Asia/Seoul")
        private val LEASE_DURATION = Duration.ofMinutes(5)
        private val CONFIG_RECHECK = Duration.ofMinutes(5)
        private val RETRY_SECONDS = listOf(10L, 30L, 60L, 300L)
        private const val MAX_RETRY_ATTEMPT = 4
    }
}
