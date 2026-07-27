package app.spammy.hof.party.entity

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

/** 계정별 파티 프리셋을 계층적으로 정리하는 폴더 엔티티다. */
@Entity
@Table(name = "party_preset_folders")
class PartyPresetFolderEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    var account: HofAccountEntity,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_folder_id")
    var parent: PartyPresetFolderEntity? = null,

    @Column(name = "name", nullable = false, length = 100)
    var name: String,

    @Column(name = "display_order", nullable = false)
    var displayOrder: Int = 0,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
)
