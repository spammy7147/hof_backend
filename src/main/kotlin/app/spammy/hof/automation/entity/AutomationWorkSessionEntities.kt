package app.spammy.hof.automation.entity

import app.spammy.hof.account.entity.HofAccountEntity
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

enum class AutomationWorkType { QUEST, HOME_QUEST, BATTLE_MAP, ADVENTURE_MAP, RAID, UNION, FISHING }

enum class AutomationWorkStatus {
    RUNNING,
    WAITING_COOLDOWN,
    WAITING_RESOURCE,
    YIELDED_PRIORITY,
    COMPLETED,
    STOPPED,
}

@Entity
@Table(name = "automation_work_sessions")
class AutomationWorkSessionEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    val account: HofAccountEntity,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "automation_entry_id", nullable = false)
    val entry: AutomationEntryEntity,

    @Enumerated(EnumType.STRING)
    @Column(name = "work_type", nullable = false)
    val workType: AutomationWorkType,

    @Column(name = "target_key", nullable = false)
    var targetKey: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: AutomationWorkStatus,

    @Column(name = "config_version", nullable = false)
    var configVersion: String,

    @Column(name = "target_count")
    var targetCount: Int? = null,

    @Column(name = "confirmed_count", nullable = false)
    var confirmedCount: Int = 0,

    @Column(name = "quest_cycle")
    var questCycle: String? = null,

    @Column(name = "mission_key")
    var missionKey: String? = null,

    @Column(name = "mission_type")
    var missionType: String? = null,

    @Column(name = "observed_current")
    var observedCurrent: Int? = null,

    @Column(name = "observed_required")
    var observedRequired: Int? = null,

    @Column(name = "material_name")
    var materialName: String? = null,

    @Column(name = "material_missing")
    var materialMissing: Int? = null,

    @Column(name = "next_check_at")
    var nextCheckAt: Instant? = null,

    @Column(name = "last_verified_at")
    var lastVerifiedAt: Instant? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,

    @Column(name = "finished_at")
    var finishedAt: Instant? = null,

    @Version
    @Column(name = "version")
    var version: Long? = null,
)
