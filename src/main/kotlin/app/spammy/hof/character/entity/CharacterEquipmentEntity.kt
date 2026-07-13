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
 * 캐릭터 장비 한 칸의 원본 필드와 표시 순서를 저장한다.
 */
@Entity
@Table(
    name = "character_equipment",
    uniqueConstraints = [UniqueConstraint(columnNames = ["character_id", "equipment_order"])],
)
class CharacterEquipmentEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "character_id", nullable = false)
    var character: CharacterEntity,

    @Column(name = "equipment_order", nullable = false)
    var equipmentOrder: Int,

    @Column(name = "slot", nullable = false, columnDefinition = "text")
    var slot: String,

    @Column(name = "part", nullable = false, columnDefinition = "text")
    var part: String,

    @Column(name = "name", nullable = false, columnDefinition = "text")
    var name: String,

    @Column(name = "icon_url", nullable = false, columnDefinition = "text")
    var iconUrl: String,

    @Column(name = "description", nullable = false, columnDefinition = "text")
    var description: String,

    @Column(name = "checked", nullable = false)
    var checked: Boolean,
)
