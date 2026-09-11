package app.spammy.hof.automation.convergence

import app.spammy.hof.automation.repository.AutomationConvergenceShadowQueryRepository
import jakarta.persistence.EntityManager
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
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
    val checkpoint: ShadowConvergenceCheckpoint? = null,
)

data class ShadowConvergenceCheckpoint(
    /** 실행 식별자·scope key·기준 상태는 SHADOW 전용 지문이다. */
    val selection: SelectedAutomationAction,
    val result: ActionConvergenceResult,
    val reasonCode: String,
    val successfulObservationCount: Int,
    val firstPendingAt: Instant?,
    val submittedAt: Instant?,
    val nextProbeAt: Instant?,
    val finishedAt: Instant?,
    val updatedAt: Instant,
    val suppressionReleased: Boolean = false,
)

fun interface AutomationConvergenceShadowRecorder {
    fun record(evaluation: DurableShadowEvaluation)

    fun restore(
        accountId: Long,
        executionIdentityHash: String,
        scope: AutomationIsolationScope,
    ): List<ShadowConvergenceCheckpoint> = emptyList()
}

object NoOpAutomationConvergenceShadowRecorder : AutomationConvergenceShadowRecorder {
    override fun record(evaluation: DurableShadowEvaluation) = Unit
}

@Repository
@Transactional(propagation = Propagation.REQUIRES_NEW)
class JpaAutomationConvergenceShadowRecorder(
    private val entityManager: EntityManager,
    private val queries: AutomationConvergenceShadowQueryRepository,
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
                successfulObservationCount = evaluation.checkpoint?.successfulObservationCount,
                firstPendingAt = evaluation.checkpoint?.firstPendingAt,
                submittedAt = evaluation.checkpoint?.submittedAt,
                nextProbeAt = evaluation.checkpoint?.nextProbeAt,
                finishedAt = evaluation.checkpoint?.finishedAt,
                checkpointUpdatedAt = evaluation.checkpoint?.updatedAt,
                selectionEntryId = evaluation.checkpoint?.selection?.entryId,
                baselineFingerprintHash = evaluation.checkpoint?.selection?.baselineFingerprint,
                observationOnly = evaluation.checkpoint?.selection?.observationOnly,
            ),
        )
    }

    override fun restore(
        accountId: Long,
        executionIdentityHash: String,
        scope: AutomationIsolationScope,
    ): List<ShadowConvergenceCheckpoint> = queries.restore(accountId, executionIdentityHash, scope)

    private companion object {
        val RETENTION: Duration = Duration.ofDays(30)
        val BUILD_VERSION: String = JpaAutomationConvergenceShadowRecorder::class.java.`package`.implementationVersion
            ?: "local"
    }
}
