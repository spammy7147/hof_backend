package app.spammy.hof.automation.convergence

import jakarta.persistence.EntityManager
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
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
        val sanitizedSnippet = evidence.sanitizedSnippet ?: evidence.structuralSnippet()
        val evidenceCase = AutomationEvidenceCaseEntity(
            id = UUID.randomUUID().toString(),
            attempt = attempt,
            evidenceSource = evidence.sourceName(),
            observationCompleteness = evidence.completenessName(),
            observationFreshness = evidence.freshnessName(),
            stateFingerprint = evidence.stateFingerprint(),
            responseShapeFingerprint = evidence.responseShapeFingerprint ?: fingerprint(sanitizedSnippet),
            sanitizedSnippet = sanitizedSnippet,
            reasonCode = reasonCode,
            policyVersion = record.selection.policyVersion,
            buildVersion = BUILD_VERSION,
            createdAt = evidence.capturedAt,
            expiresAt = evidence.capturedAt.plus(DETAIL_RETENTION),
        )
        entityManager.persist(evidenceCase)
        entityManager.createQuery(
            "select convergence from ActionConvergenceEntity convergence where convergence.attempt.id = :attemptId",
            ActionConvergenceEntity::class.java,
        ).setParameter("attemptId", record.attemptId)
            .singleResult.evidenceCaseId = evidenceCase.id
    }

    private fun AutomationActionEvidence.sourceName(): String = when (this) {
        is AutomationActionEvidence.DirectApplied,
        is AutomationActionEvidence.DirectRejected,
        -> "POLICY_DECISION"
        is AutomationActionEvidence.StateAdvanced,
        is AutomationActionEvidence.SameState,
        -> "AUTHORITATIVE_OBSERVATION"
        is AutomationActionEvidence.IncompleteObservation -> "INCOMPLETE_OBSERVATION"
        is AutomationActionEvidence.NetworkFailure -> "NETWORK_FAILURE"
        is AutomationActionEvidence.ResultUnobserved -> "RESULT_UNOBSERVED"
        is AutomationActionEvidence.ResultUnobservedFreshDecision -> "RESULT_UNOBSERVED_FRESH_DECISION"
        is AutomationActionEvidence.BattleGateRequired -> "BATTLE_GATE"
    }

    private fun AutomationActionEvidence.completenessName(): String? = when (this) {
        is AutomationActionEvidence.IncompleteObservation,
        is AutomationActionEvidence.NetworkFailure,
        is AutomationActionEvidence.ResultUnobserved,
        is AutomationActionEvidence.ResultUnobservedFreshDecision,
        -> ObservationCompleteness.INCOMPLETE.name
        else -> ObservationCompleteness.COMPLETE.name
    }

    private fun AutomationActionEvidence.freshnessName(): String? = when (this) {
        is AutomationActionEvidence.NetworkFailure,
        is AutomationActionEvidence.ResultUnobserved,
        is AutomationActionEvidence.ResultUnobservedFreshDecision,
        -> null
        else -> ObservationFreshness.FRESH.name
    }

    private fun AutomationActionEvidence.stateFingerprint(): String? = when (this) {
        is AutomationActionEvidence.DirectApplied -> stateFingerprint
        is AutomationActionEvidence.StateAdvanced -> stateFingerprint
        is AutomationActionEvidence.SameState -> stateFingerprint
        else -> null
    }

    /** 원문 payload, 식별자, 오류 메시지를 배제한 분류 정보만 진단 샘플로 보존한다. */
    private fun AutomationActionEvidence.structuralSnippet(): String = when (this) {
        is AutomationActionEvidence.DirectApplied -> "evidence=DirectApplied"
        is AutomationActionEvidence.DirectRejected -> "evidence=DirectRejected"
        is AutomationActionEvidence.StateAdvanced -> "evidence=StateAdvanced"
        is AutomationActionEvidence.SameState -> "evidence=SameState"
        is AutomationActionEvidence.IncompleteObservation ->
            "evidence=IncompleteObservation;authoritative=$authoritative"
        is AutomationActionEvidence.NetworkFailure -> "evidence=NetworkFailure"
        is AutomationActionEvidence.ResultUnobserved -> "evidence=ResultUnobserved"
        is AutomationActionEvidence.ResultUnobservedFreshDecision ->
            "evidence=ResultUnobservedFreshDecision"
        is AutomationActionEvidence.BattleGateRequired ->
            "evidence=BattleGateRequired;challengePresent=${challengeId != null}"
    }

    private fun fingerprint(value: String): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)),
    )

    private companion object {
        val DETAIL_RETENTION: Duration = Duration.ofDays(30)
        val BUILD_VERSION: String = JpaEvidenceCaseRecorder::class.java.`package`.implementationVersion ?: "local"
    }
}
