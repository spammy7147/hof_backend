package app.spammy.hof.character.service

import app.spammy.hof.character.entity.*
import app.spammy.hof.character.repository.*
import app.spammy.hof.external.parser.CharacterPageParseResult
import app.spammy.hof.external.parser.CharacterSectionParseResult
import java.time.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 구역별 파싱 성공 데이터만 교체하고 실패 구역의 마지막 정상 snapshot을 보존한다. */
@Service
class CharacterSnapshotWriter(
    private val query: CharacterQueryRepository,
    private val characters: CharacterRepository,
    private val sections: CharacterSectionSyncStateCommandRepository,
    private val stats: CharacterStatsCommandRepository,
    private val effects: CharacterStatusEffectCommandRepository,
    private val faith: CharacterFaithCommandRepository,
    private val patternOptions: CharacterPatternOptionCommandRepository,
    private val candidates: CharacterEquipmentCandidateCommandRepository,
    private val actionPatterns: CharacterActionPatternCommandRepository,
    private val guardSettings: CharacterGuardSettingCommandRepository,
    private val positionChoices: CharacterPositionChoiceCommandRepository,
    private val patternSlots: CharacterPatternSlotCommandRepository,
    private val equipment: CharacterEquipmentCommandRepository,
    private val skills: CharacterSkillCommandRepository,
) {
    @Transactional
    fun write(
        character: CharacterEntity,
        result: CharacterPageParseResult,
        attemptedAt: Instant,
        selectedSections: Set<CharacterSection> = CharacterSection.entries.toSet(),
    ) {
        result.sections.forEach { (pageSection, parseResult) ->
            val section = CharacterSection.valueOf(pageSection.name)
            if (section !in selectedSections) return@forEach
            when (parseResult) {
                is CharacterSectionParseResult.Success -> {
                    replaceSuccessfulSection(character, section, result)
                    recordSuccess(character, section, parseResult.observedCount, attemptedAt)
                }
                is CharacterSectionParseResult.Failure ->
                    recordFailure(character, section, parseResult, attemptedAt)
            }
        }
        if (result.sections.keys.any { CharacterSection.valueOf(it.name) in selectedSections }) {
            character.detailSyncedAt = attemptedAt
        }
        character.updatedAt = attemptedAt
        characters.save(character)
    }

    private fun replaceSuccessfulSection(
        character: CharacterEntity,
        section: CharacterSection,
        result: CharacterPageParseResult,
    ) {
        val snapshot = result.snapshot
        when (section) {
            CharacterSection.PROFILE -> {
                character.name = snapshot.name
                character.job = snapshot.job
                character.level = snapshot.level
                character.imageUrl = snapshot.imageUrl.ifBlank { null }
            }
            CharacterSection.STATS -> {
                val stored = query.findStatsByCharacterId(character.id)
                    ?: CharacterStatsEntity(character = character)
                stored.applySnapshot(snapshot.stats)
                stats.save(stored)
            }
            CharacterSection.EFFECTS_FAITH -> {
                replace(query.findStatusEffects(character.id), effects)
                effects.saveAll(snapshot.statusEffects.mapIndexed { index, effect ->
                    CharacterStatusEffectEntity(
                        character = character,
                        effectOrder = index,
                        effectType = CharacterStatusEffectType.valueOf(effect.type),
                        name = effect.name,
                        valueText = effect.valueText,
                        description = effect.description,
                        active = effect.active,
                    )
                })
                query.findFaith(character.id)?.let { faith.delete(it); faith.flush() }
                snapshot.faith?.let { value ->
                    faith.save(CharacterFaithEntity(character = character, godName = value.godName, currentValue = value.current, maxValue = value.max))
                }
            }
            CharacterSection.CURRENT_PATTERN -> {
                replace(query.findActionPatternsByCharacterIds(listOf(character.id)), actionPatterns)
                actionPatterns.saveAll(snapshot.actionPatterns.map { row ->
                    CharacterActionPatternEntity(
                        character = character, rowIndex = row.index, judge = row.judge, judgeText = row.judgeText,
                        quantity = row.quantity, quantityText = row.quantityText, skill = row.skill, skillText = row.skillText,
                    )
                })
                replace(query.findPatternOptions(character.id), patternOptions)
                patternOptions.saveAll(snapshot.patternOptions.mapIndexed { index, option ->
                    CharacterPatternOptionEntity(
                        character = character,
                        optionType = CharacterPatternOptionType.valueOf(option.type),
                        optionOrder = index,
                        sourceValue = option.value,
                        label = option.label,
                        category = option.category.ifBlank { null },
                    )
                })
            }
            CharacterSection.POSITION_GUARD -> {
                query.findGuardSettingByCharacterId(character.id)?.let { guardSettings.delete(it); guardSettings.flush() }
                guardSettings.save(
                    CharacterGuardSettingEntity(
                        character = character,
                        selectedPosition = snapshot.positionGuard.selectedPosition,
                        guardValue = snapshot.positionGuard.guardValue,
                        guardText = snapshot.positionGuard.guardText,
                    ),
                )
                replace(query.findPositionChoicesByCharacterIds(listOf(character.id)), positionChoices)
                positionChoices.saveAll(snapshot.positionGuard.positions.mapIndexed { index, position ->
                    CharacterPositionChoiceEntity(
                        character = character, choiceOrder = index, value = position.value, checked = position.checked,
                    )
                })
            }
            CharacterSection.SAVED_PATTERNS -> {
                val existing = query.findPatternSlotsByCharacterIds(listOf(character.id)).associateBy { it.slotCode }
                val incoming = snapshot.patternSlots.map { it.slot }.toSet()
                replace(existing.values.filter { it.slotCode !in incoming }, patternSlots)
                patternSlots.saveAll(snapshot.patternSlots.map { slot ->
                    existing[slot.slot]?.apply { label = slot.label; canLoad = slot.canLoad }
                        ?: CharacterPatternSlotEntity(
                            character = character, slotCode = slot.slot, label = slot.label, canLoad = slot.canLoad,
                        )
                })
                character.patternSlotCount = snapshot.patternSlots.size
            }
            CharacterSection.EQUIPMENT -> {
                replace(query.findEquipmentByCharacterIds(listOf(character.id)), equipment)
                equipment.saveAll(snapshot.equipment.mapIndexed { index, item ->
                    CharacterEquipmentEntity(
                        character = character, equipmentOrder = index, slot = item.slot, part = item.part,
                        name = item.name, iconUrl = item.iconUrl, description = item.description, checked = item.checked,
                    )
                })
            }
            CharacterSection.EQUIPMENT_CANDIDATES -> {
                replace(query.findEquipmentCandidates(character.id), candidates)
                candidates.saveAll(snapshot.equipmentCandidates.mapIndexed { index, item ->
                    CharacterEquipmentCandidateEntity(
                        character = character, candidateOrder = index, sourceValue = item.value,
                        typeCode = item.typeCode, name = item.name, iconUrl = item.iconUrl, description = item.description,
                        quantity = item.quantity,
                    )
                })
            }
            CharacterSection.SKILLS -> {
                replace(query.findSkillsByCharacterIds(listOf(character.id)), skills)
                skills.saveAll(
                    snapshot.learnedSkills.mapIndexed { index, skill -> skill.toEntity(character, CharacterSkillType.LEARNED, index) } +
                        snapshot.learnableSkills.mapIndexed { index, skill -> skill.toEntity(character, CharacterSkillType.LEARNABLE, index) },
                )
            }
            CharacterSection.MANAGEMENT -> Unit
        }
    }

    private fun recordSuccess(character: CharacterEntity, section: CharacterSection, count: Int, at: Instant) {
        val state = query.findSectionState(character.id, section)
            ?: CharacterSectionSyncStateEntity(
                character = character, section = section, status = CharacterSectionSyncStatus.SUCCESS,
                parserVersion = PARSER_VERSION, lastAttemptedAt = at,
            )
        state.status = CharacterSectionSyncStatus.SUCCESS
        state.parserVersion = PARSER_VERSION
        state.lastAttemptedAt = at
        state.lastSucceededAt = at
        state.errorCode = null
        state.errorMessage = null
        state.observedCount = count
        sections.save(state)
    }

    private fun recordFailure(
        character: CharacterEntity,
        section: CharacterSection,
        failure: CharacterSectionParseResult.Failure,
        at: Instant,
    ) {
        val state = query.findSectionState(character.id, section)
            ?: CharacterSectionSyncStateEntity(
                character = character, section = section, status = CharacterSectionSyncStatus.FAILED,
                parserVersion = failure.parserVersion, lastAttemptedAt = at,
            )
        state.status = CharacterSectionSyncStatus.FAILED
        state.parserVersion = failure.parserVersion
        state.lastAttemptedAt = at
        state.errorCode = failure.errorCode
        state.errorMessage = failure.safeMessage()
        state.observedCount = failure.observedCount
        sections.save(state)
    }

    private fun <T : Any> replace(existing: List<T>, repository: app.spammy.hof.common.persistence.CommandRepository<T, Long>) {
        if (existing.isEmpty()) return
        repository.deleteAll(existing)
        repository.flush()
    }

    private companion object { const val PARSER_VERSION = "character-v1" }
}

private fun CharacterStatsEntity.applySnapshot(value: app.spammy.hof.external.model.HofCharacterStats) {
    statusPoints = value.statusPoints; skillPoints = value.skillPoints
    atk = value.atk; matk = value.matk; defBase = value.defBase; defBonus = value.defBonus
    mdefBase = value.mdefBase; mdefBonus = value.mdefBonus; handleUsed = value.handleUsed; handleMax = value.handleMax
    costUsed = value.costUsed; costMax = value.costMax; expCurrent = value.expCurrent; expMax = value.expMax
    expMaxed = value.expMaxed; hpBase = value.hpBase; hpBonus = value.hpBonus; spBase = value.spBase; spBonus = value.spBonus
    strReal = value.strReal; strBonus = value.strBonus; intReal = value.intReal; intBonus = value.intBonus
    dexReal = value.dexReal; dexBonus = value.dexBonus; spdReal = value.spdReal; spdBonus = value.spdBonus
    lukReal = value.lukReal; lukBonus = value.lukBonus
    expDescription = value.descriptions["Exp"]; hpDescription = value.descriptions["HP"]; spDescription = value.descriptions["SP"]
    strDescription = value.descriptions["STR"]; intDescription = value.descriptions["INT"]
    dexDescription = value.descriptions["DEX"]; spdDescription = value.descriptions["SPD"]; lukDescription = value.descriptions["LUK"]
}

private fun app.spammy.hof.external.model.HofSkill.toEntity(
    character: CharacterEntity,
    type: CharacterSkillType,
    order: Int,
): CharacterSkillEntity = CharacterSkillEntity(
    character = character, skillType = type, skillOrder = order, sourceValue = value, name = name,
    iconUrl = iconUrl, category = category, targetText = targetText, scopeText = scopeText,
    spCost = spCost, multiplierText = multiplierText, description = description,
)
