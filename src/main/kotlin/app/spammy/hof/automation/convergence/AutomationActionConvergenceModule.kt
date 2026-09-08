package app.spammy.hof.automation.convergence

import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.automation.service.QuestAutomationSnapshot
import java.time.Duration
import java.time.Instant
import org.springframework.stereotype.Service

interface AutomationActionConvergenceModule {
    fun openSelection(
        accountId: Long,
        entryId: Long,
        quest: QuestAutomationSnapshot? = null,
        mode: AutomationConvergenceMode? = null,
    ): AutomationConvergenceSelection
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
    fun discardUnsubmitted(
        accountId: Long,
        selection: SelectedAutomationAction,
        discardedAt: Instant,
        reasonCode: String,
    ): Boolean
    fun retryUnsubmitted(
        accountId: Long,
        selection: SelectedAutomationAction,
        retriedAt: Instant,
    ): ConvergenceDirective
    fun holdUnresolved(
        accountId: Long,
        selection: SelectedAutomationAction,
        evidence: AutomationActionEvidence.ResultUnobserved,
        successfulObservationCount: Int,
        firstPendingAt: Instant,
    ): ConvergenceDirective
    fun requireBattleGate(
        accountId: Long,
        challengeId: Long?,
        reason: String,
        capturedAt: Instant,
    ): ConvergenceDirective.BattleGateWait
    fun resumeDue(accountId: Long): ConvergenceDirective
    fun releaseBattleGate(accountId: Long, resolvedAt: Instant): Boolean
    fun observeAuthoritativeBaseline(
        accountId: Long,
        scope: AutomationIsolationScope,
        baselineFingerprint: String,
        observedAt: Instant,
    ): Int = observeAuthoritativeBaselines(accountId, scope, setOf(baselineFingerprint), observedAt)
    fun observeAuthoritativeBaselines(
        accountId: Long,
        scope: AutomationIsolationScope,
        baselineFingerprints: Set<String>,
        observedAt: Instant,
    ): Int
    fun allowFreshDecision(accountId: Long, attemptId: Long, allowedAt: Instant): Boolean
    fun allowRaidRegistrationFreshDecision(
        accountId: Long,
        entryId: Long,
        raidId: String,
        observedAt: Instant,
    ): Int
}

@Service
class DefaultAutomationActionConvergenceModule(
    private val store: ConvergenceStore,
    private val timeProvider: TimeProvider,
    private val evidenceCaseRecorder: EvidenceCaseRecorder = NoOpEvidenceCaseRecorder,
) : AutomationActionConvergenceModule {
    override fun openSelection(
        accountId: Long,
        entryId: Long,
        quest: QuestAutomationSnapshot?,
        mode: AutomationConvergenceMode?,
    ) = AutomationConvergenceSelection(accountId, entryId, quest, mode, this, store, timeProvider)

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
            is AutomationActionEvidence.ResultUnobservedFreshDecision -> {
                terminal(
                    record,
                    ActionConvergenceResult.RESULT_UNOBSERVED,
                    "RESULT_UNOBSERVED_FRESH_DECISION",
                    evidence.capturedAt,
                )
                check(store.releaseSuppression(record.accountId, record.attemptId, evidence.capturedAt)) {
                    "Fresh-decision convergence suppression must be releasable."
                }
                ConvergenceDirective.ContinueSelection
            }
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

    override fun discardUnsubmitted(
        accountId: Long,
        selection: SelectedAutomationAction,
        discardedAt: Instant,
        reasonCode: String,
    ): Boolean {
        val active = store.findActive(accountId, selection.scope)
            ?.takeIf { record ->
                record.selection.executionIdentity == selection.executionIdentity &&
                    record.submittedAt == null
            }
            ?: return false
        terminal(active, ActionConvergenceResult.NOT_APPLIED, reasonCode, discardedAt)
        return true
    }

    override fun retryUnsubmitted(
        accountId: Long,
        selection: SelectedAutomationAction,
        retriedAt: Instant,
    ): ConvergenceDirective {
        store.activeBattleGate(accountId)?.takeIf { selection.actionKind.battle }?.let { gate ->
            return ConvergenceDirective.BattleGateWait(gate.openedAt, gate.reason)
        }
        if (
            selection.baselineFingerprint in
            store.findSuppressedBaselines(accountId)[selection.scope].orEmpty()
        ) return ConvergenceDirective.ContinueSelection
        store.findActive(accountId, selection.scope)?.let { active ->
            return ConvergenceDirective.WaitUntil(
                active.nextProbeAt ?: retriedAt.plus(PROBE_INTERVAL),
                selection.scope,
            )
        }
        val existing = store.createOrGet(accountId, selection, retriedAt)
        if (
            existing.selection.executionIdentity != selection.executionIdentity ||
            existing.result != ActionConvergenceResult.NOT_APPLIED ||
            existing.submittedAt != null
        ) return ConvergenceDirective.ContinueSelection
        existing.result = ActionConvergenceResult.PENDING
        existing.successfulObservationCount = 0
        existing.firstPendingAt = null
        existing.nextProbeAt = null
        existing.reasonCode = "UNSUBMITTED_RETRY_PREPARED"
        existing.finishedAt = null
        existing.updatedAt = retriedAt
        store.save(existing)
        return ConvergenceDirective.Submit(existing.attemptId)
    }

    override fun holdUnresolved(
        accountId: Long,
        selection: SelectedAutomationAction,
        evidence: AutomationActionEvidence.ResultUnobserved,
        successfulObservationCount: Int,
        firstPendingAt: Instant,
    ): ConvergenceDirective {
        if (
            selection.baselineFingerprint in
            store.findSuppressedBaselines(accountId)[selection.scope].orEmpty()
        ) return ConvergenceDirective.ContinueSelection
        val record = store.createOrGet(accountId, selection, evidence.capturedAt)
        if (!record.active) return ConvergenceDirective.ContinueSelection
        evidenceCaseRecorder.record(record, evidence, "PENDING_BUDGET_EXHAUSTED")
        record.successfulObservationCount = successfulObservationCount
        record.firstPendingAt = firstPendingAt
        return terminal(
            record,
            if (selection.actionKind.battle) {
                ActionConvergenceResult.RESULT_UNOBSERVED
            } else {
                ActionConvergenceResult.HELD
            },
            "PENDING_BUDGET_EXHAUSTED",
            evidence.capturedAt,
        )
    }

    override fun requireBattleGate(
        accountId: Long,
        challengeId: Long?,
        reason: String,
        capturedAt: Instant,
    ): ConvergenceDirective.BattleGateWait {
        val gate = store.openBattleGate(accountId, challengeId, reason, capturedAt)
        return ConvergenceDirective.BattleGateWait(gate.openedAt, gate.reason)
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

    override fun observeAuthoritativeBaselines(
        accountId: Long,
        scope: AutomationIsolationScope,
        baselineFingerprints: Set<String>,
        observedAt: Instant,
    ): Int {
        require(baselineFingerprints.isNotEmpty()) { "An authoritative observation must contain a baseline." }
        return store.releaseSupersededSuppressions(accountId, scope, baselineFingerprints, observedAt)
    }

    override fun allowFreshDecision(accountId: Long, attemptId: Long, allowedAt: Instant): Boolean =
        store.releaseSuppression(accountId, attemptId, allowedAt)

    override fun allowRaidRegistrationFreshDecision(
        accountId: Long,
        entryId: Long,
        raidId: String,
        observedAt: Instant,
    ): Int =
        store.releaseRaidRegistrationSuppressions(accountId, entryId, raidId, observedAt)

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
        AutomationConvergenceBudget.exhausted(
            record.successfulObservationCount,
            record.firstPendingAt,
            now,
        )

    private fun AutomationActionEvidence.reasonCode(): String = when (this) {
        is AutomationActionEvidence.DirectApplied -> "DIRECT_RESPONSE_APPLIED"
        is AutomationActionEvidence.DirectRejected -> "DIRECT_RESPONSE_REJECTED"
        is AutomationActionEvidence.StateAdvanced -> "AUTHORITATIVE_STATE_ADVANCED"
        is AutomationActionEvidence.SameState -> "AUTHORITATIVE_STATE_UNCHANGED"
        is AutomationActionEvidence.IncompleteObservation -> "OBSERVATION_INCOMPLETE"
        is AutomationActionEvidence.NetworkFailure -> "OBSERVATION_NETWORK_FAILURE"
        is AutomationActionEvidence.ResultUnobserved -> "RESULT_UNOBSERVED"
        is AutomationActionEvidence.ResultUnobservedFreshDecision -> "RESULT_UNOBSERVED_FRESH_DECISION"
        is AutomationActionEvidence.BattleGateRequired -> "BATTLE_GATE_REQUIRED"
    }

    private companion object {
        val PROBE_INTERVAL: Duration = Duration.ofSeconds(10)
    }
}
