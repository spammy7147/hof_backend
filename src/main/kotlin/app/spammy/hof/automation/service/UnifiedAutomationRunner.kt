package app.spammy.hof.automation.service

import app.spammy.hof.automation.convergence.AutomationActionConvergenceModule
import app.spammy.hof.automation.convergence.AutomationActionEvidence
import app.spammy.hof.automation.convergence.AutomationConvergenceRollout
import app.spammy.hof.automation.convergence.AutomationConvergenceShadowEvaluator
import app.spammy.hof.automation.convergence.ConvergenceDirective
import app.spammy.hof.automation.convergence.LegacyConvergenceDecision
import app.spammy.hof.automation.convergence.ProductionActionEvidenceInterpreter
import app.spammy.hof.automation.convergence.SelectedAutomationAction
import app.spammy.hof.automation.convergence.StoredActionConvergenceSelectionFactory
import app.spammy.hof.automation.convergence.StoredConvergenceActionLoader
import app.spammy.hof.automation.entity.AutomationWaitReason
import app.spammy.hof.automation.history.*
import app.spammy.hof.automation.port.AutomationWakeupPort
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.external.client.HofAutomationDeferredException
import app.spammy.hof.common.time.TimeProvider
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
    private val decisionJournal: AutomationDecisionJournal? = null,
    private val convergenceModule: AutomationActionConvergenceModule? = null,
    private val convergenceSelectionFactory: StoredActionConvergenceSelectionFactory? = null,
    private val storedConvergenceActionLoader: StoredConvergenceActionLoader? = null,
    private val timeProvider: TimeProvider? = null,
    private val rollout: AutomationConvergenceRollout? = null,
    private val shadowEvaluator: AutomationConvergenceShadowEvaluator? = null,
    private val convergenceWorkPriority: AutomationConvergenceWorkPriority? = null,
    private val evidenceInterpreter: ProductionActionEvidenceInterpreter? = null,
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
                    acquisition.execution.checkpoint == null &&
                    convergenceActive()
                ) {
                    convergenceModule?.resumeDue(accountId)
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
    ) {
        var execution = initialExecution
        var checkpoint = execution.checkpoint
        val resumedLegacyCheckpoint = checkpoint != null
        var decisionCycleId: Long? = null
        var selectedWarnings: List<String>? = null
        var convergenceAttemptId: Long? = null
        var convergenceSelection: SelectedAutomationAction? = null
        var evidenceSelection: SelectedAutomationAction? = null
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
            if (rollout?.shadow == true) {
                convergenceSelectionFactory?.create(stored)?.let { selection ->
                    evidenceSelection = selection
                    selectShadow(accountId, selection)
                }
            }
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
                is AutomationCoordination.Idle -> {
                    if (fallbackConvergenceProbe != null) {
                        runConvergenceProbe(accountId, execution, fallbackConvergenceProbe)
                        return
                    }
                    if (decision.warnings.isEmpty()) {
                        typedRuntime.complete(execution, TypedRuntimeOutcome.Idle)
                    } else {
                        completeAndSchedule(
                            accountId,
                            execution,
                            TypedRuntimeOutcome.ConfigurationWait(decision.warnings),
                            "TYPED_CONFIG_RECHECK",
                        )
                    }
                    return
                }
            }
            stored = managedAction.storedAction
            val selectionFactory = convergenceSelectionFactory
            if (selectionFactory != null && convergenceActive()) {
                if (convergenceModule == null) {
                    stopPreparationFailure(
                        accountId,
                        execution,
                        stored.entryId,
                        "CONVERGENCE_MODULE",
                        IllegalStateException("Active convergence module is missing."),
                    )
                    return
                }
                convergenceSelection = selectionFactory.create(stored)
                evidenceSelection = convergenceSelection
            } else if (selectionFactory != null && rollout?.shadow == true) {
                val selection = selectionFactory.create(stored)
                evidenceSelection = selection
                selectShadow(accountId, selection)
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
        val actionDescriptor = managedAction.descriptor
        fun trace(
            kind: AutomationHistoryEventKind,
            code: String,
            message: String,
            nextRunAt: Instant? = null,
        ) = actionTrace(stored, kind, code, message, nextRunAt, actionDescriptor)
        fun raidCycleTrace(outcome: app.spammy.hof.automation.raid.RaidCycleOutcome): AutomationActionTrace =
            outcome.toAutomationActionTrace().let { result ->
                trace(
                    result.kind,
                    result.reasonCode,
                    result.message,
                    result.nextRunAt,
                )
            }
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
                ))
            }
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
        if (resumedLegacyCheckpoint && convergenceActive()) {
            when (activeCheckpoint.phase) {
                TypedRuntimeCheckpointPhase.PREPARED -> {
                    typedRuntime.complete(
                        execution,
                        TypedRuntimeOutcome.PreparedDiscarded(
                            warning = "Active convergence cutover discarded an unsubmitted legacy payload.",
                            wakeReason = TYPED_CONVERGENCE_WAKE_REASON,
                        ),
                    )
                    return
                }
                TypedRuntimeCheckpointPhase.RECONCILING -> {
                    cutoverLegacyReconciliation(accountId, execution, managedAction, stored)
                    return
                }
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
                        TypedRuntimeOutcome.ReconciliationDeferred(deferred.retryAt, message),
                        HOF_COOLDOWN_WAKE_REASON,
                    )
                    return
                }
                log.warn(
                    "Typed automation reconciliation stopped accountId={} errorType={}",
                    accountId,
                    error.javaClass.name,
                )
                retryExecution(accountId, execution, classifyActionStop(error), error.message ?: error.javaClass.simpleName)
                return
            }
            when (resolution) {
                is AmbiguousActionResolution.Applied -> {
                    observeShadow(
                        accountId,
                        stored.executionIdentity,
                        AutomationActionEvidence.StateAdvanced(now(), "advanced:${stored.executionIdentity}"),
                        LegacyConvergenceDecision.APPLIED,
                    )
                    applyRecoveredExecution(accountId, resolution.execution)
                    typedRuntime.complete(
                        execution,
                        TypedRuntimeOutcome.ReconciliationApplied(recoveredWakeReason(resolution.execution)),
                    )
                    decisionCycleId?.let { cycleId ->
                        val resultTrace = when (val recovered = resolution.execution) {
                            is TypedAutomationExecution.RaidCycleFinished -> raidCycleTrace(recovered.outcome)
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
                    observeShadow(
                        accountId,
                        stored.executionIdentity,
                        AutomationActionEvidence.SameState(now(), "same:${stored.executionIdentity}"),
                        LegacyConvergenceDecision.RESUBMIT,
                    )
                    decisionCycleId?.let { cycleId ->
                        decisionJournal?.appendActionResult(cycleId, trace(
                            AutomationHistoryEventKind.WAITING,
                            "AMBIGUOUS_RESULT_RESUBMIT",
                            "상태 재확인 결과 적용되지 않아 같은 단계를 다시 제출합니다.",
                        ))
                    }
                    typedRuntime.complete(
                        execution,
                        TypedRuntimeOutcome.ReconciliationResubmit("TYPED_RECONCILED_RESUBMIT"),
                    )
                }
                is AmbiguousActionResolution.Superseded -> {
                    observeShadow(
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
                is AmbiguousActionResolution.VerifyLater -> {
                    observeShadow(
                        accountId,
                        stored.executionIdentity,
                        AutomationActionEvidence.IncompleteObservation(now(), resolution.reason),
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
                        TypedRuntimeOutcome.ReconciliationDeferred(resolution.retryAt, resolution.reason),
                        "TYPED_RECONCILE_RETRY",
                    )
                }
                is AmbiguousActionResolution.HandedOff -> finishRaidBattleHandoff(resolution)
            }
            return
        }

        if (rollout?.automationPostsEnabled == false) {
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

        convergenceSelection?.let { selection ->
            val convergence = requireNotNull(convergenceModule)
            when (val directive = convergence.prepare(accountId, selection)) {
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
                convergenceModule?.record(attemptId, evidence)
            }
            observeShadow(
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
                convergenceModule?.record(attemptId, evidence)
            }
            observeShadow(
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
            val captcha = error.findCaptchaRequired()
            if (
                captcha != null &&
                convergenceAttemptId != null &&
                convergenceActive() &&
                convergenceSelection?.actionKind?.battle == true
            ) {
                val directive = convergenceModule?.record(
                    requireNotNull(convergenceAttemptId),
                    AutomationActionEvidence.BattleGateRequired(
                        capturedAt = now(),
                        challengeId = null,
                        reason = ErrorCode.CAPTCHA_REQUIRED.name,
                    ),
                )
                typedRuntime.complete(
                    execution,
                    TypedRuntimeOutcome.PreparedDiscarded(
                        warning = captcha.message,
                        wakeReason = TYPED_BATTLE_GATE_WAKE_REASON,
                    ),
                )
                if (directive != null) scheduleConvergenceDirective(accountId, directive)
                return
            }
            val message = error.message ?: "제출 직전 최신 상태 확인에 실패했습니다."
            val evidence = AutomationActionEvidence.NetworkFailure(now(), message)
            val directive = convergenceAttemptId?.let { attemptId ->
                convergenceModule?.record(attemptId, evidence)
            }
            observeShadow(
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
                convergenceModule?.record(
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
            val evidenceExecution = managedAction.execute()
            appliedEvidence = evidenceSelection?.let { selection ->
                evidenceInterpreter?.fromExecution(selection, evidenceExecution, now())
                    ?: AutomationActionEvidence.IncompleteObservation(
                        capturedAt = now(),
                        reason = "PRODUCTION_EVIDENCE_INTERPRETER_MISSING",
                    )
            }
            if (
                convergenceAttemptId != null &&
                appliedEvidence !is AutomationActionEvidence.DirectApplied &&
                evidenceExecution !is TypedAutomationExecution.SharedCooldown
            ) {
                val policyEvidence = requireNotNull(appliedEvidence)
                val convergenceDirective = convergenceModule?.record(
                    requireNotNull(convergenceAttemptId),
                    policyEvidence,
                )
                val warning = when (policyEvidence) {
                    is AutomationActionEvidence.DirectRejected ->
                        "직접 응답이 행동 미적용을 확인해 최신 상태로 다시 판단합니다."
                    is AutomationActionEvidence.StateAdvanced ->
                        "직접 응답에서 저장 행동보다 최신 상태가 확인되어 성공으로 귀속하지 않습니다."
                    is AutomationActionEvidence.SameState ->
                        "직접 응답만으로 행동 적용을 확인하지 못해 권위 상태를 다시 관측합니다."
                    is AutomationActionEvidence.IncompleteObservation ->
                        "직접 응답 관측이 불완전해 같은 행동을 다시 보내지 않고 결과를 재확인합니다."
                    is AutomationActionEvidence.NetworkFailure ->
                        "직접 응답 확인에 실패해 같은 행동을 다시 보내지 않고 결과를 재확인합니다."
                    is AutomationActionEvidence.ResultUnobserved ->
                        "직접 응답에서 행동 결과를 관측하지 못해 자동 재제출을 보류합니다."
                    is AutomationActionEvidence.BattleGateRequired ->
                        "전투 캡차 해결 전에는 전투 행동을 성공으로 처리하지 않습니다."
                    is AutomationActionEvidence.DirectApplied -> error("Handled above")
                }
                val policyOutcome = when (policyEvidence) {
                    is AutomationActionEvidence.DirectRejected,
                    is AutomationActionEvidence.StateAdvanced,
                    -> TypedRuntimeOutcome.ActionSuperseded(warning, TYPED_CONVERGENCE_WAKE_REASON)
                    else -> TypedRuntimeOutcome.AmbiguousHandoff(
                        warning,
                        TYPED_CONVERGENCE_WAKE_REASON,
                    )
                }
                typedRuntime.complete(execution, policyOutcome)
                convergenceDirective?.let { scheduleConvergenceDirective(accountId, it) }
                decisionCycleId?.let { cycleId ->
                    val kind = if (policyOutcome is TypedRuntimeOutcome.ActionSuperseded) {
                        AutomationHistoryEventKind.SKIPPED
                    } else {
                        AutomationHistoryEventKind.WAITING
                    }
                    decisionJournal?.appendActionResult(
                        cycleId,
                        trace(kind, "ACTION_RESULT_NOT_APPLIED", warning),
                    )
                }
                return
            }
            val acceptedExecution = if (convergenceAttemptId != null) {
                managedAction.applyPolicyAcceptedExecution(evidenceExecution)
            } else {
                managedAction.applyLegacyExecution(evidenceExecution)
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
            appliedEvidence?.let { evidence ->
                observeShadow(
                    accountId,
                    stored.executionIdentity,
                    evidence,
                    if (domainExecution is TypedAutomationExecution.SharedCooldown) {
                        LegacyConvergenceDecision.SUPERSEDED
                    } else {
                        LegacyConvergenceDecision.APPLIED
                    },
                )
            }
            convergenceAttemptId?.let { attemptId ->
                convergenceModule?.record(
                    attemptId,
                    requireNotNull(appliedEvidence) {
                        "Active convergence execution is missing production evidence."
                    },
                )
            }
            typedRuntime.complete(execution, outcome)
            decisionCycleId?.let { cycleId ->
                val resultTrace = when (domainExecution) {
                    is TypedAutomationExecution.RaidCycleFinished -> raidCycleTrace(domainExecution.outcome)
                    is TypedAutomationExecution.BattleCompleted if recoveryAppliedByTerminalResult -> trace(
                        AutomationHistoryEventKind.ACTION_SUCCEEDED,
                        RAID_BATTLE_APPLIED_TERMINAL_RESULT,
                        "정확한 전투 단말 결과로 레이드 전투 적용을 확인하고 복구를 종료했습니다.",
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
                    convergenceModule?.record(attemptId, evidence)
                }
                observeShadow(
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
            val captcha = error.findCaptchaRequired()
            if (
                captcha != null &&
                convergenceAttemptId != null &&
                convergenceActive() &&
                convergenceSelection?.actionKind?.battle == true
            ) {
                val directive = convergenceModule?.record(
                    requireNotNull(convergenceAttemptId),
                    AutomationActionEvidence.BattleGateRequired(
                        capturedAt = now(),
                        challengeId = null,
                        reason = ErrorCode.CAPTCHA_REQUIRED.name,
                    ),
                )
                if (directive != null) {
                    typedRuntime.complete(
                        execution,
                        TypedRuntimeOutcome.AmbiguousHandoff(
                            warning = captcha.message,
                            wakeReason = TYPED_BATTLE_GATE_WAKE_REASON,
                        ),
                    )
                    scheduleConvergenceDirective(accountId, directive)
                    return
                }
            }
            if (captcha != null) {
                observeShadow(
                    accountId,
                    stored.executionIdentity,
                    AutomationActionEvidence.BattleGateRequired(
                        capturedAt = now(),
                        challengeId = null,
                        reason = ErrorCode.CAPTCHA_REQUIRED.name,
                    ),
                    LegacyConvergenceDecision.HELD,
                )
            }
            error.findHofAutomationDeferral()?.let { deferred ->
                val evidence = AutomationActionEvidence.NetworkFailure(
                    capturedAt = now(),
                    reason = deferred.message ?: "HOF_DEFERRED",
                )
                convergenceAttemptId?.let { attemptId ->
                    convergenceModule?.record(attemptId, evidence)
                }
                observeShadow(
                    accountId,
                    stored.executionIdentity,
                    evidence,
                    LegacyConvergenceDecision.RECONCILING,
                )
                decisionCycleId?.let { cycleId -> runCatching {
                    decisionJournal?.appendActionResult(cycleId, trace(
                        AutomationHistoryEventKind.WAITING,
                        "ACTION_HOF_DEFERRED",
                        "HOF 서버가 잠시 요청을 받지 않아 현재 단계를 보존하고 재시도합니다. 사유: ${deferred.message ?: "일시적 응답 지연"}",
                        deferred.retryAt,
                    ))
                } }
                completeAndSchedule(
                    accountId,
                    execution,
                    TypedRuntimeOutcome.SubmissionDeferred(
                        deferred.retryAt,
                        deferred.message ?: "HOF server returned 503.",
                    ),
                    HOF_COOLDOWN_WAKE_REASON,
                )
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
                    )
                    val directive = convergenceAttemptId?.let { attemptId ->
                        convergenceModule?.record(attemptId, evidence)
                    }
                    observeShadow(
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
                    val directive = convergenceModule?.record(
                        attemptId,
                        AutomationActionEvidence.IncompleteObservation(
                            capturedAt = now(),
                            reason = ambiguous.message ?: "SUBMISSION_RESULT_UNKNOWN",
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
                observeShadow(
                    accountId,
                    stored.executionIdentity,
                    appliedEvidence ?: AutomationActionEvidence.IncompleteObservation(now(), message),
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
                convergenceModule?.record(attemptId, evidence)
            }
            observeShadow(
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

    private fun runConvergenceProbe(
        accountId: Long,
        execution: TypedRuntimeExecutionRight,
        directive: ConvergenceDirective.Probe,
    ) {
        val convergence = convergenceModule ?: return
        val evidence = observeStoredConvergenceAction(accountId, directive)
        val next = convergence.record(directive.attemptId, evidence)
        typedRuntime.complete(
            execution,
            TypedRuntimeOutcome.SelectionChanged(TYPED_CONVERGENCE_WAKE_REASON),
        )
        scheduleConvergenceDirective(accountId, next)
    }

    private fun convergenceActive(): Boolean = rollout?.active ?: (convergenceModule != null)

    private fun cutoverLegacyReconciliation(
        accountId: Long,
        execution: TypedRuntimeExecutionRight,
        managed: ManagedAutomationAction,
        stored: StoredTypedAutomationAction,
    ) {
        val convergence = convergenceModule
        val factory = convergenceSelectionFactory
        if (convergence == null || factory == null) {
            typedRuntime.complete(
                execution,
                TypedRuntimeOutcome.AmbiguousHandoff(
                    "Active convergence cutover dependencies are missing.",
                    TYPED_CONVERGENCE_WAKE_REASON,
                ),
            )
            return
        }
        val directive = when (val prepared = convergence.prepare(accountId, factory.create(stored))) {
            is ConvergenceDirective.Submit -> {
                val observation = try {
                    when (val resolution = managed.reconcile()) {
                        is AmbiguousActionResolution.Applied -> {
                            applyRecoveredExecution(accountId, resolution.execution)
                            evidenceInterpreter?.fromReconciliation(
                                factory.create(stored),
                                resolution,
                                now(),
                            ) ?: AutomationActionEvidence.StateAdvanced(now(), "advanced:${stored.executionIdentity}")
                        }
                        else -> evidenceInterpreter?.fromReconciliation(
                            factory.create(stored),
                            resolution,
                            now(),
                        ) ?: when (resolution) {
                            AmbiguousActionResolution.Resubmit -> AutomationActionEvidence.SameState(
                                now(),
                                "same:${stored.executionIdentity}",
                            )
                            is AmbiguousActionResolution.VerifyLater -> AutomationActionEvidence.IncompleteObservation(
                                now(),
                                resolution.reason,
                            )
                            is AmbiguousActionResolution.HandedOff -> AutomationActionEvidence.ResultUnobserved(
                                now(),
                                resolution.reason,
                            )
                            is AmbiguousActionResolution.Superseded -> AutomationActionEvidence.StateAdvanced(
                                now(),
                                "superseded:${stored.executionIdentity}",
                            )
                            is AmbiguousActionResolution.Applied -> error("Handled above")
                        }
                    }
                } catch (error: Throwable) {
                    if (error.findHofAutomationDeferral() != null) {
                        AutomationActionEvidence.NetworkFailure(now(), error.message ?: "HOF_DEFERRED")
                    } else {
                        AutomationActionEvidence.ResultUnobserved(
                            now(),
                            error.message ?: error.javaClass.simpleName,
                        )
                    }
                }
                convergence.record(prepared.attemptId, observation)
            }
            else -> prepared
        }
        typedRuntime.complete(
            execution,
            TypedRuntimeOutcome.AmbiguousHandoff(
                "Legacy ambiguous payload was closed after one fresh authoritative observation.",
                TYPED_CONVERGENCE_WAKE_REASON,
            ),
        )
        scheduleConvergenceDirective(accountId, directive)
    }

    private fun observeShadow(
        accountId: Long,
        executionIdentity: String,
        evidence: AutomationActionEvidence,
        legacyDecision: LegacyConvergenceDecision,
    ) {
        if (rollout?.shadow == true) {
            try {
                shadowEvaluator?.observe(accountId, executionIdentity, evidence, legacyDecision)
            } catch (error: RuntimeException) {
                log.warn(
                    "Automation convergence SHADOW observation failed accountId={} errorType={}",
                    accountId,
                    error.javaClass.name,
                )
            }
        }
    }

    private fun selectShadow(accountId: Long, selection: SelectedAutomationAction) {
        try {
            shadowEvaluator?.selected(accountId, selection)
        } catch (error: RuntimeException) {
            log.warn(
                "Automation convergence SHADOW selection failed accountId={} errorType={}",
                accountId,
                error.javaClass.name,
            )
        }
    }

    private fun observeStoredConvergenceAction(
        accountId: Long,
        directive: ConvergenceDirective.Probe,
    ): AutomationActionEvidence {
        val stored = storedConvergenceActionLoader?.load(accountId, directive.executionIdentity)
            ?: return AutomationActionEvidence.ResultUnobserved(now(), "STORED_ACTION_NOT_FOUND")
        val managed = try {
            actionLifecycleModule.restoreVerified(stored, accountId)
        } catch (error: RuntimeException) {
            return AutomationActionEvidence.ResultUnobserved(
                now(),
                error.message ?: "STORED_ACTION_INVALID",
            )
        }
        val selection = convergenceSelectionFactory?.create(stored)
        return try {
            when (val resolution = managed.reconcile()) {
                is AmbiguousActionResolution.Applied -> {
                    applyRecoveredExecution(accountId, resolution.execution)
                    selection?.let { selected ->
                        evidenceInterpreter?.fromReconciliation(selected, resolution, now())
                    } ?: AutomationActionEvidence.StateAdvanced(
                        now(),
                        "advanced:${directive.executionIdentity}",
                    )
                }
                else -> selection?.let { selected ->
                    evidenceInterpreter?.fromReconciliation(selected, resolution, now())
                } ?: when (resolution) {
                    AmbiguousActionResolution.Resubmit -> AutomationActionEvidence.SameState(
                        now(),
                        "unchanged:${directive.executionIdentity}",
                    )
                    is AmbiguousActionResolution.VerifyLater -> AutomationActionEvidence.IncompleteObservation(
                        now(),
                        resolution.reason,
                    )
                    is AmbiguousActionResolution.HandedOff -> AutomationActionEvidence.ResultUnobserved(
                        now(),
                        resolution.reason,
                    )
                    is AmbiguousActionResolution.Superseded -> AutomationActionEvidence.StateAdvanced(
                        now(),
                        "superseded:${directive.executionIdentity}",
                    )
                    is AmbiguousActionResolution.Applied -> error("Handled above")
                }
            }
        } catch (error: Throwable) {
            error.findHofAutomationDeferral()?.let {
                return AutomationActionEvidence.NetworkFailure(
                    now(),
                    it.message ?: "HOF_DEFERRED",
                )
            }
            AutomationActionEvidence.NetworkFailure(
                now(),
                error.message ?: error.javaClass.simpleName,
            )
        }
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

    private fun Throwable.findCaptchaRequired(): ApiException? =
        generateSequence(this) { it.cause }
            .filterIsInstance<ApiException>()
            .firstOrNull { it.errorCode == ErrorCode.CAPTCHA_REQUIRED }

    private fun applyRecoveredExecution(accountId: Long, execution: TypedAutomationExecution) {
        when (execution) {
            TypedAutomationExecution.Completed -> Unit
            is TypedAutomationExecution.ActionCompleted ->
                execution.runtimeDomainExecution().let { recovered ->
                    if (recovered !== execution) applyRecoveredExecution(accountId, recovered)
                }
            is TypedAutomationExecution.RaidCycleFinished -> Unit
            is TypedAutomationExecution.BattleCompleted -> sharedBattleCooldowns.applyAfterSuccessfulBattle(
                accountId,
                execution.categoryId,
                execution.mapCode,
            )
            is TypedAutomationExecution.SharedCooldown -> sharedBattleCooldowns.learnAndApply(
                accountId,
                execution.categoryId,
                execution.mapCode,
                execution.retryAt,
            )
        }
    }

    private fun recoveredWakeReason(execution: TypedAutomationExecution): String =
        when (execution) {
            is TypedAutomationExecution.SharedCooldown -> "TYPED_SHARED_COOLDOWN_SKIPPED"
            is TypedAutomationExecution.RaidCycleFinished -> "TYPED_RAID_CYCLE_FINISHED"
            else -> "TYPED_ACTION_COMPLETED"
        }

    private fun TypedAutomationExecution.runtimeDomainExecution(): TypedAutomationExecution = when (this) {
        is TypedAutomationExecution.ActionCompleted -> raidOutcome?.let(TypedAutomationExecution::RaidCycleFinished)
            ?: TypedAutomationExecution.Completed
        is TypedAutomationExecution.BattleCompleted -> raidOutcome?.let(TypedAutomationExecution::RaidCycleFinished)
            ?: this
        else -> this
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
    )

    private companion object {
        const val HOF_COOLDOWN_WAKE_REASON = "HOF_503_COOLDOWN"
        const val AUTOMATIC_RETRY_WAKE_REASON = "TYPED_AUTOMATIC_RETRY"
        const val RAID_BATTLE_RECOVERY_WAKE_REASON = "RAID_BATTLE_RECOVERY_STARTED"
        const val RAID_BATTLE_APPLIED_TERMINAL_RESULT = "RAID_BATTLE_APPLIED_TERMINAL_RESULT"
        const val TYPED_CONVERGENCE_WAKE_REASON = "TYPED_CONVERGENCE_CONTINUE"
        const val TYPED_CONVERGENCE_PROBE_REASON = "TYPED_CONVERGENCE_PROBE"
        const val TYPED_BATTLE_GATE_WAKE_REASON = "TYPED_BATTLE_GATE_OPENED"
        const val ACTION_SUPERSEDED_REASON = "ACTION_SUPERSEDED_BY_FRESH_STATE"
        const val POST_KILL_SWITCH_WAKE_REASON = "AUTOMATION_POST_KILL_SWITCH"
        const val POST_KILL_SWITCH_RECHECK_SECONDS = 30L
    }
}
