package app.spammy.hof.character.service

import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.entity.CharacterEquipmentSavedItemEntity
import app.spammy.hof.character.entity.CharacterEquipmentSavedSlotEntity
import app.spammy.hof.character.entity.CharacterSavedPatternRowEntity
import app.spammy.hof.character.entity.CharacterSection
import app.spammy.hof.character.repository.CharacterEquipmentSavedItemCommandRepository
import app.spammy.hof.character.repository.CharacterEquipmentSavedSlotCommandRepository
import app.spammy.hof.character.repository.CharacterQueryRepository
import app.spammy.hof.character.repository.CharacterSavedPatternRowCommandRepository
import app.spammy.hof.external.parser.CharacterPageParseResult
import java.time.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 깊은 동기화 중 일시적으로 load한 저장 패턴과 장비 프리셋을 현재 설정과 분리해 보관한다. */
@Service
class CharacterSnapshotArchiveWriter(
    private val query: CharacterQueryRepository,
    private val snapshotWriter: CharacterSnapshotWriter,
    private val savedPatternRows: CharacterSavedPatternRowCommandRepository,
    private val equipmentSavedSlots: CharacterEquipmentSavedSlotCommandRepository,
    private val equipmentSavedItems: CharacterEquipmentSavedItemCommandRepository,
) {
    @Transactional
    fun saveCurrent(character: CharacterEntity, snapshot: CharacterPageParseResult, observedAt: Instant) {
        snapshotWriter.write(character, snapshot, observedAt)
    }

    @Transactional
    fun savePatternSlot(character: CharacterEntity, slotCode: String, snapshot: CharacterPageParseResult) {
        val slot = query.findPatternSlot(character.id, slotCode)
            ?: error("저장 패턴 슬롯을 찾지 못했습니다: $slotCode")
        val existing = query.findSavedPatternRows(slot.id)
        if (existing.isNotEmpty()) {
            savedPatternRows.deleteAll(existing)
            savedPatternRows.flush()
        }
        slot.selectedPosition = snapshot.snapshot.positionGuard.selectedPosition
        slot.guardValue = snapshot.snapshot.positionGuard.guardValue
        slot.guardText = snapshot.snapshot.positionGuard.guardText
        savedPatternRows.saveAll(
            snapshot.snapshot.actionPatterns.map { row ->
                CharacterSavedPatternRowEntity(
                    patternSlot = slot,
                    rowIndex = row.index,
                    judge = row.judge,
                    judgeText = row.judgeText,
                    quantity = row.quantity,
                    quantityText = row.quantityText,
                    skill = row.skill,
                    skillText = row.skillText,
                )
            },
        )
    }

    @Transactional
    fun saveEquipmentPreset(
        character: CharacterEntity,
        slotNumber: Int,
        snapshot: CharacterPageParseResult,
        observedAt: Instant,
    ) {
        require(slotNumber in 1..2) { "장비 저장 슬롯은 1 또는 2여야 합니다." }
        val slot = query.findEquipmentSavedSlot(character.id, slotNumber)
            ?.apply { this.observedAt = observedAt }
            ?: equipmentSavedSlots.save(
                CharacterEquipmentSavedSlotEntity(character = character, slotNumber = slotNumber, observedAt = observedAt),
            )
        val existing = query.findEquipmentSavedItems(slot.id)
        if (existing.isNotEmpty()) {
            equipmentSavedItems.deleteAll(existing)
            equipmentSavedItems.flush()
        }
        equipmentSavedItems.saveAll(
            snapshot.snapshot.equipment.filter { it.name.isNotBlank() }.mapIndexed { index, item ->
                CharacterEquipmentSavedItemEntity(
                    equipmentSavedSlot = slot,
                    itemOrder = index,
                    equipmentPart = item.part.ifBlank { item.slot },
                    name = item.name,
                    iconUrl = item.iconUrl,
                    description = item.description,
                )
            },
        )
    }

    companion object {
        val RESTORABLE_CURRENT_SECTIONS = setOf(
            CharacterSection.CURRENT_PATTERN,
            CharacterSection.POSITION_GUARD,
            CharacterSection.EQUIPMENT,
        )
    }
}
