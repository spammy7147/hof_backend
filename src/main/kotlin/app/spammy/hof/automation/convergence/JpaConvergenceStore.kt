package app.spammy.hof.automation.convergence

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.automation.entity.AutomationEntryEntity
import jakarta.persistence.EntityManager
import java.time.Instant
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
@Transactional
class JpaConvergenceStore(
    private val entityManager: EntityManager,
) : ConvergenceStore {
    override fun createOrGet(
        accountId: Long,
        selection: SelectedAutomationAction,
        now: Instant,
    ): ActionConvergenceRecord {
        findAttempt(accountId, selection.executionIdentity)?.let { return it.toRecord() }
        val account = requireNotNull(entityManager.find(HofAccountEntity::class.java, accountId)) {
            "Account $accountId does not exist."
        }
        val entry = entityManager.find(AutomationEntryEntity::class.java, selection.entryId)
            ?.takeIf { it.account.id == accountId }
            ?: error("Automation entry ${selection.entryId} does not belong to account $accountId.")
        val attempt = AutomationActionAttemptEntity(
            account = account,
            entry = entry,
            executionIdentity = selection.executionIdentity,
            actionKind = selection.actionKind,
            scopeKind = selection.scope.kind,
            scopeKey = selection.scope.key,
            policyVersion = selection.policyVersion,
            baselineFingerprint = selection.baselineFingerprint,
            createdAt = now,
        )
        entityManager.persist(attempt)
        entityManager.flush()
        val convergence = ActionConvergenceEntity(
            attempt = attempt,
            accountId = accountId,
            scopeKind = selection.scope.kind,
            scopeKey = selection.scope.key,
            updatedAt = now,
        )
        entityManager.persist(convergence)
        // Surface active-scope races through the repository boundary where Spring translates them.
        entityManager.flush()
        return convergence.toRecord()
    }

    @Transactional(readOnly = true)
    override fun findActive(
        accountId: Long,
        scope: AutomationIsolationScope,
    ): ActionConvergenceRecord? = entityManager.createQuery(
        """
        select convergence from ActionConvergenceEntity convergence
        join fetch convergence.attempt attempt
        left join fetch attempt.entry
        where convergence.accountId = :accountId
          and convergence.scopeKind = :scopeKind
          and convergence.scopeKey = :scopeKey
          and convergence.activeMarker = 1
        order by convergence.id desc
        """.trimIndent(),
        ActionConvergenceEntity::class.java,
    ).setParameter("accountId", accountId)
        .setParameter("scopeKind", scope.kind)
        .setParameter("scopeKey", scope.key)
        .setMaxResults(1)
        .resultList
        .firstOrNull()
        ?.toRecord()

    @Transactional(readOnly = true)
    override fun findActiveScopes(accountId: Long): Set<AutomationIsolationScope> =
        entityManager.createQuery(
            """
            select convergence from ActionConvergenceEntity convergence
            where convergence.accountId = :accountId and convergence.activeMarker = 1
            """.trimIndent(),
            ActionConvergenceEntity::class.java,
        ).setParameter("accountId", accountId)
            .resultList
            .map { AutomationIsolationScope(it.scopeKind, it.scopeKey) }
            .toSet()

    @Transactional(readOnly = true)
    override fun findSuppressedBaselines(accountId: Long): Map<AutomationIsolationScope, Set<String>> =
        entityManager.createQuery(
            """
            select convergence from ActionConvergenceEntity convergence
            join fetch convergence.attempt attempt
            where convergence.accountId = :accountId
              and convergence.result in :results
              and convergence.suppressionReleasedAt is null
            """.trimIndent(),
            ActionConvergenceEntity::class.java,
        ).setParameter("accountId", accountId)
            .setParameter(
                "results",
                setOf(ActionConvergenceResult.HELD, ActionConvergenceResult.RESULT_UNOBSERVED),
            )
            .resultList
            .groupBy(
                { AutomationIsolationScope(it.scopeKind, it.scopeKey) },
                { it.attempt.baselineFingerprint },
            )
            .mapValues { (_, values) -> values.toSet() }

    @Transactional(readOnly = true)
    override fun findDue(accountId: Long, now: Instant): ActionConvergenceRecord? = entityManager.createQuery(
        """
        select convergence from ActionConvergenceEntity convergence
        join fetch convergence.attempt attempt
        left join fetch attempt.entry
        where convergence.accountId = :accountId
          and convergence.result = :pending
          and convergence.activeMarker = 1
          and (convergence.nextProbeAt is null or convergence.nextProbeAt <= :now)
        order by convergence.nextProbeAt asc, convergence.id asc
        """.trimIndent(),
        ActionConvergenceEntity::class.java,
    ).setParameter("accountId", accountId)
        .setParameter("pending", ActionConvergenceResult.PENDING)
        .setParameter("now", now)
        .setMaxResults(1)
        .resultList
        .firstOrNull()
        ?.toRecord()

    @Transactional(readOnly = true)
    override fun get(attemptId: Long): ActionConvergenceRecord? = findConvergence(attemptId)?.toRecord()

    override fun save(record: ActionConvergenceRecord) {
        val entity = requireNotNull(findConvergence(record.attemptId)) {
            "Convergence attempt ${record.attemptId} does not exist."
        }
        entity.attempt.submittedAt = record.submittedAt
        entity.result = record.result
        entity.activeMarker = if (record.active) ActionConvergenceEntity.ACTIVE else null
        entity.successfulObservationCount = record.successfulObservationCount
        entity.firstPendingAt = record.firstPendingAt
        entity.nextProbeAt = record.nextProbeAt
        entity.reasonCode = record.reasonCode
        entity.finishedAt = record.finishedAt
        entity.updatedAt = record.updatedAt
    }

    override fun releaseSuppression(accountId: Long, attemptId: Long, releasedAt: Instant): Boolean {
        val entity = findConvergence(attemptId)?.takeIf {
            it.accountId == accountId &&
                it.result in setOf(ActionConvergenceResult.HELD, ActionConvergenceResult.RESULT_UNOBSERVED) &&
                it.suppressionReleasedAt == null
        } ?: return false
        entity.suppressionReleasedAt = releasedAt
        return true
    }

    @Transactional(readOnly = true)
    override fun activeBattleGate(accountId: Long): AccountBattleGate? =
        entityManager.find(AccountBattleGateEntity::class.java, accountId)
            ?.takeIf { it.resolvedAt == null }
            ?.toDomain()

    override fun openBattleGate(
        accountId: Long,
        challengeId: Long?,
        reason: String,
        now: Instant,
    ): AccountBattleGate {
        val existing = entityManager.find(AccountBattleGateEntity::class.java, accountId)
        if (existing != null && existing.resolvedAt == null) return existing.toDomain()
        if (existing != null) {
            existing.challengeId = challengeId
            existing.reason = reason
            existing.openedAt = now
            existing.resolvedAt = null
            return existing.toDomain()
        }
        val account = requireNotNull(entityManager.find(HofAccountEntity::class.java, accountId)) {
            "Account $accountId does not exist."
        }
        return AccountBattleGateEntity(
            accountId = accountId,
            account = account,
            challengeId = challengeId,
            reason = reason,
            openedAt = now,
        ).also(entityManager::persist).toDomain()
    }

    override fun releaseBattleGate(accountId: Long, resolvedAt: Instant): Boolean {
        val gate = entityManager.find(AccountBattleGateEntity::class.java, accountId)
            ?.takeIf { it.resolvedAt == null }
            ?: return false
        gate.resolvedAt = resolvedAt
        return true
    }

    private fun findAttempt(accountId: Long, executionIdentity: String): ActionConvergenceEntity? =
        entityManager.createQuery(
            """
            select convergence from ActionConvergenceEntity convergence
            join fetch convergence.attempt attempt
            left join fetch attempt.entry
            where attempt.account.id = :accountId and attempt.executionIdentity = :executionIdentity
            """.trimIndent(),
            ActionConvergenceEntity::class.java,
        ).setParameter("accountId", accountId)
            .setParameter("executionIdentity", executionIdentity)
            .resultList
            .firstOrNull()

    private fun findConvergence(attemptId: Long): ActionConvergenceEntity? =
        entityManager.createQuery(
            """
            select convergence from ActionConvergenceEntity convergence
            join fetch convergence.attempt attempt
            left join fetch attempt.entry
            where attempt.id = :attemptId
            """.trimIndent(),
            ActionConvergenceEntity::class.java,
        ).setParameter("attemptId", attemptId)
            .resultList
            .firstOrNull()

    private fun ActionConvergenceEntity.toRecord(): ActionConvergenceRecord {
        val attemptId = requireNotNull(attempt.id)
        val entryId = requireNotNull(attempt.entry?.id) {
            "Convergence attempt $attemptId no longer has an automation entry."
        }
        return ActionConvergenceRecord(
            attemptId = attemptId,
            accountId = accountId,
            selection = SelectedAutomationAction(
                entryId = entryId,
                executionIdentity = attempt.executionIdentity,
                actionKind = attempt.actionKind,
                scope = AutomationIsolationScope(attempt.scopeKind, attempt.scopeKey),
                policyVersion = attempt.policyVersion,
                baselineFingerprint = attempt.baselineFingerprint,
            ),
            result = result,
            submittedAt = attempt.submittedAt,
            successfulObservationCount = successfulObservationCount,
            firstPendingAt = firstPendingAt,
            nextProbeAt = nextProbeAt,
            reasonCode = reasonCode,
            finishedAt = finishedAt,
            updatedAt = updatedAt,
        )
    }

    private fun AccountBattleGateEntity.toDomain() = AccountBattleGate(
        accountId = accountId,
        challengeId = challengeId,
        reason = reason,
        openedAt = openedAt,
        resolvedAt = resolvedAt,
    )
}
