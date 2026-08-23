package app.spammy.hof.automation.convergence

import app.spammy.hof.common.time.TimeProvider
import jakarta.persistence.EntityManager
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class AutomationEvidenceRetentionScheduler(
    private val entityManager: EntityManager,
    private val timeProvider: TimeProvider,
) {
    @Scheduled(cron = "\${hof.automation-convergence.evidence-cleanup-cron:0 35 4 * * *}")
    @Transactional
    fun deleteExpiredEvidence(): Int {
        val now = timeProvider.now()
        val evidenceCases = entityManager.createQuery(
            "delete from AutomationEvidenceCaseEntity evidence where evidence.expiresAt <= :now",
        ).setParameter("now", now)
            .executeUpdate()
        val shadowEvaluations = entityManager.createQuery(
            "delete from AutomationConvergenceShadowEvaluationEntity shadow where shadow.expiresAt <= :now",
        ).setParameter("now", now)
            .executeUpdate()
        val raidCooldownEvidence = entityManager.createQuery(
            "delete from RaidCooldownEvidenceCaseEntity evidence where evidence.expiresAt <= :now",
        ).setParameter("now", now)
            .executeUpdate()
        return evidenceCases + shadowEvaluations + raidCooldownEvidence
    }
}
