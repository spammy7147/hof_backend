package app.spammy.hof.character.entity

import app.spammy.hof.account.entity.HofAccountEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant

/**
 * 동기화된 HOF 캐릭터의 계정 소유권과 목록용 핵심 정보를 저장한다.
 */
@Entity
@Table(name = "characters")
class CharacterEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    var account: HofAccountEntity,

    @Column(name = "hof_character_id", nullable = false, length = 50)
    var hofCharacterId: String,

    @Column(name = "name", nullable = false, length = 100)
    var name: String,

    @Column(name = "job", nullable = false, length = 100)
    var job: String,

    @Column(name = "level")
    var level: Int? = null,

    @Column(name = "pattern_slot_count", nullable = false)
    var patternSlotCount: Int = 0,

    @Column(name = "image_url", columnDefinition = "text")
    var imageUrl: String? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,

    @Column(name = "detail_synced_at")
    var detailSyncedAt: Instant? = null,
)
