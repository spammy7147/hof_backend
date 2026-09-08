package app.spammy.hof.automation.service

import app.spammy.hof.automation.convergence.AutomationActionEvidence
import app.spammy.hof.automation.convergence.AutomationConvergenceBudget
import app.spammy.hof.automation.convergence.ConvergenceDirective
import app.spammy.hof.automation.convergence.LegacyConvergenceDecision
import app.spammy.hof.automation.convergence.SelectedAutomationAction
import app.spammy.hof.automation.entity.AutomationWaitReason
import app.spammy.hof.automation.history.*
import app.spammy.hof.automation.port.AutomationWakeupPort
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.auth.service.AccountExecutionSubmissionGate
import app.spammy.hof.external.client.HofAutomationDeferredException
import app.spammy.hof.battle.service.BattleNotSubmittedException
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.town.fishing.model.FishingAction
import app.spammy.hof.town.common.service.AccountHofObservationInvalidatedException
import app.spammy.hof.town.common.service.ObservedTownActionPreconditionChangedException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant

/** 최신 타입별 스냅샷 결정 또는 저장된 prepared payload 중 action 하나만 실행하는 자동화 루프다. */
@Service
class UnifiedAutomationRunner @Autowired constructor(
    private val dailyPreflight: AutomationDailyPreflight,
    private val typedRuntime: TypedAutomationRuntimeService,
    private val decisionSource: AutomationDecisionSource,
    private val wakeupPort: AutomationWakeupPort,
    private val sharedBattleCooldowns: SharedBattleCooldownService,
    private val actionLifecycleModule: AutomationActionLifecycleModule,
    private val submissionGate: AccountExecutionSubmissionGate,
    private val results: AutomationResultCoordinator,
    private val decisionJournal: AutomationDecisionJournal? = null,
    private val timeProvider: TimeProvider? = null,
    private val convergenceWorkPriority: AutomationConvergenceWorkPriority? = null,
    private val fishingCycleExecutor: FishingCycleExecutor? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 한 wakeup에서 최대 action 하나만 실행하고 후속 판단은 새 wakeup과 새 스냅샷에 맡긴다. */
    fun runOne(accountId: Long) {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "Typed automation runner must not be called with an active transaction."
        }
        if (!typedRuntime.isRunning(accountId)) return
        val completingCurrentAction = typedRuntime.isCompletingCurrentAction(accountId)
        when (val acquisition = typedRuntime.acquire(accountId)) {
            TypedRuntimeAcquisition.Inactive,
            TypedRuntimeAcquisition.Busy,
            -> return
            is TypedRuntimeAcquisition.RetryScheduled -> {
                wakeupPort.schedule(accountId, acquisition.retryAt, AUTOMATIC_RETRY_WAKE_REASON)
                return
            }
            is TypedRuntimeAcquisition.Acquired -> {
                if (!completingCurrentAction && !ensurePreflight(accountId, acquisition.execution)) return
                val dueDirective = if (
                    !completingCurrentAction &&
                    acquisition.execution.checkpoint == null
                ) {
                    results.resumeDue(accountId)
                } else {
                    null
                }
                when (dueDirective) {
                    null,
                    ConvergenceDirective.ContinueSelection,
                    -> runAcquired(accountId, acquisition.execution)
                    is ConvergenceDirective.Probe -> runAcquired(accountId, acquisition.execution, dueDirective)
                    else -> releaseForConvergenceDirective(accountId, acquisition.execution, dueDirective)
                }
            }
        }
    }

    private fun ensurePreflight(
        accountId: Long,
        execution: TypedRuntimeExecutionRight,
    ): Boolean = when (val preflight = dailyPreflight.ensureReady(accountId)) {
        AutomationDailyPreflight.Result.Ready -> true
        is AutomationDailyPreflight.Result.Busy -> {
            completeAndSchedule(
                accountId,
                execution,
                TypedRuntimeOutcome.ScheduledWait(
                    preflight.retryAt,
                    AutomationWaitReason.SCHEDULED,
                ),
                "DAILY_PREFLIGHT_BUSY",
            )
            false
        }
        is AutomationDailyPreflight.Result.RetryScheduled -> {
            completeAndSchedule(
                accountId,
                execution,
                TypedRuntimeOutcome.ScheduledWait(
                    preflight.nextAttemptAt,
                    AutomationWaitReason.HOF_CONNECTION,
                ),
                "DAILY_PREFLIGHT_RETRY",
            )
            false
        }
        is AutomationDailyPreflight.Result.Stopped -> {
            val reason = when (preflight.reason) {
                AutomationDailyPreflight.StopReason.AUTHENTICATION -> AutomationStopReason.AUTHENTICATION
                AutomationDailyPreflight.StopReason.CAPTCHA -> AutomationStopReason.CAPTCHA
                AutomationDailyPreflight.StopReason.NETWORK -> AutomationStopReason.NETWORK
                AutomationDailyPreflight.StopReason.FATAL -> AutomationStopReason.FATAL
            }
            dailyPreflight.resume(accountId)
            completeAndSchedule(
                accountId,
                execution,
                TypedRuntimeOutcome.RetryableFailure(
                    reason,
                    "Daily preflight failed: ${preflight.reason}",
                ),
                AUTOMATIC_RETRY_WAKE_REASON,
            )
            false
        }
    }

    private fun runAcquired(
        accountId: Long,
        initialExecution: TypedRuntimeExecutionRight,
        fallbackConvergenceProbe: ConvergenceDirective.Probe? = null,
        continuedDecisionCycleId: Long? = null,
        continuedWarnings: List<String>? = null,
        continuedPreparedFollowup: Boolean = false,
    ) {
        var execution = initialExecution
        var checkpoint = execution.checkpoint
        val restoredCheckpoint = checkpoint != null && !continuedPreparedFollowup
        val resumedDeferredSubmission = restoredCheckpoint && checkpoint.deferredSubmissionRetry
        val resumedLegacyCheckpoint = restoredCheckpoint && !resumedDeferredSubmission
        var decisionCycleId: Long? = continuedDecisionCycleId
        var selectedWarnings: List<String>? = continuedWarnings
        var convergenceAttemptId: Long? = null
        var resultSelection = AutomationResultCoordinator.ActionSelection()
        lateinit var managedAction: ManagedAutomationAction
        val stored: StoredTypedAutomationAction

        if (checkpoint != null) {
            stored = checkpoint.storedAction
            managedAction = try {
                actionLifecycleModule.restoreVerified(stored, accountId)
            } catch (error: RuntimeException) {
                completeAndSchedule(
                    accountId,
                    execution,
                    TypedRuntimeOutcome.IntegrityFailure("Stored typed action integrity check failed."),
                    AUTOMATIC_RETRY_WAKE_REASON,
                )
                return
            }
            resultSelection = results.restoredSelection(accountId, stored)
        } else {
            val decision = try {
                decisionSource.select(accountId)
            } catch (_: TypedAutomationConfigurationChangedException) {
                typedRuntime.complete(
                    execution,
                    TypedRuntimeOutcome.SelectionChanged("TYPED_CONFIG_RELOAD"),
                )
                return
            } catch (error: HofAutomationDeferredException) {
                completeAndSchedule(
                    accountId,
                    execution,
                    TypedRuntimeOutcome.ScheduledWait(
                        error.retryAt,
                        AutomationWaitReason.HOF_CONNECTION,
                    ),
                    HOF_COOLDOWN_WAKE_REASON,
                )
                return
            } catch (error: SafeRetryableAutomationException) {
                completeAndSchedule(
                    accountId,
                    execution,
                    TypedRuntimeOutcome.SafeRetry(error.message ?: "Safe snapshot retry"),
                    "TYPED_SAFE_RETRY",
                )
                return
            } catch (error: AutomationLoginRequiredException) {
                retryExecution(accountId, execution, AutomationStopReason.AUTHENTICATION, error.message)
                return
            } catch (error: ApiException) {
                val reason = when (error.errorCode) {
                    ErrorCode.CAPTCHA_REQUIRED -> AutomationStopReason.CAPTCHA
                    ErrorCode.HOF_LOGIN_FAILED, ErrorCode.HOF_SESSION_EXPIRED -> AutomationStopReason.AUTHENTICATION
                    else -> AutomationStopReason.FATAL
                }
                retryExecution(accountId, execution, reason, error.message)
                return
            } catch (error: FatalAutomationException) {
                retryExecution(
                    accountId,
                    execution,
                    AutomationStopReason.FATAL,
                    error.message ?: "Fatal live snapshot failure",
                )
                return
            }
            val decisionDescriptor = try {
                if (
                    decision is AutomationCoordination.Runnable &&
                    fallbackConvergenceProbe?.entryId?.let { probeEntryId ->
                        convergenceWorkPriority?.probeBeforeFresh(
                            accountId,
                            probeEntryId,
                            decision.entryId,
                        )
                    } == true
                ) {
                    runConvergenceProbe(accountId, execution, fallbackConvergenceProbe)
                    return
                }
                (decision as? AutomationCoordination.Runnable)
                    ?.let { actionLifecycleModule.describe(it.action) }
            } catch (error: Exception) {
                stopPreparationFailure(
                    accountId,
                    execution,
                    (decision as? AutomationCoordination.Runnable)?.entryId ?: 0,
                    "DESCRIBE",
                    error,
                )
                return
            }
            try {
                decisionCycleId = decisionJournal?.appendDecision(
                    accountId,
                    decision.withDescriptor(decisionDescriptor),
                )
            } catch (error: Exception) {
                stopPreparationFailure(
                    accountId,
                    execution,
                    (decision as? AutomationCoordination.Runnable)?.entryId ?: 0,
                    "JOURNAL",
                    error,
                )
                return
            }
            when (decision) {
                is AutomationCoordination.Runnable -> {
                    try {
                        selectedWarnings = decision.warnings
                        managedAction = actionLifecycleModule.prepare(accountId, decision.entryId, decision.action)
                    } catch (error: Exception) {
                        stopPreparationFailure(accountId, execution, decision.entryId, "BUILD", error)
                        return
                    }
                }
                is AutomationCoordination.Fatal -> {
                    completeAndSchedule(
                        accountId,
                        execution,
                        TypedRuntimeOutcome.RetryableFailure(
                            decision.reason,
                            decision.message,
                            decision.warnings,
                        ),
                        AUTOMATIC_RETRY_WAKE_REASON,
                    )
                    return
                }
                is AutomationCoordination.Unavailable -> {
                    if (fallbackConvergenceProbe != null) {
                        runConvergenceProbe(accountId, execution, fallbackConvergenceProbe)
                        return
                    }
                    completeAndSchedule(
                        accountId,
                        execution,
                        TypedRuntimeOutcome.ScheduledWait(
                            decision.nextRunAt,
                            AutomationWaitReason.SCHEDULED,
                            decision.warnings,
                        ),
                        "TYPED_UNAVAILABLE",
                    )
                    return
                }
                is AutomationCoordination.CycleBoundary -> {
                    typedRuntime.complete(
                        execution,
                        TypedRuntimeOutcome.SelectionChanged(WORK_CYCLE_BOUNDARY_WAKE_REASON),
                    )
                    return
                }
                is AutomationCoordination.Idle -> {
                    if (fallbackConvergenceProbe != null) {
                        runConvergenceProbe(accountId, execution, fallbackConvergenceProbe)
                        return
                    }
                    typedRuntime.complete(execution, TypedRuntimeOutcome.RoundCompleted(decision.warnings))
                    return
                }
            }
            stored = managedAction.storedAction
            resultSelection = results.freshSelection(accountId, stored) ?: run {
                stopPreparationFailure(accountId, execution, stored.entryId, "CONVERGENCE_MODULE",
                    IllegalStateException("Active convergence module is missing."))
                return
            }
            val preparation = try {
                typedRuntime.persistPrepared(execution, stored, selectedWarnings.orEmpty())
            } catch (error: Exception) {
                stopPreparationFailure(accountId, execution, stored.entryId, "PERSIST", error)
                return
            }
            if (preparation !is TypedRuntimePreparation.Ready) {
                typedRuntime.complete(
                    execution,
                    TypedRuntimeOutcome.SelectionChanged("TYPED_CONFIG_RELOAD"),
                )
                return
            }
            execution = preparation.execution
            checkpoint = requireNotNull(execution.checkpoint)
        }

        val activeCheckpoint = requireNotNull(checkpoint)
        if (continuedPreparedFollowup || resumedDeferredSubmission) {
            resultSelection = results.continuationSelection(stored, resultSelection) ?: run {
                stopPreparationFailure(accountId, execution, stored.entryId, "CONVERGENCE_MODULE",
                    IllegalStateException("Active convergence module is missing."))
                return
            }
        }
        val actionDescriptor = managedAction.descriptor
        fun trace(
            kind: AutomationHistoryEventKind,
            code: String,
            message: String,
            nextRunAt: Instant? = null,
            diagnosticKind: AutomationDiagnosticKind? = null,
            cooldownSource: app.spammy.hof.automation.raid.RaidCooldownSource? = null,
            impactScope: AutomationImpactScope? = null,
            releaseCondition: String? = null,
        ) = actionTrace(
            stored,
            kind,
            code,
            message,
            nextRunAt,
            actionDescriptor,
            diagnosticKind,
            cooldownSource,
            impactScope,
            releaseCondition,
            managedAction.diagnosticContext,
        )
        fun raidCycleTrace(outcome: app.spammy.hof.automation.raid.RaidCycleOutcome): AutomationActionTrace =
            outcome.toAutomationActionTrace().let { result ->
                trace(
                    result.kind,
                    result.reasonCode,
                    result.message,
                    result.nextRunAt,
                )
            }
        fun raidWaitTrace(wait: TypedAutomationExecution.RaidWaiting): AutomationActionTrace = trace(
            kind = if (wait.completedCycle == null) {
                AutomationHistoryEventKind.WAITING
            } else {
                AutomationHistoryEventKind.CYCLE_COMPLETED
            },
            code = wait.reasonCode,
            message = wait.message,
            nextRunAt = wait.retryAt,
            impactScope = AutomationImpactScope.RAID_ONLY,
            releaseCondition = wait.releaseCondition,
        ).copy(targetKey = wait.raidId)
        fun finishRaidBattleHandoff(resolution: AmbiguousActionResolution.HandedOff) {
            typedRuntime.complete(
                execution,
                TypedRuntimeOutcome.AmbiguousHandoff(
                    resolution.reason,
                    RAID_BATTLE_RECOVERY_WAKE_REASON,
                ),
            )
            decisionCycleId?.let { cycleId ->
                decisionJournal?.appendActionResult(cycleId, trace(
                    AutomationHistoryEventKind.WAITING,
                    "RAID_BATTLE_RECOVERY_STARTED",
                    "레이드 전투 결과가 불확실해 레이드 전용 복구로 인계했습니다. ${resolution.reason}",
                    resolution.retryAt,
                    diagnosticKind = AutomationDiagnosticKind.RAID_BATTLE_RESULT_UNKNOWN,
                    impactScope = AutomationImpactScope.RAID_ONLY,
                    releaseCondition = "최신 레이드 상태 또는 쿨타임 관측으로 결과 재확인",
                ))
            }
        }
        fun holdAmbiguousScope(resolution: AmbiguousActionResolution.Held) {
            val observedAt = now()
            val successfulObservationCount = activeCheckpoint.successfulObservationCount + 1
            val firstPendingAt = activeCheckpoint.firstPendingAt ?: activeCheckpoint.submittedAt ?: observedAt
            val evidence = AutomationActionEvidence.ResultUnobserved(observedAt, resolution.reason)
            results.holdUnresolved(accountId, stored, activeCheckpoint, evidence, successfulObservationCount, firstPendingAt)
            typedRuntime.complete(
                execution,
                TypedRuntimeOutcome.AmbiguousHandoff(
                    resolution.reason,
                    "TYPED_FISHING_AMBIGUITY_HELD",
                    successfulObservationCount,
                ),
            )
            decisionCycleId?.let { cycleId ->
                decisionJournal?.appendActionResult(cycleId, trace(
                    AutomationHistoryEventKind.WAITING,
                    "FISHING_AMBIGUITY_HELD",
                    resolution.reason,
                ))
            }
        }
        fun closeBattleForCaptcha(error: Throwable): Boolean {
            val gate = results.closeBattleForCaptcha(accountId, stored, resultSelection, convergenceAttemptId, error)
                ?: return false
            typedRuntime.complete(
                execution,
                TypedRuntimeOutcome.BattleGateBlocked(
                    warning = gate.warning,
                    wakeReason = TYPED_BATTLE_GATE_WAKE_REASON,
                ),
            )
            decisionCycleId?.let { cycleId -> runCatching {
                decisionJournal?.appendActionResult(cycleId, trace(
                    AutomationHistoryEventKind.WAITING,
                    ErrorCode.CAPTCHA_REQUIRED.name,
                    "캡차가 해결될 때까지 전투만 보류하고 저장된 전투는 폐기합니다.",
                ))
            } }
            scheduleConvergenceDirective(accountId, gate.directive)
            return true
        }
        if (decisionCycleId == null) {
            decisionCycleId = try {
                val reconciling = activeCheckpoint.phase == TypedRuntimeCheckpointPhase.RECONCILING
                val raidRecoveryHandoff = reconciling &&
                    (stored.payload as? StoredTypedActionPayload.BattleMap)?.let { payload ->
                        payload.source == BattleAutomationActionSource.RAID_AUTOMATION &&
                            payload.sourceTargetKey != null
                    } == true
                decisionJournal?.appendPreparedActionAttempt(
                    accountId,
                    trace(
                        if (reconciling) AutomationHistoryEventKind.WAITING else AutomationHistoryEventKind.SELECTED,
                        when {
                            raidRecoveryHandoff -> "RAID_BATTLE_RECOVERY_HANDOFF"
                            reconciling -> "AMBIGUOUS_RESULT_VERIFY"
                            else -> "PREPARED_ACTION_RETRY"
                        },
                        if (raidRecoveryHandoff) {
                            "저장된 레이드 전투의 불명확 결과를 전용 복구로 인계하고 다음 확인 시각까지 기다립니다."
                        } else if (reconciling) {
                            "이전 요청의 처리 결과가 불확실해 HOF 최신 상태로 적용 여부를 재확인합니다."
                        } else {
                            "저장된 작업을 이어서 재시도합니다."
                        },
                    ),
                )
            } catch (error: Exception) {
                stopPreparationFailure(accountId, execution, stored.entryId, "JOURNAL_RETRY", error)
                return
            }
        }
        val fishingPayload = stored.payload as? StoredTypedActionPayload.FishingTown
        if (
            restoredCheckpoint &&
            activeCheckpoint.phase == TypedRuntimeCheckpointPhase.PREPARED &&
            fishingPayload?.action != null &&
            fishingPayload.action in setOf(FishingAction.START, FishingAction.CATCH) &&
            managedAction is ManagedFishingAutomationAction &&
            managedAction.cycleObservation == null
        ) {
            results.discardLostFishingObservation(accountId, stored, FISHING_OBSERVATION_LOST_BEFORE_SUBMISSION)
            typedRuntime.complete(
                execution,
                TypedRuntimeOutcome.ActionSuperseded(
                    warning = "저장된 낚시 관측 form은 프로세스 경계를 넘어 재사용하지 않고 최신 상태를 다시 판단합니다.",
                    wakeReason = ACTION_SUPERSEDED_REASON,
                ),
            )
            return
        }
        if (resumedLegacyCheckpoint) {
            results.recoverLegacyCheckpoint(accountId, managedAction, stored, activeCheckpoint)?.let { recovery ->
                typedRuntime.complete(execution, recovery.outcome)
                recovery.directive?.let { scheduleConvergenceDirective(accountId, it) }
                return
            }
        }
        if (activeCheckpoint.phase == TypedRuntimeCheckpointPhase.RECONCILING) {
            val resolution = try {
                managedAction.handoffAmbiguousSubmission(
                    activeCheckpoint.submittedAt,
                    activeCheckpoint.diagnostic ?: "Stored raid battle submission outcome is ambiguous.",
                )?.let { handedOff ->
                    finishRaidBattleHandoff(handedOff)
                    return
                }
                managedAction.reconcile()
            } catch (error: Throwable) {
                error.findHofAutomationDeferral()?.let { deferred ->
                    val message = deferred.message ?: "HOF server returned 503 while verifying an ambiguous action."
                    val observedAt = now()
                    val firstPendingAt = activeCheckpoint.firstPendingAt ?: activeCheckpoint.submittedAt ?: observedAt
                    if (
                        AutomationConvergenceBudget.exhausted(
                            activeCheckpoint.successfulObservationCount,
                            firstPendingAt,
                            observedAt,
                        )
                    ) {
                        completeLegacyReconciliationBudget(
                            accountId = accountId,
                            execution = execution,
                            stored = stored,
                            actionDescriptor = actionDescriptor,
                            activeCheckpoint = activeCheckpoint,
                            observedAt = observedAt,
                            reason = message,
                            successfulObservation = false,
                            decisionCycleId = decisionCycleId,
                        )
                        return
                    }
                    decisionCycleId?.let { cycleId -> runCatching {
                        decisionJournal?.appendActionResult(cycleId, trace(
                            AutomationHistoryEventKind.WAITING,
                            "RECONCILIATION_HOF_DEFERRED",
                            "적용 여부를 확인하는 중 HOF 응답이 지연되어 다시 확인합니다. 사유: $message",
                            deferred.retryAt,
                        ))
                    } }
                    completeAndSchedule(
                        accountId,
                        execution,
                        TypedRuntimeOutcome.ReconciliationDeferred(
                            deferred.retryAt,
                            message,
                            successfulObservation = false,
                        ),
                        HOF_COOLDOWN_WAKE_REASON,
                    )
                    return
                }
                log.warn(
                    "Typed automation reconciliation stopped accountId={} errorType={}",
                    accountId,
                    error.javaClass.name,
                )
                val observedAt = now()
                val firstPendingAt = activeCheckpoint.firstPendingAt ?: activeCheckpoint.submittedAt ?: observedAt
                if (
                    AutomationConvergenceBudget.exhausted(
                        activeCheckpoint.successfulObservationCount,
                        firstPendingAt,
                        observedAt,
                    )
                ) {
                    completeLegacyReconciliationBudget(
                        accountId = accountId,
                        execution = execution,
                        stored = stored,
                        actionDescriptor = actionDescriptor,
                        activeCheckpoint = activeCheckpoint,
                        observedAt = observedAt,
                        reason = error.message ?: error.javaClass.simpleName,
                        successfulObservation = false,
                        decisionCycleId = decisionCycleId,
                    )
                    return
                }
                retryExecution(accountId, execution, classifyActionStop(error), error.message ?: error.javaClass.simpleName)
                return
            }
            when (resolution) {
                is AmbiguousActionResolution.Applied -> {
                    results.observeShadow(
                        accountId,
                        stored.executionIdentity,
                        AutomationActionEvidence.StateAdvanced(now(), "advanced:${stored.executionIdentity}"),
                        LegacyConvergenceDecision.APPLIED,
                    )
                    results.applyRecoveredExecution(accountId, resolution.execution)
                    typedRuntime.complete(
                        execution,
                        TypedRuntimeOutcome.ReconciliationApplied(recoveredWakeReason(resolution.execution)),
                    )
                    decisionCycleId?.let { cycleId ->
                        val resultTrace = when (val recovered = resolution.execution) {
                            is TypedAutomationExecution.RaidCycleFinished -> raidCycleTrace(recovered.outcome)
                            is TypedAutomationExecution.RaidWaiting -> raidWaitTrace(recovered)
                            else -> trace(
                                AutomationHistoryEventKind.ACTION_SUCCEEDED,
                                "AMBIGUOUS_RESULT_APPLIED",
                                "상태 재확인 결과 이전 요청이 이미 적용된 것으로 확인했습니다.",
                            )
                        }
                        decisionJournal?.appendActionResult(cycleId, resultTrace)
                    }
                }
                AmbiguousActionResolution.Resubmit -> {
                    val observedAt = now()
                    val reason = "권위 상태가 행동 전과 같아 적용 여부를 아직 확정할 수 없습니다."
                    val firstPendingAt = activeCheckpoint.firstPendingAt ?: activeCheckpoint.submittedAt ?: observedAt
                    if (
                        AutomationConvergenceBudget.exhausted(
                            activeCheckpoint.successfulObservationCount + 1,
                            firstPendingAt,
                            observedAt,
                        )
                    ) {
                        completeLegacyReconciliationBudget(
                            accountId = accountId,
                            execution = execution,
                            stored = stored,
                            actionDescriptor = actionDescriptor,
                            activeCheckpoint = activeCheckpoint,
                            observedAt = observedAt,
                            reason = reason,
                            successfulObservation = true,
                            decisionCycleId = decisionCycleId,
                        )
                        return
                    }
                    results.observeShadow(
                        accountId,
                        stored.executionIdentity,
                        AutomationActionEvidence.SameState(observedAt, "same:${stored.executionIdentity}"),
                        LegacyConvergenceDecision.RESUBMIT,
                    )
                    decisionCycleId?.let { cycleId ->
                        decisionJournal?.appendActionResult(cycleId, trace(
                            AutomationHistoryEventKind.WAITING,
                            "AMBIGUOUS_RESULT_UNCHANGED",
                            "$reason 저장 행동은 다시 제출하지 않습니다.",
                            observedAt.plusSeconds(RECONCILIATION_RETRY_SECONDS),
                        ))
                    }
                    completeAndSchedule(
                        accountId,
                        execution,
                        TypedRuntimeOutcome.ReconciliationDeferred(
                            observedAt.plusSeconds(RECONCILIATION_RETRY_SECONDS),
                            reason,
                            successfulObservation = true,
                        ),
                        "TYPED_RECONCILE_RETRY",
                    )
                }
                is AmbiguousActionResolution.Superseded -> {
                    results.observeShadow(
                        accountId,
                        stored.executionIdentity,
                        AutomationActionEvidence.StateAdvanced(now(), "superseded:${stored.executionIdentity}"),
                        LegacyConvergenceDecision.SUPERSEDED,
                    )
                    decisionCycleId?.let { cycleId ->
                        decisionJournal?.appendActionResult(cycleId, trace(
                            AutomationHistoryEventKind.SKIPPED,
                            ACTION_SUPERSEDED_REASON,
                            resolution.reason,
                        ))
                    }
                    typedRuntime.complete(
                        execution,
                        TypedRuntimeOutcome.ActionSuperseded(resolution.reason, ACTION_SUPERSEDED_REASON),
                    )
                }
                is AmbiguousActionResolution.FreshDecision -> {
                    val evidence = AutomationActionEvidence.ResultUnobservedFreshDecision(
                        capturedAt = now(),
                        reason = resolution.reason,
                    )
                    results.observeShadow(
                        accountId,
                        stored.executionIdentity,
                        evidence,
                        LegacyConvergenceDecision.RESULT_UNOBSERVED,
                    )
                    decisionCycleId?.let { cycleId ->
                        decisionJournal?.appendActionResult(cycleId, trace(
                            AutomationHistoryEventKind.SKIPPED,
                            QUEST_PROGRESS_FRESH_DECISION,
                            resolution.reason,
                        ))
                    }
                    typedRuntime.complete(
                        execution,
                        TypedRuntimeOutcome.ActionSuperseded(
                            resolution.reason,
                            QUEST_PROGRESS_FRESH_DECISION,
                        ),
                    )
                }
                is AmbiguousActionResolution.VerifyLater -> {
                    val observedAt = now()
                    val firstPendingAt = activeCheckpoint.firstPendingAt ?: activeCheckpoint.submittedAt ?: observedAt
                    if (
                        AutomationConvergenceBudget.exhausted(
                            activeCheckpoint.successfulObservationCount + 1,
                            firstPendingAt,
                            observedAt,
                        )
                    ) {
                        completeLegacyReconciliationBudget(
                            accountId = accountId,
                            execution = execution,
                            stored = stored,
                            actionDescriptor = actionDescriptor,
                            activeCheckpoint = activeCheckpoint,
                            observedAt = observedAt,
                            reason = resolution.reason,
                            successfulObservation = true,
                            decisionCycleId = decisionCycleId,
                        )
                        return
                    }
                    results.observeShadow(
                        accountId,
                        stored.executionIdentity,
                        AutomationActionEvidence.IncompleteObservation(observedAt, resolution.reason),
                        LegacyConvergenceDecision.RECONCILING,
                    )
                    decisionCycleId?.let { cycleId ->
                        decisionJournal?.appendActionResult(cycleId, trace(
                            AutomationHistoryEventKind.WAITING,
                            "AMBIGUOUS_RESULT_VERIFY_LATER",
                            "아직 적용 여부를 확정할 수 없어 다음 확인 시각까지 기다립니다. 사유: ${resolution.reason}",
                            resolution.retryAt,
                        ))
                    }
                    completeAndSchedule(
                        accountId,
                        execution,
                        TypedRuntimeOutcome.ReconciliationDeferred(
                            resolution.retryAt,
                            resolution.reason,
                            successfulObservation = true,
                        ),
                        "TYPED_RECONCILE_RETRY",
                    )
                }
                is AmbiguousActionResolution.Held -> holdAmbiguousScope(resolution)
                is AmbiguousActionResolution.HandedOff -> finishRaidBattleHandoff(resolution)
            }
            return
        }

        if (!results.postsEnabled) {
            val retryAt = now().plusSeconds(POST_KILL_SWITCH_RECHECK_SECONDS)
            decisionCycleId?.let { cycleId -> runCatching {
                decisionJournal?.appendActionResult(cycleId, trace(
                    AutomationHistoryEventKind.WAITING,
                    POST_KILL_SWITCH_WAKE_REASON,
                    "운영 안전장치가 자동화 제출을 일시 중지했습니다. 상태 확인은 계속 사용할 수 있습니다.",
                    retryAt,
                ))
            } }
            completeAndSchedule(
                accountId,
                execution,
                TypedRuntimeOutcome.ScheduledWait(retryAt, AutomationWaitReason.SCHEDULED),
                POST_KILL_SWITCH_WAKE_REASON,
            )
            return
        }

        if (
            fishingPayload?.action == FishingAction.CATCH &&
            managedAction is ManagedFishingAutomationAction &&
            managedAction.cycleObservation != null
        ) {
            runObservedFishingCatch(
                accountId = accountId,
                execution = execution,
                managed = managedAction,
                stored = stored,
                decisionCycleId = decisionCycleId,
                selectedWarnings = selectedWarnings,
                retryUnsubmitted = resumedDeferredSubmission,
            )
            return
        }
        if (
            fishingPayload?.action == FishingAction.START &&
            fishingCycleExecutor != null &&
            managedAction is ManagedFishingAutomationAction
        ) {
            runFishingCycle(
                accountId = accountId,
                initialExecution = execution,
                startManaged = managedAction,
                startStored = stored,
                decisionCycleId = decisionCycleId,
                selectedWarnings = selectedWarnings,
                retryUnsubmitted = resumedDeferredSubmission,
            )
            return
        }

        resultSelection.policy?.let { selection ->
            val directive = results.prepare(accountId, selection, resumedDeferredSubmission)
            when (directive) {
                is ConvergenceDirective.Submit -> convergenceAttemptId = directive.attemptId
                else -> {
                    releaseForConvergenceDirective(accountId, execution, directive)
                    return
                }
            }
        }

        try {
            managedAction.validateBeforeSubmission()
        } catch (changed: AutomationActionPreconditionChangedException) {
            val evidence = AutomationActionEvidence.StateAdvanced(
                capturedAt = now(),
                stateFingerprint = "precondition-changed:${stored.payload.kind()}",
            )
            val directive = convergenceAttemptId?.let { attemptId ->
                results.record(attemptId, evidence)
            }
            results.observeShadow(
                accountId,
                stored.executionIdentity,
                evidence,
                LegacyConvergenceDecision.SUPERSEDED,
            )
            typedRuntime.complete(
                execution,
                TypedRuntimeOutcome.ActionSuperseded(
                    warning = changed.message ?: "최신 상태에서 저장 행동의 사전조건이 사라졌습니다.",
                    wakeReason = ACTION_SUPERSEDED_REASON,
                ),
            )
            directive?.let { scheduleConvergenceDirective(accountId, it) }
            return
        } catch (incomplete: AutomationPreSubmitObservationIncompleteException) {
            val message = incomplete.message ?: "제출 직전 최신 상태를 완전하게 관측하지 못했습니다."
            val evidence = AutomationActionEvidence.IncompleteObservation(now(), message)
            val directive = convergenceAttemptId?.let { attemptId ->
                results.record(attemptId, evidence)
            }
            results.observeShadow(
                accountId,
                stored.executionIdentity,
                evidence,
                LegacyConvergenceDecision.RECONCILING,
            )
            if (directive != null) {
                typedRuntime.complete(
                    execution,
                    TypedRuntimeOutcome.PreparedDiscarded(message, TYPED_CONVERGENCE_WAKE_REASON),
                )
                scheduleConvergenceDirective(accountId, directive)
            } else {
                retryExecution(accountId, execution, AutomationStopReason.NETWORK, message)
            }
            return
        } catch (error: Throwable) {
            if (closeBattleForCaptcha(error)) return
            val message = error.message ?: "제출 직전 최신 상태 확인에 실패했습니다."
            val evidence = AutomationActionEvidence.NetworkFailure(now(), message)
            val directive = convergenceAttemptId?.let { attemptId ->
                results.record(attemptId, evidence)
            }
            results.observeShadow(
                accountId,
                stored.executionIdentity,
                evidence,
                LegacyConvergenceDecision.RECONCILING,
            )
            retryExecution(accountId, execution, classifyActionStop(error), message)
            directive?.let { scheduleConvergenceDirective(accountId, it) }
            return
        }

        val submission = typedRuntime.beginSubmission(execution)
        if (submission !is TypedRuntimeSubmission.Started) {
            convergenceAttemptId?.let { attemptId ->
                results.record(
                    attemptId,
                    AutomationActionEvidence.DirectRejected(now(), "SUBMISSION_NOT_STARTED"),
                )
            }
            typedRuntime.complete(
                execution,
                TypedRuntimeOutcome.SelectionChanged("TYPED_CONFIG_RELOAD"),
            )
            return
        }
        var appliedEvidence: AutomationActionEvidence? = null
        try {
            decisionCycleId?.let { cycleId ->
                decisionJournal?.appendActionResult(
                    cycleId,
                    trace(AutomationHistoryEventKind.ACTION_STARTED, "ACTION_STARTED", "자동화 행동을 시작했습니다."),
                )
            }
            val authorizedExecution = executeAuthorized(accountId) { managedAction.execute() }
            if (!authorizedExecution.authorized) {
                return discardUnauthorizedSubmission(accountId, execution, convergenceAttemptId)
            }
            val evidenceExecution = requireNotNull(authorizedExecution.value)
            appliedEvidence = results.directEvidence(resultSelection.evidence, evidenceExecution)
            val acceptedExecution = when (val connected = results.applyDirect(managedAction, evidenceExecution, appliedEvidence, convergenceAttemptId)) {
                is AutomationResultCoordinator.DirectResult.Accepted -> connected.execution
                is AutomationResultCoordinator.DirectResult.Unapplied -> {
                    typedRuntime.complete(execution, connected.outcome)
                    connected.directive?.let { scheduleConvergenceDirective(accountId, it) }
                    decisionCycleId?.let { cycleId ->
                        decisionJournal?.appendActionResult(cycleId, trace(
                            if (connected.superseded) AutomationHistoryEventKind.SKIPPED else AutomationHistoryEventKind.WAITING,
                            "ACTION_RESULT_NOT_APPLIED", connected.warning,
                        ))
                    }
                    return
                }
            }
            val domainExecution = acceptedExecution.runtimeDomainExecution()
            val storedBattle = stored.payload as? StoredTypedActionPayload.BattleMap
            val recoveryAppliedByTerminalResult =
                domainExecution is TypedAutomationExecution.BattleCompleted &&
                    storedBattle?.source == BattleAutomationActionSource.RAID_AUTOMATION &&
                    storedBattle.recoveryChainId != null
            val wakeReason = when (domainExecution) {
                TypedAutomationExecution.Completed -> "TYPED_ACTION_COMPLETED"
                is TypedAutomationExecution.ActionCompleted -> error("Action evidence must be unwrapped before runtime use.")
                is TypedAutomationExecution.BattleCompleted -> {
                    sharedBattleCooldowns.applyAfterSuccessfulBattle(
                        accountId,
                        domainExecution.categoryId,
                        domainExecution.mapCode,
                    )
                    if (recoveryAppliedByTerminalResult) {
                        RAID_BATTLE_APPLIED_TERMINAL_RESULT
                    } else {
                        "TYPED_ACTION_COMPLETED"
                    }
                }
                is TypedAutomationExecution.SharedCooldown -> {
                    sharedBattleCooldowns.learnAndApply(
                        accountId,
                        domainExecution.categoryId,
                        domainExecution.mapCode,
                        domainExecution.retryAt,
                    )
                    "TYPED_SHARED_COOLDOWN_SKIPPED"
                }
                is TypedAutomationExecution.RaidCycleFinished -> "TYPED_RAID_CYCLE_FINISHED"
                is TypedAutomationExecution.RaidWaiting -> "TYPED_RAID_WAITING"
            }
            val finalWarnings = if (
                domainExecution is TypedAutomationExecution.BattleCompleted &&
                (stored.payload as? StoredTypedActionPayload.BattleMap)?.source ==
                    BattleAutomationActionSource.RAID_AUTOMATION
            ) {
                selectedWarnings.orEmpty().filterNot { it.startsWith("레이드 전투 결과 미확정") }
            } else {
                selectedWarnings
            }
            val outcome = if (domainExecution is TypedAutomationExecution.SharedCooldown) {
                TypedRuntimeOutcome.SharedCooldownHandled(wakeReason, finalWarnings)
            } else {
                TypedRuntimeOutcome.ActionSucceeded(wakeReason, finalWarnings)
            }
            results.finishDirect(accountId, stored.executionIdentity, appliedEvidence, convergenceAttemptId, domainExecution)
            typedRuntime.complete(execution, outcome)
            decisionCycleId?.let { cycleId ->
                val resultTrace = when (domainExecution) {
                    is TypedAutomationExecution.RaidCycleFinished -> raidCycleTrace(domainExecution.outcome)
                    is TypedAutomationExecution.RaidWaiting -> raidWaitTrace(domainExecution)
                    is TypedAutomationExecution.BattleCompleted if recoveryAppliedByTerminalResult -> trace(
                        AutomationHistoryEventKind.ACTION_SUCCEEDED,
                        RAID_BATTLE_APPLIED_TERMINAL_RESULT,
                        "정확한 전투 단말 결과로 레이드 전투 적용을 확인하고 복구를 종료했습니다.",
                    )
                    is TypedAutomationExecution.SharedCooldown if
                        (stored.payload as? StoredTypedActionPayload.BattleMap)?.source ==
                            BattleAutomationActionSource.RAID_AUTOMATION -> trace(
                        AutomationHistoryEventKind.ACTION_SUCCEEDED,
                        wakeReason,
                        "HOF가 명시한 쿨타임까지 레이드 전투만 기다립니다.",
                        domainExecution.retryAt,
                        diagnosticKind = AutomationDiagnosticKind.RAID_EXPLICIT_COOLDOWN_WAIT,
                        impactScope = AutomationImpactScope.RAID_ONLY,
                        releaseCondition = "HOF가 준 시각 뒤 최신 레이드 상태 재확인",
                    )
                    else -> trace(
                        AutomationHistoryEventKind.ACTION_SUCCEEDED,
                        wakeReason,
                        "자동화 행동을 완료했습니다.",
                    )
                }
                decisionJournal?.appendActionResult(cycleId, resultTrace)
            }
        } catch (error: Throwable) {
            error.findActionPreconditionChanged()?.let { changed ->
                val evidence = AutomationActionEvidence.StateAdvanced(
                    capturedAt = now(),
                    stateFingerprint = "precondition-changed:${stored.payload.kind()}",
                )
                val directive = convergenceAttemptId?.let { attemptId ->
                    results.record(attemptId, evidence)
                }
                results.observeShadow(
                    accountId,
                    stored.executionIdentity,
                    evidence,
                    LegacyConvergenceDecision.SUPERSEDED,
                )
                decisionCycleId?.let { cycleId -> runCatching {
                    decisionJournal?.appendActionResult(
                        cycleId,
                        trace(
                            AutomationHistoryEventKind.SKIPPED,
                            ACTION_SUPERSEDED_REASON,
                            "제출 직전 최신 상태가 바뀌어 저장 행동을 폐기하고 새로 판단합니다. 사유: ${changed.message}",
                        ),
                    )
                } }
                typedRuntime.complete(
                    execution,
                    TypedRuntimeOutcome.ActionSuperseded(
                        warning = changed.message ?: "최신 상태에서 저장 행동의 사전조건이 사라졌습니다.",
                        wakeReason = ACTION_SUPERSEDED_REASON,
                    ),
                )
                directive?.let { scheduleConvergenceDirective(accountId, it) }
                return
            }
            if (closeBattleForCaptcha(error)) return
            val unsubmittedBattle = generateSequence(error) { it.cause }
                .filterIsInstance<BattleNotSubmittedException>()
                .firstOrNull()
            unsubmittedBattle?.let { failure ->
                resultSelection.policy?.let { selection ->
                    results.discardUnsubmitted(
                        accountId, selection, now(), BattleNotSubmittedException.REASON_CODE,
                    )
                }
                results.observeShadow(
                    accountId,
                    stored.executionIdentity,
                    AutomationActionEvidence.DirectRejected(now(), BattleNotSubmittedException.REASON_CODE),
                    LegacyConvergenceDecision.SUPERSEDED,
                )
                val message = requireNotNull(failure.message)
                val retryAt = typedRuntime.complete(execution, TypedRuntimeOutcome.UnsubmittedFailure(message)).nextAttemptAt
                retryAt?.let { wakeupPort.schedule(accountId, it, HOF_COOLDOWN_WAKE_REASON) }
                decisionCycleId?.let { cycleId ->
                    decisionJournal?.appendActionResult(cycleId, trace(
                        AutomationHistoryEventKind.WAITING, BattleNotSubmittedException.REASON_CODE, message, retryAt,
                    ))
                }
                return
            }
            error.findHofAutomationDeferral()?.let { deferred ->
                val evidence = if (deferred.actionSubmissionAttempted) {
                    AutomationActionEvidence.NetworkFailure(
                        capturedAt = now(),
                        reason = deferred.message ?: "HOF_DEFERRED_AFTER_REQUEST",
                    )
                } else {
                    AutomationActionEvidence.DirectRejected(
                        capturedAt = now(),
                        reason = "SUBMISSION_NOT_ATTEMPTED",
                    )
                }
                val directive = convergenceAttemptId?.let { attemptId ->
                    if (deferred.actionSubmissionAttempted) {
                        results.record(attemptId, evidence)
                    } else {
                        resultSelection.policy?.let { selection ->
                            results.discardUnsubmitted(
                                accountId = accountId,
                                selection = selection,
                                discardedAt = now(),
                                reasonCode = deferred.reasonCode ?: "SUBMISSION_NOT_ATTEMPTED",
                            )
                        }
                        ConvergenceDirective.ContinueSelection
                    }
                }
                results.observeShadow(
                    accountId,
                    stored.executionIdentity,
                    evidence,
                    if (deferred.actionSubmissionAttempted) {
                        LegacyConvergenceDecision.RECONCILING
                    } else {
                        LegacyConvergenceDecision.SUPERSEDED
                    },
                )
                decisionCycleId?.let { cycleId -> runCatching {
                    decisionJournal?.appendActionResult(cycleId, trace(
                        AutomationHistoryEventKind.WAITING,
                        if (deferred.actionSubmissionAttempted) "ACTION_SUBMISSION_AMBIGUOUS" else "ACTION_HOF_DEFERRED",
                        if (deferred.actionSubmissionAttempted) {
                            "HOF 요청 결과가 불확실해 같은 행동을 다시 보내지 않고 최신 상태를 확인합니다."
                        } else {
                            "HOF 서버가 잠시 요청을 받지 않아 제출 전 상태를 보존하고 재시도합니다."
                        },
                        deferred.retryAt.takeUnless { deferred.actionSubmissionAttempted },
                    ))
                } }
                if (deferred.actionSubmissionAttempted) {
                    typedRuntime.complete(
                        execution,
                        TypedRuntimeOutcome.SubmissionAmbiguous(
                            deferred.message ?: "HOF submission result is ambiguous.",
                        ),
                    )
                    directive?.let { scheduleConvergenceDirective(accountId, it) }
                } else {
                    completeAndSchedule(
                        accountId,
                        execution,
                        TypedRuntimeOutcome.SubmissionDeferred(
                            deferred.retryAt,
                            deferred.message ?: "HOF request spacing is deferred.",
                        ),
                        HOF_COOLDOWN_WAKE_REASON,
                    )
                }
                return
            }
            error.findAmbiguousSubmission()?.let { ambiguous ->
                val message = ambiguous.message ?: "Automation submission outcome is ambiguous."
                val handedOff = runCatching {
                    managedAction.handoffAmbiguousSubmission(submission.submittedAt, message)
                }.onFailure { handoffError ->
                    log.warn(
                        "Typed automation ambiguous handoff failed accountId={} errorType={}",
                        accountId,
                        handoffError.javaClass.name,
                    )
                }.getOrNull()
                if (handedOff != null) {
                    val evidence = AutomationActionEvidence.IncompleteObservation(
                        capturedAt = now(),
                        reason = handedOff.reason,
                        responseShapeFingerprint = ambiguous.responseShapeFingerprint,
                        sanitizedSnippet = ambiguous.sanitizedSnippet,
                    )
                    val directive = convergenceAttemptId?.let { attemptId ->
                        results.record(attemptId, evidence)
                    }
                    results.observeShadow(
                        accountId,
                        stored.executionIdentity,
                        evidence,
                        LegacyConvergenceDecision.RECONCILING,
                    )
                    finishRaidBattleHandoff(handedOff)
                    directive?.let { scheduleConvergenceDirective(accountId, it) }
                    return
                }
                convergenceAttemptId?.let { attemptId ->
                    val directive = results.record(
                        attemptId,
                        AutomationActionEvidence.IncompleteObservation(
                            capturedAt = now(),
                            reason = ambiguous.message ?: "SUBMISSION_RESULT_UNKNOWN",
                            responseShapeFingerprint = ambiguous.responseShapeFingerprint,
                            sanitizedSnippet = ambiguous.sanitizedSnippet,
                        ),
                    )
                    if (directive != null) {
                        typedRuntime.complete(
                            execution,
                            TypedRuntimeOutcome.AmbiguousHandoff(
                                warning = message,
                                wakeReason = TYPED_CONVERGENCE_WAKE_REASON,
                            ),
                        )
                        scheduleConvergenceDirective(accountId, directive)
                        return
                    }
                }
                results.observeShadow(
                    accountId,
                    stored.executionIdentity,
                    appliedEvidence ?: AutomationActionEvidence.IncompleteObservation(
                        now(),
                        message,
                        responseShapeFingerprint = ambiguous.responseShapeFingerprint,
                        sanitizedSnippet = ambiguous.sanitizedSnippet,
                    ),
                    LegacyConvergenceDecision.RECONCILING,
                )
                decisionCycleId?.let { cycleId -> runCatching {
                    decisionJournal?.appendActionResult(cycleId, trace(
                        AutomationHistoryEventKind.WAITING,
                        "ACTION_RESULT_AMBIGUOUS",
                        "요청 전송 후 결과가 불확실합니다. 같은 동작을 즉시 다시 보내지 않고 HOF 상태를 재확인합니다. 사유: ${ambiguous.message ?: "응답 확인 실패"}",
                    ))
                } }
                typedRuntime.complete(execution, TypedRuntimeOutcome.SubmissionAmbiguous(message))
                return
            }
            log.warn("Typed automation action stopped accountId={} errorType={}", accountId, error.javaClass.name)
            val evidence = AutomationActionEvidence.ResultUnobserved(
                capturedAt = now(),
                reason = error.javaClass.simpleName,
            )
            convergenceAttemptId?.let { attemptId ->
                results.record(attemptId, evidence)
            }
            results.observeShadow(
                accountId,
                stored.executionIdentity,
                evidence,
                LegacyConvergenceDecision.RESULT_UNOBSERVED,
            )
            decisionCycleId?.let { cycleId -> runCatching {
                decisionJournal?.appendActionResult(
                    cycleId,
                    trace(AutomationHistoryEventKind.ACTION_FAILED, "ACTION_FAILED", error.message ?: error.javaClass.simpleName),
                )
            } }
            retryExecution(accountId, execution, classifyActionStop(error), error.message ?: error.javaClass.simpleName)
        }
    }

    private fun runObservedFishingCatch(
        accountId: Long,
        execution: TypedRuntimeExecutionRight,
        managed: ManagedFishingAutomationAction,
        stored: StoredTypedAutomationAction,
        decisionCycleId: Long?,
        selectedWarnings: List<String>?,
        retryUnsubmitted: Boolean,
    ) {
        val selection = results.fishingSelection(stored)
        var attemptId: Long? = null
        var attemptTerminalized = false

        fun append(kind: AutomationHistoryEventKind, code: String, message: String) {
            decisionCycleId?.let { cycleId ->
                try {
                    decisionJournal?.appendActionResult(
                        cycleId,
                        actionTrace(stored, kind, code, message, descriptor = managed.descriptor,
                            diagnosticContext = managed.diagnosticContext),
                    )
                } catch (error: RuntimeException) {
                    log.warn("Fishing result history unavailable accountId={} executionIdentity={} errorType={}",
                        accountId, stored.executionIdentity, error.javaClass.name)
                }
            }
        }

        results.prepareFishing(accountId, selection, retryUnsubmitted)?.let { directive ->
            when (directive) {
                is ConvergenceDirective.Submit -> attemptId = directive.attemptId
                else -> {
                    releaseForConvergenceDirective(accountId, execution, directive)
                    return
                }
            }
        }
        val submission = typedRuntime.beginSubmission(execution)
        if (submission !is TypedRuntimeSubmission.Started) {
            attemptId?.let { id ->
                results.record(
                    id,
                    AutomationActionEvidence.DirectRejected(now(), "SUBMISSION_NOT_STARTED"),
                )
            }
            typedRuntime.complete(execution, TypedRuntimeOutcome.SelectionChanged("TYPED_CONFIG_RELOAD"))
            return
        }
        try {
            append(AutomationHistoryEventKind.ACTION_STARTED, "ACTION_STARTED", "낚시 CATCH를 시작했습니다.")
            val authorizedExecution = executeAuthorized(accountId) { managed.executeObservedResponse() }
            if (!authorizedExecution.authorized) {
                return discardUnauthorizedSubmission(accountId, execution, attemptId)
            }
            val direct = authorizedExecution.value
                ?: throw AutomationActionPreconditionChangedException("재사용할 최신 CATCH 관측이 없습니다.")
            val evidence = results.directEvidence(selection, direct.execution)
            when (val result = results.applyFishingDirect(
                managed, direct.execution, evidence, attemptId,
                "낚시 CATCH 직접 응답이 적용을 확정하지 못했습니다.",
            )) {
                is AutomationResultCoordinator.DirectResult.Unapplied -> {
                    typedRuntime.complete(execution, result.outcome)
                    result.directive?.let { scheduleConvergenceDirective(accountId, it) }
                    return
                }
                is AutomationResultCoordinator.DirectResult.Accepted -> attemptTerminalized = attemptId != null
            }
            evidence?.let { observed ->
                results.observeShadow(
                    accountId,
                    stored.executionIdentity,
                    observed,
                    LegacyConvergenceDecision.APPLIED,
                )
            }
            typedRuntime.complete(
                execution,
                TypedRuntimeOutcome.ActionSucceeded("TYPED_FISHING_CYCLE_COMPLETED", selectedWarnings),
            )
            append(
                AutomationHistoryEventKind.ACTION_SUCCEEDED,
                "FISHING_CATCH_APPLIED",
                "낚시 CATCH 적용을 확인해 한 번 낚시를 완료했습니다.",
            )
        } catch (error: Throwable) {
            error.findActionPreconditionChanged()?.let { changed ->
                attemptId?.let { id ->
                    results.record(
                        id,
                        AutomationActionEvidence.StateAdvanced(
                            now(),
                            "precondition-changed:${stored.payload.kind()}",
                        ),
                    )
                }
                typedRuntime.complete(
                    execution,
                    TypedRuntimeOutcome.ActionSuperseded(
                        changed.message ?: "최신 낚시 상태가 바뀌었습니다.",
                        ACTION_SUPERSEDED_REASON,
                    ),
                )
                return
            }
            error.findHofAutomationDeferral()?.takeIf { !it.actionSubmissionAttempted }?.let { deferred ->
                if (attemptId != null && selection != null) {
                    results.discardUnsubmitted(
                        accountId = accountId,
                        selection = selection,
                        discardedAt = now(),
                        reasonCode = deferred.reasonCode ?: "SUBMISSION_NOT_ATTEMPTED",
                    )
                }
                completeAndSchedule(
                    accountId,
                    execution,
                    TypedRuntimeOutcome.SubmissionDeferred(
                        deferred.retryAt,
                        deferred.message ?: "HOF 요청 간격을 기다립니다.",
                    ),
                    HOF_COOLDOWN_WAKE_REASON,
                )
                return
            }
            val message = error.message ?: "낚시 CATCH 제출 결과가 불확실합니다."
            attemptId?.takeUnless { attemptTerminalized }?.let { id ->
                results.record(id, AutomationActionEvidence.IncompleteObservation(now(), message))
            }
            results.observeShadow(
                accountId,
                stored.executionIdentity,
                AutomationActionEvidence.IncompleteObservation(now(), message),
                LegacyConvergenceDecision.RECONCILING,
            )
            typedRuntime.complete(execution, TypedRuntimeOutcome.SubmissionAmbiguous(message))
            append(
                AutomationHistoryEventKind.WAITING,
                "FISHING_STAGE_AMBIGUOUS",
                "낚시 CATCH 결과가 불확실해 같은 POST를 다시 보내지 않고 최신 상태를 확인합니다.",
            )
        }
    }

    private fun runFishingCycle(
        accountId: Long,
        initialExecution: TypedRuntimeExecutionRight,
        startManaged: ManagedFishingAutomationAction,
        startStored: StoredTypedAutomationAction,
        decisionCycleId: Long?,
        selectedWarnings: List<String>?,
        retryUnsubmitted: Boolean,
    ) {
        val cycleExecutor = requireNotNull(fishingCycleExecutor)
        val startPayload = startStored.payload as StoredTypedActionPayload.FishingTown
        val catchExecutionIdentity = java.util.UUID.randomUUID().toString()
        val command = FishingCycleCommand(
            accountId = accountId,
            cycleIdentity = startStored.executionIdentity,
            startExecutionIdentity = startStored.executionIdentity,
            catchExecutionIdentity = catchExecutionIdentity,
            observation = startManaged.cycleObservation,
        )
        var execution = initialExecution
        var activeStored = startStored
        var activeManaged: ManagedAutomationAction = startManaged
        var activeAttemptId: Long? = null
        var activeAttemptTerminalized = false
        var activeSelection = results.fishingSelection(startStored)
        var handled = false

        fun append(
            stored: StoredTypedAutomationAction,
            managed: ManagedAutomationAction,
            kind: AutomationHistoryEventKind,
            code: String,
            message: String,
        ) {
            decisionCycleId?.let { cycleId ->
                try {
                    decisionJournal?.appendActionResult(
                        cycleId,
                        actionTrace(stored, kind, code, message, descriptor = managed.descriptor,
                            diagnosticContext = managed.diagnosticContext),
                    )
                } catch (error: RuntimeException) {
                    log.warn("Fishing result history unavailable accountId={} executionIdentity={} errorType={}",
                        accountId, stored.executionIdentity, error.javaClass.name)
                }
            }
        }

        fun prepareConvergence(
            stored: StoredTypedAutomationAction,
            selection: SelectedAutomationAction?,
        ): Long? {
            val directive = results.prepareFishing(
                accountId, selection,
                retryUnsubmitted && stored.executionIdentity == startStored.executionIdentity,
            ) ?: return null
            return when (directive) {
                is ConvergenceDirective.Submit -> directive.attemptId
                else -> {
                    releaseForConvergenceDirective(accountId, execution, directive)
                    throw FishingCycleFlowStopped()
                }
            }
        }

        fun acceptStep(
            managed: ManagedFishingAutomationAction,
            stored: StoredTypedAutomationAction,
            selection: SelectedAutomationAction?,
            attemptId: Long?,
            response: app.spammy.hof.town.fishing.dto.FishingResponse,
        ) {
            val direct = managed.observeDirectResponse(response)
            val evidence = results.directEvidence(selection, direct)
            when (val result = results.applyFishingDirect(
                managed, direct, evidence, attemptId,
                "낚시 직접 응답이 현재 단계를 확정하지 못했습니다.",
            )) {
                is AutomationResultCoordinator.DirectResult.Unapplied -> {
                    typedRuntime.complete(execution, result.outcome)
                    result.directive?.let { scheduleConvergenceDirective(accountId, it) }
                    throw FishingCycleFlowStopped()
                }
                is AutomationResultCoordinator.DirectResult.Accepted -> activeAttemptTerminalized = attemptId != null
            }
            evidence?.let { observed ->
                results.observeShadow(
                    accountId,
                    stored.executionIdentity,
                    observed,
                    LegacyConvergenceDecision.APPLIED,
                )
            }
        }

        try {
            activeAttemptId = prepareConvergence(startStored, activeSelection)
            val submission = typedRuntime.beginSubmission(execution)
            if (submission !is TypedRuntimeSubmission.Started) {
                activeAttemptId?.let { attemptId ->
                    results.record(
                        attemptId,
                        AutomationActionEvidence.DirectRejected(now(), "SUBMISSION_NOT_STARTED"),
                    )
                }
                typedRuntime.complete(execution, TypedRuntimeOutcome.SelectionChanged("TYPED_CONFIG_RELOAD"))
                return
            }
            append(startStored, startManaged, AutomationHistoryEventKind.ACTION_STARTED, "ACTION_STARTED", "낚시 START를 시작했습니다.")
            cycleExecutor.executeOneCast(command, object : FishingCycleTransitions {
                override fun startAppliedAndCatchPrepared(
                    command: FishingCycleCommand,
                    start: FishingCycleStepEvidence,
                    catch: FishingCyclePreparedCatch,
                ) {
                    acceptStep(startManaged, startStored, activeSelection, activeAttemptId, start.response)
                    val catchStored = StoredTypedAutomationAction(
                        entryId = startStored.entryId,
                        executionIdentity = catch.executionIdentity,
                        payload = StoredTypedActionPayload.FishingTown(
                            action = FishingAction.CATCH,
                            observedPrimaryAction = catch.observed.primaryAction,
                            observedRemainingCasts = catch.observed.remainingCasts,
                            progressDate = startPayload.progressDate,
                        ),
                    )
                    val catchManaged = actionLifecycleModule.restoreVerified(catchStored, accountId)
                        as? ManagedFishingAutomationAction
                        ?: throw IllegalStateException("Stored fishing CATCH is not managed as a fishing action.")
                    val preparation = typedRuntime.advanceAppliedActionToPreparedFollowup(execution, catchStored)
                    if (preparation !is TypedRuntimePreparation.Ready) {
                        typedRuntime.complete(
                            execution,
                            TypedRuntimeOutcome.SubmissionAmbiguous("START 적용 뒤 CATCH 준비 상태를 저장하지 못했습니다."),
                        )
                        throw FishingCycleFlowStopped()
                    }
                    append(startStored, startManaged, AutomationHistoryEventKind.ACTION_SUCCEEDED, "FISHING_START_APPLIED", "낚시 START 적용을 확인했습니다.")
                    execution = preparation.execution
                    activeStored = catchStored
                    activeManaged = catchManaged
                    activeSelection = results.fishingSelection(catchStored)
                    activeAttemptTerminalized = false
                    activeAttemptId = prepareConvergence(catchStored, activeSelection)
                    val catchSubmission = typedRuntime.beginSubmission(execution)
                    if (catchSubmission !is TypedRuntimeSubmission.Started) {
                        activeAttemptId?.let { attemptId ->
                            results.record(
                                attemptId,
                                AutomationActionEvidence.DirectRejected(now(), "SUBMISSION_NOT_STARTED"),
                            )
                        }
                        typedRuntime.complete(execution, TypedRuntimeOutcome.SelectionChanged("TYPED_CONFIG_RELOAD"))
                        throw FishingCycleFlowStopped()
                    }
                    append(catchStored, catchManaged, AutomationHistoryEventKind.ACTION_STARTED, "ACTION_STARTED", "낚시 CATCH를 시작했습니다.")
                }

                override fun catchApplied(
                    command: FishingCycleCommand,
                    catch: FishingCycleStepEvidence,
                ) {
                    val catchManaged = activeManaged as? ManagedFishingAutomationAction
                        ?: error("Prepared fishing CATCH is not managed as a fishing action.")
                    acceptStep(catchManaged, activeStored, activeSelection, activeAttemptId, catch.response)
                    typedRuntime.complete(
                        execution,
                        TypedRuntimeOutcome.ActionSucceeded("TYPED_FISHING_CYCLE_COMPLETED", selectedWarnings),
                    )
                    append(
                        activeStored,
                        activeManaged,
                        AutomationHistoryEventKind.ACTION_SUCCEEDED,
                        "FISHING_CATCH_APPLIED",
                        "낚시 CATCH 적용을 확인해 한 번 낚시를 완료했습니다.",
                    )
                    handled = true
                }

                override fun battleRequired(
                    command: FishingCycleCommand,
                    start: FishingCycleStepEvidence,
                ) {
                    val evidence = AutomationActionEvidence.StateAdvanced(
                        now(),
                        "fishing-start-observed-pending-battle:${start.response.battleTarget?.mapCode ?: "unknown"}",
                    )
                    val direct = startManaged.observeDirectResponse(start.response)
                    results.resolveFishingStateAdvanced(startManaged, direct, evidence, activeAttemptId)
                    activeAttemptTerminalized = activeAttemptId != null
                    results.observeShadow(
                        accountId,
                        startStored.executionIdentity,
                        evidence,
                        LegacyConvergenceDecision.SUPERSEDED,
                    )
                    typedRuntime.complete(
                        execution,
                        TypedRuntimeOutcome.ActionSuperseded(
                            "START 응답에서 이전 낚시 전투를 확인해 최신 상태로 다시 판단합니다.",
                            ACTION_SUPERSEDED_REASON,
                        ),
                    )
                    append(
                        startStored,
                        startManaged,
                        AutomationHistoryEventKind.SKIPPED,
                        "FISHING_BATTLE_RECOVERED_FROM_START",
                        "START는 성공으로 귀속하지 않고 낚시 작업권을 놓았습니다. 다음 판단에서 방해 전투를 확인합니다.",
                    )
                    handled = true
                }

                override fun waitingForCatch(
                    command: FishingCycleCommand,
                    start: FishingCycleStepEvidence,
                ) {
                    acceptStep(startManaged, startStored, activeSelection, activeAttemptId, start.response)
                    typedRuntime.complete(
                        execution,
                        TypedRuntimeOutcome.ActionSucceeded("TYPED_FISHING_WAITING_FOR_CATCH", selectedWarnings),
                    )
                    append(
                        startStored,
                        startManaged,
                        AutomationHistoryEventKind.WAITING,
                        "FISHING_WAITING_FOR_CATCH",
                        "START 응답에 CATCH form이 없어 다음 판단에서 한 번만 다시 확인합니다.",
                    )
                    handled = true
                }
            })
            check(handled) { "Fishing cycle finished without a durable terminal transition." }
        } catch (_: FishingCycleFlowStopped) {
            return
        } catch (_: FishingSubmissionAuthorizationCancelledException) {
            discardUnauthorizedSubmission(accountId, execution, activeAttemptId)
            return
        } catch (error: Throwable) {
            error.findActionPreconditionChanged()?.let { changed ->
                activeAttemptId?.let { attemptId ->
                    results.record(
                        attemptId,
                        AutomationActionEvidence.StateAdvanced(
                            now(),
                            "precondition-changed:${activeStored.payload.kind()}",
                        ),
                    )
                }
                typedRuntime.complete(
                    execution,
                    TypedRuntimeOutcome.ActionSuperseded(
                        changed.message ?: "최신 낚시 상태가 바뀌었습니다.",
                        ACTION_SUPERSEDED_REASON,
                    ),
                )
                return
            }
            error.findHofAutomationDeferral()?.takeIf { !it.actionSubmissionAttempted }?.let { deferred ->
                if (activeAttemptId != null && activeSelection != null) {
                    results.discardUnsubmitted(
                        accountId = accountId,
                        selection = requireNotNull(activeSelection),
                        discardedAt = now(),
                        reasonCode = deferred.reasonCode ?: "SUBMISSION_NOT_ATTEMPTED",
                    )
                }
                completeAndSchedule(
                    accountId,
                    execution,
                    TypedRuntimeOutcome.SubmissionDeferred(
                        deferred.retryAt,
                        deferred.message ?: "HOF 요청 간격을 기다립니다.",
                    ),
                    HOF_COOLDOWN_WAKE_REASON,
                )
                return
            }
            val message = error.message ?: "낚시 단계 제출 결과가 불확실합니다."
            activeAttemptId?.takeUnless { activeAttemptTerminalized }?.let { attemptId ->
                results.record(
                    attemptId,
                    AutomationActionEvidence.IncompleteObservation(now(), message),
                )
            }
            results.observeShadow(
                accountId,
                activeStored.executionIdentity,
                AutomationActionEvidence.IncompleteObservation(now(), message),
                LegacyConvergenceDecision.RECONCILING,
            )
            typedRuntime.complete(execution, TypedRuntimeOutcome.SubmissionAmbiguous(message))
            append(
                activeStored,
                activeManaged,
                AutomationHistoryEventKind.WAITING,
                "FISHING_STAGE_AMBIGUOUS",
                "낚시 요청 결과가 불확실해 같은 POST를 다시 보내지 않고 최신 상태를 확인합니다.",
            )
        }
    }

    private fun <T> executeAuthorized(accountId: Long, submission: () -> T): AuthorizedExecution<T> {
        var result: Any? = SUBMISSION_NOT_EXECUTED
        val authorized = submissionGate.executeIfAuthorized(accountId, Runnable { result = submission() })
        if (!authorized) return AuthorizedExecution(false, null)
        check(result !== SUBMISSION_NOT_EXECUTED) { "Authorized submission did not execute." }
        @Suppress("UNCHECKED_CAST")
        return AuthorizedExecution(true, result as T)
    }

    private fun discardUnauthorizedSubmission(
        accountId: Long,
        execution: TypedRuntimeExecutionRight,
        convergenceAttemptId: Long?,
    ) {
        convergenceAttemptId?.let { attemptId ->
            results.record(
                attemptId,
                AutomationActionEvidence.DirectRejected(now(), AUTHORIZATION_ENDED_REASON),
            )
        }
        typedRuntime.complete(
            execution,
            TypedRuntimeOutcome.SubmissionDeferred(now(), "로그아웃되어 제출하지 않은 자동화 행동을 폐기했습니다."),
        )
        log.info("Discarded unsubmitted typed action after authentication ended accountId={}", accountId)
    }

    private class FishingCycleFlowStopped : RuntimeException()

    private fun runConvergenceProbe(
        accountId: Long,
        execution: TypedRuntimeExecutionRight,
        directive: ConvergenceDirective.Probe,
    ) {
        val next = results.probe(accountId, directive) ?: return
        typedRuntime.complete(
            execution,
            TypedRuntimeOutcome.SelectionChanged(TYPED_CONVERGENCE_WAKE_REASON),
        )
        scheduleConvergenceDirective(accountId, next)
    }

    private fun completeLegacyReconciliationBudget(
        accountId: Long,
        execution: TypedRuntimeExecutionRight,
        stored: StoredTypedAutomationAction,
        actionDescriptor: AutomationActionDescriptor,
        activeCheckpoint: TypedRuntimeCheckpoint,
        observedAt: Instant,
        reason: String,
        successfulObservation: Boolean,
        decisionCycleId: Long?,
    ) {
        val successfulObservationCount = activeCheckpoint.successfulObservationCount +
            if (successfulObservation) 1 else 0
        val firstPendingAt = activeCheckpoint.firstPendingAt ?: activeCheckpoint.submittedAt ?: observedAt
        val sanitizedReason = reason.take(500)
        val warning = "이전 요청 결과를 최대 5회 또는 2분 안에 확정하지 못해 해당 상태만 보류합니다. " +
            "성공 관측 ${successfulObservationCount}회 · 마지막 사유: $sanitizedReason"
        val evidence = AutomationActionEvidence.ResultUnobserved(observedAt, sanitizedReason)
        results.holdUnresolved(accountId, stored, activeCheckpoint, evidence, successfulObservationCount, firstPendingAt)
        decisionCycleId?.let { cycleId ->
            decisionJournal?.appendActionResult(cycleId, actionTrace(
                stored,
                AutomationHistoryEventKind.SKIPPED,
                TYPED_RECONCILIATION_BUDGET_EXHAUSTED,
                warning,
                descriptor = actionDescriptor,
            ))
        }
        typedRuntime.complete(
            execution,
            TypedRuntimeOutcome.AmbiguousHandoff(
                warning,
                TYPED_RECONCILIATION_BUDGET_EXHAUSTED,
                successfulObservationCount,
            ),
        )
    }

    private fun releaseForConvergenceDirective(
        accountId: Long,
        execution: TypedRuntimeExecutionRight,
        directive: ConvergenceDirective,
    ) {
        typedRuntime.complete(
            execution,
            TypedRuntimeOutcome.SelectionChanged(TYPED_CONVERGENCE_WAKE_REASON),
        )
        scheduleConvergenceDirective(accountId, directive)
    }

    private fun scheduleConvergenceDirective(accountId: Long, directive: ConvergenceDirective) {
        if (directive is ConvergenceDirective.WaitUntil) {
            wakeupPort.schedule(accountId, directive.at, TYPED_CONVERGENCE_PROBE_REASON)
        }
    }

    private fun now(): Instant = timeProvider?.now() ?: Instant.now()

    private fun completeAndSchedule(
        accountId: Long,
        execution: TypedRuntimeExecutionRight,
        outcome: TypedRuntimeOutcome,
        wakeReason: String,
    ) {
        typedRuntime.complete(execution, outcome).nextAttemptAt?.let { retryAt ->
            wakeupPort.schedule(accountId, retryAt, wakeReason)
        }
    }

    private fun retryExecution(
        accountId: Long,
        execution: TypedRuntimeExecutionRight,
        reason: AutomationStopReason,
        message: String?,
    ) {
        completeAndSchedule(
            accountId,
            execution,
            TypedRuntimeOutcome.RetryableFailure(reason, message ?: reason.name),
            AUTOMATIC_RETRY_WAKE_REASON,
        )
    }

    private fun stopPreparationFailure(
        accountId: Long,
        execution: TypedRuntimeExecutionRight,
        entryId: Long,
        stage: String,
        error: Exception,
    ) {
        val interrupted = generateSequence<Throwable>(error) { it.cause }
            .any { it is InterruptedException }
        log.error(
            "Typed automation preparation failed accountId={} entryId={} stage={} errorType={}",
            accountId,
            entryId,
            stage,
            error.javaClass.name,
            error,
        )
        try {
            retryExecution(
                accountId,
                execution,
                AutomationStopReason.FATAL,
                error.message ?: error.javaClass.simpleName,
            )
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun classifyActionStop(error: Throwable): AutomationStopReason {
        val causes = generateSequence(error) { it.cause }.toList()
        if (causes.any { it is AutomationLoginRequiredException }) return AutomationStopReason.AUTHENTICATION
        causes.filterIsInstance<ApiException>().firstOrNull()?.let { api ->
            return when (api.errorCode) {
                ErrorCode.CAPTCHA_REQUIRED -> AutomationStopReason.CAPTCHA
                ErrorCode.HOF_LOGIN_FAILED, ErrorCode.HOF_SESSION_EXPIRED -> AutomationStopReason.AUTHENTICATION
                else -> AutomationStopReason.FATAL
            }
        }
        return if (causes.any { it is AmbiguousAutomationSubmissionException }) {
            AutomationStopReason.NETWORK
        } else {
            AutomationStopReason.FATAL
        }
    }

    private fun Throwable.findHofAutomationDeferral(): HofAutomationDeferredException? =
        generateSequence(this) { it.cause }.filterIsInstance<HofAutomationDeferredException>().firstOrNull()

    private fun Throwable.findAmbiguousSubmission(): AmbiguousAutomationSubmissionException? =
        generateSequence(this) { it.cause }.filterIsInstance<AmbiguousAutomationSubmissionException>().firstOrNull()

    private fun Throwable.findActionPreconditionChanged(): AutomationActionPreconditionChangedException? =
        generateSequence(this) { it.cause }
            .filterIsInstance<AutomationActionPreconditionChangedException>()
            .firstOrNull()
            ?: generateSequence(this) { it.cause }
                .filterIsInstance<AccountHofObservationInvalidatedException>()
                .firstOrNull()
                ?.let { invalidated ->
                    AutomationActionPreconditionChangedException(
                        invalidated.message ?: "관측 뒤 계정 상태가 변경되었습니다.",
                        invalidated,
                    )
                }
            ?: generateSequence(this) { it.cause }
                .filterIsInstance<ObservedTownActionPreconditionChangedException>()
                .firstOrNull()
                ?.let { changed ->
                    AutomationActionPreconditionChangedException(
                        changed.message ?: "관측한 작업 양식이 변경되었습니다.",
                        changed,
                    )
                }

    private fun recoveredWakeReason(execution: TypedAutomationExecution): String =
        when (execution) {
            is TypedAutomationExecution.SharedCooldown -> "TYPED_SHARED_COOLDOWN_SKIPPED"
            is TypedAutomationExecution.RaidCycleFinished -> "TYPED_RAID_CYCLE_FINISHED"
            is TypedAutomationExecution.RaidWaiting -> "TYPED_RAID_WAITING"
            else -> "TYPED_ACTION_COMPLETED"
        }

    private fun AutomationCoordination.withDescriptor(
        descriptor: AutomationActionDescriptor?,
    ): AutomationCoordination {
        if (this !is AutomationCoordination.Runnable || descriptor == null) return this
        return copy(trace = trace.map { item ->
            if (item.entryId == entryId && item.outcome == AutomationDecisionOutcome.SELECTED) {
                item.copy(
                    type = descriptor.source,
                    message = descriptor.context,
                    actionKind = descriptor.actionKind,
                    targetKey = descriptor.targetKey,
                    targetName = descriptor.targetName,
                    presetId = descriptor.presetId,
                )
            } else {
                item
            }
        })
    }

    private fun actionTrace(
        action: StoredTypedAutomationAction,
        kind: AutomationHistoryEventKind,
        code: String,
        message: String,
        nextRunAt: Instant? = null,
        descriptor: AutomationActionDescriptor,
        diagnosticKind: AutomationDiagnosticKind? = null,
        cooldownSource: app.spammy.hof.automation.raid.RaidCooldownSource? = null,
        impactScope: AutomationImpactScope? = null,
        releaseCondition: String? = null,
        diagnosticContext: String? = null,
    ) = AutomationActionTrace(
        kind = kind,
        reasonCode = code,
        message = "${descriptor.context} · $message",
        entryId = action.entryId,
        type = descriptor.source,
        actionKind = descriptor.actionKind,
        targetKey = descriptor.targetKey,
        targetName = descriptor.targetName,
        presetId = descriptor.presetId,
        nextRunAt = nextRunAt,
        diagnosticKind = diagnosticKind,
        cooldownSource = cooldownSource,
        impactScope = impactScope,
        releaseCondition = releaseCondition,
        diagnosticContext = if (descriptor.source == app.spammy.hof.automation.entity.AutomationType.FISHING) {
            AutomationDecisionDiagnostics.actionResult(
                diagnosticContext ?: AutomationDecisionDiagnostics.fishingAction(action, null, "UNOBSERVED", now()),
                code, nextRunAt,
            )
        } else diagnosticContext,
    )

    private companion object {
        val SUBMISSION_NOT_EXECUTED = Any()
        const val AUTHORIZATION_ENDED_REASON = "AUTHORIZATION_ENDED_BEFORE_SUBMISSION"
        const val TYPED_RECONCILIATION_BUDGET_EXHAUSTED = "TYPED_RECONCILIATION_BUDGET_EXHAUSTED"
        const val RECONCILIATION_RETRY_SECONDS = 10L
        const val HOF_COOLDOWN_WAKE_REASON = "HOF_503_COOLDOWN"
        const val AUTOMATIC_RETRY_WAKE_REASON = "TYPED_AUTOMATIC_RETRY"
        const val RAID_BATTLE_RECOVERY_WAKE_REASON = "RAID_BATTLE_RECOVERY_STARTED"
        const val RAID_BATTLE_APPLIED_TERMINAL_RESULT = "RAID_BATTLE_APPLIED_TERMINAL_RESULT"
        const val TYPED_CONVERGENCE_WAKE_REASON = "TYPED_CONVERGENCE_CONTINUE"
        const val FISHING_OBSERVATION_LOST_BEFORE_SUBMISSION =
            "FISHING_OBSERVATION_LOST_BEFORE_SUBMISSION"
        const val TYPED_CONVERGENCE_PROBE_REASON = "TYPED_CONVERGENCE_PROBE"
        const val TYPED_BATTLE_GATE_WAKE_REASON = "TYPED_BATTLE_GATE_OPENED"
        const val WORK_CYCLE_BOUNDARY_WAKE_REASON = "WORK_CYCLE_BOUNDARY"
        const val ACTION_SUPERSEDED_REASON = "ACTION_SUPERSEDED_BY_FRESH_STATE"
        const val QUEST_PROGRESS_FRESH_DECISION = "QUEST_PROGRESS_FRESH_DECISION"
        const val POST_KILL_SWITCH_WAKE_REASON = "AUTOMATION_POST_KILL_SWITCH"
        const val POST_KILL_SWITCH_RECHECK_SECONDS = 30L
    }

    private data class AuthorizedExecution<T>(
        val authorized: Boolean,
        val value: T?,
    )
}
