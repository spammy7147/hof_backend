package app.spammy.hof.automation.entity

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.automation.raid.RaidBattleRecoveryObservation
import app.spammy.hof.automation.raid.RaidRewardRecoveryKind
import app.spammy.hof.party.entity.PartyPresetEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant

enum class AutomationType { QUEST, HOME_QUEST, BATTLE_MAP, ADVENTURE_MAP, RAID, UNION, FISHING }

fun AutomationType.singletonMarker(): AutomationType? = when (this) {
    AutomationType.BATTLE_MAP, AutomationType.ADVENTURE_MAP -> null
    else -> this
}

enum class PresetSelectionMode { PRIMARY, EXPLICIT }

@Entity
@Table(name = "automation_entries")
class AutomationEntryEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "account_id", nullable = false)
    var account: HofAccountEntity,
    @Enumerated(EnumType.STRING) @Column(name = "automation_type", nullable = false, length = 30)
    var type: AutomationType,
    @Column(name = "priority", nullable = false)
    var priority: Int,
    @Column(name = "enabled", nullable = false)
    var enabled: Boolean,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
    @Enumerated(EnumType.STRING) @Column(name = "singleton_type_marker", length = 30)
    var singletonTypeMarker: AutomationType? = type.singletonMarker(),
    @Column(name = "display_name", length = 100)
    var displayName: String? = null,
    @Column(name = "settings_revision", nullable = false)
    var settingsRevision: Long = 0,
    @Column(name = "minimum_remaining_time")
    var minimumRemainingTime: Int? = null,
)

@Entity
@Table(name = "quest_automation_selections")
class QuestAutomationSelectionEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "automation_entry_id", nullable = false)
    var entry: AutomationEntryEntity,
    @Column(name = "quest_code", nullable = false, length = 100)
    var questKey: String,
    @Column(name = "enabled", nullable = false)
    var enabled: Boolean,
    @Column(name = "source_order", nullable = false)
    var sourceOrder: Int,
    @Column(name = "display_code", nullable = false, length = 100)
    var displayCode: String = questKey,
    @Column(name = "quest_name", nullable = false, length = 255)
    var questName: String = questKey,
)

@Entity
@Table(name = "home_quest_automation_selections")
class HomeQuestAutomationSelectionEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "automation_entry_id", nullable = false)
    var entry: AutomationEntryEntity,
    @Column(name = "quest_id", nullable = false, length = 64)
    var questId: String,
    @Column(name = "quest_name", nullable = false, length = 300)
    var questName: String,
    @Column(name = "enabled", nullable = false)
    var enabled: Boolean,
    @Column(name = "source_order", nullable = false)
    var sourceOrder: Int,
)

@Entity
@Table(name = "quest_automation_maps")
class QuestAutomationMapEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "quest_selection_id", nullable = false)
    var questSelection: QuestAutomationSelectionEntity,
    @Column(name = "mission_key", nullable = false, length = 100)
    var missionKey: String,
    @Column(name = "category_id", nullable = false, length = 50)
    var categoryId: String,
    @Column(name = "map_code", nullable = false, length = 100)
    var mapCode: String,
    @Enumerated(EnumType.STRING) @Column(name = "preset_mode", nullable = false, length = 20)
    var presetMode: PresetSelectionMode,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "party_preset_id")
    var partyPreset: PartyPresetEntity? = null,
    @Column(name = "execution_order", nullable = false)
    var executionOrder: Int,
    @Column(name = "manually_overridden", nullable = false)
    var manuallyOverridden: Boolean,
)

@Entity
@Table(name = "battle_automation_maps")
class BattleAutomationMapEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "automation_entry_id", nullable = false)
    var entry: AutomationEntryEntity,
    @Column(name = "category_id", nullable = false, length = 50)
    var categoryId: String,
    @Column(name = "map_code", nullable = false, length = 100)
    var mapCode: String,
    @Column(name = "daily_target_count", nullable = false)
    var dailyTargetCount: Int,
    @Enumerated(EnumType.STRING) @Column(name = "preset_mode", nullable = false, length = 20)
    var presetMode: PresetSelectionMode,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "party_preset_id")
    var partyPreset: PartyPresetEntity? = null,
    @Column(name = "execution_order", nullable = false)
    var executionOrder: Int,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "account_id", nullable = false)
    var account: HofAccountEntity = entry.account,
)

@Entity
@Table(name = "adventure_automation_maps")
class AdventureAutomationMapEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "automation_entry_id", nullable = false)
    var entry: AutomationEntryEntity,
    @Column(name = "category_id", nullable = false, length = 50)
    var categoryId: String,
    @Column(name = "map_code", nullable = false, length = 100)
    var mapCode: String,
    @Enumerated(EnumType.STRING) @Column(name = "preset_mode", nullable = false, length = 20)
    var presetMode: PresetSelectionMode,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "party_preset_id")
    var partyPreset: PartyPresetEntity? = null,
    @Column(name = "execution_order", nullable = false)
    var executionOrder: Int,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "account_id", nullable = false)
    var account: HofAccountEntity = entry.account,
)

@Entity
@Table(name = "union_automation_maps")
class UnionAutomationMapEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "automation_entry_id", nullable = false)
    var entry: AutomationEntryEntity,
    @Column(name = "category_id", nullable = false, length = 50)
    var categoryId: String,
    @Column(name = "map_code", nullable = false, length = 100)
    var mapCode: String,
    @Enumerated(EnumType.STRING) @Column(name = "preset_mode", nullable = false, length = 20)
    var presetMode: PresetSelectionMode,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "party_preset_id")
    var partyPreset: PartyPresetEntity? = null,
    @Column(name = "execution_order", nullable = false)
    var executionOrder: Int,
)

@Entity
@Table(name = "raid_automation_targets")
class RaidAutomationTargetEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "automation_entry_id", nullable = false)
    var entry: AutomationEntryEntity,
    @Column(name = "raid_id", nullable = false, length = 200)
    var raidId: String,
    @Column(name = "display_name", nullable = false, length = 255)
    var displayName: String,
    @Enumerated(EnumType.STRING) @Column(name = "preset_mode", nullable = false, length = 20)
    var presetMode: PresetSelectionMode,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "party_preset_id")
    var partyPreset: PartyPresetEntity? = null,
    @Column(name = "execution_order", nullable = false)
    var executionOrder: Int,
)

@Entity
@Table(name = "fishing_automation_settings")
class FishingAutomationSettingEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "automation_entry_id", nullable = false)
    var entry: AutomationEntryEntity,
    @Enumerated(EnumType.STRING) @Column(name = "preset_mode", nullable = false, length = 20)
    var presetMode: PresetSelectionMode,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "party_preset_id")
    var partyPreset: PartyPresetEntity? = null,
)

@Entity
@Table(name = "fishing_automation_maps")
class FishingAutomationMapEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "automation_entry_id", nullable = false)
    var entry: AutomationEntryEntity,
    @Column(name = "category_id", nullable = false, length = 50)
    var categoryId: String,
    @Column(name = "map_code", nullable = false, length = 100)
    var mapCode: String,
    @Enumerated(EnumType.STRING) @Column(name = "preset_mode", nullable = false, length = 20)
    var presetMode: PresetSelectionMode,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "party_preset_id")
    var partyPreset: PartyPresetEntity? = null,
    @Column(name = "execution_order", nullable = false)
    var executionOrder: Int,
)

@Entity
@Table(name = "automation_rotation_states")
class AutomationRotationStateEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "automation_entry_id", nullable = false)
    var entry: AutomationEntryEntity,
    @Column(name = "current_target_key", nullable = false, length = 255)
    var currentTargetKey: String,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
    @Version @Column(name = "version", nullable = false)
    var version: Long? = null,
)

enum class RaidAutomationCycleStatus {
    PREPARING,
    REGISTRATION_REFRESH_REQUIRED,
    REGISTRATION_COOLDOWN,
    REGISTERED_WAITING,
    IN_BATTLE,
    REWARD_PENDING,
    POST_REWARD_CHECK,
    COMPLETED,
    ABORTED_CLOSED,
    ABORTED_REGISTRATION_LOST,
    HANDED_OFF_MANUAL,
    SUPERSEDED_BY_OBSERVED_RAID,
}

@Entity
@Table(name = "raid_automation_cycles")
class RaidAutomationCycleEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "account_id", nullable = false)
    val account: HofAccountEntity,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "automation_entry_id")
    var entry: AutomationEntryEntity?,
    @Column(name = "raid_id", nullable = false, length = 200)
    val raidId: String,
    @Column(name = "raid_name", nullable = false, length = 255)
    var raidName: String,
    @Enumerated(EnumType.STRING) @Column(name = "status", nullable = false, length = 32)
    var status: RaidAutomationCycleStatus,
    @Column(name = "last_observed_status", length = 32)
    var lastObservedStatus: String? = null,
    @Column(name = "next_check_at")
    var nextCheckAt: Instant? = null,
    @Column(name = "battle_recovery_chain_id", length = 64)
    var battleRecoveryChainId: String? = null,
    @Column(name = "battle_recovery_original_execution_identity", length = 128)
    var battleRecoveryOriginalExecutionIdentity: String? = null,
    @Column(name = "battle_recovery_latest_execution_identity", length = 128)
    var battleRecoveryLatestExecutionIdentity: String? = null,
    @Column(name = "battle_recovery_first_ambiguous_at")
    var battleRecoveryFirstAmbiguousAt: Instant? = null,
    @Column(name = "battle_recovery_last_submitted_at")
    var battleRecoveryLastSubmittedAt: Instant? = null,
    @Column(name = "battle_recovery_retransmission_count")
    var battleRecoveryRetransmissionCount: Int? = null,
    @Column(name = "battle_recovery_next_check_at")
    var battleRecoveryNextCheckAt: Instant? = null,
    @Column(name = "battle_recovery_category_id", length = 50)
    var battleRecoveryCategoryId: String? = null,
    @Column(name = "battle_recovery_map_code", length = 100)
    var battleRecoveryMapCode: String? = null,
    @Column(name = "battle_recovery_submitted_from_runnable")
    var battleRecoverySubmittedFromRunnable: Boolean? = null,
    @Enumerated(EnumType.STRING) @Column(name = "battle_recovery_last_observation", length = 32)
    var battleRecoveryLastObservation: RaidBattleRecoveryObservation? = null,
    @Column(name = "battle_cooldown_not_before")
    var battleCooldownNotBefore: Instant? = null,
    @Enumerated(EnumType.STRING) @Column(name = "battle_cooldown_source", length = 40)
    var battleCooldownSource: app.spammy.hof.automation.raid.RaidCooldownSource? = null,
    @Column(name = "battle_cooldown_started_at")
    var battleCooldownStartedAt: Instant? = null,
    @Column(name = "battle_cooldown_raid_id", length = 200)
    var battleCooldownRaidId: String? = null,
    @Column(name = "battle_cooldown_category_id", length = 50)
    var battleCooldownCategoryId: String? = null,
    @Column(name = "battle_cooldown_map_code", length = 100)
    var battleCooldownMapCode: String? = null,
    @Column(name = "battle_cooldown_execution_identity", length = 128)
    var battleCooldownExecutionIdentity: String? = null,
    @Column(name = "battle_cooldown_first_incomplete_at")
    var battleCooldownFirstIncompleteAt: Instant? = null,
    @Column(name = "battle_cooldown_incomplete_observations")
    var battleCooldownIncompleteObservations: Int? = null,
    @Column(name = "battle_cooldown_last_observed_at")
    var battleCooldownLastObservedAt: Instant? = null,
    @Column(name = "battle_cooldown_evidence_case_id", length = 64)
    var battleCooldownEvidenceCaseId: String? = null,
    @Column(name = "battle_cooldown_held", nullable = false)
    var battleCooldownHeld: Boolean = false,
    @Column(name = "battle_safety_version", nullable = false)
    var battleSafetyVersion: Int = app.spammy.hof.automation.raid.CURRENT_RAID_BATTLE_SAFETY_VERSION,
    @Enumerated(EnumType.STRING)
    @Column(name = "reward_recovery_kind", length = 32)
    var rewardRecoveryKind: RaidRewardRecoveryKind? = null,
    @Column(name = "reward_recovery_execution_identity", length = 128)
    var rewardRecoveryExecutionIdentity: String? = null,
    @Column(name = "reward_recovery_first_ambiguous_at")
    var rewardRecoveryFirstAmbiguousAt: Instant? = null,
    @Column(name = "reward_recovery_observation_count")
    var rewardRecoveryObservationCount: Int? = null,
    @Column(name = "reward_recovery_retry_count")
    var rewardRecoveryRetryCount: Int? = null,
    @Column(name = "reward_recovery_held", nullable = false)
    var rewardRecoveryHeld: Boolean = false,
    @Column(name = "open_marker")
    var openMarker: Int? = 1,
    @Column(name = "started_at", nullable = false)
    val startedAt: Instant,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
    @Column(name = "finished_at")
    var finishedAt: Instant? = null,
    @Version @Column(name = "version", nullable = false)
    var version: Long? = null,
) {
    fun clearBattleRecovery() {
        battleRecoveryChainId = null
        battleRecoveryOriginalExecutionIdentity = null
        battleRecoveryLatestExecutionIdentity = null
        battleRecoveryFirstAmbiguousAt = null
        battleRecoveryLastSubmittedAt = null
        battleRecoveryRetransmissionCount = null
        battleRecoveryNextCheckAt = null
        battleRecoveryCategoryId = null
        battleRecoveryMapCode = null
        battleRecoverySubmittedFromRunnable = null
        battleRecoveryLastObservation = null
    }

    fun clearBattleSafetyGate() {
        battleCooldownNotBefore = null
        battleCooldownSource = null
        battleCooldownStartedAt = null
        battleCooldownRaidId = null
        battleCooldownCategoryId = null
        battleCooldownMapCode = null
        battleCooldownExecutionIdentity = null
        battleCooldownFirstIncompleteAt = null
        battleCooldownIncompleteObservations = null
        battleCooldownLastObservedAt = null
        battleCooldownEvidenceCaseId = null
        battleCooldownHeld = false
    }

    fun clearRewardRecovery() {
        rewardRecoveryKind = null
        rewardRecoveryExecutionIdentity = null
        rewardRecoveryFirstAmbiguousAt = null
        rewardRecoveryObservationCount = null
        rewardRecoveryRetryCount = null
        rewardRecoveryHeld = false
    }
}
