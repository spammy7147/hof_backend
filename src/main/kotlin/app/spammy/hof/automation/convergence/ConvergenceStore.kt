package app.spammy.hof.automation.convergence

import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

interface ConvergenceStore {
    fun createOrGet(accountId: Long, selection: SelectedAutomationAction, now: Instant): ActionConvergenceRecord
    fun findActive(accountId: Long, scope: AutomationIsolationScope): ActionConvergenceRecord?
    fun findActiveScopes(accountId: Long): Set<AutomationIsolationScope>
    fun findSuppressedBaselines(accountId: Long): Map<AutomationIsolationScope, Set<String>>
    fun findDue(accountId: Long, now: Instant): ActionConvergenceRecord?
    fun get(attemptId: Long): ActionConvergenceRecord?
    fun save(record: ActionConvergenceRecord)
    fun releaseSuppression(accountId: Long, attemptId: Long, releasedAt: Instant): Boolean
    fun activeBattleGate(accountId: Long): AccountBattleGate?
    fun openBattleGate(accountId: Long, challengeId: Long?, reason: String, now: Instant): AccountBattleGate
    fun releaseBattleGate(accountId: Long, resolvedAt: Instant): Boolean
}

class InMemoryConvergenceStore : ConvergenceStore {
    private val ids = AtomicLong()
    private val records = linkedMapOf<Long, ActionConvergenceRecord>()
    private val gates = linkedMapOf<Long, AccountBattleGate>()
    private val releasedSuppressions = mutableSetOf<Pair<Long, Long>>()

    @Synchronized
    override fun createOrGet(
        accountId: Long,
        selection: SelectedAutomationAction,
        now: Instant,
    ): ActionConvergenceRecord = records.values.singleOrNull {
        it.accountId == accountId && it.selection.executionIdentity == selection.executionIdentity
    } ?: ActionConvergenceRecord(
        attemptId = ids.incrementAndGet(),
        accountId = accountId,
        selection = selection,
        updatedAt = now,
    ).also { records[it.attemptId] = it }

    @Synchronized
    override fun findActive(
        accountId: Long,
        scope: AutomationIsolationScope,
    ): ActionConvergenceRecord? = records.values
        .filter { it.accountId == accountId && it.selection.scope == scope && it.active }
        .maxByOrNull(ActionConvergenceRecord::attemptId)

    @Synchronized
    override fun findActiveScopes(accountId: Long): Set<AutomationIsolationScope> = records.values
        .asSequence()
        .filter { it.accountId == accountId && it.active }
        .map { it.selection.scope }
        .toSet()

    @Synchronized
    override fun findSuppressedBaselines(accountId: Long): Map<AutomationIsolationScope, Set<String>> = records.values
        .asSequence()
        .filter {
            it.accountId == accountId &&
                it.result in setOf(ActionConvergenceResult.HELD, ActionConvergenceResult.RESULT_UNOBSERVED) &&
                (accountId to it.attemptId) !in releasedSuppressions
        }
        .groupBy({ it.selection.scope }, { it.selection.baselineFingerprint })
        .mapValues { (_, values) -> values.toSet() }

    @Synchronized
    override fun findDue(accountId: Long, now: Instant): ActionConvergenceRecord? = records.values
        .asSequence()
        .filter { it.accountId == accountId && it.result == ActionConvergenceResult.PENDING }
        .filter { it.nextProbeAt?.isAfter(now) != true }
        .minWithOrNull(compareBy<ActionConvergenceRecord> { it.nextProbeAt }.thenBy { it.attemptId })

    @Synchronized
    override fun get(attemptId: Long): ActionConvergenceRecord? = records[attemptId]

    @Synchronized
    override fun save(record: ActionConvergenceRecord) {
        records[record.attemptId] = record
    }

    @Synchronized
    override fun releaseSuppression(accountId: Long, attemptId: Long, releasedAt: Instant): Boolean {
        val record = records[attemptId]?.takeIf {
            it.accountId == accountId &&
                it.result in setOf(ActionConvergenceResult.HELD, ActionConvergenceResult.RESULT_UNOBSERVED)
        } ?: return false
        releasedSuppressions += accountId to record.attemptId
        return true
    }

    @Synchronized
    override fun activeBattleGate(accountId: Long): AccountBattleGate? = gates[accountId]?.takeIf { it.active }

    @Synchronized
    override fun openBattleGate(
        accountId: Long,
        challengeId: Long?,
        reason: String,
        now: Instant,
    ): AccountBattleGate = gates[accountId]?.takeIf { it.active } ?: AccountBattleGate(
        accountId = accountId,
        challengeId = challengeId,
        reason = reason,
        openedAt = now,
    ).also { gates[accountId] = it }

    @Synchronized
    override fun releaseBattleGate(accountId: Long, resolvedAt: Instant): Boolean {
        val gate = gates[accountId]?.takeIf { it.active } ?: return false
        gate.resolvedAt = resolvedAt
        return true
    }
}
