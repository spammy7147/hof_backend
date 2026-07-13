package app.spammy.hof.character.entity

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

/**
 * 동기화 job에서 실패한 HOF 캐릭터 ID와 결정적인 발생 순서를 저장한다.
 */
@Entity
@Table(
    name = "character_sync_failures",
    uniqueConstraints = [
        UniqueConstraint(columnNames = ["sync_job_id", "hof_character_id"]),
        UniqueConstraint(columnNames = ["sync_job_id", "failure_order"]),
    ],
)
class CharacterSyncFailureEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sync_job_id", nullable = false)
    var syncJob: CharacterSyncJobEntity,

    @Column(name = "failure_order", nullable = false)
    var failureOrder: Int,

    @Column(name = "hof_character_id", nullable = false)
    var hofCharacterId: String,
)
