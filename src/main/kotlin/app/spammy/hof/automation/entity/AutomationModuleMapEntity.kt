package app.spammy.hof.automation.entity

import app.spammy.hof.battle.entity.BattleMapEntity
import app.spammy.hof.party.entity.PartyPresetEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

@Entity
@Table(
    name = "automation_module_maps",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_automation_module_maps_module_map",
            columnNames = ["module_config_id", "battle_map_id"],
        ),
    ],
)
class AutomationModuleMapEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "module_config_id", nullable = false)
    var moduleConfig: AutomationModuleConfigEntity,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "battle_map_id", nullable = false)
    var battleMap: BattleMapEntity,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "party_preset_id")
    var partyPreset: PartyPresetEntity?,

    @Column(name = "execution_order", nullable = false)
    var executionOrder: Int,
)
