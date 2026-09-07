package app.spammy.hof.character.entity

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant

/** 작업별 최초 복원 원본. 현재 snapshot 교체·archive 정리와 수명을 공유하지 않는다. */
@Entity
@Table(name = "character_recovery_originals")
class CharacterRecoveryOriginalEntity(
    @Id @Column(name = "job_id") val jobId: Long,
    @Column(name = "hof_character_id", nullable = false, updatable = false) val hofCharacterId: String,
    @Column(nullable = false, updatable = false) val position: String,
    @Column(name = "guard_value", nullable = false, updatable = false) val guard: String,
    @Column(name = "captured_at", nullable = false, updatable = false) val capturedAt: Instant,
    @Column(name = "collection_complete", nullable = false) var collectionComplete: Boolean = false,
    @Column(name = "collection_error", columnDefinition = "text") var collectionError: String? = null,
    @Column(name = "restore_attempts", nullable = false) var restoreAttempts: Int = 0,
    @Column(name = "observed_equipment", length = 64) var observedEquipment: String? = null,
    @Column(name = "observed_patterns", length = 64) var observedPatterns: String? = null,
    @Column(name = "observed_conditions", length = 64) var observedConditions: String? = null,
    @Column(name = "observed_position") var observedPosition: String? = null,
    @Column(name = "observed_guard") var observedGuard: String? = null,
    @Column(name = "pending_change", length = 32) var pendingChange: String? = null,
    @Version var version: Long? = null,
) {
    @OneToMany(mappedBy = "original", cascade = [CascadeType.PERSIST])
    val patterns: MutableList<CharacterRecoveryPatternEntity> = mutableListOf()

    @OneToMany(mappedBy = "original", cascade = [CascadeType.PERSIST])
    val equipment: MutableList<CharacterRecoveryEquipmentEntity> = mutableListOf()
}

@Entity
@Table(name = "character_recovery_patterns")
class CharacterRecoveryPatternEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "job_id", nullable = false, updatable = false)
    val original: CharacterRecoveryOriginalEntity,
    @Column(name = "row_index", nullable = false, updatable = false) val rowIndex: Int,
    @Column(name = "judge_value", nullable = false, updatable = false) val judge: String,
    @Column(nullable = false, updatable = false) val quantity: String,
    @Column(nullable = false, updatable = false) val skill: String,
)

@Entity
@Table(name = "character_recovery_equipment")
class CharacterRecoveryEquipmentEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "job_id", nullable = false, updatable = false)
    val original: CharacterRecoveryOriginalEntity,
    @Column(name = "item_order", nullable = false, updatable = false) val itemOrder: Int,
    @Column(nullable = false, updatable = false) val slot: String,
    @Column(nullable = false, updatable = false) val part: String,
    @Column(nullable = false, updatable = false, columnDefinition = "text") val name: String,
    @Column(name = "icon_url", nullable = false, updatable = false, columnDefinition = "text") val iconUrl: String,
    @Column(nullable = false, updatable = false, columnDefinition = "text") val description: String,
)
