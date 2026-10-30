package app.spammy.hof.automation.entity

import app.spammy.hof.account.entity.HofAccountEntity
import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "automation_presets", uniqueConstraints = [UniqueConstraint(name = "uk_automation_presets_account_name", columnNames = ["account_id", "name"])])
class AutomationPresetEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "account_id", nullable = false)
    val account: HofAccountEntity,
    @Column(nullable = false, length = 100) var name: String,
    @Column(name = "settings_json", nullable = false, columnDefinition = "text") var settingsJson: String,
    @Column(name = "entry_count", nullable = false) var entryCount: Int,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
    @Version @Column(nullable = false) var revision: Long = 0,
)
