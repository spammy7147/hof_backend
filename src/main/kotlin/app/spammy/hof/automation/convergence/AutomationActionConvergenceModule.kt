package app.spammy.hof.automation.convergence

import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.automation.service.QuestAutomationSnapshot
import java.time.Duration
import java.time.Instant
import org.springframework.stereotype.Service
import org.slf4j.LoggerFactory
import org.springframework.transaction.support.TransactionSynchronizationManager

interface AutomationActionConvergenceModule {
    fun openSelection(
        accountId: Long,
        entryId: Long,
        quest: QuestAutomationSnapshot? = null,
        mode: AutomationConvergenceMode? = null,
    ): AutomationConvergenceSelection
    fun prepare(accountId: Long, selection: SelectedAutomationAction): ConvergenceDirective
    fun storedSelection(accountId: Long, executionIdentity: String): SelectedAutomationAction?
    fun restoreCheckpoint(accountId: Long, selection: SelectedAutomationAction, checkpoint: RestoredActionCheckpoint): ConvergenceDirective
    fun restoreDirectResponse(accountId: Long, selection: SelectedAutomationAction, checkpoint: RestoredActionCheckpoint): Long
    fun record(attemptId: Long, evidence: AutomationActionEvidence): ConvergenceDirective
    /** 실제 제출의 원래 응답에만 사용한다. 후속 GET 관측은 record로 남긴다. */
    fun recordDirectResponse(attemptId: Long, evidence: AutomationActionEvidence): ConvergenceDirective
    fun record(attemptId: Long, evidence: AutomationActionEvidence, persistObservation: (ConvergenceDirective) -> Unit): ConvergenceDirective
    fun recordLateApplication(accountId: Long, executionIdentity: String, evidence: AutomationActionEvidence.DirectApplied)
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
    fun resumeDue(accountId: Long, excludedExecutionIdentities: Set<String> = emptySet()): ConvergenceDirective
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
    fun allowLocalResultFreshDecision(accountId: Long, executionIdentity: String, allowedAt: Instant)
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
        if (selection.actionKind == AutomationActionKind.RAID_REFRESH) {
            store.releaseRaidSuppressions(accountId, selection.entryId, selection.scope.key, timeProvider.now(), selection.actionKind)
        }
        if (isSuppressed(accountId, selection)) return ConvergenceDirective.ContinueSelection
        store.findActive(accountId, selection.scope)?.let { active ->
            return ConvergenceDirective.WaitUntil(
                active.nextProbeAt ?: timeProvider.now().plus(PROBE_INTERVAL),
                selection.scope,
            )
        }
        val attempt = store.createOrGet(accountId, selection, timeProvider.now())
        if (!attempt.active) return ConvergenceDirective.ContinueSelection
        if (!ProductionActionEvidenceInterpreter.supportsVersion(attempt.selection.policyVersion)) {
            return requireNotNull(store.withLockedAttempt(attempt.attemptId) { current ->
                if (!current.active) ConvergenceDirective.ContinueSelection
                else holdUnsupportedPolicy(current, timeProvider.now())
            })
        }
        return ConvergenceDirective.Submit(attempt.attemptId, attempt.selection)
    }

    override fun recordLateApplication(accountId: Long, executionIdentity: String, evidence: AutomationActionEvidence.DirectApplied) {
        val existing = store.get(accountId, executionIdentity) ?: return
        if (existing.active || existing.result in LATE_APPLICATION_RESULTS) {
            record(existing.attemptId, evidence)
        }
    }

    override fun storedSelection(accountId: Long, executionIdentity: String): SelectedAutomationAction? =
        store.get(accountId, executionIdentity)?.selection

    /** 수신한 직접 응답은 새 관측 예산을 소비하지 않고 원래 시도에 귀속한다. */
    override fun restoreDirectResponse(
        accountId: Long,
        selection: SelectedAutomationAction,
        checkpoint: RestoredActionCheckpoint,
    ): Long {
        requireNotNull(checkpoint.submittedAt) { "A direct response requires an original submission." }
        val attempt = store.get(accountId, selection.executionIdentity)
            ?: store.createOrGet(accountId, selection, checkpoint.submittedAt)
        return requireNotNull(store.withLockedAttempt(attempt.attemptId) { current ->
            require(current.selection == selection) { "Direct response selection differs from its original attempt." }
            current.submittedAt = current.submittedAt ?: checkpoint.submittedAt
            current.successfulObservationCount = maxOf(current.successfulObservationCount, checkpoint.successfulObservationCount)
            current.firstPendingAt = current.firstPendingAt ?: checkpoint.firstPendingAt ?: current.submittedAt
            store.save(current)
            current.attemptId
        })
    }

    override fun restoreCheckpoint(
        accountId: Long,
        selection: SelectedAutomationAction,
        checkpoint: RestoredActionCheckpoint,
    ): ConvergenceDirective {
        val now = timeProvider.now()
        val existing = store.get(accountId, selection.executionIdentity)
        val attempt = existing ?: store.createOrGet(accountId, selection, now)
        return requireNotNull(store.withLockedAttempt(attempt.attemptId) { current ->
            if (!current.active) {
                if (current.result == ActionConvergenceResult.NOT_APPLIED && current.submittedAt == null &&
                    !ProductionActionEvidenceInterpreter.supportsVersion(current.selection.policyVersion)
                ) return@withLockedAttempt holdUnsupportedPolicy(current, now)
                return@withLockedAttempt ConvergenceDirective.ContinueSelection
            }
            // prepare와 실제 제출 기록은 별도 전이다. 재시작 때 typed에만 남은 사실도 보존한다.
            current.submittedAt = current.submittedAt ?: checkpoint.submittedAt
            current.successfulObservationCount = maxOf(current.successfulObservationCount, checkpoint.successfulObservationCount)
            current.firstPendingAt = current.firstPendingAt ?: checkpoint.firstPendingAt ?: current.submittedAt
            current.nextProbeAt = current.nextProbeAt ?: now
            store.save(current)
            when {
                !ProductionActionEvidenceInterpreter.supportsVersion(current.selection.policyVersion) -> holdUnsupportedPolicy(current, now)
                budgetExhausted(current, now) -> terminal(current, ActionConvergenceResult.HELD, "PENDING_BUDGET_EXHAUSTED", now)
                current.nextProbeAt?.isAfter(now) == true -> ConvergenceDirective.WaitUntil(requireNotNull(current.nextProbeAt), current.selection.scope)
                else -> ConvergenceDirective.Probe(current.attemptId, current.selection)
            }
        })
    }

    override fun record(attemptId: Long, evidence: AutomationActionEvidence): ConvergenceDirective =
        requireNotNull(store.withLockedAttempt(attemptId) { record -> recordEvidence(record, evidence) }) {
            "Convergence attempt $attemptId does not exist."
        }

    override fun recordDirectResponse(attemptId: Long, evidence: AutomationActionEvidence): ConvergenceDirective =
        requireNotNull(store.withLockedAttempt(attemptId) { record -> recordEvidence(record, evidence, originalResponse = true) }) {
            "Convergence attempt $attemptId does not exist."
        }

    override fun record(
        attemptId: Long,
        evidence: AutomationActionEvidence,
        persistObservation: (ConvergenceDirective) -> Unit,
    ): ConvergenceDirective {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "Observation persistence must start outside a transaction."
        }
        val (next, persisted) = requireNotNull(store.withLockedAttempt(attemptId) { record ->
            if (record.result == ActionConvergenceResult.APPLIED) {
                ConvergenceDirective.ContinueSelection to null
            } else {
                val supported = ProductionActionEvidenceInterpreter.supportsVersion(record.selection.policyVersion) &&
                    evidence !is AutomationActionEvidence.PolicyUnavailable
                val accepted = record.active || !evidence.isUnconfirmedObservation()
                recordEvidence(record, evidence) to record.copy().takeIf { supported && accepted }
            }
        }) {
            "Convergence attempt $attemptId does not exist."
        }
        // 판정을 먼저 commit한다. 이력 transaction 실패가 수렴 결과를 취소하지 않는다.
        if (persisted != null) try {
            store.withLockedAttempt(attemptId) { current ->
                if (current.result == persisted.result && current.reasonCode == persisted.reasonCode &&
                    current.successfulObservationCount == persisted.successfulObservationCount
                ) persistObservation(next)
            }
        } catch (error: RuntimeException) {
            LoggerFactory.getLogger(javaClass).warn(
                "Convergence observation history unavailable after result commit attemptId={} errorType={}",
                attemptId, error.javaClass.name,
            )
        }
        return next
    }

    private fun recordEvidence(
        record: ActionConvergenceRecord,
        evidence: AutomationActionEvidence,
        originalResponse: Boolean = false,
    ): ConvergenceDirective {
        if (record.result == ActionConvergenceResult.APPLIED) return ConvergenceDirective.ContinueSelection
        if (!ProductionActionEvidenceInterpreter.supportsVersion(record.selection.policyVersion) ||
            evidence is AutomationActionEvidence.PolicyUnavailable
        ) {
            if (record.active) return holdUnsupportedPolicy(record, evidence.capturedAt, evidence)
            recordUnsupportedPolicy(record, evidence.capturedAt, evidence)
            return ConvergenceDirective.ContinueSelection
        }
        if (originalResponse && (
                (record.result == ActionConvergenceResult.NOT_APPLIED && evidence is AutomationActionEvidence.DirectRejected) ||
                    (record.result == ActionConvergenceResult.SUPERSEDED && evidence is AutomationActionEvidence.StateAdvanced)
                )) return ConvergenceDirective.ContinueSelection
        // 예산 소진 뒤 도착한 원래 응답의 확정 결과는 일반 후속 관측과 구분한다.
        val lateOriginalResult = record.result in LATE_APPLICATION_RESULTS && (
            evidence is AutomationActionEvidence.DirectApplied ||
                (originalResponse && (evidence is AutomationActionEvidence.DirectRejected || evidence is AutomationActionEvidence.StateAdvanced))
            )
        if (!record.active && evidence.isUnconfirmedObservation()) {
            return ConvergenceDirective.ContinueSelection
        }
        check(record.active || lateOriginalResult) { "Convergence attempt ${record.attemptId} is already terminal." }
        val unsubmittedGate = evidence is AutomationActionEvidence.BattleGateRequired && !evidence.submissionAttempted
        if (record.submittedAt == null && !record.selection.observationOnly && !unsubmittedGate) {
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
            is AutomationActionEvidence.PolicyUnavailable -> error("Unsupported policy handled before evidence classification")
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
        if (isSuppressed(accountId, selection)) return ConvergenceDirective.ContinueSelection
        val now = timeProvider.now()
        val active = store.findActive(accountId, selection.scope)
        if (active != null) {
            if (!ProductionActionEvidenceInterpreter.supportsVersion(active.selection.policyVersion)) {
                return record(active.attemptId, AutomationActionEvidence.PolicyUnavailable(now))
            }
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
        if (!ProductionActionEvidenceInterpreter.supportsVersion(active.selection.policyVersion)) {
            record(active.attemptId, AutomationActionEvidence.PolicyUnavailable(resolvedAt))
            return false
        }
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
            ?: return false
        return store.withLockedAttempt(active.attemptId) { current ->
            if (!current.active || current.selection.executionIdentity != selection.executionIdentity || current.submittedAt != null) {
                return@withLockedAttempt false
            }
            if (!ProductionActionEvidenceInterpreter.supportsVersion(current.selection.policyVersion)) {
                holdUnsupportedPolicy(current, discardedAt)
            } else terminal(current, ActionConvergenceResult.NOT_APPLIED, reasonCode, discardedAt)
            true
        } ?: false
    }

    override fun retryUnsubmitted(
        accountId: Long,
        selection: SelectedAutomationAction,
        retriedAt: Instant,
    ): ConvergenceDirective {
        if (store.get(accountId, selection.executionIdentity) == null) return prepare(accountId, selection)
        store.activeBattleGate(accountId)?.takeIf { selection.actionKind.battle }?.let { gate ->
            return ConvergenceDirective.BattleGateWait(gate.openedAt, gate.reason)
        }
        if (isSuppressed(accountId, selection)) return ConvergenceDirective.ContinueSelection
        store.findActive(accountId, selection.scope)?.let { active ->
            return ConvergenceDirective.WaitUntil(
                active.nextProbeAt ?: retriedAt.plus(PROBE_INTERVAL),
                selection.scope,
            )
        }
        val attempt = store.createOrGet(accountId, selection, retriedAt)
        return store.withLockedAttempt(attempt.attemptId) { existing ->
            if (
                existing.selection.executionIdentity != selection.executionIdentity ||
                existing.result != ActionConvergenceResult.NOT_APPLIED ||
                existing.submittedAt != null
            ) return@withLockedAttempt ConvergenceDirective.ContinueSelection
            if (!ProductionActionEvidenceInterpreter.supportsVersion(existing.selection.policyVersion)) {
                return@withLockedAttempt holdUnsupportedPolicy(existing, retriedAt)
            }
            existing.result = ActionConvergenceResult.PENDING
            existing.successfulObservationCount = 0
            existing.firstPendingAt = null
            existing.nextProbeAt = null
            existing.reasonCode = "UNSUBMITTED_RETRY_PREPARED"
            existing.finishedAt = null
            existing.updatedAt = retriedAt
            store.save(existing)
            ConvergenceDirective.Submit(existing.attemptId, existing.selection)
        } ?: ConvergenceDirective.ContinueSelection
    }

    override fun holdUnresolved(
        accountId: Long,
        selection: SelectedAutomationAction,
        evidence: AutomationActionEvidence.ResultUnobserved,
        successfulObservationCount: Int,
        firstPendingAt: Instant,
    ): ConvergenceDirective {
        if (isSuppressed(accountId, selection)) return ConvergenceDirective.ContinueSelection
        val attempt = store.createOrGet(accountId, selection, evidence.capturedAt)
        return store.withLockedAttempt(attempt.attemptId) { record ->
            if (!record.active) return@withLockedAttempt ConvergenceDirective.ContinueSelection
            if (!ProductionActionEvidenceInterpreter.supportsVersion(record.selection.policyVersion)) {
                return@withLockedAttempt holdUnsupportedPolicy(record, evidence.capturedAt, evidence)
            }
            evidenceCaseRecorder.record(record, evidence, "PENDING_BUDGET_EXHAUSTED")
            record.successfulObservationCount = successfulObservationCount
            record.firstPendingAt = firstPendingAt
            terminal(
                record,
                if (selection.actionKind.battle) ActionConvergenceResult.RESULT_UNOBSERVED else ActionConvergenceResult.HELD,
                "PENDING_BUDGET_EXHAUSTED",
                evidence.capturedAt,
            )
        } ?: ConvergenceDirective.ContinueSelection
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

    override fun resumeDue(accountId: Long, excludedExecutionIdentities: Set<String>): ConvergenceDirective {
        val now = timeProvider.now()
        store.normalizeOrphans(accountId, now)
        val due = store.findDue(accountId, now, excludedExecutionIdentities) ?: return ConvergenceDirective.ContinueSelection
        return store.withLockedAttempt(due.attemptId) { current ->
            if (!current.active || current.result != ActionConvergenceResult.PENDING || current.nextProbeAt?.isAfter(now) == true) {
                return@withLockedAttempt ConvergenceDirective.ContinueSelection
            }
            if (!ProductionActionEvidenceInterpreter.supportsVersion(current.selection.policyVersion)) {
                return@withLockedAttempt holdUnsupportedPolicy(current, now)
            }
            if (budgetExhausted(current, now)) {
                return@withLockedAttempt terminal(current, ActionConvergenceResult.HELD, "PENDING_BUDGET_EXHAUSTED", now)
            }
            ConvergenceDirective.Probe(current.attemptId, current.selection)
        } ?: ConvergenceDirective.ContinueSelection
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

    override fun allowLocalResultFreshDecision(accountId: Long, executionIdentity: String, allowedAt: Instant) {
        val original = store.get(accountId, executionIdentity) ?: return
        store.withLockedAttempt(original.attemptId) { record ->
            // 사용자 허용은 과거 적용 사실을 만들거나 취소하지 않는다.
            if (record.active) {
                terminal(record, ActionConvergenceResult.RESULT_UNOBSERVED, "LOCAL_RESULT_FRESH_DECISION_ALLOWED", allowedAt)
            }
            store.releaseSuppression(accountId, record.attemptId, allowedAt)
        }
    }

    override fun allowRaidRegistrationFreshDecision(
        accountId: Long,
        entryId: Long,
        raidId: String,
        observedAt: Instant,
    ): Int =
        store.releaseRaidSuppressions(accountId, entryId, raidId, observedAt)

    private fun holdUnsupportedPolicy(
        record: ActionConvergenceRecord,
        at: Instant,
        evidence: AutomationActionEvidence? = null,
    ): ConvergenceDirective.ContinueSelection {
        recordUnsupportedPolicy(record, at, evidence)
        return terminal(record, ActionConvergenceResult.HELD,
            ProductionActionEvidenceInterpreter.UNSUPPORTED_POLICY_REASON, at)
    }

    private fun recordUnsupportedPolicy(record: ActionConvergenceRecord, at: Instant, evidence: AutomationActionEvidence?) {
        evidenceCaseRecorder.record(record, AutomationActionEvidence.PolicyUnavailable(
            at, evidence?.responseShapeFingerprint, evidence?.sanitizedSnippet,
        ), ProductionActionEvidenceInterpreter.UNSUPPORTED_POLICY_REASON)
    }

    private fun isSuppressed(accountId: Long, selection: SelectedAutomationAction): Boolean =
        selection.scope in store.findPolicyHeldScopes(accountId) ||
            selection.baselineFingerprint in store.findSuppressedBaselines(accountId)[selection.scope].orEmpty()

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

    private fun AutomationActionEvidence.isUnconfirmedObservation(): Boolean =
        this is AutomationActionEvidence.IncompleteObservation || this is AutomationActionEvidence.SameState ||
            this is AutomationActionEvidence.NetworkFailure || this is AutomationActionEvidence.ResultUnobserved

    private fun budgetExhausted(record: ActionConvergenceRecord, now: Instant): Boolean =
        AutomationConvergenceBudget.exhausted(
            record.successfulObservationCount,
            record.firstPendingAt,
            now,
        )

    private fun AutomationActionEvidence.reasonCode(): String = when (this) {
        is AutomationActionEvidence.PolicyUnavailable -> ProductionActionEvidenceInterpreter.UNSUPPORTED_POLICY_REASON
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
        val LATE_APPLICATION_RESULTS = setOf(
            ActionConvergenceResult.HELD,
            ActionConvergenceResult.RESULT_UNOBSERVED,
            ActionConvergenceResult.SUPERSEDED,
        )
    }
}
