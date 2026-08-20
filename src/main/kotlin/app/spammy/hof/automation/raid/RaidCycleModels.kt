package app.spammy.hof.automation.raid

import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.automation.entity.RaidAutomationCycleStatus
import app.spammy.hof.automation.service.ResolvedAutomationParty
import java.time.Instant

enum class RaidIntentKind {
    RESET,
    REGISTER,
    START,
    BATTLE,
    REWARD,
    REFRESH,
}

sealed interface RaidIntent {
    val entryId: Long
    val raidId: String
    val raidName: String
    val kind: RaidIntentKind

    data class Town(
        override val entryId: Long,
        override val raidId: String,
        override val raidName: String,
        override val kind: RaidIntentKind,
        val requestRaidId: String? = raidId,
        val observedStatus: String? = null,
    ) : RaidIntent {
        init {
            require(kind != RaidIntentKind.BATTLE)
        }
    }

    data class Battle(
        override val entryId: Long,
        override val raidId: String,
        override val raidName: String,
        val categoryId: String,
        val mapCode: String,
        val presetMode: PresetSelectionMode,
        val presetId: Long,
        val party: ResolvedAutomationParty,
    ) : RaidIntent {
        override val kind: RaidIntentKind = RaidIntentKind.BATTLE
    }
}

data class RaidAttempt(
    val entryId: Long,
    val kind: RaidIntentKind,
    val raidId: String,
    val requestRaidId: String? = raidId,
)

enum class RaidObservedStatus {
    RECRUITING,
    WAITING,
    READY,
    IN_BATTLE,
    COMPLETED,
    CLOSED,
    TESTING,
    UNKNOWN,
}

data class RaidObservedBattle(
    val categoryId: String,
    val mapCode: String,
    val cooldownRemainingSeconds: Long? = null,
)

data class RaidObservedTarget(
    val id: String,
    val name: String,
    val playable: Boolean,
    val status: RaidObservedStatus,
    val statusText: String? = null,
    val waitSeconds: Int? = null,
    val joined: Boolean,
    val actions: Set<RaidIntentKind>,
    val battle: RaidObservedBattle? = null,
)

data class RaidObservation(
    val raids: List<RaidObservedTarget>,
    val applied: Boolean,
    val registrationWait: Boolean,
    val registrationWaitSeconds: Int? = null,
    val globalActions: Set<RaidIntentKind> = emptySet(),
    val resultMessages: List<String> = emptyList(),
)

sealed interface RaidResultObservation {
    data class Page(val value: RaidObservation) : RaidResultObservation
    data object BattleCompleted : RaidResultObservation
    data object ManualHandoff : RaidResultObservation
    data class LegacyCycleAbort(val reason: RaidCycleOutcomeKind) : RaidResultObservation
}

enum class RaidWaitReason {
    REGISTRATION_COOLDOWN,
    WAITING_TO_START,
    BATTLE_COOLDOWN,
    REWARD_CONFIRMATION,
    POST_REWARD_CHECK,
}

enum class RaidHoldReason {
    CONFIGURATION_MISSING,
    INVALID_PRESET,
    MANUAL_RAID_ACTIVE,
    TARGET_TEMPORARILY_MISSING,
    UNKNOWN_OR_CONFLICTING_STATE,
    ACTION_UNAVAILABLE,
}

enum class RaidCycleOutcomeKind {
    COMPLETED,
    ABORTED_CLOSED,
    ABORTED_REGISTRATION_LOST,
    HANDED_OFF_MANUAL,
    SUPERSEDED_BY_OBSERVED_RAID,
}

data class RaidCycleOutcome(
    val entryId: Long?,
    val raidId: String,
    val kind: RaidCycleOutcomeKind,
)

sealed interface RaidDirective {
    data class Execute(val intent: RaidIntent) : RaidDirective
    data class WaitUntil(
        val at: Instant,
        val reason: RaidWaitReason,
        val message: String,
        val entryId: Long,
        val raidId: String,
    ) : RaidDirective
    data class Hold(
        val reason: RaidHoldReason,
        val message: String,
        val recheckAt: Instant? = null,
        val entryId: Long? = null,
        val raidId: String? = null,
    ) : RaidDirective
    data class Complete(val outcome: RaidCycleOutcome) : RaidDirective
}

sealed interface RaidRecordResult {
    data class Recorded(val completion: RaidCycleOutcome? = null) : RaidRecordResult
    data class NotApplied(val message: String) : RaidRecordResult
    data class NeedsRecheck(val at: Instant, val message: String) : RaidRecordResult
}

data class RaidCycleTarget(
    val raidId: String,
    val name: String,
    val presetMode: PresetSelectionMode,
    val presetId: Long?,
    val executionOrder: Int,
    val party: ResolvedAutomationParty?,
)

data class RaidCycleSnapshot(
    val id: Long,
    val entryId: Long?,
    val raidId: String,
    val raidName: String,
    val status: RaidAutomationCycleStatus,
    val nextCheckAt: Instant?,
)

data class RaidCycleConfiguration(
    val entryId: Long,
    val enabled: Boolean,
    val targets: List<RaidCycleTarget>,
    val currentTargetKey: String?,
)

data class RaidCycleAccountState(
    val configuration: RaidCycleConfiguration?,
    val openCycle: RaidCycleSnapshot?,
)
