package app.spammy.hof.automation.raid

import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.automation.entity.RaidAutomationCycleStatus
import app.spammy.hof.automation.service.ResolvedAutomationParty
import app.spammy.hof.automation.service.AutomationDiagnosticKind
import app.spammy.hof.automation.service.AutomationImpactScope
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
        val recoveryChainId: String? = null,
        val retransmissionCount: Int = 0,
        val submittedFromRunnable: Boolean = true,
    ) : RaidIntent {
        override val kind: RaidIntentKind = RaidIntentKind.BATTLE
    }
}

data class RaidAttempt(
    val entryId: Long,
    val kind: RaidIntentKind,
    val raidId: String,
    val requestRaidId: String? = raidId,
    val executionIdentity: String? = null,
    val categoryId: String? = null,
    val mapCode: String? = null,
    val recoveryChainId: String? = null,
    val retransmissionCount: Int = 0,
    val submittedAt: Instant? = null,
    val finishedAt: Instant? = null,
    val submittedFromRunnable: Boolean = false,
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
    val cooldownSource: RaidCooldownSource? = cooldownRemainingSeconds
        ?.takeIf { it > 0 }
        ?.let { RaidCooldownSource.HOF_DIRECT },
)

enum class RaidCooldownSource {
    HOF_DIRECT,
    HOF_SINGLE_TARGET_INFERENCE,
    LOCAL_FALLBACK,
    DEPLOYMENT_FALLBACK,
}

enum class RaidBattleAvailability {
    RUNNABLE,
    COOLDOWN,
    ABSENT,
    INCOMPLETE,
}

sealed interface RaidRewardWindowObservation {
    data object Available : RaidRewardWindowObservation
    data class ClaimWindow(val remainingSeconds: Long) : RaidRewardWindowObservation
    data object Absent : RaidRewardWindowObservation
    data class Incomplete(val evidenceCaseId: String? = null) : RaidRewardWindowObservation
}

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
    val battleAvailability: RaidBattleAvailability = when {
        battle == null -> RaidBattleAvailability.INCOMPLETE
        battle.cooldownRemainingSeconds?.let { it > 0 } == true -> RaidBattleAvailability.COOLDOWN
        else -> RaidBattleAvailability.RUNNABLE
    },
    val battleEvidenceCaseId: String? = null,
    val rewardWindow: RaidRewardWindowObservation = when {
        status == RaidObservedStatus.COMPLETED && waitSeconds?.let { it > 0 } == true ->
            RaidRewardWindowObservation.ClaimWindow(waitSeconds.toLong())
        status == RaidObservedStatus.COMPLETED && RaidIntentKind.REWARD in actions ->
            RaidRewardWindowObservation.Available
        status == RaidObservedStatus.COMPLETED -> RaidRewardWindowObservation.Incomplete()
        else -> RaidRewardWindowObservation.Absent
    },
)

data class RaidObservation(
    val raids: List<RaidObservedTarget>,
    val applied: Boolean,
    val registrationWait: Boolean,
    val registrationWaitSeconds: Int? = null,
    val globalActions: Set<RaidIntentKind> = emptySet(),
    val resultMessages: List<String> = emptyList(),
    val observedAt: Instant? = null,
    val fresh: Boolean = true,
    val actionSuccessMarker: Boolean = false,
    val rewardResult: RaidRewardResultKind? = null,
    val registrationStateObserved: Boolean = false,
    val directRefreshResponse: Boolean = false,
)

enum class RaidRewardResultKind {
    RECEIVED,
    NOTHING_AVAILABLE,
}

sealed interface RaidResultObservation {
    data class Page(val value: RaidObservation) : RaidResultObservation
    data object BattleCompleted : RaidResultObservation
    data class BattleAmbiguous(val reason: String) : RaidResultObservation
    data object ManualHandoff : RaidResultObservation
    data object ManualStop : RaidResultObservation
    data class LegacyCycleAbort(val reason: RaidCycleOutcomeKind) : RaidResultObservation
}

enum class RaidWaitReason {
    REGISTRATION_COOLDOWN,
    WAITING_TO_START,
    BATTLE_COOLDOWN,
    BATTLE_APPLIED_COOLDOWN,
    BATTLE_RECOVERY_RECHECK,
    REWARD_CONFIRMATION,
    POST_REWARD_CHECK,
}

enum class RaidHoldReason {
    CONFIGURATION_MISSING,
    INVALID_PRESET,
    MANUAL_RAID_ACTIVE,
    EXTERNAL_RAID_ACTIVE,
    TARGET_TEMPORARILY_MISSING,
    UNKNOWN_OR_CONFLICTING_STATE,
    ACTION_UNAVAILABLE,
    BATTLE_OBSERVATION_INCOMPLETE,
    BATTLE_TARGET_ABSENT,
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

data class RaidAuthoritativeState(
    val raidId: String,
    val target: RaidAuthoritativeTargetState?,
    val registrationWait: Boolean,
    val registrationWaitSeconds: Int?,
    val globalActions: Set<RaidIntentKind>,
) {
    init {
        require(raidId.isNotBlank()) { "Raid authoritative state id must not be blank." }
    }
}

data class RaidAuthoritativeTargetState(
    val status: RaidObservedStatus,
    val joined: Boolean,
    val playable: Boolean,
    val waitSeconds: Int?,
    val actions: Set<RaidIntentKind>,
    val battleAvailability: RaidBattleAvailability,
    val battleCategoryId: String?,
    val battleMapCode: String?,
    val battleCooldownRemainingSeconds: Long?,
    val rewardWindow: RaidAuthoritativeRewardWindow,
)

data class RaidAuthoritativeRewardWindow(
    val kind: RaidAuthoritativeRewardWindowKind,
    val remainingSeconds: Long? = null,
)

enum class RaidAuthoritativeRewardWindowKind {
    AVAILABLE,
    ABSENT,
    CLAIM_WINDOW,
    INCOMPLETE,
}

data class RaidDecision(
    val directive: RaidDirective,
    val authoritativeState: RaidAuthoritativeState? = null,
)

sealed interface RaidDirective {
    data class Execute(
        val intent: RaidIntent,
        val reasonCode: String? = null,
        val message: String? = null,
        val warning: String? = null,
    ) : RaidDirective
    data class WaitUntil(
        val at: Instant,
        val reason: RaidWaitReason,
        val message: String,
        val entryId: Long,
        val raidId: String,
        val cooldownSource: RaidCooldownSource? = null,
        val impactScope: AutomationImpactScope? = null,
        val releaseCondition: String? = null,
        val reasonCode: String? = null,
    ) : RaidDirective
    data class Hold(
        val reason: RaidHoldReason,
        val message: String,
        val recheckAt: Instant? = null,
        val entryId: Long? = null,
        val raidId: String? = null,
        val reasonCode: String? = null,
        val diagnosticKind: AutomationDiagnosticKind? = null,
        val impactScope: AutomationImpactScope? = null,
        val releaseCondition: String? = null,
    ) : RaidDirective
    data class Complete(
        val outcome: RaidCycleOutcome,
        val reasonCode: String? = null,
        val message: String? = null,
    ) : RaidDirective
}

sealed interface RaidRecordResult {
    data class Recorded(val completion: RaidCycleOutcome? = null) : RaidRecordResult
    data class EntryWait(
        val at: Instant,
        val raidId: String,
        val message: String,
        val completion: RaidCycleOutcome? = null,
        val reasonCode: String = "RAID_ENTRY_WAIT",
        val releaseCondition: String = "예약 시각 뒤 최신 레이드 상태 재확인",
        val warning: String? = raidEntryWaitWarning(reasonCode, message),
    ) : RaidRecordResult
    data class EntrySkipped(val message: String) : RaidRecordResult
    data class NotApplied(val message: String) : RaidRecordResult
    data class FreshDecision(val message: String) : RaidRecordResult
    data class NeedsRecheck(val at: Instant, val message: String) : RaidRecordResult
    data class BattleRecoveryStarted(val at: Instant, val message: String) : RaidRecordResult
    data class RewardRetryReady(val message: String) : RaidRecordResult
    data class RewardHeld(val message: String) : RaidRecordResult
}

private fun raidEntryWaitWarning(reasonCode: String, message: String): String? = when (reasonCode) {
    "RAID_WAITING_TO_START",
    "RAID_GLOBAL_REGISTRATION_COOLDOWN",
    "RAID_POST_REWARD_GLOBAL_COOLDOWN",
    "RAID_PERSONAL_BATTLE_COOLDOWN",
    -> null
    else -> message
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
    val battleRecovery: RaidBattleRecovery? = null,
    val battleSafetyGate: RaidBattleSafetyGate? = null,
    val battleSafetyVersion: Int = CURRENT_RAID_BATTLE_SAFETY_VERSION,
    val rewardRecovery: RaidRewardRecovery? = null,
)

const val CURRENT_RAID_BATTLE_SAFETY_VERSION = 1

data class RaidBattleSafetyGate(
    val raidId: String,
    val categoryId: String?,
    val mapCode: String?,
    val executionIdentity: String?,
    val startedAt: Instant,
    val notBefore: Instant,
    val source: RaidCooldownSource,
    val firstIncompleteAt: Instant? = null,
    val successfulIncompleteObservations: Int = 0,
    val lastObservedAt: Instant? = null,
    val evidenceCaseId: String? = null,
    val held: Boolean = false,
)

enum class RaidRewardRecoveryKind {
    WINDOW_OBSERVATION,
    ACTION_RESULT,
}

data class RaidRewardRecovery(
    val executionIdentity: String?,
    val firstAmbiguousAt: Instant,
    val successfulObservationCount: Int,
    val retryCount: Int,
    val held: Boolean = false,
    val kind: RaidRewardRecoveryKind = RaidRewardRecoveryKind.ACTION_RESULT,
)

enum class RaidBattleRecoveryObservation {
    RESULT_UNOBSERVED,
    RUNNABLE,
    COOLDOWN,
    ABSENT,
    INCOMPLETE,
}

data class RaidBattleRecovery(
    val chainId: String,
    val raidId: String,
    val categoryId: String,
    val mapCode: String,
    val originalExecutionIdentity: String,
    val latestExecutionIdentity: String,
    val firstAmbiguousAt: Instant,
    val lastSubmittedAt: Instant,
    val retransmissionCount: Int,
    val nextCheckAt: Instant,
    val submittedFromRunnable: Boolean,
    val lastObservation: RaidBattleRecoveryObservation,
)

internal fun RaidBattleRecovery.warningMessage(
    detail: String = lastObservation.defaultWarningDetail(),
): String =
    "레이드 전투 결과 미확정 · 최초 미확정 $firstAmbiguousAt · " +
        "재전송 ${retransmissionCount}회 · 다음 확인 $nextCheckAt · $detail"

private fun RaidBattleRecoveryObservation.defaultWarningDetail(): String = when (this) {
    RaidBattleRecoveryObservation.RESULT_UNOBSERVED -> "레이드 전투 결과를 아직 관측하지 못했습니다."
    RaidBattleRecoveryObservation.RUNNABLE -> "최신 관측에서 같은 레이드 전투 맵이 실행 가능합니다."
    RaidBattleRecoveryObservation.COOLDOWN -> "현재 쿨타임만으로 이전 제출의 적용 여부를 확정하지 못했습니다."
    RaidBattleRecoveryObservation.ABSENT -> "최신 레이드 화면에서 전투 맵이 보이지 않습니다."
    RaidBattleRecoveryObservation.INCOMPLETE -> "최신 레이드 전투 맵 관측이 불완전합니다."
}

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
