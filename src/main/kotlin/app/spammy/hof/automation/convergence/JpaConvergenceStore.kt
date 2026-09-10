package app.spammy.hof.automation.convergence

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.automation.entity.AutomationEntryEntity
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
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
            observationOnly = selection.observationOnly,
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
        join fetch attempt.entry entry
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
            where convergence.accountId = :accountId
              and convergence.activeMarker = 1
              and convergence.attempt.entry is not null
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
            join attempt.entry entry
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
    override fun findDue(accountId: Long, now: Instant, excludedExecutionIdentities: Set<String>): ActionConvergenceRecord? = entityManager.createQuery(
        """
        select convergence from ActionConvergenceEntity convergence
        join fetch convergence.attempt attempt
        join fetch attempt.entry entry
        where convergence.accountId = :accountId
          and convergence.result = :pending
          and convergence.activeMarker = 1
          and attempt.observationOnly = false
          ${if (excludedExecutionIdentities.isNotEmpty()) "and attempt.executionIdentity not in :excludedExecutionIdentities" else ""}
          and (convergence.nextProbeAt is null or convergence.nextProbeAt <= :now)
        order by entry.priority asc,
                 convergence.nextProbeAt asc,
                 convergence.id asc
        """.trimIndent(),
        ActionConvergenceEntity::class.java,
    ).setParameter("accountId", accountId)
        .setParameter("pending", ActionConvergenceResult.PENDING)
        .setParameter("now", now)
        .also { if (excludedExecutionIdentities.isNotEmpty()) it.setParameter("excludedExecutionIdentities", excludedExecutionIdentities) }
        .setMaxResults(1)
        .resultList
        .firstOrNull()
        ?.toRecord()

    override fun normalizeOrphans(accountId: Long, now: Instant): Int {
        val deletedEntries = entityManager.createQuery(
            """
            update ActionConvergenceEntity convergence
            set convergence.result = :superseded,
                convergence.activeMarker = null,
                convergence.nextProbeAt = null,
                convergence.reasonCode = :deletedReason,
                convergence.finishedAt = :now,
                convergence.updatedAt = :now
            where convergence.accountId = :accountId
              and convergence.activeMarker = 1
              and convergence.attempt.entry is null
            """.trimIndent(),
        ).setParameter("superseded", ActionConvergenceResult.SUPERSEDED)
            .setParameter("deletedReason", "AUTOMATION_ENTRY_DELETED")
            .setParameter("now", now)
            .setParameter("accountId", accountId)
            .executeUpdate()
        val missingResults = entityManager.createQuery(
            """
            update ActionConvergenceEntity convergence
            set convergence.result = :pending,
                convergence.firstPendingAt = coalesce(convergence.firstPendingAt, :now),
                convergence.nextProbeAt = :now,
                convergence.reasonCode = :orphanReason,
                convergence.updatedAt = :now
            where convergence.accountId = :accountId
              and convergence.activeMarker = 1
              and convergence.result is null
            """.trimIndent(),
        ).setParameter("pending", ActionConvergenceResult.PENDING)
            .setParameter("now", now)
            .setParameter("orphanReason", "ORPHAN_RESULT_RECONCILED")
            .setParameter("accountId", accountId)
            .executeUpdate()
        return deletedEntries + missingResults
    }

    @Transactional(readOnly = true)
    override fun get(attemptId: Long): ActionConvergenceRecord? = findConvergence(attemptId)
        ?.takeIf { it.attempt.entry != null }
        ?.toRecord()

    @Transactional(readOnly = true)
    override fun get(accountId: Long, executionIdentity: String): ActionConvergenceRecord? =
        findAttempt(accountId, executionIdentity)?.toRecord()

    override fun <T> withLockedAttempt(attemptId: Long, update: (ActionConvergenceRecord) -> T): T? {
        val entity = entityManager.createQuery(
            "select convergence from ActionConvergenceEntity convergence where convergence.attempt.id = :attemptId",
            ActionConvergenceEntity::class.java,
        ).setParameter("attemptId", attemptId).resultList.firstOrNull() ?: return null
        // 같은 transaction의 이전 조회가 영속성 컨텍스트에 있어도 잠금 이후 상태로 판정한다.
        entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE)
        entityManager.refresh(entity.attempt)
        return entity.takeIf { it.attempt.entry != null }?.toRecord()?.let(update)
    }

    override fun save(record: ActionConvergenceRecord) {
        val entity = requireNotNull(findConvergence(record.attemptId)) {
            "Convergence attempt ${record.attemptId} does not exist."
        }
        entity.attempt.submittedAt = record.submittedAt
        entity.result = record.result ?: ActionConvergenceResult.PENDING
        entity.activeMarker = if (record.active) ActionConvergenceEntity.ACTIVE else null
        entity.successfulObservationCount = record.successfulObservationCount
        entity.firstPendingAt = record.firstPendingAt
        entity.nextProbeAt = record.nextProbeAt
        entity.reasonCode = record.reasonCode
        entity.finishedAt = record.finishedAt
        entity.updatedAt = record.updatedAt
    }

    @Transactional(readOnly = true)
    override fun findPolicyHeldScopes(accountId: Long): Set<AutomationIsolationScope> =
        entityManager.createQuery(
            """
            select convergence from ActionConvergenceEntity convergence
            join fetch convergence.attempt attempt
            join attempt.entry entry
            where convergence.accountId = :accountId
              and convergence.result in :results
              and convergence.suppressionReleasedAt is null
            """.trimIndent(),
            ActionConvergenceEntity::class.java,
        ).setParameter("accountId", accountId)
            .setParameter("results", setOf(ActionConvergenceResult.HELD, ActionConvergenceResult.RESULT_UNOBSERVED))
            .resultList
            .filterNot { ProductionActionEvidenceInterpreter.supportsVersion(it.attempt.policyVersion) }
            .map { AutomationIsolationScope(it.scopeKind, it.scopeKey) }
            .toSet()

    override fun releaseSupersededSuppressions(
        accountId: Long,
        scope: AutomationIsolationScope,
        currentBaselineFingerprints: Set<String>,
        releasedAt: Instant,
    ): Int {
        val superseded = entityManager.createQuery(
            """
            select convergence from ActionConvergenceEntity convergence
            join fetch convergence.attempt attempt
            where convergence.accountId = :accountId
              and convergence.scopeKind = :scopeKind
              and convergence.scopeKey = :scopeKey
              and convergence.result in :results
              and convergence.suppressionReleasedAt is null
              and attempt.baselineFingerprint not in :currentBaselineFingerprints
            """.trimIndent(),
            ActionConvergenceEntity::class.java,
        ).setParameter("accountId", accountId)
            .setParameter("scopeKind", scope.kind)
            .setParameter("scopeKey", scope.key)
            .setParameter(
                "results",
                setOf(ActionConvergenceResult.HELD, ActionConvergenceResult.RESULT_UNOBSERVED),
            )
            .setParameter("currentBaselineFingerprints", currentBaselineFingerprints)
            .resultList
            .filter { ProductionActionEvidenceInterpreter.supportsVersion(it.attempt.policyVersion) }
        superseded.forEach { it.suppressionReleasedAt = releasedAt }
        return superseded.size
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

    override fun releaseRaidRegistrationSuppressions(
        accountId: Long,
        entryId: Long,
        raidId: String,
        observedAt: Instant,
    ): Int {
        val held = entityManager.createQuery(
            """
            select convergence from ActionConvergenceEntity convergence
            join fetch convergence.attempt attempt
            where convergence.accountId = :accountId
              and attempt.entry.id = :entryId
              and attempt.actionKind = :actionKind
              and convergence.scopeKind = :scopeKind
              and convergence.scopeKey = :scopeKey
              and convergence.result in :results
              and convergence.suppressionReleasedAt is null
            """.trimIndent(),
            ActionConvergenceEntity::class.java,
        ).setParameter("accountId", accountId)
            .setParameter("entryId", entryId)
            .setParameter("actionKind", AutomationActionKind.RAID_REGISTER)
            .setParameter("scopeKind", AutomationIsolationScopeKind.RAID_ENTRY)
            .setParameter("scopeKey", raidId)
            .setParameter("results", setOf(ActionConvergenceResult.HELD, ActionConvergenceResult.RESULT_UNOBSERVED))
            .resultList
            .filter { ProductionActionEvidenceInterpreter.supportsVersion(it.attempt.policyVersion) }
        held.forEach {
            it.suppressionReleasedAt = observedAt
            it.reasonCode = "RAID_REGISTRATION_FRESH_DECISION_RELEASED"
            it.updatedAt = observedAt
        }
        return held.size
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
            join fetch attempt.entry
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
                observationOnly = attempt.observationOnly,
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
