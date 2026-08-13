package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.*
import app.spammy.hof.automation.repository.*
import app.spammy.hof.common.time.TimeProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class AutomationContentProgressService(
    private val query: TypedAutomationQueryRepository,
    private val rotations: AutomationRotationStateCommandRepository,
    private val cycles: RaidAutomationCycleCommandRepository,
    private val runtimeStates: TypedAutomationRuntimeStateCommandRepository,
    private val timeProvider: TimeProvider,
) {
    @Transactional
    fun unionBattleCompleted(accountId: Long, entryId: Long, categoryId: String, mapCode: String) {
        val entry = requireEntry(accountId, entryId, AutomationType.UNION)
        val settings = query.findUnionSettings(entryId)
        val currentIndex = settings.indexOfFirst { it.categoryId == categoryId && it.mapCode == mapCode }
        if (currentIndex < 0 || settings.isEmpty()) return
        saveRotation(entry, "${settings[(currentIndex + 1) % settings.size].categoryId}:${settings[(currentIndex + 1) % settings.size].mapCode}")
    }

    @Transactional
    fun raidRegistered(accountId: Long, entryId: Long, raidId: String, raidName: String, waitSeconds: Int?) {
        check(query.findOpenRaidCycle(accountId) == null) { "An open raid cycle already exists." }
        val entry = requireEntry(accountId, entryId, AutomationType.RAID)
        val now = timeProvider.now()
        cycles.save(RaidAutomationCycleEntity(
            account = entry.account, entry = entry, raidId = raidId, raidName = raidName,
            status = RaidAutomationCycleStatus.REGISTERED_WAITING, lastObservedStatus = "REGISTERED",
            nextCheckAt = now.plusSeconds((waitSeconds ?: 30).coerceAtLeast(5).toLong()), startedAt = now, updatedAt = now,
        ))
    }

    @Transactional
    fun raidStarted(accountId: Long, raidId: String) = updateOpen(accountId, raidId) { cycle, now ->
        cycle.status = RaidAutomationCycleStatus.IN_BATTLE; cycle.lastObservedStatus = "IN_BATTLE"
        cycle.nextCheckAt = null; cycle.updatedAt = now
    }

    @Transactional
    fun raidRewarded(accountId: Long) {
        val cycle = query.findOpenRaidCycle(accountId) ?: return
        val now = timeProvider.now()
        cycle.status = RaidAutomationCycleStatus.REWARD_PENDING; cycle.lastObservedStatus = "REWARDED"
        cycle.nextCheckAt = null; cycle.updatedAt = now
        cycles.save(cycle)
    }

    @Transactional
    fun raidStatusRefreshed(accountId: Long) {
        val cycle = query.findOpenRaidCycle(accountId) ?: return
        check(cycle.status == RaidAutomationCycleStatus.REWARD_PENDING) {
            "Raid status refresh is only valid after reward collection."
        }
        val now = timeProvider.now()
        cycle.lastObservedStatus = "REWARD_STATUS_REFRESHED"
        cycle.nextCheckAt = now.plusSeconds(RAID_STATUS_REFRESH_INTERVAL_SECONDS)
        cycle.updatedAt = now
        cycles.save(cycle)
    }

    @Transactional
    fun raidReset(accountId: Long, raidId: String) {
        val cycle = query.findOpenRaidCycle(accountId) ?: return
        check(cycle.raidId == raidId) { "Raid cycle target changed unexpectedly." }
        val now = timeProvider.now()
        cycle.status = RaidAutomationCycleStatus.COMPLETED; cycle.lastObservedStatus = "COMPLETED"
        cycle.openMarker = null; cycle.finishedAt = now; cycle.nextCheckAt = null; cycle.updatedAt = now
        val entry = cycle.entry
        if (entry != null) {
            val targets = query.findRaidTargets(entry.id)
            val index = targets.indexOfFirst { it.raidId == cycle.raidId }
            if (index >= 0 && targets.isNotEmpty()) saveRotation(entry, targets[(index + 1) % targets.size].raidId, now)
        }
        cycles.save(cycle)
    }

    @Transactional
    fun raidClosed(accountId: Long, raidId: String) = updateOpen(accountId, raidId) { cycle, now ->
        cycle.status = RaidAutomationCycleStatus.ABORTED_CLOSED; cycle.lastObservedStatus = "CLOSED"
        cycle.openMarker = null; cycle.finishedAt = now; cycle.nextCheckAt = null; cycle.updatedAt = now
    }

    @Transactional
    fun finishDrainIfNoOpenCycle(accountId: Long) {
        if (query.findOpenRaidCycle(accountId) != null) return
        val now = timeProvider.now()
        val runtime = query.lockRuntimeState(accountId) ?: return
        if (runtime.lifecycleStatus != TypedAutomationLifecycle.DRAINING) return
        runtime.lifecycleStatus = runtime.requestedLifecycle ?: TypedAutomationLifecycle.STOPPED
        if (runtime.lifecycleStatus == TypedAutomationLifecycle.STOPPED && runtime.stopReason == null) runtime.stopReason = AutomationStopReason.MANUAL_STOP.name
        runtime.requestedLifecycle = null; runtime.nextAttemptAt = null; runtime.leaseToken = null; runtime.leaseUntil = null; runtime.updatedAt = now
        runtimeStates.save(runtime)
    }

    private fun updateOpen(accountId: Long, raidId: String, change: (RaidAutomationCycleEntity, Instant) -> Unit) {
        val cycle = query.findOpenRaidCycle(accountId) ?: error("Open raid cycle is missing.")
        check(cycle.raidId == raidId) { "Raid cycle target changed unexpectedly." }
        change(cycle, timeProvider.now()); cycles.save(cycle)
    }

    private fun requireEntry(accountId: Long, entryId: Long, type: AutomationType) =
        query.findEntry(accountId, entryId)?.takeIf { it.type == type }
            ?: throw AutomationConfigurationException("Automation entry is missing or has an invalid type.")

    private fun saveRotation(entry: AutomationEntryEntity, key: String, now: Instant = timeProvider.now()) {
        val state = query.findRotationState(entry.id)
        if (state == null) rotations.save(AutomationRotationStateEntity(entry = entry, currentTargetKey = key, updatedAt = now))
        else { state.currentTargetKey = key; state.updatedAt = now; rotations.save(state) }
    }

    private companion object {
        const val RAID_STATUS_REFRESH_INTERVAL_SECONDS = 30L
    }
}
