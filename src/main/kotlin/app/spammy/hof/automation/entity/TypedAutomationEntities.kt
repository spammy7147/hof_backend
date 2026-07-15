package app.spammy.hof.automation.entity

import app.spammy.hof.account.entity.HofAccountEntity
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
import java.time.Instant

enum class AutomationType { QUEST, BATTLE_MAP, ADVENTURE_MAP }

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
)

@Entity
@Table(name = "quest_automation_selections")
class QuestAutomationSelectionEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "automation_entry_id", nullable = false)
    var entry: AutomationEntryEntity,
    @Column(name = "quest_code", nullable = false, length = 100)
    var questCode: String,
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
)
