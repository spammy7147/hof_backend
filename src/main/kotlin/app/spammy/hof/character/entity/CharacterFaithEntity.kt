package app.spammy.hof.character.entity

import jakarta.persistence.*

@Entity
@Table(name = "character_faith")
class CharacterFaithEntity(
    @Id @Column(name = "character_id") var characterId: Long = 0,
    @MapsId @OneToOne(fetch = FetchType.LAZY) @JoinColumn(name = "character_id", nullable = false)
    var character: CharacterEntity,
    @Column(name = "god_name", nullable = false, columnDefinition = "text") var godName: String,
    @Column(name = "current_value", nullable = false) var currentValue: Long,
    @Column(name = "max_value", nullable = false) var maxValue: Long,
)
