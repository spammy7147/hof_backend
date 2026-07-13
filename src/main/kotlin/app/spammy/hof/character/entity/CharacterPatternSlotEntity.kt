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
 * HOF 저장 패턴 슬롯의 코드, 표시 라벨, 로드 가능 여부를 저장한다.
 */
@Entity
@Table(
    name = "character_pattern_slots",
    uniqueConstraints = [UniqueConstraint(columnNames = ["character_id", "slot_code"])],
)
class CharacterPatternSlotEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "character_id", nullable = false)
    var character: CharacterEntity,

    @Column(name = "slot_code", nullable = false)
    var slotCode: String,

    @Column(name = "label", nullable = false, columnDefinition = "text")
    var label: String,

    @Column(name = "can_load", nullable = false)
    var canLoad: Boolean,
)
