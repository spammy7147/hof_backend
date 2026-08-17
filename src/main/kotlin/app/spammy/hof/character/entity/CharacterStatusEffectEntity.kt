package app.spammy.hof.character.entity

import jakarta.persistence.*

@Entity
@Table(name = "character_status_effects")
class CharacterStatusEffectEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "character_id", nullable = false)
    var character: CharacterEntity,
    @Column(name = "effect_order", nullable = false) var effectOrder: Int,
    @Enumerated(EnumType.STRING) @Column(name = "effect_type", nullable = false, length = 20)
    var effectType: CharacterStatusEffectType,
    @Column(name = "name", nullable = false, columnDefinition = "text") var name: String,
    @Column(name = "value_text", nullable = false, columnDefinition = "text") var valueText: String,
    @Column(name = "description", nullable = false, columnDefinition = "text") var description: String,
    @Column(name = "active") var active: Boolean? = null,
)

enum class CharacterStatusEffectType { SET, EFFECT }
