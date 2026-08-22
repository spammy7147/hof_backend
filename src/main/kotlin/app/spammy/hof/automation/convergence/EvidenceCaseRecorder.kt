package app.spammy.hof.automation.convergence

import jakarta.persistence.EntityManager
import java.time.Duration
import java.util.UUID
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

fun interface EvidenceCaseRecorder {
    fun record(
        record: ActionConvergenceRecord,
        evidence: AutomationActionEvidence,
        reasonCode: String,
    )
}

object NoOpEvidenceCaseRecorder : EvidenceCaseRecorder {
    override fun record(
        record: ActionConvergenceRecord,
        evidence: AutomationActionEvidence,
        reasonCode: String,
    ) = Unit
}

@Repository
@Transactional
class JpaEvidenceCaseRecorder(
    private val entityManager: EntityManager,
) : EvidenceCaseRecorder {
    override fun record(
        record: ActionConvergenceRecord,
        evidence: AutomationActionEvidence,
        reasonCode: String,
    ) {
        val attempt = requireNotNull(
            entityManager.find(AutomationActionAttemptEntity::class.java, record.attemptId),
        ) { "Convergence attempt ${record.attemptId} does not exist." }
        val evidenceCase = AutomationEvidenceCaseEntity(
            id = UUID.randomUUID().toString(),
            attempt = attempt,
            evidenceSource = evidence.sourceName(),
            observationCompleteness = evidence.completenessName(),
            observationFreshness = evidence.freshnessName(),
            stateFingerprint = evidence.stateFingerprint(),
            reasonCode = reasonCode,
            policyVersion = record.selection.policyVersion,
            buildVersion = BUILD_VERSION,
            createdAt = evidence.capturedAt,
            expiresAt = evidence.capturedAt.plus(DETAIL_RETENTION),
        )
        entityManager.persist(evidenceCase)
        entityManager.createQuery(
            "update ActionConvergenceEntity convergence set convergence.evidenceCaseId = :caseId " +
                "where convergence.attempt.id = :attemptId",
        ).setParameter("caseId", evidenceCase.id)
            .setParameter("attemptId", record.attemptId)
            .executeUpdate()
    }

    private fun AutomationActionEvidence.sourceName(): String = when (this) {
        is AutomationActionEvidence.DirectApplied,
        is AutomationActionEvidence.DirectRejected,
        -> ActionEvidenceSource.DIRECT_RESPONSE.name
        is AutomationActionEvidence.StateAdvanced,
        is AutomationActionEvidence.SameState,
        -> "AUTHORITATIVE_OBSERVATION"
        is AutomationActionEvidence.IncompleteObservation -> "INCOMPLETE_OBSERVATION"
        is AutomationActionEvidence.NetworkFailure -> "NETWORK_FAILURE"
        is AutomationActionEvidence.ResultUnobserved -> "RESULT_UNOBSERVED"
        is AutomationActionEvidence.BattleGateRequired -> "BATTLE_GATE"
    }

    private fun AutomationActionEvidence.completenessName(): String? = when (this) {
        is AutomationActionEvidence.IncompleteObservation,
        is AutomationActionEvidence.NetworkFailure,
        is AutomationActionEvidence.ResultUnobserved,
        -> ObservationCompleteness.INCOMPLETE.name
        else -> ObservationCompleteness.COMPLETE.name
    }

    private fun AutomationActionEvidence.freshnessName(): String? = when (this) {
        is AutomationActionEvidence.NetworkFailure,
        is AutomationActionEvidence.ResultUnobserved,
        -> null
        else -> ObservationFreshness.FRESH.name
    }

    private fun AutomationActionEvidence.stateFingerprint(): String? = when (this) {
        is AutomationActionEvidence.DirectApplied -> stateFingerprint
        is AutomationActionEvidence.StateAdvanced -> stateFingerprint
        is AutomationActionEvidence.SameState -> stateFingerprint
        else -> null
    }

    private companion object {
        val DETAIL_RETENTION: Duration = Duration.ofDays(30)
        val BUILD_VERSION: String = JpaEvidenceCaseRecorder::class.java.`package`.implementationVersion ?: "local"
    }
}
