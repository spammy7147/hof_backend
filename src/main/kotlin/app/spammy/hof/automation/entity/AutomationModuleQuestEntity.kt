package app.spammy.hof.automation.entity

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
    name = "automation_module_quests",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_automation_module_quests_module_quest",
            columnNames = ["module_config_id", "quest_code"],
        ),
    ],
)
class AutomationModuleQuestEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "module_config_id", nullable = false)
    var moduleConfig: AutomationModuleConfigEntity,

    @Column(name = "quest_code", nullable = false, length = 100)
    var questCode: String,

    @Column(name = "execution_order", nullable = false)
    var executionOrder: Int,
)
