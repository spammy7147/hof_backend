package app.spammy.hof.automation.convergence

import app.spammy.hof.automation.service.AmbiguousActionResolution
import app.spammy.hof.automation.service.TypedAutomationExecution
import java.time.Instant
import org.springframework.stereotype.Component

/** action adapter가 이미 검증한 domain 결과를 production evidence policy의 입력으로 변환한다. */
@Component
class ProductionActionEvidenceInterpreter(
    private val policies: ActionEvidencePolicies,
) {
    companion object {
        const val VERSION_1 = "automation-action-convergence-v1"
        const val UNSUPPORTED_POLICY_REASON = "POLICY_VERSION_UNSUPPORTED"

        fun supportsVersion(version: String): Boolean = version == VERSION_1
    }

    fun fromExecution(
        selection: SelectedAutomationAction,
        execution: TypedAutomationExecution,
        capturedAt: Instant,
    ): AutomationActionEvidence {
        if (!supportsVersion(selection.policyVersion)) {
            return AutomationActionEvidence.PolicyUnavailable(
                capturedAt,
                (execution as? TypedAutomationExecution.ActionCompleted)?.responseShapeMaterial?.let(ProductionEvidenceShapes::fingerprint),
                (execution as? TypedAutomationExecution.ActionCompleted)?.sanitizedSnippet,
            )
        }
        if (execution is TypedAutomationExecution.ActionCompleted) {
            val responseShapeFingerprint = ProductionEvidenceShapes.fingerprint(execution.responseShapeMaterial)
            if (responseShapeFingerprint !in ProductionEvidenceShapes.knownFingerprints(selection.actionKind)) {
                return AutomationActionEvidence.IncompleteObservation(
                    capturedAt = capturedAt,
                    reason = "UNKNOWN_RESPONSE_SHAPE",
                    authoritative = true,
                    responseShapeFingerprint = responseShapeFingerprint,
                    sanitizedSnippet = execution.sanitizedSnippet,
                )
            }
            return policies.evaluate(
                selection,
                ActionPolicyObservation(
                    capturedAt = capturedAt,
                    source = ActionEvidenceSource.DIRECT_RESPONSE,
                    completeness = ObservationCompleteness.COMPLETE,
                    freshness = ObservationFreshness.FRESH,
                    state = execution.observedState,
                    explicitRejected = execution.explicitRejected,
                    rejectionReason = execution.rejectionReason,
                    actionSuccessMarker = execution.actionSuccessMarker,
                    responseShapeFingerprint = responseShapeFingerprint,
                    sanitizedSnippet = execution.sanitizedSnippet,
                ),
            )
        }
        if (execution is TypedAutomationExecution.BattleCompleted && execution.terminalOutcomes.isNotEmpty()) {
            val snippet = "BattleResultResponse|roundCount=${execution.terminalOutcomes.size}|" +
                "terminalOutcomes=${execution.terminalOutcomes.joinToString(",")}"
            return policies.evaluate(
                selection,
                ActionPolicyObservation(
                    capturedAt = capturedAt,
                    source = ActionEvidenceSource.DIRECT_RESPONSE,
                    completeness = ObservationCompleteness.COMPLETE,
                    freshness = ObservationFreshness.FRESH,
                    state = BattleObservedState(
                        fingerprint = ProductionEvidenceShapes.fingerprint(snippet),
                        targetPresent = true,
                        runnable = false,
                        terminalOutcomes = execution.terminalOutcomes,
                    ),
                    responseShapeFingerprint = ProductionEvidenceShapes.fingerprint(
                        ProductionEvidenceShapes.BATTLE_RESPONSE,
                    ),
                    sanitizedSnippet = snippet,
                ),
            )
        }
        val diagnostics = diagnostics(
            ProductionEvidenceShapes.executionDiagnostic(selection.actionKind, execution.javaClass.simpleName),
        )
        return when (execution) {
            is TypedAutomationExecution.SharedCooldown -> AutomationActionEvidence.StateAdvanced(
                capturedAt,
                "lifecycle:shared-cooldown:${selection.actionKind.name}",
                diagnostics.fingerprint,
                diagnostics.snippet,
            )
            else -> policies.evaluate(
                selection,
                ActionPolicyObservation(
                    capturedAt = capturedAt,
                    source = ActionEvidenceSource.LIFECYCLE_RESULT,
                    completeness = ObservationCompleteness.COMPLETE,
                    freshness = ObservationFreshness.FRESH,
                    state = LifecycleResultObservedState(
                        fingerprint = "lifecycle:${selection.actionKind.name}:${execution.javaClass.simpleName}",
                        actionKind = selection.actionKind,
                        resultKind = execution.javaClass.simpleName,
                    ),
                    responseShapeFingerprint = diagnostics.fingerprint,
                    sanitizedSnippet = diagnostics.snippet,
                ),
            )
        }
    }

    fun fromReconciliation(
        selection: SelectedAutomationAction,
        resolution: AmbiguousActionResolution,
        capturedAt: Instant,
    ): AutomationActionEvidence {
        if (!supportsVersion(selection.policyVersion)) return AutomationActionEvidence.PolicyUnavailable(capturedAt)
        val resultKind = resolution.javaClass.simpleName
        val diagnostics = diagnostics(
            ProductionEvidenceShapes.reconciliationDiagnostic(selection.actionKind, resultKind),
        )
        return when (resolution) {
            is AmbiguousActionResolution.Applied -> {
                val appliedResultKind = "ReconciledApplied"
                policies.evaluate(
                    selection,
                    ActionPolicyObservation(
                        capturedAt = capturedAt,
                        source = ActionEvidenceSource.LIFECYCLE_RESULT,
                        completeness = ObservationCompleteness.COMPLETE,
                        freshness = ObservationFreshness.FRESH,
                        state = LifecycleResultObservedState(
                            fingerprint = "lifecycle:${selection.actionKind.name}:$appliedResultKind",
                            actionKind = selection.actionKind,
                            resultKind = appliedResultKind,
                        ),
                        responseShapeFingerprint = diagnostics.fingerprint,
                        sanitizedSnippet = diagnostics.snippet,
                    ),
                )
            }
            AmbiguousActionResolution.Resubmit -> AutomationActionEvidence.SameState(
                capturedAt = capturedAt,
                stateFingerprint = selection.baselineFingerprint,
                responseShapeFingerprint = diagnostics.fingerprint,
                sanitizedSnippet = diagnostics.snippet,
            )
            is AmbiguousActionResolution.VerifyLater -> AutomationActionEvidence.IncompleteObservation(
                capturedAt,
                resolution.reason,
                responseShapeFingerprint = diagnostics.fingerprint,
                sanitizedSnippet = diagnostics.snippet,
            )
            is AmbiguousActionResolution.Held -> AutomationActionEvidence.ResultUnobserved(
                capturedAt = capturedAt,
                reason = resolution.reason,
                responseShapeFingerprint = diagnostics.fingerprint,
                sanitizedSnippet = diagnostics.snippet,
            )
            is AmbiguousActionResolution.HandedOff -> AutomationActionEvidence.IncompleteObservation(
                capturedAt = capturedAt,
                reason = resolution.reason,
                authoritative = true,
                responseShapeFingerprint = diagnostics.fingerprint,
                sanitizedSnippet = diagnostics.snippet,
            )
            is AmbiguousActionResolution.FreshDecision -> AutomationActionEvidence.ResultUnobservedFreshDecision(
                capturedAt = capturedAt,
                reason = resolution.reason,
                responseShapeFingerprint = diagnostics.fingerprint,
                sanitizedSnippet = diagnostics.snippet,
            )
            is AmbiguousActionResolution.Superseded -> AutomationActionEvidence.StateAdvanced(
                capturedAt,
                "lifecycle:superseded:${selection.actionKind.name}",
                diagnostics.fingerprint,
                diagnostics.snippet,
            )
        }
    }

    private fun diagnostics(snippet: String): ShapeDiagnostics = ShapeDiagnostics(
        ProductionEvidenceShapes.fingerprint(snippet),
        snippet,
    )

    private data class ShapeDiagnostics(
        val fingerprint: String,
        val snippet: String,
    )
}
