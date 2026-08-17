package app.spammy.hof.character.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant

@Entity
@IdClass(CharacterSectionSyncStateId::class)
@Table(name = "character_section_sync_states")
class CharacterSectionSyncStateEntity(
    @Id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "character_id", nullable = false)
    var character: CharacterEntity,
    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "section", nullable = false, length = 30)
    var section: CharacterSection,
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: CharacterSectionSyncStatus,
    @Column(name = "parser_version", nullable = false, length = 40)
    var parserVersion: String,
    @Column(name = "last_attempted_at", nullable = false)
    var lastAttemptedAt: Instant,
    @Column(name = "last_succeeded_at")
    var lastSucceededAt: Instant? = null,
    @Column(name = "error_code", length = 60)
    var errorCode: String? = null,
    @Column(name = "error_message", length = 500)
    var errorMessage: String? = null,
    @Column(name = "observed_count")
    var observedCount: Int? = null,
)

data class CharacterSectionSyncStateId(
    var character: Long = 0,
    var section: CharacterSection = CharacterSection.PROFILE,
) : Serializable

enum class CharacterSection {
    PROFILE, STATS, EFFECTS_FAITH, CURRENT_PATTERN, POSITION_GUARD,
    SAVED_PATTERNS, EQUIPMENT, EQUIPMENT_CANDIDATES, SKILLS, MANAGEMENT,
}

enum class CharacterSectionSyncStatus { SUCCESS, FAILED }
