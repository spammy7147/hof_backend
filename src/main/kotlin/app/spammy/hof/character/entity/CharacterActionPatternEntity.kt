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
 * 캐릭터 행동 패턴 한 행의 판정, 수량, 스킬 원본 값과 표시 문자열을 저장한다.
 */
@Entity
@Table(
    name = "character_action_patterns",
    uniqueConstraints = [UniqueConstraint(columnNames = ["character_id", "row_index"])],
)
class CharacterActionPatternEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "character_id", nullable = false)
    var character: CharacterEntity,

    @Column(name = "row_index", nullable = false)
    var rowIndex: Int,

    @Column(name = "judge", nullable = false, columnDefinition = "text")
    var judge: String,

    @Column(name = "judge_text", nullable = false, columnDefinition = "text")
    var judgeText: String,

    @Column(name = "quantity", nullable = false, columnDefinition = "text")
    var quantity: String,

    @Column(name = "quantity_text", nullable = false, columnDefinition = "text")
    var quantityText: String,

    @Column(name = "skill", nullable = false, columnDefinition = "text")
    var skill: String,

    @Column(name = "skill_text", nullable = false, columnDefinition = "text")
    var skillText: String,
)
