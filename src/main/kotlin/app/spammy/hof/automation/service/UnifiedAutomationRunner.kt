package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.AutomationWaitReason
import app.spammy.hof.automation.history.*
import app.spammy.hof.automation.port.AutomationWakeupPort
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.external.client.HofAutomationDeferredException
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
                runAcquired(accountId, acquisition.execution)
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
    ) {
        var execution = initialExecution
        var checkpoint = execution.checkpoint
        var decisionCycleId: Long? = null
        var selectedWarnings: List<String>? = null
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
                    applyRecoveredExecution(accountId, resolution.execution)
                    typedRuntime.complete(
                        execution,
                        TypedRuntimeOutcome.ReconciliationApplied(recoveredWakeReason(resolution.execution)),
                    )
                    decisionCycleId?.let { cycleId ->
                        val resultTrace = when (val recovered = resolution.execution) {
                            is TypedAutomationExecution.RaidCycleFinished -> recovered.outcome.toAutomationActionTrace()
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
                is AmbiguousActionResolution.VerifyLater -> {
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

        val submission = typedRuntime.beginSubmission(execution)
        if (submission !is TypedRuntimeSubmission.Started) {
            typedRuntime.complete(
                execution,
                TypedRuntimeOutcome.SelectionChanged("TYPED_CONFIG_RELOAD"),
            )
            return
        }
        decisionCycleId?.let { cycleId ->
            decisionJournal?.appendActionResult(
                cycleId,
                trace(AutomationHistoryEventKind.ACTION_STARTED, "ACTION_STARTED", "자동화 행동을 시작했습니다."),
            )
        }
        try {
            val domainExecution = managedAction.execute()
            val storedBattle = stored.payload as? StoredTypedActionPayload.BattleMap
            val recoveryAppliedByTerminalResult =
                domainExecution is TypedAutomationExecution.BattleCompleted &&
                    storedBattle?.source == BattleAutomationActionSource.RAID_AUTOMATION &&
                    storedBattle.recoveryChainId != null
            val wakeReason = when (domainExecution) {
                TypedAutomationExecution.Completed -> "TYPED_ACTION_COMPLETED"
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
            typedRuntime.complete(execution, outcome)
            decisionCycleId?.let { cycleId ->
                val resultTrace = when (domainExecution) {
                    is TypedAutomationExecution.RaidCycleFinished -> domainExecution.outcome.toAutomationActionTrace()
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
            error.findHofAutomationDeferral()?.let { deferred ->
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
                    finishRaidBattleHandoff(handedOff)
                    return
                }
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
            decisionCycleId?.let { cycleId -> runCatching {
                decisionJournal?.appendActionResult(
                    cycleId,
                    trace(AutomationHistoryEventKind.ACTION_FAILED, "ACTION_FAILED", error.message ?: error.javaClass.simpleName),
                )
            } }
            retryExecution(accountId, execution, classifyActionStop(error), error.message ?: error.javaClass.simpleName)
        }
    }

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

    @Suppress("unused")
    private fun runOneLegacy(accountId: Long) {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "Typed automation runner must not be called with an active transaction."
        }
        if (!typedRuntime.isRunning(accountId)) return
        if (!typedRuntime.isCompletingCurrentAction(accountId)) when (val preflight = dailyPreflight.ensureReady(accountId)) {
            AutomationDailyPreflight.Result.Ready -> Unit
            is AutomationDailyPreflight.Result.Busy -> { wakeupPort.schedule(accountId, preflight.retryAt, "DAILY_PREFLIGHT_BUSY"); return }
            is AutomationDailyPreflight.Result.RetryScheduled -> {
                if (typedRuntime.deferUntil(
                        accountId,
                        preflight.nextAttemptAt,
                        AutomationWaitReason.HOF_CONNECTION,
                    )
                ) {
                    wakeupPort.schedule(accountId, preflight.nextAttemptAt, "DAILY_PREFLIGHT_RETRY")
                }
                return
            }
            is AutomationDailyPreflight.Result.Stopped -> {
                val reason = when (preflight.reason) {
                    AutomationDailyPreflight.StopReason.AUTHENTICATION -> AutomationStopReason.AUTHENTICATION
                    AutomationDailyPreflight.StopReason.CAPTCHA -> AutomationStopReason.CAPTCHA
                    AutomationDailyPreflight.StopReason.NETWORK -> AutomationStopReason.NETWORK
                    AutomationDailyPreflight.StopReason.FATAL -> AutomationStopReason.FATAL
                }
                dailyPreflight.resume(accountId)
                scheduleAutomaticRetry(accountId, reason, "Daily preflight failed: ${preflight.reason}")
                return
            }
        }
        val claim = typedRuntime.claim(accountId)
        if (claim !is TypedRuntimeClaim.Acquired) return
        val token = claim.token
        var decisionCycleId: Long? = null
        var selectedWarnings: List<String>? = null
        lateinit var managedAction: ManagedAutomationAction
        var stored = claim.preparedAction?.let {
            runCatching {
                actionLifecycleModule.restore(it, accountId).let { restored ->
                    managedAction = restored
                    restored.storedAction
                }
            }.getOrElse { error ->
                isolateStoredActionIntegrityFailure(accountId, token, it, error)
                return
            }
        } ?: run {
            val decision = try {
                decisionSource.select(accountId)
            } catch (_: TypedAutomationConfigurationChangedException) {
                typedRuntime.releaseAndEnqueueWake(accountId, token, "TYPED_CONFIG_RELOAD")
                return
            } catch (error: HofAutomationDeferredException) {
                if (typedRuntime.release(
                        accountId,
                        token,
                        error.retryAt,
                        AutomationWaitReason.HOF_CONNECTION,
                    )
                ) {
                    wakeupPort.schedule(accountId, error.retryAt, HOF_COOLDOWN_WAKE_REASON)
                }
                return
            } catch (error: SafeRetryableAutomationException) {
                typedRuntime.scheduleSafeRetry(accountId, token, error.message ?: "Safe snapshot retry")?.let {
                    wakeupPort.schedule(accountId, it, "TYPED_SAFE_RETRY")
                }
                return
            } catch (error: AutomationLoginRequiredException) {
                scheduleAutomaticRetry(accountId, token, null, AutomationStopReason.AUTHENTICATION, error.message)
                return
            } catch (error: ApiException) {
                val reason = when (error.errorCode) {
                    ErrorCode.CAPTCHA_REQUIRED -> AutomationStopReason.CAPTCHA
                    ErrorCode.HOF_LOGIN_FAILED, ErrorCode.HOF_SESSION_EXPIRED -> AutomationStopReason.AUTHENTICATION
                    else -> AutomationStopReason.FATAL
                }
                scheduleAutomaticRetry(accountId, token, null, reason, error.message)
                return
            } catch (error: FatalAutomationException) {
                scheduleAutomaticRetry(accountId, token, null, AutomationStopReason.FATAL, error.message ?: "Fatal live snapshot failure")
                return
            }
            val decisionDescriptor = try {
                (decision as? AutomationCoordination.Runnable)
                    ?.let { actionLifecycleModule.describe(it.action) }
            } catch (error: Exception) {
                stopPreparationFailure(
                    accountId,
                    token,
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
                stopPreparationFailure(accountId, token, (decision as? AutomationCoordination.Runnable)?.entryId ?: 0, "JOURNAL", error)
                return
            }
            when (decision) {
                is AutomationCoordination.Runnable -> {
                    try {
                        selectedWarnings = decision.warnings
                        typedRuntime.recordWarnings(accountId, token, decision.warnings)
                        actionLifecycleModule.prepare(accountId, decision.entryId, decision.action).let { prepared ->
                            managedAction = prepared
                            prepared.storedAction
                        }
                    } catch (error: Exception) {
                        stopPreparationFailure(accountId, token, decision.entryId, "BUILD", error)
                        return
                    }
                }
                is AutomationCoordination.Fatal -> {
                    typedRuntime.recordWarnings(accountId, token, decision.warnings)
                    scheduleAutomaticRetry(accountId, token, null, decision.reason, decision.message)
                    return
                }
                is AutomationCoordination.Unavailable -> {
                    typedRuntime.releaseWithDiagnostics(accountId, token, decision.nextRunAt, decision.warnings)
                    wakeupPort.schedule(accountId, decision.nextRunAt, "TYPED_UNAVAILABLE")
                    return
                }
                is AutomationCoordination.Idle -> {
                    if (decision.warnings.isEmpty()) {
                        typedRuntime.releaseWithDiagnostics(accountId, token, null, emptyList())
                    } else {
                        typedRuntime.deferForConfiguration(accountId, token, decision.warnings)?.let {
                            wakeupPort.schedule(accountId, it, "TYPED_CONFIG_RECHECK")
                        }
                    }
                    return
                }
            }
        }
        val row = claim.preparedAction ?: run {
            val prepared = try {
                typedRuntime.prepare(accountId, token, stored)
            } catch (error: Exception) {
                stopPreparationFailure(accountId, token, stored.entryId, "PERSIST", error)
                return
            }
            prepared ?: run {
                typedRuntime.releaseAndEnqueueWake(accountId, token, "TYPED_CONFIG_RELOAD")
                return
            }
        }
        if (claim.preparedAction == null) {
            val restored = runCatching {
                actionLifecycleModule.restore(row, accountId)
            }.getOrElse { error ->
                isolateStoredActionIntegrityFailure(accountId, token, row, error)
                return
            }
            managedAction = restored
            stored = restored.storedAction
        }
        val actionDescriptor = managedAction.descriptor
        fun trace(
            kind: AutomationHistoryEventKind,
            code: String,
            message: String,
            nextRunAt: Instant? = null,
        ) = actionTrace(stored, kind, code, message, nextRunAt, actionDescriptor)
        fun finishRaidBattleHandoff(resolution: AmbiguousActionResolution.HandedOff) {
            typedRuntime.handoffAmbiguousAction(
                accountId,
                token,
                row.id,
                resolution.reason,
                RAID_BATTLE_RECOVERY_WAKE_REASON,
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
                val reconciling = row.status == app.spammy.hof.automation.entity.TypedAutomationActionStatus.RECONCILING
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
                stopPreparationFailure(accountId, token, stored.entryId, "JOURNAL_RETRY", error)
                return
            }
        }
        if (row.status == app.spammy.hof.automation.entity.TypedAutomationActionStatus.RECONCILING) {
            val resolution = try {
                managedAction.handoffAmbiguousSubmission(
                    row.submittedAt,
                    row.lastError ?: "Stored raid battle submission outcome is ambiguous.",
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
                    if (typedRuntime.deferReconciliation(accountId, token, row.id, deferred.retryAt, message)) {
                        wakeupPort.schedule(accountId, deferred.retryAt, HOF_COOLDOWN_WAKE_REASON)
                    }
                    return
                }
                log.warn(
                    "Typed automation reconciliation stopped accountId={} actionId={} errorType={}",
                    accountId,
                    row.id,
                    error.javaClass.name,
                )
                scheduleAutomaticRetry(
                    accountId, token, row.id, classifyActionStop(error),
                    error.message ?: error.javaClass.simpleName,
                )
                return
            }
            when (resolution) {
                is AmbiguousActionResolution.Applied -> {
                    applyRecoveredExecution(accountId, resolution.execution)
                    typedRuntime.succeedReconciliation(
                        accountId,
                        token,
                        row.id,
                        recoveredWakeReason(resolution.execution),
                    )
                    decisionCycleId?.let { cycleId ->
                        val trace = when (val execution = resolution.execution) {
                            is TypedAutomationExecution.RaidCycleFinished -> execution.outcome.toAutomationActionTrace()
                            else -> trace(
                                AutomationHistoryEventKind.ACTION_SUCCEEDED,
                                "AMBIGUOUS_RESULT_APPLIED",
                                "상태 재확인 결과 이전 요청이 이미 적용된 것으로 확인했습니다.",
                            )
                        }
                        decisionJournal?.appendActionResult(cycleId, trace)
                    }
                }
                AmbiguousActionResolution.Resubmit -> {
                    decisionCycleId?.let { cycleId ->
                        decisionJournal?.appendActionResult(cycleId, trace(
                            AutomationHistoryEventKind.WAITING,
                            "AMBIGUOUS_RESULT_RESUBMIT",
                            "상태 재확인 결과 적용되지 않아 같은 단계를 다시 제출합니다.",
                        ))
                    }
                    typedRuntime.retryReconciledSubmission(accountId, token, row.id, "TYPED_RECONCILED_RESUBMIT")
                }
                is AmbiguousActionResolution.VerifyLater -> {
                    decisionCycleId?.let { cycleId ->
                        decisionJournal?.appendActionResult(cycleId, trace(
                            AutomationHistoryEventKind.WAITING,
                            "AMBIGUOUS_RESULT_VERIFY_LATER",
                            "아직 적용 여부를 확정할 수 없어 다음 확인 시각까지 기다립니다. 사유: ${resolution.reason}",
                            resolution.retryAt,
                        ))
                    }
                    if (typedRuntime.deferReconciliation(
                            accountId,
                            token,
                            row.id,
                            resolution.retryAt,
                            resolution.reason,
                        )
                    ) {
                        wakeupPort.schedule(accountId, resolution.retryAt, "TYPED_RECONCILE_RETRY")
                    }
                }
                is AmbiguousActionResolution.HandedOff -> finishRaidBattleHandoff(resolution)
            }
            return
        }
        val submittedAt = typedRuntime.markSubmitting(accountId, token, row.id)
        if (submittedAt == null) {
            typedRuntime.releaseAndEnqueueWake(accountId, token, "TYPED_CONFIG_RELOAD")
            return
        }
        decisionCycleId?.let { cycleId ->
            decisionJournal?.appendActionResult(cycleId, trace(AutomationHistoryEventKind.ACTION_STARTED, "ACTION_STARTED", "자동화 행동을 시작했습니다."))
        }
        try {
            val execution = managedAction.execute()
            val storedBattle = stored.payload as? StoredTypedActionPayload.BattleMap
            val recoveryAppliedByTerminalResult =
                execution is TypedAutomationExecution.BattleCompleted &&
                    storedBattle?.source == BattleAutomationActionSource.RAID_AUTOMATION &&
                    storedBattle.recoveryChainId != null
            val wakeReason = when (execution) {
                TypedAutomationExecution.Completed -> "TYPED_ACTION_COMPLETED"
                is TypedAutomationExecution.BattleCompleted -> {
                    sharedBattleCooldowns.applyAfterSuccessfulBattle(
                        accountId,
                        execution.categoryId,
                        execution.mapCode,
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
                        execution.categoryId,
                        execution.mapCode,
                        execution.retryAt,
                    )
                    "TYPED_SHARED_COOLDOWN_SKIPPED"
                }
                is TypedAutomationExecution.RaidCycleFinished -> "TYPED_RAID_CYCLE_FINISHED"
            }
            val finalWarnings = if (
                execution is TypedAutomationExecution.BattleCompleted &&
                (stored.payload as? StoredTypedActionPayload.BattleMap)?.source ==
                    BattleAutomationActionSource.RAID_AUTOMATION
            ) {
                selectedWarnings.orEmpty().filterNot { it.startsWith("레이드 전투 결과 미확정") }
            } else {
                selectedWarnings
            }
            typedRuntime.succeedAndEnqueueWake(accountId, token, row.id, wakeReason, finalWarnings)
            decisionCycleId?.let { cycleId ->
                val trace = when (execution) {
                    is TypedAutomationExecution.RaidCycleFinished -> execution.outcome.toAutomationActionTrace()
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
                decisionJournal?.appendActionResult(cycleId, trace)
            }
        } catch (error: Throwable) {
            error.findHofAutomationDeferral()?.let { deferred ->
                decisionCycleId?.let { cycleId -> runCatching {
                    decisionJournal?.appendActionResult(cycleId, trace(
                        AutomationHistoryEventKind.WAITING,
                        "ACTION_HOF_DEFERRED",
                        "HOF 서버가 잠시 요청을 받지 않아 현재 단계를 보존하고 재시도합니다. 사유: ${deferred.message ?: "일시적 응답 지연"}",
                        deferred.retryAt,
                    ))
                } }
                if (typedRuntime.deferSubmittedAction(
                        accountId,
                        token,
                        row.id,
                        deferred.retryAt,
                        deferred.message ?: "HOF server returned 503.",
                    )
                ) {
                    wakeupPort.schedule(accountId, deferred.retryAt, HOF_COOLDOWN_WAKE_REASON)
                }
                return
            }
            error.findAmbiguousSubmission()?.let { ambiguous ->
                val message = ambiguous.message ?: "Automation submission outcome is ambiguous."
                val handedOff = runCatching {
                    managedAction.handoffAmbiguousSubmission(submittedAt, message)
                }.onFailure { handoffError ->
                    log.warn(
                        "Typed automation ambiguous handoff failed accountId={} actionId={} errorType={}",
                        accountId,
                        row.id,
                        handoffError.javaClass.name,
                    )
                }.getOrNull()
                if (handedOff != null) {
                    finishRaidBattleHandoff(handedOff)
                    return
                }
                decisionCycleId?.let { cycleId -> runCatching {
                    decisionJournal?.appendActionResult(cycleId, trace(
                        AutomationHistoryEventKind.WAITING,
                        "ACTION_RESULT_AMBIGUOUS",
                        "요청 전송 후 결과가 불확실합니다. 같은 동작을 즉시 다시 보내지 않고 HOF 상태를 재확인합니다. 사유: ${ambiguous.message ?: "응답 확인 실패"}",
                    ))
                } }
                typedRuntime.markReconcilingAndEnqueueWake(
                    accountId,
                    token,
                    row.id,
                    message,
                )
                return
            }
            log.warn("Typed automation action stopped accountId={} actionId={} errorType={}", accountId, row.id, error.javaClass.name)
            decisionCycleId?.let { cycleId -> runCatching {
                decisionJournal?.appendActionResult(cycleId, trace(AutomationHistoryEventKind.ACTION_FAILED, "ACTION_FAILED", error.message ?: error.javaClass.simpleName))
            } }
            scheduleAutomaticRetry(
                accountId, token, row.id, classifyActionStop(error),
                error.message ?: error.javaClass.simpleName,
            )
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

    private fun stopPreparationFailure(
        accountId: Long,
        token: String,
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
            scheduleAutomaticRetry(
                accountId, token, null, AutomationStopReason.FATAL,
                error.message ?: error.javaClass.simpleName,
            )
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun isolateStoredActionIntegrityFailure(
        accountId: Long,
        token: String,
        row: app.spammy.hof.automation.entity.TypedAutomationActionRunEntity,
        error: Throwable,
    ) {
        log.warn(
            "Stored typed action integrity failure accountId={} actionId={} errorType={}",
            accountId,
            row.id,
            error.javaClass.name,
        )
        typedRuntime.isolateIntegrityFailureForRetry(
            accountId,
            token,
            row.id,
            "Stored typed action integrity check failed.",
        )?.let { retryAt ->
            wakeupPort.schedule(accountId, retryAt, AUTOMATIC_RETRY_WAKE_REASON)
        }
    }

    private fun scheduleAutomaticRetry(
        accountId: Long,
        reason: AutomationStopReason,
        message: String,
    ) {
        typedRuntime.scheduleAutomaticRetry(accountId, reason, message)?.let {
            wakeupPort.schedule(accountId, it, AUTOMATIC_RETRY_WAKE_REASON)
        }
    }

    private fun scheduleAutomaticRetry(
        accountId: Long,
        token: String,
        actionId: Long?,
        reason: AutomationStopReason,
        message: String,
    ) {
        typedRuntime.scheduleAutomaticRetry(accountId, token, actionId, reason, message)?.let {
            wakeupPort.schedule(accountId, it, AUTOMATIC_RETRY_WAKE_REASON)
        }
    }

    private fun Throwable.findHofAutomationDeferral(): HofAutomationDeferredException? =
        generateSequence(this) { it.cause }.filterIsInstance<HofAutomationDeferredException>().firstOrNull()

    private fun Throwable.findAmbiguousSubmission(): AmbiguousAutomationSubmissionException? =
        generateSequence(this) { it.cause }.filterIsInstance<AmbiguousAutomationSubmissionException>().firstOrNull()

    private fun applyRecoveredExecution(accountId: Long, execution: TypedAutomationExecution) {
        when (execution) {
            TypedAutomationExecution.Completed -> Unit
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
    }
}
