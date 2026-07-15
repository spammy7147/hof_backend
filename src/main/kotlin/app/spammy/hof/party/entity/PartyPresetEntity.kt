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

/**
 * 계정이 저장한 5인 파티 프리셋의 이름과 생성·수정 시각을 보관하는 부모 엔티티다.
 *
 * 슬롯 구성은 [PartyPresetMemberEntity]가 `(preset_id, slot_index)` 관계로 소유한다. 부모에
 * 컬렉션을 매핑하지 않는 이유는 목록 조회 시 모든 프리셋의 슬롯을 QueryDSL 한 번으로 읽어
 * 조립하고, 교체 시에도 command repository의 명시적인 삭제·저장 순서를 유지하기 위해서다.
 */
@Entity
@Table(name = "party_presets")
class PartyPresetEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    var account: HofAccountEntity,

    @Column(name = "name", nullable = false)
    var name: String,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,

    isPrimary: Boolean = false,
) {
    @Column(name = "is_primary", nullable = false)
    var isPrimary: Boolean = isPrimary
        set(value) {
            field = value
            primaryMarker = if (value) PRIMARY_MARKER else null
        }

    @Column(name = "primary_marker")
    var primaryMarker: Int? = if (isPrimary) PRIMARY_MARKER else null
        protected set

    companion object {
        private const val PRIMARY_MARKER = 1
    }
}
