package app.spammy.hof.automation.raid

import app.spammy.hof.automation.entity.AutomationRotationStateEntity
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.automation.entity.RaidAutomationCycleEntity
import app.spammy.hof.automation.entity.RaidAutomationCycleStatus
import app.spammy.hof.automation.repository.AutomationRotationStateCommandRepository
import app.spammy.hof.automation.repository.RaidAutomationCycleCommandRepository
import app.spammy.hof.automation.repository.TypedAutomationQueryRepository
import app.spammy.hof.automation.service.ResolvedAutomationParty
import app.spammy.hof.automation.service.validAutomationPartyMembersByPreset
import app.spammy.hof.battle.dto.BattlePatternLoadRequest
import app.spammy.hof.party.repository.PartyPresetQueryRepository
import java.time.Instant
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class JpaRaidCycleStore(
    private val query: TypedAutomationQueryRepository,
    private val cycles: RaidAutomationCycleCommandRepository,
    private val rotations: AutomationRotationStateCommandRepository,
    private val presets: PartyPresetQueryRepository,
) : RaidCycleStore {
    @Transactional(readOnly = true)
    override fun load(accountId: Long): RaidCycleAccountState {
        val entry = query.findEntries(accountId).singleOrNull { it.type == AutomationType.RAID }
        val configuration = entry?.let {
            val presetRows = presets.findAllByAccountId(accountId)
            val members = presets.findMembersByPresetIds(presetRows.map { preset -> preset.id })
            val validMembers = validAutomationPartyMembersByPreset(
                members,
                presetId = { member -> member.preset.id },
                characterId = { member -> member.character?.hofCharacterId },
                patternSlotCode = { member -> member.patternSlot?.slotCode },
                canLoadPattern = { member -> member.patternSlot?.canLoad == true },
            )
            val parties = validMembers.mapValues { (_, configured) ->
                val ordered = configured.sortedBy { member -> member.slotIndex }
                ResolvedAutomationParty(
                    characterIds = ordered.map { member -> requireNotNull(member.character?.hofCharacterId) },
                    patternLoads = ordered.map { member ->
                        BattlePatternLoadRequest(
                            requireNotNull(member.character?.hofCharacterId),
                            requireNotNull(member.patternSlot?.slotCode).toInt(),
                        )
                    },
                )
            }
            val primaryPresetId = presetRows.singleOrNull { preset -> preset.isPrimary }?.id
            RaidCycleConfiguration(
                entryId = it.id,
                enabled = it.enabled,
                targets = query.findRaidTargets(it.id).map { target ->
                    val resolvedPresetId = when (target.presetMode) {
                        PresetSelectionMode.PRIMARY -> primaryPresetId
                        PresetSelectionMode.EXPLICIT -> target.partyPreset?.id
                    }?.takeIf(parties::containsKey)
                    RaidCycleTarget(
                        raidId = target.raidId,
                        name = target.displayName,
                        presetMode = target.presetMode,
                        presetId = resolvedPresetId,
                        executionOrder = target.executionOrder,
                        party = resolvedPresetId?.let(parties::get),
                    )
                },
                currentTargetKey = query.findRotationState(it.id)?.currentTargetKey,
            )
        }
        return RaidCycleAccountState(
            configuration = configuration,
            openCycle = query.findOpenRaidCycle(accountId)?.toSnapshot(),
        )
    }

    @Transactional
    override fun open(
        accountId: Long,
        entryId: Long,
        target: RaidCycleTarget,
        now: Instant,
        status: RaidAutomationCycleStatus,
        observedStatus: String?,
        nextCheckAt: Instant?,
    ): RaidCycleSnapshot {
        query.lockAccount(accountId)
        query.findOpenRaidCycle(accountId)?.let { return it.toSnapshot() }
        val entry = query.findEntry(accountId, entryId)
            ?.takeIf { it.type == AutomationType.RAID }
            ?: error("Raid automation entry $entryId is missing.")
        return cycles.save(
            RaidAutomationCycleEntity(
                account = entry.account,
                entry = entry,
                raidId = target.raidId,
                raidName = target.name,
                status = status,
                lastObservedStatus = observedStatus?.take(LAST_OBSERVED_STATUS_LENGTH),
                nextCheckAt = nextCheckAt,
                startedAt = now,
                updatedAt = now,
            ),
        ).toSnapshot()
    }

    @Transactional
    override fun transition(
        accountId: Long,
        raidId: String,
        status: RaidAutomationCycleStatus,
        observedStatus: String?,
        nextCheckAt: Instant?,
        now: Instant,
    ): RaidCycleSnapshot {
        query.lockAccount(accountId)
        val cycle = requireOpenCycle(accountId, raidId)
        cycle.status = status
        cycle.lastObservedStatus = observedStatus?.take(LAST_OBSERVED_STATUS_LENGTH)
        cycle.nextCheckAt = nextCheckAt
        cycle.updatedAt = now
        return cycles.save(cycle).toSnapshot()
    }

    @Transactional
    override fun saveBattleRecovery(
        accountId: Long,
        raidId: String,
        recovery: RaidBattleRecovery,
        now: Instant,
    ): RaidCycleSnapshot {
        query.lockAccount(accountId)
        val cycle = requireOpenCycle(accountId, raidId)
        cycle.battleRecoveryChainId = recovery.chainId
        cycle.battleRecoveryOriginalExecutionIdentity = recovery.originalExecutionIdentity
        cycle.battleRecoveryLatestExecutionIdentity = recovery.latestExecutionIdentity
        cycle.battleRecoveryFirstAmbiguousAt = recovery.firstAmbiguousAt
        cycle.battleRecoveryLastSubmittedAt = recovery.lastSubmittedAt
        cycle.battleRecoveryRetransmissionCount = recovery.retransmissionCount
        cycle.battleRecoveryNextCheckAt = recovery.nextCheckAt
        cycle.battleRecoveryCategoryId = recovery.categoryId
        cycle.battleRecoveryMapCode = recovery.mapCode
        cycle.battleRecoverySubmittedFromRunnable = recovery.submittedFromRunnable
        cycle.battleRecoveryLastObservation = recovery.lastObservation
        cycle.updatedAt = now
        return cycles.save(cycle).toSnapshot()
    }

    @Transactional
    override fun clearBattleRecovery(accountId: Long, raidId: String, now: Instant): RaidCycleSnapshot {
        query.lockAccount(accountId)
        val cycle = requireOpenCycle(accountId, raidId)
        cycle.clearBattleRecovery()
        cycle.updatedAt = now
        return cycles.save(cycle).toSnapshot()
    }

    @Transactional
    override fun saveBattleSafetyGate(
        accountId: Long,
        raidId: String,
        gate: RaidBattleSafetyGate,
        now: Instant,
    ): RaidCycleSnapshot {
        query.lockAccount(accountId)
        val cycle = requireOpenCycle(accountId, raidId)
        cycle.battleCooldownNotBefore = gate.notBefore
        cycle.battleCooldownSource = gate.source
        cycle.battleCooldownStartedAt = gate.startedAt
        cycle.battleCooldownRaidId = gate.raidId
        cycle.battleCooldownCategoryId = gate.categoryId
        cycle.battleCooldownMapCode = gate.mapCode
        cycle.battleCooldownExecutionIdentity = gate.executionIdentity
        cycle.battleCooldownFirstIncompleteAt = gate.firstIncompleteAt
        cycle.battleCooldownIncompleteObservations = gate.successfulIncompleteObservations
        cycle.battleCooldownLastObservedAt = gate.lastObservedAt
        cycle.battleCooldownEvidenceCaseId = gate.evidenceCaseId
        cycle.battleCooldownHeld = gate.held
        cycle.battleSafetyVersion = CURRENT_RAID_BATTLE_SAFETY_VERSION
        cycle.updatedAt = now
        return cycles.save(cycle).toSnapshot()
    }

    @Transactional
    override fun clearBattleSafetyGate(accountId: Long, raidId: String, now: Instant): RaidCycleSnapshot {
        query.lockAccount(accountId)
        val cycle = requireOpenCycle(accountId, raidId)
        cycle.clearBattleSafetyGate()
        cycle.battleSafetyVersion = CURRENT_RAID_BATTLE_SAFETY_VERSION
        cycle.updatedAt = now
        return cycles.save(cycle).toSnapshot()
    }

    @Transactional
    override fun saveRewardRecovery(
        accountId: Long,
        raidId: String,
        recovery: RaidRewardRecovery,
        now: Instant,
    ): RaidCycleSnapshot {
        query.lockAccount(accountId)
        val cycle = requireOpenCycle(accountId, raidId)
        cycle.rewardRecoveryKind = recovery.kind
        cycle.rewardRecoveryExecutionIdentity = recovery.executionIdentity
        cycle.rewardRecoveryFirstAmbiguousAt = recovery.firstAmbiguousAt
        cycle.rewardRecoveryObservationCount = recovery.successfulObservationCount
        cycle.rewardRecoveryRetryCount = recovery.retryCount
        cycle.rewardRecoveryHeld = recovery.held
        cycle.updatedAt = now
        return cycles.save(cycle).toSnapshot()
    }

    @Transactional
    override fun clearRewardRecovery(accountId: Long, raidId: String, now: Instant): RaidCycleSnapshot {
        query.lockAccount(accountId)
        val cycle = requireOpenCycle(accountId, raidId)
        cycle.clearRewardRecovery()
        cycle.updatedAt = now
        return cycles.save(cycle).toSnapshot()
    }

    @Transactional
    override fun finish(
        accountId: Long,
        raidId: String,
        outcome: RaidCycleOutcomeKind,
        now: Instant,
        advanceRotation: Boolean,
    ): RaidCycleOutcome {
        query.lockAccount(accountId)
        val cycle = requireOpenCycle(accountId, raidId)
        cycle.status = outcome.toPersistedStatus()
        cycle.lastObservedStatus = outcome.name
        cycle.openMarker = null
        cycle.nextCheckAt = null
        cycle.clearBattleRecovery()
        cycle.clearBattleSafetyGate()
        cycle.clearRewardRecovery()
        cycle.finishedAt = now
        cycle.updatedAt = now
        if (advanceRotation) advanceRotation(cycle, now)
        cycles.save(cycle)
        return RaidCycleOutcome(cycle.entry?.id, cycle.raidId, outcome)
    }

    private fun requireOpenCycle(accountId: Long, raidId: String): RaidAutomationCycleEntity {
        val cycle = query.findOpenRaidCycle(accountId) ?: error("Open raid cycle is missing.")
        check(cycle.raidId == raidId) { "Raid cycle target changed unexpectedly." }
        return cycle
    }

    private fun advanceRotation(cycle: RaidAutomationCycleEntity, now: Instant) {
        val entry = cycle.entry ?: return
        val targets = query.findRaidTargets(entry.id)
        val currentIndex = targets.indexOfFirst { target -> target.raidId == cycle.raidId }
        if (currentIndex < 0 || targets.isEmpty()) return
        val next = targets[(currentIndex + 1) % targets.size].raidId
        val rotation = query.findRotationState(entry.id)
        if (rotation == null) {
            rotations.save(AutomationRotationStateEntity(entry = entry, currentTargetKey = next, updatedAt = now))
        } else {
            rotation.currentTargetKey = next
            rotation.updatedAt = now
            rotations.save(rotation)
        }
    }

    private fun RaidAutomationCycleEntity.toSnapshot() = RaidCycleSnapshot(
        id = id,
        entryId = entry?.id,
        raidId = raidId,
        raidName = raidName,
        status = status,
        nextCheckAt = nextCheckAt,
        battleRecovery = toBattleRecoveryOrNull(),
        battleSafetyGate = toBattleSafetyGateOrNull(),
        battleSafetyVersion = battleSafetyVersion,
        rewardRecovery = toRewardRecoveryOrNull(),
    )

    private fun RaidCycleOutcomeKind.toPersistedStatus(): RaidAutomationCycleStatus = when (this) {
        RaidCycleOutcomeKind.COMPLETED -> RaidAutomationCycleStatus.COMPLETED
        RaidCycleOutcomeKind.ABORTED_CLOSED -> RaidAutomationCycleStatus.ABORTED_CLOSED
        RaidCycleOutcomeKind.ABORTED_REGISTRATION_LOST -> RaidAutomationCycleStatus.ABORTED_REGISTRATION_LOST
        RaidCycleOutcomeKind.HANDED_OFF_MANUAL -> RaidAutomationCycleStatus.HANDED_OFF_MANUAL
        RaidCycleOutcomeKind.SUPERSEDED_BY_OBSERVED_RAID -> RaidAutomationCycleStatus.SUPERSEDED_BY_OBSERVED_RAID
    }

    private companion object {
        const val LAST_OBSERVED_STATUS_LENGTH = 32
    }
}

internal fun RaidAutomationCycleEntity.toBattleRecoveryOrNull(): RaidBattleRecovery? =
    battleRecoveryChainId?.let { chainId ->
        RaidBattleRecovery(
            chainId = chainId,
            raidId = raidId,
            categoryId = requireNotNull(battleRecoveryCategoryId),
            mapCode = requireNotNull(battleRecoveryMapCode),
            originalExecutionIdentity = requireNotNull(battleRecoveryOriginalExecutionIdentity),
            latestExecutionIdentity = requireNotNull(battleRecoveryLatestExecutionIdentity),
            firstAmbiguousAt = requireNotNull(battleRecoveryFirstAmbiguousAt),
            lastSubmittedAt = requireNotNull(battleRecoveryLastSubmittedAt),
            retransmissionCount = requireNotNull(battleRecoveryRetransmissionCount),
            nextCheckAt = requireNotNull(battleRecoveryNextCheckAt),
            submittedFromRunnable = requireNotNull(battleRecoverySubmittedFromRunnable),
            lastObservation = requireNotNull(battleRecoveryLastObservation),
        )
    }

internal fun RaidAutomationCycleEntity.toBattleSafetyGateOrNull(): RaidBattleSafetyGate? =
    battleCooldownNotBefore?.let { notBefore ->
        RaidBattleSafetyGate(
            raidId = requireNotNull(battleCooldownRaidId),
            categoryId = battleCooldownCategoryId,
            mapCode = battleCooldownMapCode,
            executionIdentity = battleCooldownExecutionIdentity,
            startedAt = requireNotNull(battleCooldownStartedAt),
            notBefore = notBefore,
            source = requireNotNull(battleCooldownSource),
            firstIncompleteAt = battleCooldownFirstIncompleteAt,
            successfulIncompleteObservations = battleCooldownIncompleteObservations ?: 0,
            lastObservedAt = battleCooldownLastObservedAt,
            evidenceCaseId = battleCooldownEvidenceCaseId,
            held = battleCooldownHeld,
        )
    }

internal fun RaidAutomationCycleEntity.toRewardRecoveryOrNull(): RaidRewardRecovery? =
    rewardRecoveryFirstAmbiguousAt?.let { firstAmbiguousAt ->
        val kind = rewardRecoveryKind ?: rewardRecoveryExecutionIdentity
            ?.let { RaidRewardRecoveryKind.ACTION_RESULT }
            ?: return null
        RaidRewardRecovery(
            executionIdentity = rewardRecoveryExecutionIdentity,
            firstAmbiguousAt = firstAmbiguousAt,
            successfulObservationCount = requireNotNull(rewardRecoveryObservationCount),
            retryCount = requireNotNull(rewardRecoveryRetryCount),
            held = rewardRecoveryHeld,
            kind = kind,
        )
    }
