package app.spammy.hof.character.entity

import jakarta.persistence.*

@Entity
@Table(name = "character_equipment_candidates")
class CharacterEquipmentCandidateEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "character_id", nullable = false)
    var character: CharacterEntity,
    @Column(name = "candidate_order", nullable = false) var candidateOrder: Int,
    @Column(name = "source_value", nullable = false, columnDefinition = "text") var sourceValue: String,
    @Column(name = "type_code", nullable = false, length = 40) var typeCode: String,
    @Column(name = "name", nullable = false, columnDefinition = "text") var name: String,
    @Column(name = "icon_url", nullable = false, columnDefinition = "text") var iconUrl: String,
    @Column(name = "description", nullable = false, columnDefinition = "text") var description: String,
    @Column(name = "quantity") var quantity: Int? = null,
)
