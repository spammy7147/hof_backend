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
 * 캐릭터 위치 선택지의 원본 값, 선택 여부, 표시 순서를 저장한다.
 */
@Entity
@Table(
    name = "character_position_choices",
    uniqueConstraints = [UniqueConstraint(columnNames = ["character_id", "choice_order"])],
)
class CharacterPositionChoiceEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "character_id", nullable = false)
    var character: CharacterEntity,

    @Column(name = "choice_order", nullable = false)
    var choiceOrder: Int,

    @Column(name = "\"value\"", nullable = false, columnDefinition = "text")
    var value: String,

    @Column(name = "checked", nullable = false)
    var checked: Boolean,
)
