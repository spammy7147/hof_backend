package app.spammy.hof.automation.convergence

import app.spammy.hof.common.time.TimeProvider
import java.time.Duration
import java.time.Instant
import org.springframework.stereotype.Service

interface AutomationActionConvergenceModule {
    fun prepare(accountId: Long, selection: SelectedAutomationAction): ConvergenceDirective
    fun record(attemptId: Long, evidence: AutomationActionEvidence): ConvergenceDirective
    fun observeGap(
        accountId: Long,
        selection: SelectedAutomationAction,
        evidence: AutomationActionEvidence.IncompleteObservation,
    ): ConvergenceDirective
    fun resolveObservationGap(
        accountId: Long,
        scope: AutomationIsolationScope,
        resolvedAt: Instant,
    ): Boolean
    fun resumeDue(accountId: Long): ConvergenceDirective
    fun releaseBattleGate(accountId: Long, resolvedAt: Instant): Boolean
    fun allowFreshDecision(accountId: Long, attemptId: Long, allowedAt: Instant): Boolean
}

@Service
class DefaultAutomationActionConvergenceModule(
    private val store: ConvergenceStore,
    private val timeProvider: TimeProvider,
    private val evidenceCaseRecorder: EvidenceCaseRecorder = NoOpEvidenceCaseRecorder,
) : AutomationActionConvergenceModule {
    override fun prepare(accountId: Long, selection: SelectedAutomationAction): ConvergenceDirective {
        store.activeBattleGate(accountId)?.takeIf { selection.actionKind.battle }?.let { gate ->
            return ConvergenceDirective.BattleGateWait(gate.openedAt, gate.reason)
        }
        if (
            selection.baselineFingerprint in
            store.findSuppressedBaselines(accountId)[selection.scope].orEmpty()
        ) return ConvergenceDirective.ContinueSelection
        store.findActive(accountId, selection.scope)?.let { active ->
            return ConvergenceDirective.WaitUntil(
                active.nextProbeAt ?: timeProvider.now().plus(PROBE_INTERVAL),
                selection.scope,
            )
        }
        val attempt = store.createOrGet(accountId, selection, timeProvider.now())
        if (!attempt.active) return ConvergenceDirective.ContinueSelection
        return ConvergenceDirective.Submit(attempt.attemptId)
    }

    override fun record(attemptId: Long, evidence: AutomationActionEvidence): ConvergenceDirective {
        val record = requireNotNull(store.get(attemptId)) { "Convergence attempt $attemptId does not exist." }
        check(record.active) { "Convergence attempt $attemptId is already terminal." }
        if (record.submittedAt == null && !record.selection.observationOnly) {
            record.submittedAt = evidence.capturedAt
        }
        val evidenceReasonCode = if (
            record.selection.observationOnly && evidence is AutomationActionEvidence.IncompleteObservation
        ) {
            evidence.reason
        } else {
            evidence.reasonCode()
        }
        evidenceCaseRecorder.record(record, evidence, evidenceReasonCode)
        return when (evidence) {
            is AutomationActionEvidence.DirectApplied -> terminal(
                record,
                ActionConvergenceResult.APPLIED,
                "DIRECT_RESPONSE_APPLIED",
                evidence.capturedAt,
            )
            is AutomationActionEvidence.DirectRejected -> terminal(
                record,
                ActionConvergenceResult.NOT_APPLIED,
                "DIRECT_RESPONSE_REJECTED",
                evidence.capturedAt,
            )
            is AutomationActionEvidence.StateAdvanced -> terminal(
                record,
                ActionConvergenceResult.SUPERSEDED,
                "AUTHORITATIVE_STATE_ADVANCED",
                evidence.capturedAt,
            )
            is AutomationActionEvidence.ResultUnobserved -> terminal(
                record,
                ActionConvergenceResult.RESULT_UNOBSERVED,
                "RESULT_UNOBSERVED",
                evidence.capturedAt,
            )
            is AutomationActionEvidence.BattleGateRequired -> {
                terminal(
                    record,
                    ActionConvergenceResult.NOT_APPLIED,
                    "BATTLE_GATE_REQUIRED",
                    evidence.capturedAt,
                )
                val gate = store.openBattleGate(
                    record.accountId,
                    evidence.challengeId,
                    evidence.reason,
                    evidence.capturedAt,
                )
                ConvergenceDirective.BattleGateWait(gate.openedAt, gate.reason)
            }
            is AutomationActionEvidence.SameState -> pending(
                record,
                evidence.capturedAt,
                successfulObservation = true,
                reasonCode = "AUTHORITATIVE_STATE_UNCHANGED",
            )
            is AutomationActionEvidence.IncompleteObservation -> pending(
                record,
                evidence.capturedAt,
                successfulObservation = evidence.authoritative,
                reasonCode = evidenceReasonCode,
            )
            is AutomationActionEvidence.NetworkFailure -> pending(
                record,
                evidence.capturedAt,
                successfulObservation = false,
                reasonCode = "OBSERVATION_NETWORK_FAILURE",
            )
        }
    }

    override fun observeGap(
        accountId: Long,
        selection: SelectedAutomationAction,
        evidence: AutomationActionEvidence.IncompleteObservation,
    ): ConvergenceDirective {
        require(selection.observationOnly) { "Observation gap selection must not represent a submission." }
        if (
            selection.baselineFingerprint in
            store.findSuppressedBaselines(accountId)[selection.scope].orEmpty()
        ) return ConvergenceDirective.ContinueSelection
        val now = timeProvider.now()
        val active = store.findActive(accountId, selection.scope)
        if (active != null) {
            if (active.selection.executionIdentity != selection.executionIdentity) {
                if (!active.selection.observationOnly) {
                    return ConvergenceDirective.WaitUntil(active.nextProbeAt ?: now.plus(PROBE_INTERVAL), selection.scope)
                }
                record(
                    active.attemptId,
                    AutomationActionEvidence.StateAdvanced(
                        capturedAt = now,
                        stateFingerprint = selection.baselineFingerprint,
                    ),
                )
            } else {
                if (active.nextProbeAt?.isAfter(now) == true) {
                    return ConvergenceDirective.WaitUntil(requireNotNull(active.nextProbeAt), selection.scope)
                }
                return record(active.attemptId, evidence)
            }
        }
        val created = store.createOrGet(accountId, selection, now)
        if (!created.active) return ConvergenceDirective.ContinueSelection
        return record(created.attemptId, evidence)
    }

    override fun resolveObservationGap(
        accountId: Long,
        scope: AutomationIsolationScope,
        resolvedAt: Instant,
    ): Boolean {
        val active = store.findActive(accountId, scope)
            ?.takeIf { it.selection.observationOnly }
            ?: return false
        record(
            active.attemptId,
            AutomationActionEvidence.StateAdvanced(
                capturedAt = resolvedAt,
                stateFingerprint = "OBSERVATION_GAP_RESOLVED",
            ),
        )
        return true
    }

    override fun resumeDue(accountId: Long): ConvergenceDirective {
        val now = timeProvider.now()
        store.normalizeOrphans(accountId, now)
        val due = store.findDue(accountId, now) ?: return ConvergenceDirective.ContinueSelection
        if (budgetExhausted(due, now)) {
            return terminal(due, ActionConvergenceResult.HELD, "PENDING_BUDGET_EXHAUSTED", now)
        }
        return ConvergenceDirective.Probe(
            due.attemptId,
            due.selection.executionIdentity,
            due.selection.entryId,
        )
    }

    override fun releaseBattleGate(accountId: Long, resolvedAt: Instant): Boolean =
        store.releaseBattleGate(accountId, resolvedAt)

    override fun allowFreshDecision(accountId: Long, attemptId: Long, allowedAt: Instant): Boolean =
        store.releaseSuppression(accountId, attemptId, allowedAt)

    private fun pending(
        record: ActionConvergenceRecord,
        observedAt: Instant,
        successfulObservation: Boolean,
        reasonCode: String,
    ): ConvergenceDirective {
        if (record.firstPendingAt == null) record.firstPendingAt = observedAt
        if (successfulObservation) record.successfulObservationCount += 1
        if (budgetExhausted(record, observedAt)) {
            return terminal(record, ActionConvergenceResult.HELD, "PENDING_BUDGET_EXHAUSTED", observedAt)
        }
        record.result = ActionConvergenceResult.PENDING
        record.nextProbeAt = observedAt.plus(PROBE_INTERVAL)
        record.reasonCode = reasonCode
        record.updatedAt = observedAt
        store.save(record)
        return ConvergenceDirective.WaitUntil(requireNotNull(record.nextProbeAt), record.selection.scope)
    }

    private fun terminal(
        record: ActionConvergenceRecord,
        result: ActionConvergenceResult,
        reasonCode: String,
        at: Instant,
    ): ConvergenceDirective.ContinueSelection {
        record.result = result
        record.nextProbeAt = null
        record.reasonCode = reasonCode
        record.finishedAt = at
        record.updatedAt = at
        store.save(record)
        return ConvergenceDirective.ContinueSelection
    }

    private fun budgetExhausted(record: ActionConvergenceRecord, now: Instant): Boolean =
        record.successfulObservationCount >= MAX_SUCCESSFUL_OBSERVATIONS ||
            record.firstPendingAt?.plus(MAX_PENDING_DURATION)?.isAfter(now) == false

    private fun AutomationActionEvidence.reasonCode(): String = when (this) {
        is AutomationActionEvidence.DirectApplied -> "DIRECT_RESPONSE_APPLIED"
        is AutomationActionEvidence.DirectRejected -> "DIRECT_RESPONSE_REJECTED"
        is AutomationActionEvidence.StateAdvanced -> "AUTHORITATIVE_STATE_ADVANCED"
        is AutomationActionEvidence.SameState -> "AUTHORITATIVE_STATE_UNCHANGED"
        is AutomationActionEvidence.IncompleteObservation -> "OBSERVATION_INCOMPLETE"
        is AutomationActionEvidence.NetworkFailure -> "OBSERVATION_NETWORK_FAILURE"
        is AutomationActionEvidence.ResultUnobserved -> "RESULT_UNOBSERVED"
        is AutomationActionEvidence.BattleGateRequired -> "BATTLE_GATE_REQUIRED"
    }

    private companion object {
        const val MAX_SUCCESSFUL_OBSERVATIONS = 5
        val MAX_PENDING_DURATION: Duration = Duration.ofMinutes(2)
        val PROBE_INTERVAL: Duration = Duration.ofSeconds(10)
    }
}
