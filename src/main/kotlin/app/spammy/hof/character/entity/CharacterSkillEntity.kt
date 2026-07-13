package app.spammy.hof.character.entity

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
import jakarta.persistence.UniqueConstraint

/**
 * 캐릭터가 배운 스킬과 배울 수 있는 스킬의 원본 필드 및 표시 순서를 저장한다.
 */
@Entity
@Table(
    name = "character_skills",
    uniqueConstraints = [UniqueConstraint(columnNames = ["character_id", "skill_type", "skill_order"])],
)
class CharacterSkillEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "character_id", nullable = false)
    var character: CharacterEntity,

    @Enumerated(EnumType.STRING)
    @Column(name = "skill_type", nullable = false)
    var skillType: CharacterSkillType,

    @Column(name = "skill_order", nullable = false)
    var skillOrder: Int,

    @Column(name = "source_value", nullable = false, columnDefinition = "text")
    var sourceValue: String,

    @Column(name = "name", nullable = false, columnDefinition = "text")
    var name: String,

    @Column(name = "icon_url", nullable = false, columnDefinition = "text")
    var iconUrl: String,

    @Column(name = "category", nullable = false, columnDefinition = "text")
    var category: String,
)

/**
 * 스킬 행이 이미 배운 스킬인지 새로 배울 수 있는 스킬인지 구분한다.
 */
enum class CharacterSkillType {
    LEARNED,
    LEARNABLE,
}
