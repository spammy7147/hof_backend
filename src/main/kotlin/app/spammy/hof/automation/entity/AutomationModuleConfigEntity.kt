package app.spammy.hof.automation.entity

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

@Entity
@Table(name = "automation_module_configs")
class AutomationModuleConfigEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "profile_id", nullable = false)
    var profile: AutomationProfileEntity,

    @Enumerated(EnumType.STRING)
    @Column(name = "module_type", nullable = false, length = 50)
    var moduleType: AutomationModuleType,

    @Column(name = "enabled", nullable = false)
    var enabled: Boolean,

    @Column(name = "priority", nullable = false)
    var priority: Int,

    @Column(name = "display_name", nullable = false, length = 50)
    var displayName: String,

    @Column(name = "threshold_percent")
    var thresholdPercent: Int?,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
)
