package app.spammy.hof.automation.service

import app.spammy.hof.automation.convergence.AutomationActionEvidence
import app.spammy.hof.automation.convergence.AutomationConvergenceBudget
import app.spammy.hof.automation.convergence.ConvergenceDirective
import app.spammy.hof.automation.convergence.LegacyConvergenceDecision
import app.spammy.hof.automation.convergence.RaidObservedState
import app.spammy.hof.automation.raid.RaidRewardResultKind
import app.spammy.hof.automation.entity.AutomationWaitReason
import app.spammy.hof.automation.history.*
import app.spammy.hof.automation.port.AutomationWakeupPort
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.auth.service.AccountExecutionSubmissionGate
import app.spammy.hof.external.client.HofAutomationDeferredException
import app.spammy.hof.battle.service.BattleNotSubmittedException
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.town.raid.model.RaidAction
import app.spammy.hof.town.raid.model.RAID_NOTHING_AVAILABLE_MESSAGE
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
    private val fishingCycleModule: FishingCycleModule? = null,
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
                    else -> releaseForConvergenceDirective(acquisition.execution, dueDirective)
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
            typedRuntime.complete(
                execution,
                TypedRuntimeOutcome.ScheduledWait(
                    preflight.retryAt,
                    AutomationWaitReason.SCHEDULED,
                    wakeReason = "DAILY_PREFLIGHT_BUSY",
                ),
            )
            false
        }
        is AutomationDailyPreflight.Result.RetryScheduled -> {
            typedRuntime.complete(
                execution,
                TypedRuntimeOutcome.ScheduledWait(
                    preflight.nextAttemptAt,
                    AutomationWaitReason.HOF_CONNECTION,
                    wakeReason = "DAILY_PREFLIGHT_RETRY",
                ),
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
            typedRuntime.complete(
                execution,
                TypedRuntimeOutcome.RetryableFailure(
                    reason,
                    "Daily preflight failed: ${preflight.reason}",
                ),
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
        if (checkpoint != null && typedRuntime.completeRecordedAction(execution)) return
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
                typedRuntime.complete(
                    execution,
                    TypedRuntimeOutcome.IntegrityFailure("Stored typed action integrity check failed."),
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
                typedRuntime.complete(
                    execution,
                    TypedRuntimeOutcome.ScheduledWait(
                        error.retryAt,
                        AutomationWaitReason.HOF_CONNECTION,
                        wakeReason = HOF_COOLDOWN_WAKE_REASON,
                    ),
                )
                return
            } catch (error: SafeRetryableAutomationException) {
                typedRuntime.complete(
                    execution,
                    TypedRuntimeOutcome.SafeRetry(error.message ?: "Safe snapshot retry"),
                )
                return
            } catch (error: AutomationLoginRequiredException) {
                retryExecution(execution, AutomationStopReason.AUTHENTICATION, error.message)
                return
            } catch (error: ApiException) {
                val reason = when (error.errorCode) {
                    ErrorCode.CAPTCHA_REQUIRED -> AutomationStopReason.CAPTCHA
                    ErrorCode.HOF_LOGIN_FAILED, ErrorCode.HOF_SESSION_EXPIRED -> AutomationStopReason.AUTHENTICATION
                    else -> AutomationStopReason.FATAL
                }
                retryExecution(execution, reason, error.message)
                return
            } catch (error: FatalAutomationException) {
                retryExecution(
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
                        stopPreparationFailure(accountId, execution, decision.entryId, "BUILD", error, decisionCycleId,
                            decisionDescriptor?.copy(targetKey = preparationTargetKey(decision.entryId, decision.action)))
                        return
                    }
                }
                is AutomationCoordination.Fatal -> {
                    typedRuntime.complete(
                        execution,
                        TypedRuntimeOutcome.RetryableFailure(
                            decision.reason,
                            decision.message,
                            decision.warnings,
                        ),
                    )
                    return
                }
                is AutomationCoordination.Unavailable -> {
                    if (fallbackConvergenceProbe != null) {
                        runConvergenceProbe(accountId, execution, fallbackConvergenceProbe)
                        return
                    }
                    typedRuntime.complete(
                        execution,
                        TypedRuntimeOutcome.ScheduledWait(
                            decision.nextRunAt,
                            AutomationWaitReason.SCHEDULED,
                            decision.warnings,
                            wakeReason = "TYPED_UNAVAILABLE",
                        ),
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
            stored = managedAction.storedAction.copy(settingsRevision = decision.settingsRevision)
            resultSelection = results.freshSelection(accountId, stored) ?: run {
                stopPreparationFailure(accountId, execution, stored.entryId, "CONVERGENCE_MODULE",
                    IllegalStateException("Active convergence module is missing."))
                return
            }
            val preparation = try {
                typedRuntime.persistPrepared(execution, stored, selectedWarnings.orEmpty())
            } catch (error: Exception) {
                stopPreparationFailure(accountId, execution, stored.entryId, "PERSIST", error, decisionCycleId, preparationDescriptor(stored, managedAction.descriptor))
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
        fun finishRaidBattleHandoff(
            resolution: AmbiguousActionResolution.HandedOff,
            convergenceRecheckAt: Instant? = null,
        ) {
            completeReconciliation(
                execution,
                TypedRuntimeOutcome.AmbiguousHandoff(
                    resolution.reason,
                    RAID_BATTLE_RECOVERY_WAKE_REASON,
                ),
                convergenceRecheckAt = convergenceRecheckAt,
            ) {
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
        }
        fun holdAmbiguousScope(resolution: AmbiguousActionResolution.Held) {
            val observedAt = now()
            val successfulObservationCount = activeCheckpoint.successfulObservationCount + 1
            val firstPendingAt = activeCheckpoint.firstPendingAt ?: activeCheckpoint.submittedAt ?: observedAt
            val evidence = AutomationActionEvidence.ResultUnobserved(observedAt, resolution.reason)
            completeReconciliation(
                execution,
                TypedRuntimeOutcome.AmbiguousHandoff(
                    resolution.reason,
                    "TYPED_FISHING_AMBIGUITY_HELD",
                    successfulObservationCount,
                ),
                persistResult = {
                    results.holdUnresolved(accountId, stored, activeCheckpoint, evidence, successfulObservationCount, firstPendingAt)
                },
            ) {
                decisionCycleId?.let { cycleId ->
                    decisionJournal?.appendActionResult(cycleId, trace(
                        AutomationHistoryEventKind.WAITING,
                        "FISHING_AMBIGUITY_HELD",
                        resolution.reason,
                    ))
                }
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
                    submissionAttempted = gate.submissionAttempted,
                ),
                convergenceRecheckAt = (gate.directive as? ConvergenceDirective.WaitUntil)?.at,
            )
            decisionCycleId?.let { cycleId -> runCatching {
                decisionJournal?.appendActionResult(cycleId, trace(
                    AutomationHistoryEventKind.WAITING,
                    ErrorCode.CAPTCHA_REQUIRED.name,
                    "캡차가 해결될 때까지 전투만 보류하고 저장된 전투는 폐기합니다.",
                ))
            } }
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
        if (restoredCheckpoint && managedAction is ManagedFishingAutomationAction &&
            fishingCycleModule?.finishUnusablePrepared(accountId, execution, stored, managedAction) == true
        ) return
        if (resumedLegacyCheckpoint) {
            results.recoverLegacyCheckpoint(accountId, managedAction, stored, activeCheckpoint)?.let { recovery ->
                typedRuntime.complete(
                    execution, recovery.outcome,
                    convergenceRecheckAt = (recovery.directive as? ConvergenceDirective.WaitUntil)?.at,
                )
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
                    completeReconciliation(
                        execution,
                        TypedRuntimeOutcome.ReconciliationDeferred(
                            deferred.retryAt,
                            message,
                            successfulObservation = false,
                            wakeReason = HOF_COOLDOWN_WAKE_REASON,
                        ),
                    ) {
                        decisionCycleId?.let { cycleId ->
                            decisionJournal?.appendActionResult(cycleId, trace(
                                AutomationHistoryEventKind.WAITING,
                                "RECONCILIATION_HOF_DEFERRED",
                                "적용 여부를 확인하는 중 HOF 응답이 지연되어 다시 확인합니다. 사유: $message",
                                deferred.retryAt,
                            ))
                        }
                    }
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
                retryExecution(execution, classifyActionStop(error), error.message ?: error.javaClass.simpleName)
                return
            }
            // 원격 관측 중 이전 worker의 직접 결과가 저장됐다면 그 종결 사실을
            // 사용한다. 오래된 관측으로 보류나 미관측 이력을 새로 만들지 않는다.
            if (typedRuntime.completeRecordedAction(execution)) return
            when (resolution) {
                is AmbiguousActionResolution.Applied -> {
                    results.applyRecoveredExecution(accountId, resolution.execution)
                    completeReconciliation(
                        execution,
                        TypedRuntimeOutcome.ReconciliationApplied(recoveredWakeReason(resolution.execution)),
                        persistResult = {
                            results.observeShadow(
                                accountId,
                                stored.executionIdentity,
                                AutomationActionEvidence.StateAdvanced(now(), "advanced:${stored.executionIdentity}"),
                                LegacyConvergenceDecision.APPLIED,
                            )
                        },
                    ) {
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
                    completeReconciliation(
                        execution,
                        TypedRuntimeOutcome.ReconciliationDeferred(
                            observedAt.plusSeconds(RECONCILIATION_RETRY_SECONDS),
                            reason,
                            successfulObservation = true,
                            wakeReason = "TYPED_RECONCILE_RETRY",
                        ),
                        persistResult = {
                            results.observeShadow(
                                accountId,
                                stored.executionIdentity,
                                AutomationActionEvidence.SameState(observedAt, "same:${stored.executionIdentity}"),
                                LegacyConvergenceDecision.RESUBMIT,
                            )
                        },
                    ) {
                        decisionCycleId?.let { cycleId ->
                            decisionJournal?.appendActionResult(cycleId, trace(
                                AutomationHistoryEventKind.WAITING,
                                "AMBIGUOUS_RESULT_UNCHANGED",
                                "$reason 저장 행동은 다시 제출하지 않습니다.",
                                observedAt.plusSeconds(RECONCILIATION_RETRY_SECONDS),
                            ))
                        }
                    }
                }
                is AmbiguousActionResolution.Superseded -> {
                    completeReconciliation(
                        execution,
                        TypedRuntimeOutcome.ActionSuperseded(resolution.reason, ACTION_SUPERSEDED_REASON),
                        persistResult = {
                            results.observeShadow(
                                accountId,
                                stored.executionIdentity,
                                AutomationActionEvidence.StateAdvanced(now(), "superseded:${stored.executionIdentity}"),
                                LegacyConvergenceDecision.SUPERSEDED,
                            )
                        },
                    ) {
                        decisionCycleId?.let { cycleId ->
                            decisionJournal?.appendActionResult(cycleId, trace(
                                AutomationHistoryEventKind.SKIPPED,
                                ACTION_SUPERSEDED_REASON,
                                resolution.reason,
                            ))
                        }
                    }
                }
                is AmbiguousActionResolution.FreshDecision -> {
                    val evidence = AutomationActionEvidence.ResultUnobservedFreshDecision(
                        capturedAt = now(),
                        reason = resolution.reason,
                    )
                    completeReconciliation(
                        execution,
                        TypedRuntimeOutcome.ActionSuperseded(
                            resolution.reason,
                            QUEST_PROGRESS_FRESH_DECISION,
                        ),
                        persistResult = {
                            results.observeShadow(
                                accountId,
                                stored.executionIdentity,
                                evidence,
                                LegacyConvergenceDecision.RESULT_UNOBSERVED,
                            )
                        },
                    ) {
                        decisionCycleId?.let { cycleId ->
                            decisionJournal?.appendActionResult(cycleId, trace(
                                AutomationHistoryEventKind.SKIPPED,
                                QUEST_PROGRESS_FRESH_DECISION,
                                resolution.reason,
                            ))
                        }
                    }
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
                    completeReconciliation(
                        execution,
                        TypedRuntimeOutcome.ReconciliationDeferred(
                            resolution.retryAt,
                            resolution.reason,
                            successfulObservation = true,
                            wakeReason = "TYPED_RECONCILE_RETRY",
                        ),
                        persistResult = {
                            results.observeShadow(
                                accountId,
                                stored.executionIdentity,
                                AutomationActionEvidence.IncompleteObservation(observedAt, resolution.reason),
                                LegacyConvergenceDecision.RECONCILING,
                            )
                        },
                    ) {
                        decisionCycleId?.let { cycleId ->
                            decisionJournal?.appendActionResult(cycleId, trace(
                                AutomationHistoryEventKind.WAITING,
                                "AMBIGUOUS_RESULT_VERIFY_LATER",
                                "아직 적용 여부를 확정할 수 없어 다음 확인 시각까지 기다립니다. 사유: ${resolution.reason}",
                                resolution.retryAt,
                            ))
                        }
                    }
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
            typedRuntime.complete(
                execution,
                TypedRuntimeOutcome.ScheduledWait(retryAt, AutomationWaitReason.SCHEDULED, wakeReason = POST_KILL_SWITCH_WAKE_REASON),
            )
            return
        }

        if (managedAction is ManagedFishingAutomationAction &&
            fishingCycleModule?.executePrepared(accountId, execution, managedAction, stored,
                decisionCycleId, selectedWarnings, resumedDeferredSubmission) == true
        ) return

        results.prepare(accountId, resultSelection, resumedDeferredSubmission)?.let { directive ->
            when (directive) {
                is ConvergenceDirective.Submit -> convergenceAttemptId = directive.attemptId
                else -> {
                    releaseForConvergenceDirective(execution, directive)
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
                convergenceRecheckAt = (directive as? ConvergenceDirective.WaitUntil)?.at,
            )
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
                    convergenceRecheckAt = (directive as? ConvergenceDirective.WaitUntil)?.at,
                )
            } else {
                retryExecution(execution, AutomationStopReason.NETWORK, message)
            }
            return
        } catch (error: Throwable) {
            if (closeBattleForCaptcha(error)) return
            val causes = generateSequence(error) { it.cause }.toList()
            if (error is Exception && causes.none { it is java.io.IOException || it is ApiException ||
                    it is AutomationLoginRequiredException || it is HofAutomationDeferredException ||
                    it is SafeRetryableAutomationException }) {
                resultSelection.policy?.let { results.discardUnsubmitted(accountId, it, now(), "PREPARATION_FAILED") }
                stopPreparationFailure(accountId, execution, stored.entryId, "VALIDATE", error, decisionCycleId,
                    preparationDescriptor(stored, actionDescriptor))
                return
            }
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
            retryExecution(execution, classifyActionStop(error), message,
                convergenceRecheckAt = (directive as? ConvergenceDirective.WaitUntil)?.at)
            return
        }

        recordPreparationRecovery(decisionJournal, accountId, decisionCycleId, stored, actionDescriptor)
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
            val authorizedExecution = submissionGate.executeAuthorized(accountId) { managedAction.execute() }
            if (!authorizedExecution.authorized) {
                return typedRuntime.discardUnauthorizedSubmission(accountId, execution, convergenceAttemptId, results, now())
            }
            val evidenceExecution = requireNotNull(authorizedExecution.value)
            appliedEvidence = results.directEvidence(resultSelection, evidenceExecution)
            val acceptedExecution = when (val connected = results.applyDirect(managedAction, evidenceExecution, appliedEvidence, convergenceAttemptId)) {
                is AutomationResultCoordinator.DirectResult.Accepted -> connected.execution
                is AutomationResultCoordinator.DirectResult.Unapplied -> {
                    typedRuntime.complete(
                        execution, connected.outcome,
                        convergenceRecheckAt = (connected.directive as? ConvergenceDirective.WaitUntil)?.at,
                    )
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
            commitDirectResult(stored.executionIdentity,
                persistResult = { results.finishDirect(accountId, stored, appliedEvidence, convergenceAttemptId, domainExecution) },
                appendHistory = {
                    decisionCycleId?.let { cycleId ->
                        val noReward = (stored.payload as? StoredTypedActionPayload.RaidTown)?.action == RaidAction.REWARD &&
                            ((acceptedExecution as? TypedAutomationExecution.ActionCompleted)?.observedState as? RaidObservedState)
                                ?.rewardResult == RaidRewardResultKind.NOTHING_AVAILABLE
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
                                if (noReward) RAID_NOTHING_AVAILABLE_MESSAGE else "자동화 행동을 완료했습니다.",
                            )
                        }
                        decisionJournal?.appendActionResult(cycleId, resultTrace)
                    }
            }) { persist -> typedRuntime.complete(execution, outcome, persist) }
        } catch (error: Throwable) {
            if (error is DirectResultPersistenceFailure) throw error
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
                    convergenceRecheckAt = (directive as? ConvergenceDirective.WaitUntil)?.at,
                )
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
                        convergenceRecheckAt = (directive as? ConvergenceDirective.WaitUntil)?.at,
                    )
                } else {
                    typedRuntime.complete(
                        execution,
                        TypedRuntimeOutcome.SubmissionDeferred(
                            deferred.retryAt,
                            deferred.message ?: "HOF request spacing is deferred.",
                        ),
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
                    finishRaidBattleHandoff(handedOff,
                        convergenceRecheckAt = (directive as? ConvergenceDirective.WaitUntil)?.at)
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
                            convergenceRecheckAt = (directive as? ConvergenceDirective.WaitUntil)?.at,
                        )
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
            retryExecution(execution, classifyActionStop(error), error.message ?: error.javaClass.simpleName)
        }
    }

    private fun runConvergenceProbe(
        accountId: Long,
        execution: TypedRuntimeExecutionRight,
        directive: ConvergenceDirective.Probe,
    ) {
        val next = results.probe(accountId, directive) ?: return
        typedRuntime.complete(
            execution,
            TypedRuntimeOutcome.SelectionChanged(TYPED_CONVERGENCE_WAKE_REASON),
            convergenceRecheckAt = (next as? ConvergenceDirective.WaitUntil)?.at,
        )
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
        completeReconciliation(
            execution,
            TypedRuntimeOutcome.AmbiguousHandoff(
                warning,
                TYPED_RECONCILIATION_BUDGET_EXHAUSTED,
                successfulObservationCount,
            ),
            persistResult = {
                results.holdUnresolved(accountId, stored, activeCheckpoint, evidence, successfulObservationCount, firstPendingAt)
            },
        ) {
            decisionCycleId?.let { cycleId ->
                decisionJournal?.appendActionResult(cycleId, actionTrace(
                    stored,
                    AutomationHistoryEventKind.SKIPPED,
                    TYPED_RECONCILIATION_BUDGET_EXHAUSTED,
                    warning,
                    descriptor = actionDescriptor,
                ))
            }
        }
    }

    private fun releaseForConvergenceDirective(
        execution: TypedRuntimeExecutionRight,
        directive: ConvergenceDirective,
    ) {
        // 제출이 허용되지 않은 미전송 행동은 닫아야 다음 깨우기가 새 판단으로 진행한다.
        val outcome = if (execution.checkpoint?.phase == TypedRuntimeCheckpointPhase.PREPARED) {
            TypedRuntimeOutcome.PreparedDiscarded(
                "행동 결과 수렴 규칙이 제출을 허용하지 않아 미전송 행동을 닫고 새로 판단합니다.",
                TYPED_CONVERGENCE_WAKE_REASON,
            )
        } else {
            TypedRuntimeOutcome.SelectionChanged(TYPED_CONVERGENCE_WAKE_REASON)
        }
        typedRuntime.complete(
            execution,
            outcome,
            convergenceRecheckAt = (directive as? ConvergenceDirective.WaitUntil)?.at,
        )
    }

    private fun now(): Instant = timeProvider?.now() ?: Instant.now()

    private fun retryExecution(
        execution: TypedRuntimeExecutionRight,
        reason: AutomationStopReason,
        message: String?,
        convergenceRecheckAt: Instant? = null,
    ) {
        typedRuntime.complete(
            execution,
            TypedRuntimeOutcome.RetryableFailure(reason, message ?: reason.name),
            convergenceRecheckAt = convergenceRecheckAt,
        )
    }

    private fun stopPreparationFailure(
        accountId: Long,
        execution: TypedRuntimeExecutionRight,
        entryId: Long,
        stage: String,
        error: Exception,
        cycleId: Long? = null,
        descriptor: AutomationActionDescriptor? = null,
    ) {
        if (error is TypedAutomationConfigurationChangedException || error.findActionPreconditionChanged() != null) {
            typedRuntime.complete(execution, TypedRuntimeOutcome.SelectionChanged("TYPED_CONFIG_RELOAD"))
            return
        }
        error.findHofAutomationDeferral()?.let { deferred ->
            typedRuntime.complete(execution, TypedRuntimeOutcome.ScheduledWait(
                deferred.retryAt, AutomationWaitReason.HOF_CONNECTION, wakeReason = HOF_COOLDOWN_WAKE_REASON))
            return
        }
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
            // RECONCILING은 이미 제출됐을 수 있으므로 전송 전 실패로 표시하거나 폐기하지 않는다.
            if (execution.checkpoint?.phase == TypedRuntimeCheckpointPhase.RECONCILING || entryId <= 0 || decisionJournal == null) {
                retryExecution(execution, AutomationStopReason.FATAL, "자동화 처리 중 오류가 발생해 다시 확인합니다.")
                return
            }
            val retryAt = now().plusSeconds(60)
            val phase = when (stage) {
                "DESCRIBE" -> "행동 설명 확인"
                "JOURNAL", "JOURNAL_RETRY" -> "판단 기록"
                "BUILD" -> "행동 생성"
                "PERSIST" -> "행동 저장"
                "VALIDATE" -> "전송 전 조건 확인"
                else -> "행동 준비"
            }
            val message = "$phase 중 오류가 발생해 HOF 요청을 전송하지 않았습니다. 해당 대상은 잠시 보류하고 다른 자동화를 계속 판단합니다."
            val failure = AutomationActionTrace(
                kind = AutomationHistoryEventKind.ACTION_FAILED,
                reasonCode = app.spammy.hof.automation.history.ACTION_PREPARATION_FAILED,
                message = message, entryId = entryId, type = descriptor?.source,
                actionKind = descriptor?.actionKind, targetKey = descriptor?.targetKey,
                targetName = descriptor?.targetName, nextRunAt = retryAt,
                releaseCondition = "예정 시각에 최신 상태로 다시 판단",
                diagnosticContext = AutomationDecisionDiagnostics.capture(stage, now(), error = error),
            )
            try {
                if (cycleId != null) decisionJournal.appendActionResult(cycleId, failure)
                else decisionJournal.appendPreparedActionAttempt(accountId, failure)
            } catch (journalError: Exception) {
                log.error("Could not persist automation preparation failure accountId={}", accountId, journalError)
                retryExecution(execution, AutomationStopReason.FATAL, "$phase 중 오류가 발생해 다시 확인합니다.")
                return
            }
            val outcome = if (execution.checkpoint == null) {
                TypedRuntimeOutcome.SelectionChanged("PREPARATION_FAILED_CONTINUE_SELECTION")
            } else {
                TypedRuntimeOutcome.ActionSuperseded(warning = message, wakeReason = "PREPARATION_FAILED_CONTINUE_SELECTION")
            }
            typedRuntime.complete(execution, outcome, convergenceRecheckAt = retryAt)
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

    private fun Throwable.findAmbiguousSubmission(): AmbiguousAutomationSubmissionException? =
        generateSequence(this) { it.cause }.filterIsInstance<AmbiguousAutomationSubmissionException>().firstOrNull()

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

    private fun completeReconciliation(
        execution: TypedRuntimeExecutionRight,
        outcome: TypedRuntimeOutcome,
        convergenceRecheckAt: Instant? = null,
        persistResult: () -> Unit = {},
        appendHistory: () -> Unit,
    ): TypedRuntimeProjection {
        var accepted = false
        val projection = typedRuntime.completeReconciliation(execution, outcome, convergenceRecheckAt) {
            persistResult()
            accepted = true
        }
        if (accepted) try {
            typedRuntime.persistReconciliationHistory(execution, outcome, appendHistory)
        } catch (error: RuntimeException) {
            log.warn("Reconciliation history unavailable after result commit executionIdentity={} errorType={}",
                execution.checkpoint?.storedAction?.executionIdentity, error.javaClass.name)
        }
        return projection
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
    ) = automationActionTrace(
        action, kind, code, message, nextRunAt, descriptor,
        diagnosticKind, cooldownSource, impactScope, releaseCondition, diagnosticContext, now(),
    )

    private companion object {
        const val TYPED_RECONCILIATION_BUDGET_EXHAUSTED = "TYPED_RECONCILIATION_BUDGET_EXHAUSTED"
        const val RECONCILIATION_RETRY_SECONDS = 10L
        const val HOF_COOLDOWN_WAKE_REASON = "HOF_503_COOLDOWN"
        const val AUTOMATIC_RETRY_WAKE_REASON = "TYPED_AUTOMATIC_RETRY"
        const val RAID_BATTLE_RECOVERY_WAKE_REASON = "RAID_BATTLE_RECOVERY_STARTED"
        const val RAID_BATTLE_APPLIED_TERMINAL_RESULT = "RAID_BATTLE_APPLIED_TERMINAL_RESULT"
        const val TYPED_CONVERGENCE_WAKE_REASON = "TYPED_CONVERGENCE_CONTINUE"
        const val TYPED_BATTLE_GATE_WAKE_REASON = "TYPED_BATTLE_GATE_OPENED"
        const val WORK_CYCLE_BOUNDARY_WAKE_REASON = "WORK_CYCLE_BOUNDARY"
        const val ACTION_SUPERSEDED_REASON = "ACTION_SUPERSEDED_BY_FRESH_STATE"
        const val QUEST_PROGRESS_FRESH_DECISION = "QUEST_PROGRESS_FRESH_DECISION"
        const val POST_KILL_SWITCH_WAKE_REASON = "AUTOMATION_POST_KILL_SWITCH"
        const val POST_KILL_SWITCH_RECHECK_SECONDS = 30L
    }


}
