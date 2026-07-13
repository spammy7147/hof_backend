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
 * 캐릭터 상태 영역의 원문 한 줄과 원본 표시 순서를 저장한다.
 */
@Entity
@Table(
    name = "character_status_lines",
    uniqueConstraints = [UniqueConstraint(columnNames = ["character_id", "line_order"])],
)
class CharacterStatusLineEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "character_id", nullable = false)
    var character: CharacterEntity,

    @Column(name = "line_order", nullable = false)
    var lineOrder: Int,

    @Column(name = "content", nullable = false, columnDefinition = "text")
    var content: String,
)
