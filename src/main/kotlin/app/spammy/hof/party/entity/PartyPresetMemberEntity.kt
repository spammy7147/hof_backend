package app.spammy.hof.party.entity

import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.entity.CharacterPatternSlotEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.io.Serializable

/**
 * 파티 프리셋의 고정 슬롯 하나와 선택된 캐릭터·저장 패턴 관계를 보관한다.
 *
 * 캐릭터와 패턴은 빈 파티 슬롯을 표현할 수 있도록 nullable이다. `pattern_slot_id`가 가리키는
 * 슬롯이 `character_id` 캐릭터의 슬롯인지 여부는 서로 다른 두 FK만으로 표현할 수 없으므로,
 * 저장 전에 서비스가 [CharacterPatternSlotEntity.slotCode]와 캐릭터 소유 관계를 함께 검증한다.
 */
@Entity
@IdClass(PartyPresetMemberId::class)
@Table(
    name = "party_preset_members",
    uniqueConstraints = [UniqueConstraint(columnNames = ["preset_id", "slot_index"])],
)
class PartyPresetMemberEntity(
    @Id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "preset_id", nullable = false)
    var preset: PartyPresetEntity,

    @Id
    @Column(name = "slot_index", nullable = false)
    var slotIndex: Int,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "character_id")
    var character: CharacterEntity? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pattern_slot_id")
    var patternSlot: CharacterPatternSlotEntity? = null,
)

/**
 * 프리셋 안에서 0~4 슬롯을 유일하게 식별하는 JPA 복합 키다.
 *
 * [IdClass] 규칙에 따라 연관 필드 `preset`은 대상 엔티티 자체가 아니라 그 PK 타입인 [Long]로
 * 선언한다. 기본값은 JPA가 복합 키 객체를 리플렉션으로 생성할 수 있게 한다.
 */
data class PartyPresetMemberId(
    var preset: Long = 0,
    var slotIndex: Int = 0,
) : Serializable
