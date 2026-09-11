package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.*
import app.spammy.hof.automation.repository.*
import app.spammy.hof.automation.outbox.AutomationOutboxService
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.character.repository.CharacterOperationJobQueryRepository
import app.spammy.hof.external.config.HofRequestProperties
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
    val directResponseFingerprint: String? = null,
) : TypedRuntimeExecutionRight

@Service
class TypedAutomationRuntimeService(
    private val queryRepository: TypedAutomationQueryRepository,
    private val actionRepository: TypedAutomationActionRunCommandRepository,
    private val codec: StoredTypedAutomationActionCodec,
    private val timeProvider: TimeProvider,
    private val lifecycleBridge: TypedAutomationLifecycleBridge,
    private val outbox: AutomationOutboxService,
    private val characterJobs: CharacterOperationJobQueryRepository,
    private val directResponses: AutomationDirectResponseStore,
    private val requestProperties: HofRequestProperties = HofRequestProperties(),
) {
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    fun isRunning(accountId: Long): Boolean =
        !characterJobs.hasUnrestoredJob(accountId) && queryRepository.findRuntimeState(accountId)?.let { state ->
            state.lifecycleStatus == TypedAutomationLifecycle.DRAINING ||
                (!state.authSuspended && state.lifecycleStatus == TypedAutomationLifecycle.RUNNING && !characterJobs.hasRecoveryHold(accountId))
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
        if (characterJobs.hasUnrestoredJob(accountId)) return TypedRuntimeAcquisition.Inactive
        if (state.lifecycleStatus != TypedAutomationLifecycle.DRAINING && characterJobs.hasRecoveryHold(accountId)) {
            return TypedRuntimeAcquisition.Inactive
        }
        if (state.lifecycleStatus !in setOf(TypedAutomationLifecycle.RUNNING, TypedAutomationLifecycle.DRAINING)) {
            return TypedRuntimeAcquisition.Inactive
        }
        val active = queryRepository.findActiveTypedAction(accountId)
            ?: if (state.directResponseYieldRequired && state.lifecycleStatus == TypedAutomationLifecycle.RUNNING) null
            else queryRepository.findDueDirectResponse(accountId, timeProvider.now())
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
        if (active?.status == TypedAutomationActionStatus.RESULT_PENDING) {
            active.status = TypedAutomationActionStatus.RECONCILING
            active.nextAttemptAt = null
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
                row.status = when {
                    row.directResponseJson != null -> TypedAutomationActionStatus.RESULT_HELD
                    row.status == TypedAutomationActionStatus.PREPARED -> TypedAutomationActionStatus.FAILED
                    else -> TypedAutomationActionStatus.AMBIGUOUS
                }
                row.nextAttemptAt = null
                row.lastError = "Stored typed action integrity check failed."
                row.finishedAt = now
                row.updatedAt = now
                if (row.status == TypedAutomationActionStatus.RESULT_HELD) directResponses.recordIntegrityFailure(row, now)
                return TypedRuntimeAcquisition.RetryScheduled(
                    scheduleAutomaticRetry(state, AutomationStopReason.FATAL, row.lastError!!),
                )
            }
            val legacySuppressionEpoch = if (decoded.policyContext == null && row.status == TypedAutomationActionStatus.RECONCILING) {
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
            PersistedTypedRuntimeExecutionRight(accountId, token, active?.id, checkpoint, active?.directResponseFingerprint),
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
        if (!entry.enabled || (action.settingsRevision != null && action.settingsRevision != entry.settingsRevision)) {
            return TypedRuntimePreparation.Invalidated
        }
        val currentAction = action.copy(settingsRevision = action.settingsRevision ?: entry.settingsRevision)
        val encoded = codec.encode(currentAction)
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
        // 새 행동을 실제 준비한 사실과 함께 양보 의도를 소비한다. 획득 직후 종료되면 의도는 남는다.
        state.directResponseYieldRequired = false
        return TypedRuntimePreparation.Ready(
            PersistedTypedRuntimeExecutionRight(
                right.accountId,
                right.leaseToken,
                saved.id,
                TypedRuntimeCheckpoint(currentAction, TypedRuntimeCheckpointPhase.PREPARED, null, null),
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
        val configured = action.entry?.id?.let { queryRepository.findEntryForUpdate(right.accountId, it)
            ?: queryRepository.findEntry(right.accountId, it) }
        val selectedRevision = right.checkpoint?.storedAction?.settingsRevision
        if (configured == null || !configured.enabled || selectedRevision == null || selectedRevision != configured.settingsRevision) {
            action.status = TypedAutomationActionStatus.FAILED
            action.lastError = "설정이 변경되어 전송 전 행동을 폐기하고 다시 판단합니다."
            action.finishedAt = now
            action.updatedAt = now
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
            current.status = TypedAutomationActionStatus.SUCCEEDED
            current.lastError = null
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
        val currentFollowup = followup.copy(settingsRevision = right.checkpoint?.storedAction?.settingsRevision)
        val encoded = codec.encode(currentFollowup)
        current.status = TypedAutomationActionStatus.SUCCEEDED
        current.lastError = null
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
                TypedRuntimeCheckpoint(currentFollowup, TypedRuntimeCheckpointPhase.PREPARED, null, null),
            ),
        )
    }

    /** 다음 단계 준비와 원래 START의 직접 증거를 함께 commit한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun advanceAppliedActionToPreparedFollowup(
        execution: TypedRuntimeExecutionRight,
        followup: StoredTypedAutomationAction,
        outcome: TypedRuntimeOutcome.ActionSucceeded,
        persistDirectResult: () -> Unit,
    ): TypedRuntimePreparation {
        val preparation = advanceAppliedActionToPreparedFollowup(execution, followup)
        if (preparation is TypedRuntimePreparation.Ready) {
            persistDirectResult()
        } else {
            // 중단 요청으로 START만 종결한 경우에도 직접 사실은 한 번 저장한다.
            completeResult(execution, outcome, null, persistDirectResult)
        }
        return preparation
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun complete(
        execution: TypedRuntimeExecutionRight,
        outcome: TypedRuntimeOutcome,
        convergenceRecheckAt: Instant? = null,
    ): TypedRuntimeProjection = completeResult(execution, outcome, convergenceRecheckAt, null)

    /** 직접 응답의 귀속을 행동 상태와 같은 runtime 잠금 안에서 저장한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun complete(
        execution: TypedRuntimeExecutionRight,
        outcome: TypedRuntimeOutcome,
        persistDirectResult: () -> Unit,
    ): TypedRuntimeProjection {
        require(outcome is TypedRuntimeOutcome.ActionSucceeded || outcome is TypedRuntimeOutcome.SharedCooldownHandled ||
            outcome is TypedRuntimeOutcome.DirectResponsePending)
        return completeResult(execution, outcome, null, persistDirectResult)
    }

    private fun completeResult(
        execution: TypedRuntimeExecutionRight,
        outcome: TypedRuntimeOutcome,
        convergenceRecheckAt: Instant?,
        persistDirectResult: (() -> Unit)?,
    ): TypedRuntimeProjection {
        val right = execution.persistedRight()
        val state = fencedState(right.accountId, right.leaseToken) ?: run {
            if (recordLateActionResult(right, outcome)) persistDirectResult?.invoke()
            return TypedRuntimeProjection(false)
        }
        if (completeRecordedAction(execution)) {
            persistDirectResult?.invoke()
            return TypedRuntimeProjection(true)
        }
        if (right.checkpoint?.phase == TypedRuntimeCheckpointPhase.RECONCILING &&
            (outcome is TypedRuntimeOutcome.ReconciliationApplied || outcome is TypedRuntimeOutcome.ReconciliationDeferred ||
                outcome is TypedRuntimeOutcome.AmbiguousHandoff || outcome is TypedRuntimeOutcome.ActionSuperseded)
        ) {
            val action = queryRepository.lockTypedAction(right.requireActionId())
            if (action?.directResponseFingerprint != null && action.directResponseFingerprint != right.directResponseFingerprint) {
                // 획득 뒤 받은 원본을 과거 재관측으로 종결하지 않는다. 이미 읽은 비확정 원본은 유한 재관측을 유지한다.
                val retryAt = timeProvider.now()
                deferDirectResponse(right, TypedRuntimeOutcome.DirectResponsePending(retryAt,
                    "결과 확인 중 원래 직접 응답이 도착해 저장 응답으로 판정을 이어갑니다."))
                return TypedRuntimeProjection(false, retryAt)
            }
        }
        val projection = when (outcome) {
            is TypedRuntimeOutcome.RoundCompleted -> {
                val next = timeProvider.now().plus(
                    requestProperties.automationMinimumInterval.coerceAtLeast(Duration.ofMillis(100)),
                )
                val released = releaseCore(
                    right.accountId, right.leaseToken, next,
                    AutomationWaitReason.LOOP_INTERVAL, outcome.warnings,
                )
                if (released && state.lifecycleStatus == TypedAutomationLifecycle.RUNNING && !state.authSuspended) {
                    outbox.enqueue(right.accountId, "TYPED_NEXT_ROUND", next)
                }
                released.projection(next)
            }
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
            ).projection(outcome.nextRunAt).enqueueNext(state, outcome.wakeReason)
            is TypedRuntimeOutcome.ConfigurationWait -> {
                val next = timeProvider.now().plus(CONFIG_RECHECK)
                releaseCore(
                    right.accountId,
                    right.leaseToken,
                    next,
                    AutomationWaitReason.SCHEDULED,
                    outcome.warnings,
                ).projection(next).enqueueNext(state, "TYPED_CONFIG_RECHECK")
            }
            is TypedRuntimeOutcome.SafeRetry -> {
                val retryAt = scheduleSafeRetry(right.accountId, right.leaseToken, outcome.message)
                TypedRuntimeProjection(retryAt != null, retryAt).enqueueNext(state, "TYPED_SAFE_RETRY")
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
                TypedRuntimeProjection(retryAt != null, retryAt).enqueueNext(state, "TYPED_AUTOMATIC_RETRY")
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
                clearSubmission = !outcome.submissionAttempted,
            ).projection()
            is TypedRuntimeOutcome.SubmissionDeferred -> deferSubmittedAction(
                right.accountId,
                right.leaseToken,
                right.requireActionId(),
                outcome.retryAt,
                outcome.message,
            ).projection(outcome.retryAt).enqueueNext(state, "HOF_503_COOLDOWN")
            is TypedRuntimeOutcome.UnsubmittedFailure -> failUnsubmittedAction(right, outcome.message)
                .enqueueNext(state, "HOF_503_COOLDOWN")
            is TypedRuntimeOutcome.SubmissionAmbiguous -> markReconcilingAndEnqueueWake(
                right.accountId,
                right.leaseToken,
                right.requireActionId(),
                outcome.message,
            ).projection()
            is TypedRuntimeOutcome.DirectResponsePending -> deferDirectResponse(right, outcome).projection(outcome.retryAt)
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
            ).projection(outcome.retryAt).enqueueNext(state, outcome.wakeReason)
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
                TypedRuntimeProjection(retryAt != null, retryAt).enqueueNext(state, "TYPED_AUTOMATIC_RETRY")
            }
        }
        if (projection.applied && right.actionId == null) state.directResponseYieldRequired = false
        if (projection.applied && convergenceRecheckAt != null && canScheduleFollowUp(state)) {
            // 새 판단을 깨우는 예약과 특정 결과를 미래에 재확인하는 예약은 서로 대체하지 않는다.
            outbox.enqueue(right.accountId, "TYPED_CONVERGENCE_PROBE", convergenceRecheckAt)
        }
        if (persistDirectResult != null && (projection.applied || recordLateActionResult(right, outcome))) {
            persistDirectResult()
        }
        return projection
    }

    /**
     * 재관측의 종결 상태와 보류 쓰기를 같은 실행권 잠금에서 확정한다.
     * persistResult는 DB 결과 쓰기만 수행한다. 원격 조회와 작업 진전은 이 호출 전에 끝낸다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun completeReconciliation(
        execution: TypedRuntimeExecutionRight,
        outcome: TypedRuntimeOutcome,
        convergenceRecheckAt: Instant? = null,
        persistResult: () -> Unit,
    ): TypedRuntimeProjection {
        val right = execution.persistedRight()
        fencedState(right.accountId, right.leaseToken) ?: return TypedRuntimeProjection(false)
        if (completeRecordedAction(execution)) return TypedRuntimeProjection(true)
        val projection = complete(execution, outcome, convergenceRecheckAt)
        if (projection.applied) persistResult()
        return projection
    }

    /** commit된 재관측 결과가 여전히 유효할 때 진단 이력만 별도로 저장한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun persistReconciliationHistory(
        execution: TypedRuntimeExecutionRight,
        outcome: TypedRuntimeOutcome,
        persistHistory: () -> Unit,
    ) {
        val right = execution.persistedRight()
        queryRepository.lockRuntimeState(right.accountId) ?: return
        val action = right.actionId?.let(queryRepository::lockTypedAction) ?: return
        val expected = when (outcome) {
            is TypedRuntimeOutcome.ReconciliationApplied -> TypedAutomationActionStatus.SUCCEEDED
            is TypedRuntimeOutcome.ReconciliationDeferred -> TypedAutomationActionStatus.RECONCILING
            is TypedRuntimeOutcome.AmbiguousHandoff -> TypedAutomationActionStatus.AMBIGUOUS
            is TypedRuntimeOutcome.ActionSuperseded -> TypedAutomationActionStatus.FAILED
            else -> return
        }
        if (action.account.id != right.accountId || action.leaseToken != right.leaseToken ||
            action.executionIdentity != right.checkpoint?.storedAction?.executionIdentity || action.status != expected
        ) return
        persistHistory()
    }

    /** 복구 worker가 실행권을 얻은 뒤 도착한 직접 결과도 원래 행동에 보존한다. */
    private fun recordLateActionResult(right: PersistedTypedRuntimeExecutionRight, outcome: TypedRuntimeOutcome): Boolean {
        if (outcome !is TypedRuntimeOutcome.ActionSucceeded && outcome !is TypedRuntimeOutcome.SharedCooldownHandled) return false
        val action = right.actionId?.let(queryRepository::lockTypedAction) ?: return false
        if (action.account.id != right.accountId ||
            action.executionIdentity != right.checkpoint?.storedAction?.executionIdentity || action.submittedAt == null ||
            action.status !in setOf(TypedAutomationActionStatus.RECONCILING, TypedAutomationActionStatus.RESULT_PENDING, TypedAutomationActionStatus.FAILED, TypedAutomationActionStatus.AMBIGUOUS, TypedAutomationActionStatus.SUCCEEDED)
        ) return false
        if (action.status == TypedAutomationActionStatus.SUCCEEDED) return true
        val now = timeProvider.now()
        action.status = TypedAutomationActionStatus.SUCCEEDED
        action.lastError = null
        action.nextAttemptAt = null
        action.finishedAt = now
        action.updatedAt = now
        return true
    }

    /** 저장된 직접 성공을 재관측으로 되돌리지 않고 현재 복구 실행권만 놓는다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun completeRecordedAction(execution: TypedRuntimeExecutionRight): Boolean {
        val right = execution.persistedRight()
        val actionId = right.actionId ?: return false
        fencedState(right.accountId, right.leaseToken) ?: return false
        val action = queryRepository.lockTypedAction(actionId) ?: return false
        if (action.account.id != right.accountId || action.leaseToken != right.leaseToken ||
            action.status != TypedAutomationActionStatus.SUCCEEDED
        ) return false
        val active = queryRepository.findActiveTypedAction(right.accountId)
        if (active != null && active.id != actionId) return false
        return releaseAndEnqueueWake(right.accountId, right.leaseToken, "TYPED_DIRECT_RESULT_RECOVERED")
    }

    private fun TypedRuntimeProjection.enqueueNext(
        state: TypedAutomationRuntimeStateEntity,
        reason: String,
    ): TypedRuntimeProjection {
        if (!applied) return this
        val next = state.nextAttemptAt
        if (next != null && canScheduleFollowUp(state)) outbox.enqueue(state.account.id, reason, next)
        return copy(nextAttemptAt = next)
    }

    private fun canScheduleFollowUp(state: TypedAutomationRuntimeStateEntity): Boolean =
        state.lifecycleStatus == TypedAutomationLifecycle.DRAINING ||
            (state.lifecycleStatus == TypedAutomationLifecycle.RUNNING && !state.authSuspended)

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
        action.entry?.let { lifecycleBridge.parkWorkUntil(right.accountId, it.id, retryAt) }
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

    private fun deferDirectResponse(right: PersistedTypedRuntimeExecutionRight, outcome: TypedRuntimeOutcome.DirectResponsePending): Boolean {
        val state = fencedState(right.accountId, right.leaseToken) ?: return false
        val action = queryRepository.lockTypedAction(right.requireActionId()) ?: return false
        if (action.account.id != right.accountId || action.leaseToken != right.leaseToken ||
            action.status !in setOf(TypedAutomationActionStatus.SUBMITTING, TypedAutomationActionStatus.RECONCILING)
        ) return false
        requireNotNull(action.directResponseJson) { "Local result retry requires the original direct response." }
        val now = timeProvider.now()
        action.status = TypedAutomationActionStatus.RESULT_PENDING
        action.nextAttemptAt = outcome.retryAt
        action.retryAttempt += 1
        action.lastError = sanitizeDiagnostic(outcome.message)
        action.updatedAt = now
        state.directResponseYieldRequired = true
        val targetKey = when (val payload = right.checkpoint?.storedAction?.payload) {
            is StoredTypedActionPayload.QuestAccept -> payload.questKey
            is StoredTypedActionPayload.QuestClaim -> payload.questKey
            is StoredTypedActionPayload.HomeQuest -> payload.questId
            is StoredTypedActionPayload.QuestBattle -> payload.questKey
            is StoredTypedActionPayload.AdventureMap -> "${payload.categoryId}/${payload.mapCode}"
            is StoredTypedActionPayload.BattleMap -> when (payload.source) {
                BattleAutomationActionSource.FISHING_AUTOMATION -> FISHING_CYCLE_TARGET
                BattleAutomationActionSource.RAID_AUTOMATION -> requireNotNull(payload.sourceTargetKey)
                else -> "${payload.categoryId}/${payload.mapCode}"
            }
            else -> error("Stored direct response does not support this work target.")
        }
        // 같은 entry에서 다른 퀘스트가 작업권을 얻었어도 원래 결과의 재시도가 그 작업을 양보시키지 않는다.
        action.entry?.let { lifecycleBridge.parkWorkUntil(right.accountId, it.id, outcome.retryAt, targetKey) }
        val released = releaseCore(right.accountId, right.leaseToken, null, null, listOf(outcome.message))
        if (released && canScheduleFollowUp(state)) {
            outbox.enqueue(right.accountId, "TYPED_DIRECT_RESPONSE_RETRY", outcome.retryAt)
            outbox.enqueue(right.accountId, "TYPED_CONVERGENCE_CONTINUE")
        }
        return released
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
        clearSubmission: Boolean = false,
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
        if (clearSubmission) action.submittedAt = null
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
        warnings: List<String>? = null,
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
        if (warnings != null) {
            state.warningText = warnings.takeIf { it.isNotEmpty() }?.joinToString("\n") { sanitizeDiagnostic(it) }
        }
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
        val action = queryRepository.lockTypedAction(actionId)
        if (action?.status == TypedAutomationActionStatus.RECONCILING && action.directResponseJson != null) {
            return succeedReconciliation(accountId, token, actionId, reason, warnings)
        }
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
        val terminalStatus = when {
            action.directResponseJson != null -> TypedAutomationActionStatus.RESULT_HELD
            action.status == TypedAutomationActionStatus.PREPARED -> TypedAutomationActionStatus.FAILED
            action.status == TypedAutomationActionStatus.RECONCILING -> TypedAutomationActionStatus.AMBIGUOUS
            else -> return null
        }
        val now = timeProvider.now()
        action.status = terminalStatus
        action.nextAttemptAt = null
        action.lastError = message.take(2000)
        action.finishedAt = now
        action.updatedAt = now
        if (terminalStatus == TypedAutomationActionStatus.RESULT_HELD) directResponses.recordIntegrityFailure(action, now)
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
