package app.spammy.hof.character.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.character.dto.CharacterActionPatternResponse
import app.spammy.hof.character.dto.CharacterDetailResponse
import app.spammy.hof.character.dto.CharacterEquipmentResponse
import app.spammy.hof.character.dto.CharacterPatternSlotResponse
import app.spammy.hof.character.dto.CharacterPositionChoiceResponse
import app.spammy.hof.character.dto.CharacterPositionGuardResponse
import app.spammy.hof.character.dto.CharacterResponse
import app.spammy.hof.character.dto.CharacterSkillResponse
import app.spammy.hof.character.dto.CharacterStatsResponse
import app.spammy.hof.character.entity.CharacterActionPatternEntity
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.entity.CharacterEquipmentEntity
import app.spammy.hof.character.entity.CharacterGuardSettingEntity
import app.spammy.hof.character.entity.CharacterPatternSlotEntity
import app.spammy.hof.character.entity.CharacterPositionChoiceEntity
import app.spammy.hof.character.entity.CharacterSkillEntity
import app.spammy.hof.character.entity.CharacterSkillType
import app.spammy.hof.character.entity.CharacterStatsEntity
import app.spammy.hof.character.entity.CharacterStatusLineEntity
import app.spammy.hof.character.repository.CharacterActionPatternCommandRepository
import app.spammy.hof.character.repository.CharacterEquipmentCommandRepository
import app.spammy.hof.character.repository.CharacterGuardSettingCommandRepository
import app.spammy.hof.character.repository.CharacterPatternSlotCommandRepository
import app.spammy.hof.character.repository.CharacterPositionChoiceCommandRepository
import app.spammy.hof.character.repository.CharacterQueryRepository
import app.spammy.hof.character.repository.CharacterRepository
import app.spammy.hof.character.repository.CharacterSkillCommandRepository
import app.spammy.hof.character.repository.CharacterStatsCommandRepository
import app.spammy.hof.character.repository.CharacterStatusLineCommandRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.model.HofCharacter
import app.spammy.hof.external.model.HofCharacterStats
import app.spammy.hof.external.model.HofPositionGuard
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
/**
 * 캐릭터 핵심 정보와 정규화된 상세 스냅샷을 저장하고 DTO로 조립한다.
 */
class CharacterService(
    private val characterRepository: CharacterRepository,
    private val characterQueryRepository: CharacterQueryRepository,
    private val statsRepository: CharacterStatsCommandRepository,
    private val statusLineRepository: CharacterStatusLineCommandRepository,
    private val patternSlotRepository: CharacterPatternSlotCommandRepository,
    private val actionPatternRepository: CharacterActionPatternCommandRepository,
    private val guardSettingRepository: CharacterGuardSettingCommandRepository,
    private val positionChoiceRepository: CharacterPositionChoiceCommandRepository,
    private val equipmentRepository: CharacterEquipmentCommandRepository,
    private val skillRepository: CharacterSkillCommandRepository,
    private val timeProvider: TimeProvider,
) {
    /**
     * 계정 캐릭터를 이름과 ID 순서로 읽고 패턴 슬롯을 한 번의 bulk query로 결합한다.
     */
    @Transactional(readOnly = true)
    fun findAll(accountId: Long): List<CharacterResponse> {
        val characters = characterQueryRepository.findAllByAccountId(accountId)
        if (characters.isEmpty()) return emptyList()

        val slotsByCharacterId = characterQueryRepository
            .findPatternSlotsByCharacterIds(characters.map { it.id })
            .groupBy { it.character.id }

        return characters.map { character ->
            character.toResponse(slotsByCharacterId[character.id].orEmpty())
        }
    }

    /**
     * 특정 캐릭터의 핵심 행과 정규화된 모든 상세 자식을 조회해 기존 API DTO를 조립한다.
     */
    @Transactional(readOnly = true)
    fun findDetail(
        accountId: Long,
        hofCharacterId: String,
    ): CharacterDetailResponse {
        val character = characterQueryRepository.findByAccountIdAndHofCharacterId(accountId, hofCharacterId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "캐릭터를 찾지 못했습니다.")
        val characterIds = listOf(character.id)
        val positions = characterQueryRepository.findPositionChoicesByCharacterIds(characterIds)

        return character.toDetailResponse(
            statusLines = characterQueryRepository.findStatusLinesByCharacterIds(characterIds),
            patternSlots = characterQueryRepository.findPatternSlotsByCharacterIds(characterIds),
            stats = characterQueryRepository.findStatsByCharacterId(character.id),
            actionPatterns = characterQueryRepository.findActionPatternsByCharacterIds(characterIds),
            guardSetting = characterQueryRepository.findGuardSettingByCharacterId(character.id),
            positions = positions,
            equipment = characterQueryRepository.findEquipmentByCharacterIds(characterIds),
            skills = characterQueryRepository.findSkillsByCharacterIds(characterIds),
        )
    }

    /**
     * HOF roster 기본 정보와 상세 파싱 결과를 한 캐릭터 단위의 새 트랜잭션으로 upsert한다.
     *
     * 실제 상세 스냅샷이 없는 fallback 결과는 기존 상세 자식과 이미지를 보존한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun upsertCharacterSnapshot(
        account: HofAccountEntity,
        rosterCharacter: HofCharacter,
        detail: HofCharacter,
    ): CharacterResponse {
        val now = timeProvider.now()
        val existing = characterQueryRepository.findByAccountIdAndHofCharacterId(account.id, rosterCharacter.id)
        val character = existing ?: CharacterEntity(
            account = account,
            hofCharacterId = rosterCharacter.id,
            name = "",
            job = "",
            updatedAt = now,
        )

        val hasParsedDetail = detail.hasParsedDetail()
        when {
            hasParsedDetail -> {
                // 핵심 필드만 있어도 정상 파싱 결과이므로 빈 자식 컬렉션까지 새 스냅샷으로 반영한다.
                character.name = detail.name.ifBlank { rosterCharacter.name.ifBlank { "(이름없음)" } }
                character.job = detail.job
                character.level = detail.level
                character.patternSlotCount = detail.patternSlots.size
                character.imageUrl = detail.imageUrl.ifBlank { null }
                character.detailSyncedAt = now
            }

            existing == null -> {
                // 첫 저장의 ID-only fallback은 상세 자식을 만들지 않고 roster 핵심 정보만 사용한다.
                character.name = rosterCharacter.name.ifBlank { "(이름없음)" }
                character.job = rosterCharacter.job
                character.level = rosterCharacter.level
                character.patternSlotCount = rosterCharacter.patternSlots.size
                character.imageUrl = rosterCharacter.imageUrl.ifBlank { null }
            }

            rosterCharacter.name.isNotBlank() -> {
                // 기존 ID-only fallback은 상세 스냅샷을 건드리지 않고 roster 이름만 보정한다.
                character.name = rosterCharacter.name
            }
        }
        character.updatedAt = now

        val savedCharacter = characterRepository.save(character)
        if (hasParsedDetail) {
            replaceStatusLines(savedCharacter, detail)
            upsertPatternSlots(savedCharacter, detail)
            upsertStats(savedCharacter, detail)
            replaceActionPatterns(savedCharacter, detail)
            upsertGuardSetting(savedCharacter, detail)
            replacePositionChoices(savedCharacter, detail)
            replaceEquipment(savedCharacter, detail)
            replaceSkills(savedCharacter, detail)
        }

        val patternSlots = characterQueryRepository.findPatternSlotsByCharacterIds(listOf(savedCharacter.id))
        return savedCharacter.toResponse(patternSlots)
    }

    /** 신뢰 가능한 원격 명단에 없는 로컬 캐릭터를 제거한다. */
    @Transactional
    fun deleteCharactersAbsentFromRoster(accountId: Long, rosterIds: Set<String>) {
        require(rosterIds.isNotEmpty()) { "빈 캐릭터 명단으로 로컬 상태를 정리할 수 없습니다." }
        val stale = characterQueryRepository.findAllByAccountId(accountId)
            .filterNot { it.hofCharacterId in rosterIds }
        characterRepository.deleteAll(stale)
    }

    private fun replaceStatusLines(
        character: CharacterEntity,
        detail: HofCharacter,
    ) {
        val existing = characterQueryRepository.findStatusLinesByCharacterIds(listOf(character.id))
        if (existing.isNotEmpty()) {
            statusLineRepository.deleteAll(existing)
            statusLineRepository.flush()
        }
        statusLineRepository.saveAll(
            detail.statusLines.mapIndexed { index, content ->
                CharacterStatusLineEntity(
                    character = character,
                    lineOrder = index,
                    content = content,
                )
            },
        )
    }

    private fun upsertPatternSlots(
        character: CharacterEntity,
        detail: HofCharacter,
    ) {
        val existing = characterQueryRepository.findPatternSlotsByCharacterIds(listOf(character.id))
        val existingBySlotCode = existing.associateBy { it.slotCode }
        val incomingSlotCodes = detail.patternSlots.map { it.slot }.toSet()
        val removed = existing.filter { it.slotCode !in incomingSlotCodes }
        if (removed.isNotEmpty()) {
            patternSlotRepository.deleteAll(removed)
            patternSlotRepository.flush()
        }

        patternSlotRepository.saveAll(
            detail.patternSlots.map { slot ->
                existingBySlotCode[slot.slot]
                    ?.apply {
                        label = slot.label
                        canLoad = slot.canLoad
                    }
                    ?: CharacterPatternSlotEntity(
                        character = character,
                        slotCode = slot.slot,
                        label = slot.label,
                        canLoad = slot.canLoad,
                    )
            },
        )
    }

    private fun upsertStats(
        character: CharacterEntity,
        detail: HofCharacter,
    ) {
        val stats = characterQueryRepository.findStatsByCharacterId(character.id)
            ?: CharacterStatsEntity(character = character)
        stats.atk = detail.stats.atk
        stats.matk = detail.stats.matk
        stats.defBase = detail.stats.defBase
        stats.defBonus = detail.stats.defBonus
        stats.mdefBase = detail.stats.mdefBase
        stats.mdefBonus = detail.stats.mdefBonus
        stats.handleUsed = detail.stats.handleUsed
        stats.handleMax = detail.stats.handleMax
        stats.costUsed = detail.stats.costUsed
        stats.costMax = detail.stats.costMax
        statsRepository.save(stats)
    }

    private fun replaceActionPatterns(
        character: CharacterEntity,
        detail: HofCharacter,
    ) {
        val existing = characterQueryRepository.findActionPatternsByCharacterIds(listOf(character.id))
        if (existing.isNotEmpty()) {
            actionPatternRepository.deleteAll(existing)
            actionPatternRepository.flush()
        }
        actionPatternRepository.saveAll(
            detail.actionPatterns.map { row ->
                CharacterActionPatternEntity(
                    character = character,
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

    private fun upsertGuardSetting(
        character: CharacterEntity,
        detail: HofCharacter,
    ) {
        val guard = characterQueryRepository.findGuardSettingByCharacterId(character.id)
            ?: CharacterGuardSettingEntity(
                character = character,
                selectedPosition = "",
                guardValue = "",
                guardText = "",
            )
        guard.selectedPosition = detail.positionGuard.selectedPosition
        guard.guardValue = detail.positionGuard.guardValue
        guard.guardText = detail.positionGuard.guardText
        guardSettingRepository.save(guard)
    }

    private fun replacePositionChoices(
        character: CharacterEntity,
        detail: HofCharacter,
    ) {
        val existing = characterQueryRepository.findPositionChoicesByCharacterIds(listOf(character.id))
        if (existing.isNotEmpty()) {
            positionChoiceRepository.deleteAll(existing)
            positionChoiceRepository.flush()
        }
        positionChoiceRepository.saveAll(
            detail.positionGuard.positions.mapIndexed { index, position ->
                CharacterPositionChoiceEntity(
                    character = character,
                    choiceOrder = index,
                    value = position.value,
                    checked = position.checked,
                )
            },
        )
    }

    private fun replaceEquipment(
        character: CharacterEntity,
        detail: HofCharacter,
    ) {
        val existing = characterQueryRepository.findEquipmentByCharacterIds(listOf(character.id))
        if (existing.isNotEmpty()) {
            equipmentRepository.deleteAll(existing)
            equipmentRepository.flush()
        }
        equipmentRepository.saveAll(
            detail.equipment.mapIndexed { index, equipment ->
                CharacterEquipmentEntity(
                    character = character,
                    equipmentOrder = index,
                    slot = equipment.slot,
                    part = equipment.part,
                    name = equipment.name,
                    iconUrl = equipment.iconUrl,
                    description = equipment.description,
                    checked = equipment.checked,
                )
            },
        )
    }

    private fun replaceSkills(
        character: CharacterEntity,
        detail: HofCharacter,
    ) {
        val existing = characterQueryRepository.findSkillsByCharacterIds(listOf(character.id))
        if (existing.isNotEmpty()) {
            skillRepository.deleteAll(existing)
            skillRepository.flush()
        }
        val learned = detail.learnedSkills.mapIndexed { index, skill ->
            CharacterSkillEntity(
                character = character,
                skillType = CharacterSkillType.LEARNED,
                skillOrder = index,
                sourceValue = skill.value,
                name = skill.name,
                iconUrl = skill.iconUrl,
                category = skill.category,
            )
        }
        val learnable = detail.learnableSkills.mapIndexed { index, skill ->
            CharacterSkillEntity(
                character = character,
                skillType = CharacterSkillType.LEARNABLE,
                skillOrder = index,
                sourceValue = skill.value,
                name = skill.name,
                iconUrl = skill.iconUrl,
                category = skill.category,
            )
        }
        skillRepository.saveAll(learned + learnable)
    }

    /**
     * ID만 전달된 fallback과 핵심 정보까지 파싱된 정상 상세 결과를 구분한다.
     */
    private fun HofCharacter.hasParsedDetail(): Boolean =
        name.isNotBlank() ||
            job.isNotBlank() ||
            level != null ||
            imageUrl.isNotBlank() ||
            statusLines.isNotEmpty() ||
            patternSlots.isNotEmpty() ||
            stats != HofCharacterStats() ||
            actionPatterns.isNotEmpty() ||
            positionGuard != HofPositionGuard() ||
            equipment.isNotEmpty() ||
            learnedSkills.isNotEmpty() ||
            learnableSkills.isNotEmpty()

    private fun CharacterEntity.toResponse(patternSlots: List<CharacterPatternSlotEntity>): CharacterResponse =
        CharacterResponse(
            id = id,
            hofCharacterId = hofCharacterId,
            name = name,
            job = job,
            level = level,
            patternSlotCount = patternSlotCount,
            imageUrl = imageUrl,
            patternSlots = patternSlots.map { it.toResponse() },
        )

    private fun CharacterEntity.toDetailResponse(
        statusLines: List<CharacterStatusLineEntity>,
        patternSlots: List<CharacterPatternSlotEntity>,
        stats: CharacterStatsEntity?,
        actionPatterns: List<CharacterActionPatternEntity>,
        guardSetting: CharacterGuardSettingEntity?,
        positions: List<CharacterPositionChoiceEntity>,
        equipment: List<CharacterEquipmentEntity>,
        skills: List<CharacterSkillEntity>,
    ): CharacterDetailResponse =
        CharacterDetailResponse(
            id = id,
            hofCharacterId = hofCharacterId,
            name = name,
            job = job,
            level = level,
            patternSlotCount = patternSlotCount,
            imageUrl = imageUrl,
            statusLines = statusLines.map { it.content },
            patternSlots = patternSlots.map { it.toResponse() },
            stats = stats?.toResponse() ?: CharacterStatsResponse(),
            actionPatterns = actionPatterns.map { it.toResponse() },
            positionGuard = CharacterPositionGuardResponse(
                positions = positions.map { it.toResponse() },
                selectedPosition = guardSetting?.selectedPosition.orEmpty(),
                guardValue = guardSetting?.guardValue.orEmpty(),
                guardText = guardSetting?.guardText.orEmpty(),
            ),
            equipment = equipment.map { it.toResponse() },
            learnedSkills = skills
                .filter { it.skillType == CharacterSkillType.LEARNED }
                .map { it.toResponse() },
            learnableSkills = skills
                .filter { it.skillType == CharacterSkillType.LEARNABLE }
                .map { it.toResponse() },
        )

    private fun CharacterPatternSlotEntity.toResponse(): CharacterPatternSlotResponse =
        CharacterPatternSlotResponse(
            slot = slotCode,
            label = label,
            canLoad = canLoad,
        )

    private fun CharacterStatsEntity.toResponse(): CharacterStatsResponse =
        CharacterStatsResponse(
            atk = atk,
            matk = matk,
            defBase = defBase,
            defBonus = defBonus,
            mdefBase = mdefBase,
            mdefBonus = mdefBonus,
            handleUsed = handleUsed,
            handleMax = handleMax,
            costUsed = costUsed,
            costMax = costMax,
        )

    private fun CharacterActionPatternEntity.toResponse(): CharacterActionPatternResponse =
        CharacterActionPatternResponse(
            index = rowIndex,
            judge = judge,
            judgeText = judgeText,
            quantity = quantity,
            quantityText = quantityText,
            skill = skill,
            skillText = skillText,
        )

    private fun CharacterPositionChoiceEntity.toResponse(): CharacterPositionChoiceResponse =
        CharacterPositionChoiceResponse(
            value = value,
            checked = checked,
        )

    private fun CharacterEquipmentEntity.toResponse(): CharacterEquipmentResponse =
        CharacterEquipmentResponse(
            slot = slot,
            part = part,
            name = name,
            iconUrl = iconUrl,
            description = description,
            checked = checked,
        )

    private fun CharacterSkillEntity.toResponse(): CharacterSkillResponse =
        CharacterSkillResponse(
            value = sourceValue,
            name = name,
            iconUrl = iconUrl,
            category = category,
        )
}
