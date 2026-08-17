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

/** 한 캐릭터가 지금까지 사용한 HOF 원본 ID와 연결 근거를 시간순으로 보관한다. */
@Entity
@Table(name = "character_hof_id_history")
class CharacterHofIdHistoryEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "character_id", nullable = false)
    var character: CharacterEntity,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    var account: HofAccountEntity,

    @Column(name = "hof_character_id", nullable = false, length = 50)
    var hofCharacterId: String,

    @Column(name = "valid_from", nullable = false)
    var validFrom: Instant,

    @Column(name = "valid_to")
    var validTo: Instant? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "link_reason", nullable = false, length = 30)
    var linkReason: CharacterHofIdLinkReason,

    @Column(name = "user_confirmed", nullable = false)
    var userConfirmed: Boolean,

    @Column(name = "open_marker")
    var openMarker: Int? = 1,
) {
    fun close(at: Instant) {
        require(validTo == null) { "이미 종료된 HOF 캐릭터 ID 이력입니다." }
        require(!at.isBefore(validFrom)) { "종료 시각은 연결 시각보다 빠를 수 없습니다." }
        validTo = at
        openMarker = null
    }
}

enum class CharacterHofIdLinkReason {
    INITIAL_SYNC,
    KNOCKBACK,
    MANUAL_LINK,
    REAPPEARED,
}
