package app.spammy.hof.automation.raid

import app.spammy.hof.automation.entity.RaidAutomationCycleStatus
import java.time.Instant

interface RaidCycleStore {
    fun load(accountId: Long): RaidCycleAccountState

    fun open(
        accountId: Long,
        entryId: Long,
        target: RaidCycleTarget,
        now: Instant,
        status: RaidAutomationCycleStatus = RaidAutomationCycleStatus.PREPARING,
        observedStatus: String? = null,
        nextCheckAt: Instant? = null,
    ): RaidCycleSnapshot

    fun transition(
        accountId: Long,
        raidId: String,
        status: RaidAutomationCycleStatus,
        observedStatus: String?,
        nextCheckAt: Instant?,
        now: Instant,
    ): RaidCycleSnapshot

    fun saveBattleRecovery(
        accountId: Long,
        raidId: String,
        recovery: RaidBattleRecovery,
        now: Instant,
    ): RaidCycleSnapshot

    fun clearBattleRecovery(
        accountId: Long,
        raidId: String,
        now: Instant,
    ): RaidCycleSnapshot

    fun finish(
        accountId: Long,
        raidId: String,
        outcome: RaidCycleOutcomeKind,
        now: Instant,
        advanceRotation: Boolean = false,
    ): RaidCycleOutcome
}
