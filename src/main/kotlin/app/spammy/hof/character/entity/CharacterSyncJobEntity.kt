package app.spammy.hof.character.entity

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
import java.time.Instant

/**
 * 캐릭터 동기화 작업의 진행 상태와 카운터를 저장하는 JPA 엔티티다.
 */
@Entity
@Table(name = "character_sync_jobs")
class CharacterSyncJobEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    var account: HofAccountEntity,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: CharacterSyncJobStatus,

    @Column(name = "roster_count", nullable = false)
    var rosterCount: Int = 0,

    @Column(name = "synced_count", nullable = false)
    var syncedCount: Int = 0,

    @Column(name = "message", columnDefinition = "text")
    var message: String? = null,

    @Column(name = "started_at", nullable = false)
    var startedAt: Instant,

    @Column(name = "finished_at")
    var finishedAt: Instant? = null,
)

/**
 * 캐릭터 동기화 job의 생명주기 상태다.
 */
enum class CharacterSyncJobStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
}
