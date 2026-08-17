package app.spammy.hof.character.entity

import jakarta.persistence.*

@Entity
@Table(name = "character_pattern_options")
class CharacterPatternOptionEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "character_id", nullable = false)
    var character: CharacterEntity,
    @Enumerated(EnumType.STRING) @Column(name = "option_type", nullable = false, length = 20)
    var optionType: CharacterPatternOptionType,
    @Column(name = "option_order", nullable = false) var optionOrder: Int,
    @Column(name = "source_value", nullable = false, columnDefinition = "text") var sourceValue: String,
    @Column(name = "label", nullable = false, columnDefinition = "text") var label: String,
    @Column(name = "category", columnDefinition = "text") var category: String? = null,
)

enum class CharacterPatternOptionType { CONDITION, SKILL, CLASS }
