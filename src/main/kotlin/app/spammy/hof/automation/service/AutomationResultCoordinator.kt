package app.spammy.hof.automation.service

import app.spammy.hof.automation.convergence.*
import app.spammy.hof.automation.history.AutomationDecisionJournal
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.client.HofAutomationDeferredException
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** 모드별 증거 귀속과 진행 반영을 연결한다. 실행권·원격 제출·예약은 호출자가 소유한다. */
@Service
class AutomationResultCoordinator(
    private val actionLifecycleModule: AutomationActionLifecycleModule,
    private val sharedBattleCooldowns: SharedBattleCooldownService,
    private val convergenceModule: AutomationActionConvergenceModule? = null,
    private val convergenceSelectionFactory: StoredActionConvergenceSelectionFactory? = null,
    private val storedConvergenceActionLoader: StoredConvergenceActionLoader? = null,
    private val timeProvider: TimeProvider? = null,
    rollout: AutomationConvergenceRollout? = null,
    private val shadowEvaluator: AutomationConvergenceShadowEvaluator? = null,
    private val evidenceInterpreter: ProductionActionEvidenceInterpreter? = null,
    private val decisionJournal: AutomationDecisionJournal? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val active = rollout?.active ?: (convergenceModule != null)
    private val shadow = rollout?.shadow == true
    val postsEnabled = rollout?.automationPostsEnabled != false

    data class ActionSelection(val policy: SelectedAutomationAction? = null, val evidence: SelectedAutomationAction? = null)

    /** null은 활성 모드의 필수 수렴 연결 누락이며, 호출자는 기존 준비 실패 경로로 종료한다. */
    fun freshSelection(accountId: Long, stored: StoredTypedAutomationAction): ActionSelection? {
        val factory = convergenceSelectionFactory ?: return ActionSelection()
        if (active) {
            if (convergenceModule == null) return null
            val selection = factory.create(stored)
            return ActionSelection(selection, selection)
        }
        return restoredSelection(accountId, stored)
    }

    fun restoredSelection(accountId: Long, stored: StoredTypedAutomationAction): ActionSelection {
        val evidence = if (shadow) convergenceSelectionFactory?.create(stored)?.also {
            selectShadow(accountId, it)
        } else null
        return ActionSelection(evidence = evidence)
    }

    fun continuationSelection(stored: StoredTypedAutomationAction, previous: ActionSelection): ActionSelection? {
        if (!active) return previous
        if (convergenceModule == null) return null
        val selection = convergenceSelectionFactory?.create(stored)
        return ActionSelection(selection, selection)
    }

    fun discardLostFishingObservation(accountId: Long, stored: StoredTypedAutomationAction, reasonCode: String) {
        if (!active) return
        freshSelection(accountId, stored)?.policy?.let {
            discardUnsubmitted(accountId, it, now(), reasonCode)
        }
    }

    /** 낚시는 기존 LEGACY 경로에서도 단계별 직접 응답 증거를 만든다. */
    fun fishingSelection(stored: StoredTypedAutomationAction): SelectedAutomationAction? =
        convergenceSelectionFactory?.create(stored)

    fun prepareFishing(accountId: Long, selection: SelectedAutomationAction?, retryUnsubmitted: Boolean): ConvergenceDirective? =
        if (active && selection != null) prepare(accountId, selection, retryUnsubmitted) else null

    fun resumeDue(accountId: Long): ConvergenceDirective? = if (active) convergenceModule?.resumeDue(accountId) else null

    fun prepare(accountId: Long, selection: SelectedAutomationAction, retryUnsubmitted: Boolean): ConvergenceDirective =
        requireNotNull(convergenceModule).let {
            if (retryUnsubmitted) it.retryUnsubmitted(accountId, selection, now()) else it.prepare(accountId, selection)
        }

    fun record(attemptId: Long, evidence: AutomationActionEvidence): ConvergenceDirective? =
        convergenceModule?.record(attemptId, evidence)

    fun discardUnsubmitted(accountId: Long, selection: SelectedAutomationAction, discardedAt: Instant, reasonCode: String): Boolean? =
        convergenceModule?.discardUnsubmitted(accountId, selection, discardedAt, reasonCode)

    fun holdUnresolved(
        accountId: Long,
        stored: StoredTypedAutomationAction,
        checkpoint: TypedRuntimeCheckpoint,
        evidence: AutomationActionEvidence.ResultUnobserved,
        successfulObservationCount: Int,
        firstPendingAt: Instant,
    ) {
        convergenceSelectionFactory?.create(stored, checkpoint.legacySuppressionEpoch)?.let {
            convergenceModule?.holdUnresolved(accountId, it, evidence, successfulObservationCount, firstPendingAt)
        }
        observeShadow(accountId, stored.executionIdentity, evidence, LegacyConvergenceDecision.RESULT_UNOBSERVED)
    }

    data class BattleGate(val warning: String, val directive: ConvergenceDirective)

    fun closeBattleForCaptcha(
        accountId: Long,
        stored: StoredTypedAutomationAction,
        selected: ActionSelection,
        attemptId: Long?,
        error: Throwable,
    ): BattleGate? {
        val captcha = generateSequence(error) { it.cause }
            .filterIsInstance<ApiException>()
            .firstOrNull { it.errorCode == ErrorCode.CAPTCHA_REQUIRED } ?: return null
        val selection = selected.policy ?: selected.evidence ?: convergenceSelectionFactory?.create(stored) ?: return null
        if (!selection.actionKind.battle) return null
        val convergence = convergenceModule ?: return null
        val evidence = AutomationActionEvidence.BattleGateRequired(now(), null, "CAPTCHA_REQUIRED")
        val directive = attemptId?.let { convergence.record(it, evidence) }
            ?: convergence.requireBattleGate(accountId, null, "CAPTCHA_REQUIRED", evidence.capturedAt)
        observeShadow(accountId, stored.executionIdentity, evidence, LegacyConvergenceDecision.HELD)
        return BattleGate(captcha.message, directive)
    }

    data class CheckpointRecovery(val outcome: TypedRuntimeOutcome, val directive: ConvergenceDirective? = null)

    fun recoverLegacyCheckpoint(
        accountId: Long,
        managed: ManagedAutomationAction,
        stored: StoredTypedAutomationAction,
        checkpoint: TypedRuntimeCheckpoint,
    ): CheckpointRecovery? {
        if (!active) return null
        if (checkpoint.phase == TypedRuntimeCheckpointPhase.PREPARED) {
            return CheckpointRecovery(TypedRuntimeOutcome.PreparedDiscarded(
                "Active convergence cutover discarded an unsubmitted legacy payload.", "TYPED_CONVERGENCE_CONTINUE"))
        }
        val result = cutoverLegacyReconciliation(accountId, managed, stored, checkpoint)
        return CheckpointRecovery(TypedRuntimeOutcome.AmbiguousHandoff(result.warning, "TYPED_CONVERGENCE_CONTINUE"), result.directive)
    }

    sealed interface DirectResult {
        data class Accepted(val execution: TypedAutomationExecution) : DirectResult
        data class Unapplied(val warning: String, val superseded: Boolean, val directive: ConvergenceDirective?) : DirectResult {
            val outcome: TypedRuntimeOutcome
                get() = if (superseded) TypedRuntimeOutcome.ActionSuperseded(warning, "TYPED_CONVERGENCE_CONTINUE")
                else TypedRuntimeOutcome.AmbiguousHandoff(warning, "TYPED_CONVERGENCE_CONTINUE")
        }
    }

    fun directEvidence(selection: SelectedAutomationAction?, execution: TypedAutomationExecution): AutomationActionEvidence? =
        selection?.let {
            evidenceInterpreter?.fromExecution(it, execution, now())
                ?: AutomationActionEvidence.IncompleteObservation(now(), "PRODUCTION_EVIDENCE_INTERPRETER_MISSING")
        }

    fun applyDirect(
        managed: ManagedAutomationAction,
        execution: TypedAutomationExecution,
        evidence: AutomationActionEvidence?,
        attemptId: Long?,
    ): DirectResult {
        if (attemptId != null && evidence !is AutomationActionEvidence.DirectApplied && execution !is TypedAutomationExecution.SharedCooldown) {
            val recorded = requireNotNull(evidence)
            val directive = convergenceModule?.record(attemptId, recorded)
            if (recorded is AutomationActionEvidence.DirectRejected || recorded is AutomationActionEvidence.StateAdvanced) {
                managed.applyPolicyResolvedExecution(execution, recorded)
            }
            val warning = when (recorded) {
                is AutomationActionEvidence.DirectRejected -> "직접 응답이 행동 미적용을 확인해 최신 상태로 다시 판단합니다."
                is AutomationActionEvidence.StateAdvanced -> "직접 응답에서 저장 행동보다 최신 상태가 확인되어 성공으로 귀속하지 않습니다."
                is AutomationActionEvidence.SameState -> "직접 응답만으로 행동 적용을 확인하지 못해 권위 상태를 다시 관측합니다."
                is AutomationActionEvidence.IncompleteObservation -> "직접 응답 관측이 불완전해 같은 행동을 다시 보내지 않고 결과를 재확인합니다."
                is AutomationActionEvidence.NetworkFailure -> "직접 응답 확인에 실패해 같은 행동을 다시 보내지 않고 결과를 재확인합니다."
                is AutomationActionEvidence.ResultUnobserved -> "직접 응답에서 행동 결과를 관측하지 못해 자동 재제출을 보류합니다."
                is AutomationActionEvidence.ResultUnobservedFreshDecision -> "이전 결과는 귀속하지 않고 최신 상태에서 새 행동을 판단합니다."
                is AutomationActionEvidence.BattleGateRequired -> "전투 캡차 해결 전에는 전투 행동을 성공으로 처리하지 않습니다."
                is AutomationActionEvidence.DirectApplied -> error("Handled above")
            }
            return DirectResult.Unapplied(
                warning,
                recorded is AutomationActionEvidence.DirectRejected || recorded is AutomationActionEvidence.StateAdvanced,
                directive,
            )
        }
        return DirectResult.Accepted(applyAccepted(managed, execution, attemptId))
    }

    /** 낚시는 직접 적용만 허용하고 단계 projection 직후 증거를 종결한다. */
    fun applyFishingDirect(
        managed: ManagedFishingAutomationAction,
        execution: TypedAutomationExecution,
        evidence: AutomationActionEvidence?,
        attemptId: Long?,
        unconfirmedWarning: String,
    ): DirectResult {
        if (attemptId != null && evidence !is AutomationActionEvidence.DirectApplied) {
            return DirectResult.Unapplied(unconfirmedWarning, false, record(attemptId, requireNotNull(evidence)))
        }
        val applied = applyAccepted(managed, execution, attemptId)
        attemptId?.let { record(it, requireNotNull(evidence)) }
        return DirectResult.Accepted(applied)
    }

    /** START가 이전 전투를 관측하면 최신 상태만 반영한 뒤 기존 행동을 대체한다. */
    fun resolveFishingStateAdvanced(
        managed: ManagedFishingAutomationAction,
        execution: TypedAutomationExecution,
        evidence: AutomationActionEvidence.StateAdvanced,
        attemptId: Long?,
    ) {
        managed.applyPolicyResolvedExecution(execution, evidence)
        attemptId?.let { record(it, evidence) }
    }

    private fun applyAccepted(managed: ManagedAutomationAction, execution: TypedAutomationExecution, attemptId: Long?) =
        if (attemptId != null) managed.applyPolicyAcceptedExecution(execution) else managed.applyLegacyExecution(execution)

    /** 호출자가 도메인 후처리를 마친 뒤에만 적용 증거를 종결한다. */
    fun finishDirect(
        accountId: Long,
        identity: String,
        evidence: AutomationActionEvidence?,
        attemptId: Long?,
        domain: TypedAutomationExecution,
    ) {
        evidence?.let {
            observeShadow(accountId, identity, it,
                if (domain is TypedAutomationExecution.SharedCooldown) LegacyConvergenceDecision.SUPERSEDED else LegacyConvergenceDecision.APPLIED)
        }
        attemptId?.let {
            convergenceModule?.record(it, requireNotNull(evidence) { "Active convergence execution is missing production evidence." })
        }
    }

    fun probe(accountId: Long, directive: ConvergenceDirective.Probe): ConvergenceDirective? {
        val convergence = convergenceModule ?: return null
        val observation = observeStoredConvergenceAction(accountId, directive)
        val next = convergence.record(directive.attemptId, observation.evidence)
        journalFishingObservation(accountId, observation, next)
        return next
    }

    private fun journalFishingObservation(accountId: Long, observation: StoredObservation, next: ConvergenceDirective) {
        val stored = observation.stored
        if (stored?.payload is StoredTypedActionPayload.FishingTown) {
            try {
                decisionJournal?.appendResultObservation(accountId,
                    AutomationDecisionDiagnostics.fishingProbe(stored, observation.diagnosticContext, observation.evidence, next))
            } catch (error: RuntimeException) {
                log.warn("Fishing observation history unavailable accountId={} executionIdentity={} errorType={}",
                    accountId, stored.executionIdentity, error.javaClass.name)
            }
        }
    }

    private data class CutoverResult(val warning: String, val directive: ConvergenceDirective?)
    private fun cutoverLegacyReconciliation(
        accountId: Long,
        managed: ManagedAutomationAction,
        stored: StoredTypedAutomationAction,
        activeCheckpoint: TypedRuntimeCheckpoint,
    ): CutoverResult {
        val convergence = convergenceModule
        val factory = convergenceSelectionFactory
        if (convergence == null || factory == null) {
            return CutoverResult("Active convergence cutover dependencies are missing.", null)
        }
        val selection = factory.create(stored, activeCheckpoint.legacySuppressionEpoch)
        val directive = when (val prepared = convergence.prepare(accountId, selection)) {
            is ConvergenceDirective.Submit -> {
                val observation = observeReconciliation(accountId, managed, selection, stored.executionIdentity, ReconciliationSource.LEGACY_CHECKPOINT)
                convergence.record(prepared.attemptId, observation).also { next ->
                    journalFishingObservation(accountId, StoredObservation(observation, stored, managed.diagnosticContext), next)
                }
            }
            else -> prepared
        }
        return CutoverResult("Legacy ambiguous payload was closed after one fresh authoritative observation.", directive)
    }

    fun observeShadow(
        accountId: Long,
        executionIdentity: String,
        evidence: AutomationActionEvidence,
        legacyDecision: LegacyConvergenceDecision,
    ) {
        if (shadow) {
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

    private data class StoredObservation(
        val evidence: AutomationActionEvidence,
        val stored: StoredTypedAutomationAction? = null,
        val diagnosticContext: String? = null,
    )

    private fun observeStoredConvergenceAction(
        accountId: Long,
        directive: ConvergenceDirective.Probe,
    ): StoredObservation {
        val stored = storedConvergenceActionLoader?.load(accountId, directive.executionIdentity)
            ?: return StoredObservation(AutomationActionEvidence.ResultUnobserved(now(), "STORED_ACTION_NOT_FOUND"))
        val managed = try {
            actionLifecycleModule.restoreVerified(stored, accountId)
        } catch (error: RuntimeException) {
            return StoredObservation(AutomationActionEvidence.ResultUnobserved(
                now(), error.message ?: "STORED_ACTION_INVALID",
            ), stored)
        }
        val selection = convergenceSelectionFactory?.create(stored)
        val evidence = observeReconciliation(accountId, managed, selection, directive.executionIdentity, ReconciliationSource.STORED_PROBE)
        return StoredObservation(evidence, stored, managed.diagnosticContext)
    }

    private enum class ReconciliationSource { STORED_PROBE, LEGACY_CHECKPOINT }

    private fun observeReconciliation(
        accountId: Long,
        managed: ManagedAutomationAction,
        selection: SelectedAutomationAction?,
        executionIdentity: String,
        source: ReconciliationSource,
    ): AutomationActionEvidence = try {
        val resolution = managed.reconcile()
        if (resolution is AmbiguousActionResolution.Applied) {
            applyRecoveredExecution(accountId, resolution.execution)
        }
        selection?.let { evidenceInterpreter?.fromReconciliation(it, resolution, now()) }
            ?: when (resolution) {
                is AmbiguousActionResolution.Applied -> AutomationActionEvidence.StateAdvanced(now(), "advanced:$executionIdentity")
                AmbiguousActionResolution.Resubmit -> AutomationActionEvidence.SameState(now(),
                    "${if (source == ReconciliationSource.STORED_PROBE) "unchanged" else "same"}:$executionIdentity")
                is AmbiguousActionResolution.VerifyLater -> AutomationActionEvidence.IncompleteObservation(now(), resolution.reason)
                is AmbiguousActionResolution.Held -> AutomationActionEvidence.ResultUnobserved(now(), resolution.reason)
                is AmbiguousActionResolution.HandedOff -> AutomationActionEvidence.ResultUnobserved(now(), resolution.reason)
                is AmbiguousActionResolution.Superseded -> AutomationActionEvidence.StateAdvanced(now(), "superseded:$executionIdentity")
                is AmbiguousActionResolution.FreshDecision -> AutomationActionEvidence.ResultUnobservedFreshDecision(now(), resolution.reason)
            }
    } catch (error: Throwable) {
        val deferred = error.findHofAutomationDeferral()
        // Preserve each existing entry's fallback classification and diagnostic source.
        when {
            source == ReconciliationSource.STORED_PROBE -> AutomationActionEvidence.NetworkFailure(now(),
                if (deferred != null) deferred.message ?: "HOF_DEFERRED" else error.message ?: error.javaClass.simpleName)
            deferred != null -> AutomationActionEvidence.NetworkFailure(now(), error.message ?: "HOF_DEFERRED")
            else -> AutomationActionEvidence.ResultUnobserved(now(), error.message ?: error.javaClass.simpleName)
        }
    }

    fun applyRecoveredExecution(accountId: Long, execution: TypedAutomationExecution) {
        when (execution) {
            TypedAutomationExecution.Completed -> Unit
            is TypedAutomationExecution.ActionCompleted ->
                execution.runtimeDomainExecution().let { recovered ->
                    if (recovered !== execution) applyRecoveredExecution(accountId, recovered)
                }
            is TypedAutomationExecution.RaidCycleFinished -> Unit
            is TypedAutomationExecution.RaidWaiting -> Unit
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

    private fun now(): Instant = timeProvider?.now() ?: Instant.now()
    private fun Throwable.findHofAutomationDeferral(): HofAutomationDeferredException? =
        generateSequence(this) { it.cause }.filterIsInstance<HofAutomationDeferredException>().firstOrNull()
}

internal fun TypedAutomationExecution.runtimeDomainExecution(): TypedAutomationExecution = when (this) {
    is TypedAutomationExecution.ActionCompleted -> raidWait
        ?: raidOutcome?.let(TypedAutomationExecution::RaidCycleFinished)
        ?: TypedAutomationExecution.Completed
    is TypedAutomationExecution.BattleCompleted -> raidOutcome?.let(TypedAutomationExecution::RaidCycleFinished)
        ?: this
    else -> this
}
