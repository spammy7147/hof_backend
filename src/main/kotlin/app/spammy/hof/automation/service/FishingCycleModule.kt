package app.spammy.hof.automation.service

import app.spammy.hof.automation.convergence.AutomationActionEvidence
import app.spammy.hof.automation.convergence.ConvergenceDirective
import app.spammy.hof.automation.convergence.LegacyConvergenceDecision
import app.spammy.hof.automation.history.*
import app.spammy.hof.auth.service.AccountExecutionSubmissionGate
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.town.fishing.model.FishingAction
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import app.spammy.hof.town.common.service.TownSubmissionBoundary
import app.spammy.hof.town.fishing.dto.FishingResponse
import app.spammy.hof.town.fishing.service.FishingService
import app.spammy.hof.town.fishing.service.FishingOneCastRemoteResult

/** 준비된 낚시 한 번의 단계 전이와 영속 종료를 소유한다. */
@Service
class FishingCycleModule(
    private val fishingService: FishingService,
    private val submissionGate: AccountExecutionSubmissionGate,
    private val typedRuntime: TypedAutomationRuntimeService,
    private val actionLifecycleModule: AutomationActionLifecycleModule,
    private val results: AutomationResultCoordinator,
    private val decisionJournal: AutomationDecisionJournal? = null,
    private val timeProvider: TimeProvider? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 저장 form은 프로세스 재시작 뒤 재전송하지 않는다. 공통 복구 전에 호출한다. */
    fun finishUnusablePrepared(
        accountId: Long,
        execution: TypedRuntimeExecutionRight,
        stored: StoredTypedAutomationAction,
        managed: ManagedFishingAutomationAction,
    ): Boolean {
        val action = (stored.payload as? StoredTypedActionPayload.FishingTown)?.action
        if (execution.checkpoint?.phase != TypedRuntimeCheckpointPhase.PREPARED ||
            action !in setOf(FishingAction.START, FishingAction.CATCH) || managed.cycleObservation != null
        ) return false
        results.discardLostFishingObservation(accountId, stored, "FISHING_OBSERVATION_LOST_BEFORE_SUBMISSION")
        typedRuntime.complete(execution, TypedRuntimeOutcome.ActionSuperseded(
            "저장된 낚시 관측 form은 프로세스 경계를 넘어 재사용하지 않고 최신 상태를 다시 판단합니다.",
            ACTION_SUPERSEDED_REASON,
        ))
        return true
    }

    /** true이면 실행권의 종료까지 처리했다. 기존 단일 행동 경로 대상이면 false다. */
    fun executePrepared(
        accountId: Long,
        execution: TypedRuntimeExecutionRight,
        managed: ManagedFishingAutomationAction,
        stored: StoredTypedAutomationAction,
        decisionCycleId: Long?,
        selectedWarnings: List<String>?,
        retryUnsubmitted: Boolean,
    ): Boolean {
        when ((stored.payload as? StoredTypedActionPayload.FishingTown)?.action) {
            FishingAction.START -> runFishingCycle(accountId, execution, managed, stored,
                decisionCycleId, selectedWarnings, retryUnsubmitted)
            FishingAction.CATCH -> {
                if (managed.cycleObservation == null) return false
                runObservedFishingCatch(accountId, execution, managed, stored,
                    decisionCycleId, selectedWarnings, retryUnsubmitted)
            }
            else -> return false
        }
        return true
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

        fun append(kind: AutomationHistoryEventKind, code: String, message: String, nextRunAt: Instant? = null) {
            appendFishingResult(accountId, decisionCycleId, stored, managed, kind, code, message, nextRunAt)
        }

        results.prepare(accountId, selection, retryUnsubmitted)?.let { directive ->
            when (directive) {
                is ConvergenceDirective.Submit -> attemptId = directive.attemptId
                else -> {
                    releaseForConvergenceDirective(execution, directive)
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
            recordPreparationRecovery(decisionJournal, accountId, decisionCycleId, stored, managed.descriptor)
            append(AutomationHistoryEventKind.ACTION_STARTED, "ACTION_STARTED", "낚시 CATCH를 시작했습니다.")
            val authorizedExecution = submissionGate.executeAuthorized(accountId) { managed.executeObservedResponse() }
            if (!authorizedExecution.authorized) {
                return typedRuntime.discardUnauthorizedSubmission(accountId, execution, attemptId, results, now())
            }
            val direct = authorizedExecution.value
                ?: throw AutomationActionPreconditionChangedException("재사용할 최신 CATCH 관측이 없습니다.")
            val evidence = results.directEvidence(selection, direct.execution)
            when (val result = results.applyFishingDirect(
                managed, direct.execution, evidence, attemptId,
                "낚시 CATCH 직접 응답이 적용을 확정하지 못했습니다.",
            )) {
                is AutomationResultCoordinator.DirectResult.Unapplied -> {
                    append(AutomationHistoryEventKind.WAITING, "FISHING_DIRECT_RESULT_UNCONFIRMED",
                        "직접 낚시 응답의 적용을 확정하지 못해 결과 확인 규칙에 따라 처리합니다.",
                        (result.directive as? ConvergenceDirective.WaitUntil)?.at)
                    typedRuntime.complete(
                        execution, result.outcome,
                        convergenceRecheckAt = (result.directive as? ConvergenceDirective.WaitUntil)?.at,
                    )
                    return
                }
                is AutomationResultCoordinator.DirectResult.Accepted -> attemptTerminalized = attemptId != null
            }
            commitDirectResult(stored.executionIdentity,
                persistResult = { results.finishDirect(accountId, stored, evidence, attemptId, direct.execution) },
                appendHistory = {
                    recordFishingResult(decisionCycleId, stored, managed,
                        AutomationHistoryEventKind.ACTION_SUCCEEDED, "FISHING_CATCH_APPLIED",
                        "낚시 CATCH 적용을 확인해 한 번 낚시를 완료했습니다.")
            }) { persist -> typedRuntime.complete(execution,
                TypedRuntimeOutcome.ActionSucceeded("TYPED_FISHING_CYCLE_COMPLETED", selectedWarnings), persist) }
        } catch (error: Throwable) {
            if (error is DirectResultPersistenceFailure) throw error
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
                if (attemptId != null && selection.policy != null) {
                    results.discardUnsubmitted(
                        accountId = accountId,
                        selection = requireNotNull(selection.policy),
                        discardedAt = now(),
                        reasonCode = deferred.reasonCode ?: "SUBMISSION_NOT_ATTEMPTED",
                    )
                }
                typedRuntime.complete(
                    execution,
                    TypedRuntimeOutcome.SubmissionDeferred(
                        deferred.retryAt,
                        deferred.message ?: "HOF 요청 간격을 기다립니다.",
                    ),
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
        val startPayload = startStored.payload as StoredTypedActionPayload.FishingTown
        val catchExecutionIdentity = java.util.UUID.randomUUID().toString()
        var execution = initialExecution
        var activeStored = startStored
        var activeManaged: ManagedAutomationAction = startManaged
        var activeAttemptId: Long? = null
        var activeAttemptTerminalized = false
        var activeSelection = results.fishingSelection(startStored)

        fun append(
            stored: StoredTypedAutomationAction,
            managed: ManagedAutomationAction,
            kind: AutomationHistoryEventKind,
            code: String,
            message: String,
            nextRunAt: Instant? = null,
        ) {
            appendFishingResult(accountId, decisionCycleId, stored, managed, kind, code, message, nextRunAt)
        }

        fun prepareConvergence(
            stored: StoredTypedAutomationAction,
            selection: AutomationResultCoordinator.ActionSelection,
        ): Long? {
            val directive = results.prepare(
                accountId, selection,
                retryUnsubmitted && stored.executionIdentity == startStored.executionIdentity,
            ) ?: return null
            return when (directive) {
                is ConvergenceDirective.Submit -> directive.attemptId
                else -> {
                    releaseForConvergenceDirective(execution, directive)
                    throw FishingCycleFlowStopped()
                }
            }
        }

        fun acceptStep(
            managed: ManagedFishingAutomationAction,
            stored: StoredTypedAutomationAction,
            selection: AutomationResultCoordinator.ActionSelection,
            attemptId: Long?,
            response: app.spammy.hof.town.fishing.dto.FishingResponse,
        ): () -> Unit {
            val direct = managed.observeDirectResponse(response)
            val evidence = results.directEvidence(selection, direct)
            when (val result = results.applyFishingDirect(
                managed, direct, evidence, attemptId,
                "낚시 직접 응답이 현재 단계를 확정하지 못했습니다.",
            )) {
                is AutomationResultCoordinator.DirectResult.Unapplied -> {
                    append(stored, managed, AutomationHistoryEventKind.WAITING, "FISHING_DIRECT_RESULT_UNCONFIRMED",
                        "직접 낚시 응답의 적용을 확정하지 못해 결과 확인 규칙에 따라 처리합니다.",
                        (result.directive as? ConvergenceDirective.WaitUntil)?.at)
                    typedRuntime.complete(
                        execution, result.outcome,
                        convergenceRecheckAt = (result.directive as? ConvergenceDirective.WaitUntil)?.at,
                    )
                    throw FishingCycleFlowStopped()
                }
                is AutomationResultCoordinator.DirectResult.Accepted -> activeAttemptTerminalized = attemptId != null
            }
            return { results.finishDirect(accountId, stored, evidence, attemptId, direct) }
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
            recordPreparationRecovery(decisionJournal, accountId, decisionCycleId, startStored, startManaged.descriptor)
            append(startStored, startManaged, AutomationHistoryEventKind.ACTION_STARTED, "ACTION_STARTED", "낚시 START를 시작했습니다.")
            fun startAppliedAndCatchPrepared(startResponse: FishingResponse) {
                val persistDirectResult = acceptStep(startManaged, startStored, activeSelection, activeAttemptId, startResponse)
                val outcome = TypedRuntimeOutcome.ActionSucceeded("TYPED_FISHING_START_APPLIED", selectedWarnings)
                val appendStartHistory = {
                    recordFishingResult(decisionCycleId, startStored, startManaged,
                        AutomationHistoryEventKind.ACTION_SUCCEEDED, "FISHING_START_APPLIED",
                        "낚시 START 적용을 확인했습니다.")
                }
                val catchStored = StoredTypedAutomationAction(
                    entryId = startStored.entryId,
                    executionIdentity = catchExecutionIdentity,
                    payload = StoredTypedActionPayload.FishingTown(
                        action = FishingAction.CATCH,
                        observedPrimaryAction = startResponse.primaryAction,
                        observedRemainingCasts = startResponse.remainingCasts,
                        progressDate = startPayload.progressDate,
                    ),
                )
                lateinit var catchManaged: ManagedFishingAutomationAction
                val preparation = commitDirectResult(startStored.executionIdentity, persistDirectResult, appendStartHistory) { persist ->
                    try {
                        catchManaged = actionLifecycleModule.restoreVerified(catchStored, accountId)
                            as? ManagedFishingAutomationAction
                            ?: throw IllegalStateException("Stored fishing CATCH is not managed as a fishing action.")
                        typedRuntime.advanceAppliedActionToPreparedFollowup(execution, catchStored, outcome, persist)
                    } catch (error: Exception) {
                        // 다음 단계의 저장 실패는 이미 확인한 START 직접 적용을 취소하지 않는다.
                        log.warn(
                            "Fishing CATCH preparation failed after START applied accountId={} executionIdentity={}",
                            accountId,
                            startStored.executionIdentity,
                            error,
                        )
                        typedRuntime.complete(execution, outcome, persist)
                        TypedRuntimePreparation.Invalidated
                    }
                }
                // START 직접 적용과 다음 CATCH의 제출 허용은 서로 다른 사실이다.
                if (preparation !is TypedRuntimePreparation.Ready) throw FishingCycleFlowStopped()
                execution = preparation.execution
                activeStored = catchStored
                activeManaged = catchManaged
                activeSelection = results.fishingSelection(catchStored)
                activeAttemptTerminalized = false
                activeAttemptId = prepareConvergence(catchStored, activeSelection)
                recordPreparationRecovery(decisionJournal, accountId, decisionCycleId, catchStored, catchManaged.descriptor)
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

            fun catchApplied(catchResponse: FishingResponse) {
                val catchManaged = activeManaged as? ManagedFishingAutomationAction
                    ?: error("Prepared fishing CATCH is not managed as a fishing action.")
                val persistDirectResult = acceptStep(catchManaged, activeStored, activeSelection, activeAttemptId, catchResponse)
                commitDirectResult(activeStored.executionIdentity, persistDirectResult, appendHistory = {
                    recordFishingResult(decisionCycleId, activeStored, activeManaged,
                        AutomationHistoryEventKind.ACTION_SUCCEEDED, "FISHING_CATCH_APPLIED",
                        "낚시 CATCH 적용을 확인해 한 번 낚시를 완료했습니다.")
                }) { persist -> typedRuntime.complete(execution,
                    TypedRuntimeOutcome.ActionSucceeded("TYPED_FISHING_CYCLE_COMPLETED", selectedWarnings), persist) }
            }

            fun battleRequired(startResponse: FishingResponse) {
                val evidence = AutomationActionEvidence.StateAdvanced(
                    now(),
                    "fishing-start-observed-pending-battle:${startResponse.battleTarget?.mapCode ?: "unknown"}",
                )
                val direct = startManaged.observeDirectResponse(startResponse)
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
            }

            fun waitingForCatch(startResponse: FishingResponse) {
                val persistDirectResult = acceptStep(startManaged, startStored, activeSelection, activeAttemptId, startResponse)
                commitDirectResult(startStored.executionIdentity, persistDirectResult, appendHistory = {
                    recordFishingResult(decisionCycleId, startStored, startManaged,
                        AutomationHistoryEventKind.WAITING, "FISHING_WAITING_FOR_CATCH",
                        "START 응답에 CATCH form이 없어 다음 판단에서 한 번만 다시 확인합니다.")
                }) { persist -> typedRuntime.complete(execution,
                    TypedRuntimeOutcome.ActionSucceeded("TYPED_FISHING_WAITING_FOR_CATCH", selectedWarnings), persist) }
            }
            val boundary = TownSubmissionBoundary { submission ->
                val authorized = submissionGate.executeAuthorized(accountId, submission)
                if (!authorized.authorized) throw FishingSubmissionAuthorizationCancelledException()
                requireNotNull(authorized.value)
            }
            when (val remote = fishingService.executeOneCastForAutomation(
                accountId, startManaged.cycleObservation, boundary, ::startAppliedAndCatchPrepared,
            )) {
                is FishingOneCastRemoteResult.Completed -> catchApplied(remote.catch)
                is FishingOneCastRemoteResult.WaitingForCatch -> {
                    if (remote.start.blockedByBattle) battleRequired(remote.start)
                    else waitingForCatch(remote.start)
                }
            }
        } catch (_: FishingCycleFlowStopped) {
            return
        } catch (_: FishingSubmissionAuthorizationCancelledException) {
            typedRuntime.discardUnauthorizedSubmission(accountId, execution, activeAttemptId, results, now())
            return
        } catch (error: Throwable) {
            if (error is DirectResultPersistenceFailure) throw error
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
                if (activeAttemptId != null && activeSelection.policy != null) {
                    results.discardUnsubmitted(
                        accountId = accountId,
                        selection = requireNotNull(activeSelection.policy),
                        discardedAt = now(),
                        reasonCode = deferred.reasonCode ?: "SUBMISSION_NOT_ATTEMPTED",
                    )
                }
                typedRuntime.complete(
                    execution,
                    TypedRuntimeOutcome.SubmissionDeferred(
                        deferred.retryAt,
                        deferred.message ?: "HOF 요청 간격을 기다립니다.",
                    ),
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

    private class FishingCycleFlowStopped : RuntimeException()

    private fun releaseForConvergenceDirective(
        execution: TypedRuntimeExecutionRight,
        directive: ConvergenceDirective,
    ) {
        typedRuntime.complete(
            execution,
            TypedRuntimeOutcome.SelectionChanged(TYPED_CONVERGENCE_WAKE_REASON),
            convergenceRecheckAt = (directive as? ConvergenceDirective.WaitUntil)?.at,
        )
    }

    private fun now(): Instant = timeProvider?.now() ?: Instant.now()

    private fun appendFishingResult(
        accountId: Long,
        decisionCycleId: Long?,
        stored: StoredTypedAutomationAction,
        managed: ManagedAutomationAction,
        kind: AutomationHistoryEventKind,
        code: String,
        message: String,
        nextRunAt: Instant?,
    ) {
        try {
            recordFishingResult(decisionCycleId, stored, managed, kind, code, message, nextRunAt)
        } catch (error: RuntimeException) {
            log.warn("Fishing result history unavailable accountId={} executionIdentity={} errorType={}",
                accountId, stored.executionIdentity, error.javaClass.name)
        }
    }

    private fun recordFishingResult(
        decisionCycleId: Long?,
        stored: StoredTypedAutomationAction,
        managed: ManagedAutomationAction,
        kind: AutomationHistoryEventKind,
        code: String,
        message: String,
        nextRunAt: Instant? = null,
    ) {
        if (decisionCycleId == null) return
        decisionJournal?.appendActionResult(decisionCycleId,
            automationActionTrace(stored, kind, code, message, nextRunAt, managed.descriptor,
                diagnosticContext = managed.diagnosticContext, observedAt = now()))
    }

    private companion object {
        const val ACTION_SUPERSEDED_REASON = "ACTION_SUPERSEDED_BY_FRESH_STATE"
        const val TYPED_CONVERGENCE_WAKE_REASON = "TYPED_CONVERGENCE_CONTINUE"
    }
}

private class FishingSubmissionAuthorizationCancelledException :
    RuntimeException("Fishing submission was cancelled because the account logged out.")
