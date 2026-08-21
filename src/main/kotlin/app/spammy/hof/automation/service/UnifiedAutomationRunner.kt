package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.AutomationWaitReason
import app.spammy.hof.automation.entity.AutomationType
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
import java.util.UUID

/** 최신 타입별 스냅샷 결정 또는 저장된 prepared payload 중 action 하나만 실행하는 자동화 루프다. */
@Service
class UnifiedAutomationRunner @Autowired constructor(
    private val dailyPreflight: AutomationDailyPreflight,
    private val typedRuntime: TypedAutomationRuntimeService,
    private val decisionSource: AutomationDecisionSource,
    private val workTracker: AutomationWorkTracker,
    private val typedActionExecutor: TypedAutomationActionExecutor,
    private val typedCodec: StoredTypedAutomationActionCodec,
    private val wakeupPort: AutomationWakeupPort,
    private val sharedBattleCooldowns: SharedBattleCooldownService,
    private val ambiguousReconciler: AutomationAmbiguousActionReconciler,
    private val actionLifecycleModule: AutomationActionLifecycleModule,
    private val decisionJournal: AutomationDecisionJournal? = null,
) {
    constructor(
        dailyPreflight: AutomationDailyPreflight,
        typedRuntime: TypedAutomationRuntimeService,
        typedSnapshotLoader: TypedAutomationSnapshotLoader,
        coordinator: AutomationCoordinator,
        typedActionExecutor: TypedAutomationActionExecutor,
        typedCodec: StoredTypedAutomationActionCodec,
        wakeupPort: AutomationWakeupPort,
        sharedBattleCooldowns: SharedBattleCooldownService,
        ambiguousReconciler: AutomationAmbiguousActionReconciler,
        actionLifecycleModule: AutomationActionLifecycleModule,
    ) : this(
        dailyPreflight,
        typedRuntime,
        AutomationDecisionSource { accountId -> coordinator.coordinate(typedSnapshotLoader.loadTyped(accountId)) },
        AutomationWorkTracker { _, _, _ -> null },
        typedActionExecutor,
        typedCodec,
        wakeupPort,
        sharedBattleCooldowns,
        ambiguousReconciler,
        actionLifecycleModule,
    )

    private val log = LoggerFactory.getLogger(javaClass)

    /** 한 wakeup에서 최대 action 하나만 실행하고 후속 판단은 새 wakeup과 새 스냅샷에 맡긴다. */
    fun runOne(accountId: Long) {
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
        var managedAction: ManagedAutomationAction? = null
        var stored = claim.preparedAction?.let {
            runCatching {
                actionLifecycleModule.restore(it, accountId)?.let { restored ->
                    managedAction = restored
                    restored.storedAction
                } ?: typedCodec.verifyPersisted(it, accountId)
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
            val decisionDescriptor = (decision as? AutomationCoordination.Runnable)
                ?.let { actionLifecycleModule.describe(it.action) }
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
                        actionLifecycleModule.prepare(accountId, decision.entryId, decision.action)?.let { prepared ->
                            managedAction = prepared
                            prepared.storedAction
                        } ?: run {
                            workTracker.ensureForAction(accountId, decision.entryId, decision.action)
                            toStored(decision.entryId, decision.action)
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
        if (claim.preparedAction == null && managedAction != null) {
            val restored = runCatching {
                requireNotNull(actionLifecycleModule.restore(row, accountId)) {
                    "Persisted managed action is not owned by the action lifecycle module."
                }
            }.getOrElse { error ->
                isolateStoredActionIntegrityFailure(accountId, token, row, error)
                return
            }
            managedAction = restored
            stored = restored.storedAction
        }
        val actionDescriptor = managedAction?.descriptor
        fun trace(
            kind: AutomationHistoryEventKind,
            code: String,
            message: String,
            nextRunAt: Instant? = null,
        ) = actionTrace(stored, kind, code, message, nextRunAt, actionDescriptor)
        if (decisionCycleId == null) {
            decisionCycleId = try {
                val reconciling = row.status == app.spammy.hof.automation.entity.TypedAutomationActionStatus.RECONCILING
                decisionJournal?.appendPreparedActionAttempt(
                    accountId,
                    trace(
                        if (reconciling) AutomationHistoryEventKind.WAITING else AutomationHistoryEventKind.SELECTED,
                        if (reconciling) "AMBIGUOUS_RESULT_VERIFY" else "PREPARED_ACTION_RETRY",
                        if (reconciling) {
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
                managedAction?.reconcile() ?: ambiguousReconciler.reconcile(accountId, stored)
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
            }
            return
        }
        if (!typedRuntime.markSubmitting(accountId, token, row.id)) {
            typedRuntime.releaseAndEnqueueWake(accountId, token, "TYPED_CONFIG_RELOAD")
            return
        }
        decisionCycleId?.let { cycleId ->
            decisionJournal?.appendActionResult(cycleId, trace(AutomationHistoryEventKind.ACTION_STARTED, "ACTION_STARTED", "자동화 행동을 시작했습니다."))
        }
        try {
            val execution = managedAction?.execute() ?: typedActionExecutor.execute(accountId, stored)
            val wakeReason = when (execution) {
                TypedAutomationExecution.Completed -> "TYPED_ACTION_COMPLETED"
                is TypedAutomationExecution.BattleCompleted -> {
                    sharedBattleCooldowns.applyAfterSuccessfulBattle(
                        accountId,
                        execution.categoryId,
                        execution.mapCode,
                    )
                    "TYPED_ACTION_COMPLETED"
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
            typedRuntime.succeedAndEnqueueWake(accountId, token, row.id, wakeReason, selectedWarnings)
            decisionCycleId?.let { cycleId ->
                val trace = when (execution) {
                    is TypedAutomationExecution.RaidCycleFinished -> execution.outcome.toAutomationActionTrace()
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
                    ambiguous.message ?: "Automation submission outcome is ambiguous.",
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
                )
            } else {
                item
            }
        })
    }

    private fun toStored(entryId: Long, action: PreparedAutomationAction): StoredTypedAutomationAction {
        val executionId = when (action) {
            is BattleMapAutomationAction -> action.executionIdentity
            is AdventureMapAutomationAction -> action.executionIdentity
            else -> UUID.randomUUID().toString()
        }
        val payload = when (action) {
            is QuestAction.Claim -> StoredTypedActionPayload.QuestClaim(
                action.questKey, action.actionNo, StoredActionDisplay(questName = action.questName),
            )
            is QuestAction.Accept -> StoredTypedActionPayload.QuestAccept(
                action.questKey, action.actionNo, StoredActionDisplay(questName = action.questName),
            )
            is QuestAction.Battle -> StoredTypedActionPayload.QuestBattle(
                action.questKey, action.questCycle, action.missionKey, action.missionType,
                action.categoryId, action.mapCode, action.preset.mode,
                action.preset.resolvedPresetId ?: action.preset.presetId ?: throw AutomationConfigurationException(), action.battleCount,
                action.resolvedParty.toRequest(action.categoryId, action.mapCode, action.battleCount),
                StoredActionDisplay(
                    action.questName,
                    action.missionLabel,
                    action.missionCurrent,
                    action.missionRequired,
                    action.mapName,
                ),
                observedCurrent = action.missionCurrent,
                observedRequired = action.missionRequired,
            )
            is BattleMapAutomationAction -> StoredTypedActionPayload.BattleMap(
                action.progressDate, action.categoryId, action.mapCode, action.presetMode,
                action.presetId ?: throw AutomationConfigurationException(), action.battleCount,
                action.resolvedParty.toRequest(action.categoryId, action.mapCode, action.battleCount),
                display = StoredActionDisplay(mapName = action.mapName),
                source = action.source,
                sourceTargetKey = action.sourceTargetKey,
            )
            is AdventureMapAutomationAction -> StoredTypedActionPayload.AdventureMap(
                action.categoryId, action.mapCode, action.presetMode, action.presetId,
                action.battleCount, action.settingIdentity,
                action.resolvedParty.toRequest(action.categoryId, action.mapCode, action.battleCount),
                StoredActionDisplay(mapName = action.mapName),
                observedCooldownUntil = action.observedCooldownUntil,
                observedAttemptRemaining = action.observedAttemptRemaining,
                observedWinRemaining = action.observedWinRemaining,
                observedAvailableCount = action.observedAvailableCount,
            )
            is FishingTownAutomationAction -> StoredTypedActionPayload.FishingTown(action.action, action.observedPrimaryAction, action.observedRemainingCasts)
            is RaidTownAutomationAction -> StoredTypedActionPayload.RaidTown(
                action.action,
                action.raidId,
                action.targetRaidId,
                StoredActionDisplay(mapName = action.raidName, missionLabel = action.observedStatus),
            )
            is RaidCycleAbortAutomationAction -> StoredTypedActionPayload.RaidCycleAbort(action.raidId, action.reason)
            else -> error("Prepared action belongs to the action lifecycle module.")
        }
        return StoredTypedAutomationAction(entryId, executionId, payload)
    }

    private fun ResolvedAutomationParty?.toRequest(categoryId: String, mapCode: String, battleCount: Int) =
        this?.let { app.spammy.hof.battle.dto.RunBattleRequest(categoryId, mapCode, it.characterIds, it.patternLoads, battleCount) }
            ?: throw AutomationConfigurationException("The prepared party is missing.")

    private fun actionTrace(
        action: StoredTypedAutomationAction,
        kind: AutomationHistoryEventKind,
        code: String,
        message: String,
        nextRunAt: Instant? = null,
        descriptor: AutomationActionDescriptor? = null,
    ): AutomationActionTrace {
        if (descriptor != null) {
            return AutomationActionTrace(
                kind = kind,
                reasonCode = code,
                message = "${descriptor.context} · $message",
                entryId = action.entryId,
                type = descriptor.source,
                actionKind = descriptor.actionKind,
                targetKey = descriptor.targetKey,
                targetName = descriptor.targetName,
                presetId = null,
                nextRunAt = nextRunAt,
            )
        }
        val payload = action.payload
        val type = when (payload) {
            is StoredTypedActionPayload.QuestClaim, is StoredTypedActionPayload.QuestAccept, is StoredTypedActionPayload.QuestBattle -> AutomationType.QUEST
            is StoredTypedActionPayload.AdventureMap -> AutomationType.ADVENTURE_MAP
            is StoredTypedActionPayload.FishingTown -> AutomationType.FISHING
            is StoredTypedActionPayload.RaidTown, is StoredTypedActionPayload.RaidCycleAbort -> AutomationType.RAID
            is StoredTypedActionPayload.BattleMap -> when (payload.source) {
                BattleAutomationActionSource.UNION_AUTOMATION -> AutomationType.UNION
                BattleAutomationActionSource.FISHING_AUTOMATION -> AutomationType.FISHING
                BattleAutomationActionSource.RAID_AUTOMATION -> AutomationType.RAID
                BattleAutomationActionSource.ADVENTURE_AUTOMATION -> AutomationType.ADVENTURE_MAP
                BattleAutomationActionSource.QUEST_AUTOMATION -> AutomationType.QUEST
                BattleAutomationActionSource.BATTLE_MAP_AUTOMATION -> AutomationType.BATTLE_MAP
            }
            else -> error("Stored action belongs to the action lifecycle module.")
        }
        val actionKind = when (payload) {
            is StoredTypedActionPayload.RaidTown -> payload.action.name
            is StoredTypedActionPayload.RaidCycleAbort -> "CYCLE_ABORT"
            is StoredTypedActionPayload.FishingTown -> payload.action.name
            else -> payload.kind()
        }
        val actionContext = when (payload) {
            is StoredTypedActionPayload.QuestClaim -> "퀘스트 보상 수령 · ${payload.display?.questName ?: payload.questKey}"
            is StoredTypedActionPayload.QuestAccept -> "퀘스트 수락 · ${payload.display?.questName ?: payload.questKey}"
            is StoredTypedActionPayload.QuestBattle -> listOfNotNull(
                "퀘스트 전투 · ${payload.display?.questName ?: payload.questKey}",
                payload.display?.missionLabel,
                payload.observedCurrent?.let { current -> "실행 전 진행 $current/${payload.observedRequired ?: "?"}" },
                "맵 ${payload.display?.mapName ?: "${payload.categoryId}/${payload.mapCode}"}",
                "${payload.battleCount}회 전투",
            ).joinToString(" · ")
            is StoredTypedActionPayload.BattleMap -> listOf(
                when (payload.source) {
                    BattleAutomationActionSource.BATTLE_MAP_AUTOMATION -> "일반 전투"
                    BattleAutomationActionSource.UNION_AUTOMATION -> "유니온 전투"
                    BattleAutomationActionSource.FISHING_AUTOMATION -> "낚시 방해 전투"
                    BattleAutomationActionSource.RAID_AUTOMATION -> "레이드 누적 전투"
                    BattleAutomationActionSource.ADVENTURE_AUTOMATION -> "모험 전투"
                    BattleAutomationActionSource.QUEST_AUTOMATION -> "퀘스트 전투"
                },
                "맵 ${payload.display?.mapName ?: "${payload.categoryId}/${payload.mapCode}"}",
                "${payload.battleCount}회",
                "파티 ${payload.battleRequest.characterIds.size}명",
            ).joinToString(" · ")
            is StoredTypedActionPayload.AdventureMap -> listOfNotNull(
                "모험 맵 전투 · ${payload.display?.mapName ?: "${payload.categoryId}/${payload.mapCode}"}",
                "${payload.battleCount}회",
                payload.observedAttemptRemaining?.let { "실행 전 남은 도전 ${it}회" },
                payload.observedWinRemaining?.let { "실행 전 남은 승리 ${it}회" },
                payload.observedAvailableCount?.let { "실행 가능 ${it}회" },
                payload.observedCooldownUntil?.let { "관측 쿨다운 $it" },
            ).joinToString(" · ")
            is StoredTypedActionPayload.FishingTown -> when (payload.action) {
                app.spammy.hof.town.fishing.model.FishingAction.START -> "낚시 사이클 시작 · 다음 필수 단계 잡기(CATCH)${payload.observedRemainingCasts?.let { " · 실행 전 남은 ${it}회" } ?: ""}"
                app.spammy.hof.town.fishing.model.FishingAction.CATCH -> "낚시 사이클 잡기 · 이후 물고기 획득/전투 발생 결과와 남은 횟수 재확인${payload.observedRemainingCasts?.let { " · 실행 전 남은 ${it}회" } ?: ""}"
                else -> "낚시 ${payload.action.name}"
            }
            is StoredTypedActionPayload.RaidTown -> {
                val phase = when (payload.action) {
                app.spammy.hof.town.raid.model.RaidAction.REGISTER -> "파티 등록"
                app.spammy.hof.town.raid.model.RaidAction.START -> "전투 시작"
                app.spammy.hof.town.raid.model.RaidAction.REWARD -> "보상 수령"
                app.spammy.hof.town.raid.model.RaidAction.REFRESH -> "상태 갱신"
                app.spammy.hof.town.raid.model.RaidAction.RESET -> "레이드 리셋"
                else -> payload.action.name
                }
                "$phase 단계${payload.display?.missionLabel?.let { " · 관측 상태: $it" } ?: ""}"
            }
            is StoredTypedActionPayload.RaidCycleAbort -> when (payload.reason) {
                RaidCycleAbortReason.CLOSED -> "레이드 사이클 중단 · ${payload.raidId}"
                RaidCycleAbortReason.REGISTRATION_LOST -> "레이드 등록 상태 유실 복구 · ${payload.raidId}"
            }
        }
        val detailedMessage = "$actionContext · $message"
        return AutomationActionTrace(kind, code, detailedMessage, action.entryId, type, actionKind,
            targetKey = when (payload) {
                is StoredTypedActionPayload.QuestClaim -> payload.questKey
                is StoredTypedActionPayload.QuestAccept -> payload.questKey
                is StoredTypedActionPayload.QuestBattle -> "${payload.categoryId}/${payload.mapCode}"
                is StoredTypedActionPayload.BattleMap -> "${payload.categoryId}/${payload.mapCode}"
                is StoredTypedActionPayload.AdventureMap -> "${payload.categoryId}/${payload.mapCode}"
                is StoredTypedActionPayload.RaidTown -> payload.targetRaidId ?: payload.raidId
                is StoredTypedActionPayload.RaidCycleAbort -> payload.raidId
                else -> null
            }, targetName = payload.display?.mapName ?: payload.display?.questName, presetId = when (payload) {
                is StoredTypedActionPayload.QuestBattle -> payload.presetId
                is StoredTypedActionPayload.BattleMap -> payload.presetId
                is StoredTypedActionPayload.AdventureMap -> payload.presetId
                else -> null
            }, nextRunAt = nextRunAt)
    }

    private companion object {
        const val HOF_COOLDOWN_WAKE_REASON = "HOF_503_COOLDOWN"
        const val AUTOMATIC_RETRY_WAKE_REASON = "TYPED_AUTOMATIC_RETRY"
    }
}
