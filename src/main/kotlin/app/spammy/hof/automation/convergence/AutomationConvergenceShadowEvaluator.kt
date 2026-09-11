package app.spammy.hof.automation.convergence

import app.spammy.hof.common.time.TimeProvider
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

enum class LegacyConvergenceDecision {
    APPLIED,
    RECONCILING,
    RESUBMIT,
    SUPERSEDED,
    HELD,
    RESULT_UNOBSERVED,
}

data class ShadowConvergenceEvaluation(
    val actionKind: AutomationActionKind,
    val evidenceKind: String,
    val legacyDecision: LegacyConvergenceDecision,
    val newResult: ActionConvergenceResult,
    val differs: Boolean,
)

data class ShadowConvergenceAggregate(
    val actionKind: AutomationActionKind,
    val evidenceKind: String,
    val legacyDecision: LegacyConvergenceDecision,
    val newResult: ActionConvergenceResult,
    val differs: Boolean,
    val count: Long,
)

interface AutomationConvergenceShadowEvaluator {
    fun selected(accountId: Long, selection: SelectedAutomationAction)

    fun observe(
        accountId: Long,
        executionIdentity: String,
        evidence: AutomationActionEvidence,
        legacyDecision: LegacyConvergenceDecision,
    ): ShadowConvergenceEvaluation?

    fun snapshot(): List<ShadowConvergenceAggregate>
}

/**
 * production runtime/store와 분리된 evaluator다. 비교 기록에서 판정 상태를 복원하며 실제 실행의 evidence를 소비하고
 * 관문 해제 여부만 production store에서 읽는다. HOF 요청, runtime 전이, production row는 만들지 않는다.
 */
@Service
class DefaultAutomationConvergenceShadowEvaluator(
    private val timeProvider: TimeProvider,
    private val recorder: AutomationConvergenceShadowRecorder = NoOpAutomationConvergenceShadowRecorder,
    private val productionStore: ConvergenceStore? = null,
) : AutomationConvergenceShadowEvaluator {
    private val log = LoggerFactory.getLogger(javaClass)
    private val store = InMemoryConvergenceStore()
    private val engine = DefaultAutomationActionConvergenceModule(store, timeProvider)
    private val attempts = ConcurrentHashMap<Pair<Long, String>, Long>()
    private val aggregate = ConcurrentHashMap<ShadowKey, AtomicLong>()

    @Synchronized
    override fun selected(accountId: Long, selection: SelectedAutomationAction) {
        val redacted = selection.copy(
            executionIdentity = fingerprint(selection.executionIdentity),
            scope = selection.scope.copy(key = fingerprint(selection.scope.key)),
            baselineFingerprint = fingerprint(selection.baselineFingerprint),
        )
        if (store.get(accountId, redacted.executionIdentity) == null) {
            recorder.restore(accountId, redacted.executionIdentity, redacted.scope).forEach { checkpoint ->
                val restored = store.get(accountId, checkpoint.selection.executionIdentity)
                    ?: store.createOrGet(accountId, checkpoint.selection, checkpoint.updatedAt).also {
                        it.result = checkpoint.result
                        it.reasonCode = checkpoint.reasonCode
                        it.successfulObservationCount = checkpoint.successfulObservationCount
                        it.firstPendingAt = checkpoint.firstPendingAt
                        it.submittedAt = checkpoint.submittedAt
                        it.nextProbeAt = checkpoint.nextProbeAt
                        it.finishedAt = checkpoint.finishedAt
                        store.save(it)
                    }
                if (checkpoint.suppressionReleased ||
                    !ProductionActionEvidenceInterpreter.supportsVersion(checkpoint.selection.policyVersion) ||
                    (checkpoint.result == ActionConvergenceResult.RESULT_UNOBSERVED &&
                        checkpoint.reasonCode == "RESULT_UNOBSERVED_FRESH_DECISION")
                ) {
                    store.releaseSuppression(accountId, restored.attemptId, checkpoint.updatedAt)
                }
                attempts[accountId to checkpoint.selection.executionIdentity] = restored.attemptId
            }
        }
        val existing = store.get(accountId, redacted.executionIdentity)
        val original = existing?.selection ?: redacted
        if (existing != null && !existing.active) {
            attempts[accountId to original.executionIdentity] = existing.attemptId
            return
        }
        if (original.actionKind.battle && store.activeBattleGate(accountId) != null &&
            productionStore != null && productionStore.activeBattleGate(accountId) == null
        ) {
            engine.releaseBattleGate(accountId, timeProvider.now())
        }
        store.findActive(accountId, original.scope)
            ?.takeIf { it.selection.executionIdentity != original.executionIdentity }
            ?.let { replaced ->
                val replacedKey = accountId to replaced.selection.executionIdentity
                attempts.putIfAbsent(replacedKey, replaced.attemptId)
                observeRedacted(
                    accountId,
                    replaced.selection.executionIdentity,
                    AutomationActionEvidence.StateAdvanced(
                        capturedAt = timeProvider.now(),
                        stateFingerprint = fingerprint(
                            "shadow-selection-replaced:${replaced.selection.actionKind}:${selection.executionIdentity}",
                        ),
                    ),
                    LegacyConvergenceDecision.SUPERSEDED,
                )
            }
        val directive = engine.prepare(accountId, original)
        if (directive is ConvergenceDirective.Submit) {
            attempts[accountId to original.executionIdentity] = directive.attemptId
        } else if (!ProductionActionEvidenceInterpreter.supportsVersion(original.policyVersion)) {
            store.get(accountId, original.executionIdentity)?.let { held ->
                attempts[accountId to original.executionIdentity] = held.attemptId
                // SHADOW의 진단 보류가 실제로 선택된 후속 행동의 비교를 막지는 않는다.
                store.releaseSuppression(accountId, held.attemptId, timeProvider.now())
            }
        }
    }

    @Synchronized
    override fun observe(
        accountId: Long,
        executionIdentity: String,
        evidence: AutomationActionEvidence,
        legacyDecision: LegacyConvergenceDecision,
    ): ShadowConvergenceEvaluation? = observeRedacted(accountId, fingerprint(executionIdentity), evidence, legacyDecision)

    private fun observeRedacted(
        accountId: Long,
        executionIdentityHash: String,
        evidence: AutomationActionEvidence,
        legacyDecision: LegacyConvergenceDecision,
    ): ShadowConvergenceEvaluation? {
        val key = accountId to executionIdentityHash
        val attemptId = attempts[key] ?: if (evidence is AutomationActionEvidence.DirectApplied) {
            store.get(accountId, executionIdentityHash)
                ?.takeIf { ProductionActionEvidenceInterpreter.supportsVersion(it.selection.policyVersion) }
                ?.attemptId
        } else null
        if (attemptId == null) return null
        val before = store.get(attemptId) ?: return null
        if (!before.active && ProductionActionEvidenceInterpreter.supportsVersion(before.selection.policyVersion)) {
            if (evidence !is AutomationActionEvidence.DirectApplied) return null
            val previousResult = before.result
            engine.recordLateApplication(accountId, executionIdentityHash, evidence)
            if (store.get(attemptId)?.result == previousResult) return null
        } else {
            engine.record(attemptId, evidence)
        }
        val after = requireNotNull(store.get(attemptId))
        val newResult = after.result ?: ActionConvergenceResult.PENDING
        val evidenceKind = evidence.javaClass.simpleName
        val differs = legacyDecision.expectedNewResult() != newResult
        val completeness = evidence.completeness()
        val newReasonCode = after.reasonCode ?: "SHADOW_REASON_MISSING"
        val legacyReasonCode = legacyDecision.expectedReasonCode(evidence)
        val shapeFingerprint = evidence.responseShapeFingerprint ?: fingerprint(evidence.shapeMaterial())
        val shapeDiffers = if (evidence.responseShapeFingerprint == null) {
            evidenceKind !in legacyDecision.expectedEvidenceKinds()
        } else {
            shapeFingerprint !in ProductionEvidenceShapes.knownFingerprints(before.selection.actionKind)
        }
        recorder.record(
            DurableShadowEvaluation(
                accountId = accountId,
                executionIdentityHash = executionIdentityHash,
                actionKind = before.selection.actionKind,
                scopeKind = before.selection.scope.kind,
                scopeKeyHash = before.selection.scope.key,
                evidenceKind = evidenceKind,
                evidenceCompleteness = completeness,
                responseShapeFingerprint = shapeFingerprint,
                sanitizedSnippet = evidence.sanitizedSnippet ?: evidence.shapeMaterial(),
                legacyDecision = legacyDecision,
                legacyReasonCode = legacyReasonCode,
                newResult = newResult,
                newReasonCode = newReasonCode,
                resultDiffers = differs,
                reasonDiffers = legacyReasonCode != newReasonCode,
                shapeDiffers = shapeDiffers,
                completenessDiffers = legacyDecision.expectedCompleteness(completeness) != completeness,
                policyVersion = before.selection.policyVersion,
                observedAt = evidence.capturedAt,
                checkpoint = ShadowConvergenceCheckpoint(
                    after.selection, newResult, newReasonCode,
                    after.successfulObservationCount, after.firstPendingAt, after.submittedAt,
                    after.nextProbeAt, after.finishedAt, after.updatedAt,
                ),
            ),
        )
        val shadowKey = ShadowKey(before.selection.actionKind, evidenceKind, legacyDecision, newResult, differs)
        val count = aggregate.computeIfAbsent(shadowKey) { AtomicLong() }.incrementAndGet()
        log.info(
            "automation_convergence_shadow actionKind={} evidenceKind={} legacyDecision={} newResult={} differs={} count={}",
            before.selection.actionKind,
            evidenceKind,
            legacyDecision,
            newResult,
            differs,
            count,
        )
        if (!after.active) attempts.remove(key, attemptId)
        return ShadowConvergenceEvaluation(before.selection.actionKind, evidenceKind, legacyDecision, newResult, differs)
    }

    override fun snapshot(): List<ShadowConvergenceAggregate> = aggregate.entries.map { (key, value) ->
        ShadowConvergenceAggregate(
            key.actionKind,
            key.evidenceKind,
            key.legacyDecision,
            key.newResult,
            key.differs,
            value.get(),
        )
    }.sortedWith(compareBy({ it.actionKind.name }, { it.evidenceKind }, { it.legacyDecision.name }, { it.newResult.name }))

    private fun LegacyConvergenceDecision.expectedNewResult(): ActionConvergenceResult = when (this) {
        LegacyConvergenceDecision.APPLIED -> ActionConvergenceResult.APPLIED
        LegacyConvergenceDecision.RECONCILING -> ActionConvergenceResult.PENDING
        LegacyConvergenceDecision.RESUBMIT -> ActionConvergenceResult.NOT_APPLIED
        LegacyConvergenceDecision.SUPERSEDED -> ActionConvergenceResult.SUPERSEDED
        LegacyConvergenceDecision.HELD -> ActionConvergenceResult.HELD
        LegacyConvergenceDecision.RESULT_UNOBSERVED -> ActionConvergenceResult.RESULT_UNOBSERVED
    }

    private fun LegacyConvergenceDecision.expectedReasonCode(evidence: AutomationActionEvidence): String = when (this) {
        LegacyConvergenceDecision.APPLIED -> "DIRECT_RESPONSE_APPLIED"
        LegacyConvergenceDecision.RECONCILING -> when (evidence) {
            is AutomationActionEvidence.NetworkFailure -> "OBSERVATION_NETWORK_FAILURE"
            is AutomationActionEvidence.IncompleteObservation -> "OBSERVATION_INCOMPLETE"
            else -> "AUTHORITATIVE_STATE_UNCHANGED"
        }
        LegacyConvergenceDecision.RESUBMIT -> "DIRECT_RESPONSE_REJECTED"
        LegacyConvergenceDecision.SUPERSEDED -> "AUTHORITATIVE_STATE_ADVANCED"
        LegacyConvergenceDecision.HELD -> "PENDING_BUDGET_EXHAUSTED"
        LegacyConvergenceDecision.RESULT_UNOBSERVED -> if (
            evidence is AutomationActionEvidence.ResultUnobservedFreshDecision
        ) {
            "RESULT_UNOBSERVED_FRESH_DECISION"
        } else {
            "RESULT_UNOBSERVED"
        }
    }

    private fun LegacyConvergenceDecision.expectedCompleteness(actual: String): String = when (this) {
        LegacyConvergenceDecision.RECONCILING -> actual
        LegacyConvergenceDecision.HELD,
        LegacyConvergenceDecision.RESULT_UNOBSERVED,
        -> "INCOMPLETE"
        else -> "COMPLETE"
    }

    private fun LegacyConvergenceDecision.expectedEvidenceKinds(): Set<String> = when (this) {
        LegacyConvergenceDecision.APPLIED -> setOf("DirectApplied")
        LegacyConvergenceDecision.RECONCILING -> setOf("SameState", "IncompleteObservation", "NetworkFailure")
        LegacyConvergenceDecision.RESUBMIT -> setOf("DirectRejected")
        LegacyConvergenceDecision.SUPERSEDED -> setOf("StateAdvanced")
        LegacyConvergenceDecision.HELD -> setOf("BattleGateRequired", "IncompleteObservation")
        LegacyConvergenceDecision.RESULT_UNOBSERVED -> setOf(
            "ResultUnobserved",
            "ResultUnobservedFreshDecision",
        )
    }

    private fun AutomationActionEvidence.completeness(): String = when (this) {
        is AutomationActionEvidence.PolicyUnavailable -> "POLICY_UNAVAILABLE"
        is AutomationActionEvidence.IncompleteObservation ->
            if (authoritative) "AUTHORITATIVE_IDENTITY_INCOMPLETE" else "INCOMPLETE"
        is AutomationActionEvidence.NetworkFailure -> "NETWORK_FAILURE"
        is AutomationActionEvidence.ResultUnobserved -> "UNOBSERVED"
        is AutomationActionEvidence.ResultUnobservedFreshDecision -> "UNOBSERVED_FRESH_DECISION"
        is AutomationActionEvidence.BattleGateRequired -> "BATTLE_GATE"
        else -> "COMPLETE"
    }

    private fun AutomationActionEvidence.shapeMaterial(): String = when (this) {
        is AutomationActionEvidence.PolicyUnavailable -> "PolicyUnavailable"
        is AutomationActionEvidence.DirectApplied -> "DirectApplied"
        is AutomationActionEvidence.DirectRejected -> "DirectRejected"
        is AutomationActionEvidence.StateAdvanced -> "StateAdvanced"
        is AutomationActionEvidence.SameState -> "SameState"
        is AutomationActionEvidence.IncompleteObservation -> "IncompleteObservation|authoritative=$authoritative"
        is AutomationActionEvidence.NetworkFailure -> "NetworkFailure"
        is AutomationActionEvidence.ResultUnobserved -> "ResultUnobserved"
        is AutomationActionEvidence.ResultUnobservedFreshDecision -> "ResultUnobservedFreshDecision"
        is AutomationActionEvidence.BattleGateRequired -> "BattleGateRequired"
    }

    private fun fingerprint(value: String): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)),
    )

    private data class ShadowKey(
        val actionKind: AutomationActionKind,
        val evidenceKind: String,
        val legacyDecision: LegacyConvergenceDecision,
        val newResult: ActionConvergenceResult,
        val differs: Boolean,
    )
}
