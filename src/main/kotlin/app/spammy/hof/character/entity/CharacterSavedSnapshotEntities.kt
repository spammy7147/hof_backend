package app.spammy.hof.character.entity

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "character_saved_pattern_rows")
class CharacterSavedPatternRowEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "pattern_slot_id", nullable = false)
    var patternSlot: CharacterPatternSlotEntity,
    @Column(name = "row_index", nullable = false) var rowIndex: Int,
    @Column(name = "judge", nullable = false, columnDefinition = "text") var judge: String,
    @Column(name = "judge_text", nullable = false, columnDefinition = "text") var judgeText: String,
    @Column(name = "quantity", nullable = false, columnDefinition = "text") var quantity: String,
    @Column(name = "quantity_text", nullable = false, columnDefinition = "text") var quantityText: String,
    @Column(name = "skill", nullable = false, columnDefinition = "text") var skill: String,
    @Column(name = "skill_text", nullable = false, columnDefinition = "text") var skillText: String,
)

@Entity
@Table(name = "character_equipment_saved_slots")
class CharacterEquipmentSavedSlotEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "character_id", nullable = false)
    var character: CharacterEntity,
    @Column(name = "slot_number", nullable = false) var slotNumber: Int,
    @Column(name = "observed_at", nullable = false) var observedAt: Instant,
)

@Entity
@Table(name = "character_equipment_saved_items")
class CharacterEquipmentSavedItemEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "equipment_saved_slot_id", nullable = false)
    var equipmentSavedSlot: CharacterEquipmentSavedSlotEntity,
    @Column(name = "item_order", nullable = false) var itemOrder: Int,
    @Column(name = "equipment_part", nullable = false, columnDefinition = "text") var equipmentPart: String,
    @Column(name = "name", nullable = false, columnDefinition = "text") var name: String,
    @Column(name = "icon_url", nullable = false, columnDefinition = "text") var iconUrl: String,
    @Column(name = "description", nullable = false, columnDefinition = "text") var description: String,
)
