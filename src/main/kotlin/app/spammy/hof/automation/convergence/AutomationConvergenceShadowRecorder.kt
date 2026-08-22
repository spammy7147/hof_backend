package app.spammy.hof.automation.convergence

import jakarta.persistence.EntityManager
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

data class DurableShadowEvaluation(
    val accountId: Long,
    val executionIdentityHash: String,
    val actionKind: AutomationActionKind,
    val scopeKind: AutomationIsolationScopeKind,
    val scopeKeyHash: String,
    val evidenceKind: String,
    val evidenceCompleteness: String,
    val responseShapeFingerprint: String,
    val sanitizedSnippet: String,
    val legacyDecision: LegacyConvergenceDecision,
    val legacyReasonCode: String,
    val newResult: ActionConvergenceResult,
    val newReasonCode: String,
    val resultDiffers: Boolean,
    val reasonDiffers: Boolean,
    val shapeDiffers: Boolean,
    val completenessDiffers: Boolean,
    val policyVersion: String,
    val observedAt: Instant,
)

fun interface AutomationConvergenceShadowRecorder {
    fun record(evaluation: DurableShadowEvaluation)
}

object NoOpAutomationConvergenceShadowRecorder : AutomationConvergenceShadowRecorder {
    override fun record(evaluation: DurableShadowEvaluation) = Unit
}

@Repository
@Transactional
class JpaAutomationConvergenceShadowRecorder(
    private val entityManager: EntityManager,
) : AutomationConvergenceShadowRecorder {
    override fun record(evaluation: DurableShadowEvaluation) {
        entityManager.persist(
            AutomationConvergenceShadowEvaluationEntity(
                id = UUID.randomUUID().toString(),
                accountId = evaluation.accountId,
                executionIdentityHash = evaluation.executionIdentityHash,
                actionKind = evaluation.actionKind,
                scopeKind = evaluation.scopeKind,
                scopeKeyHash = evaluation.scopeKeyHash,
                evidenceKind = evaluation.evidenceKind,
                evidenceCompleteness = evaluation.evidenceCompleteness,
                responseShapeFingerprint = evaluation.responseShapeFingerprint,
                sanitizedSnippet = evaluation.sanitizedSnippet,
                legacyDecision = evaluation.legacyDecision,
                legacyReasonCode = evaluation.legacyReasonCode,
                newResult = evaluation.newResult,
                newReasonCode = evaluation.newReasonCode,
                resultDiffers = evaluation.resultDiffers,
                reasonDiffers = evaluation.reasonDiffers,
                shapeDiffers = evaluation.shapeDiffers,
                completenessDiffers = evaluation.completenessDiffers,
                policyVersion = evaluation.policyVersion,
                buildVersion = BUILD_VERSION,
                createdAt = evaluation.observedAt,
                expiresAt = evaluation.observedAt.plus(RETENTION),
            ),
        )
    }

    private companion object {
        val RETENTION: Duration = Duration.ofDays(30)
        val BUILD_VERSION: String = JpaAutomationConvergenceShadowRecorder::class.java.`package`.implementationVersion
            ?: "local"
    }
}
