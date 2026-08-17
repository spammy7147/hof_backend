package app.spammy.hof.character.repository

import app.spammy.hof.character.entity.CharacterActionPatternEntity
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.entity.CharacterEquipmentEntity
import app.spammy.hof.character.entity.CharacterGuardSettingEntity
import app.spammy.hof.character.entity.CharacterPatternSlotEntity
import app.spammy.hof.character.entity.CharacterPositionChoiceEntity
import app.spammy.hof.character.entity.CharacterSkillEntity
import app.spammy.hof.character.entity.CharacterStatsEntity
import app.spammy.hof.character.entity.CharacterStatusLineEntity
import app.spammy.hof.character.entity.CharacterSection
import app.spammy.hof.character.entity.CharacterSectionSyncStateEntity
import app.spammy.hof.character.entity.CharacterStatusEffectEntity
import app.spammy.hof.character.entity.CharacterFaithEntity
import app.spammy.hof.character.entity.CharacterPatternOptionEntity
import app.spammy.hof.character.entity.CharacterEquipmentCandidateEntity
import app.spammy.hof.character.entity.CharacterSavedPatternRowEntity
import app.spammy.hof.character.entity.CharacterEquipmentSavedSlotEntity
import app.spammy.hof.character.entity.CharacterEquipmentSavedItemEntity
import app.spammy.hof.character.entity.QCharacterActionPatternEntity.characterActionPatternEntity
import app.spammy.hof.character.entity.QCharacterEntity.characterEntity
import app.spammy.hof.character.entity.QCharacterEquipmentEntity.characterEquipmentEntity
import app.spammy.hof.character.entity.QCharacterGuardSettingEntity.characterGuardSettingEntity
import app.spammy.hof.character.entity.QCharacterPatternSlotEntity.characterPatternSlotEntity
import app.spammy.hof.character.entity.QCharacterPositionChoiceEntity.characterPositionChoiceEntity
import app.spammy.hof.character.entity.QCharacterSkillEntity.characterSkillEntity
import app.spammy.hof.character.entity.QCharacterStatsEntity.characterStatsEntity
import app.spammy.hof.character.entity.QCharacterStatusLineEntity.characterStatusLineEntity
import app.spammy.hof.character.entity.QCharacterSectionSyncStateEntity.characterSectionSyncStateEntity
import app.spammy.hof.character.entity.QCharacterStatusEffectEntity.characterStatusEffectEntity
import app.spammy.hof.character.entity.QCharacterFaithEntity.characterFaithEntity
import app.spammy.hof.character.entity.QCharacterPatternOptionEntity.characterPatternOptionEntity
import app.spammy.hof.character.entity.QCharacterEquipmentCandidateEntity.characterEquipmentCandidateEntity
import app.spammy.hof.character.entity.QCharacterSavedPatternRowEntity.characterSavedPatternRowEntity
import app.spammy.hof.character.entity.QCharacterEquipmentSavedSlotEntity.characterEquipmentSavedSlotEntity
import app.spammy.hof.character.entity.QCharacterEquipmentSavedItemEntity.characterEquipmentSavedItemEntity
import com.querydsl.jpa.impl.JPAQueryFactory
import org.springframework.stereotype.Repository

@Repository
class CharacterQueryRepository(
    private val queryFactory: JPAQueryFactory,
) {
    fun findSectionState(characterId: Long, section: CharacterSection): CharacterSectionSyncStateEntity? =
        queryFactory.selectFrom(characterSectionSyncStateEntity)
            .where(
                characterSectionSyncStateEntity.character.id.eq(characterId),
                characterSectionSyncStateEntity.section.eq(section),
            )
            .fetchOne()

    fun findSectionStates(characterId: Long): List<CharacterSectionSyncStateEntity> =
        queryFactory.selectFrom(characterSectionSyncStateEntity)
            .where(characterSectionSyncStateEntity.character.id.eq(characterId))
            .orderBy(characterSectionSyncStateEntity.section.asc())
            .fetch()

    fun findSectionStatesByCharacterIds(characterIds: Collection<Long>): List<CharacterSectionSyncStateEntity> {
        if (characterIds.isEmpty()) return emptyList()
        return queryFactory.selectFrom(characterSectionSyncStateEntity)
            .where(characterSectionSyncStateEntity.character.id.`in`(characterIds))
            .orderBy(characterSectionSyncStateEntity.character.id.asc(), characterSectionSyncStateEntity.section.asc())
            .fetch()
    }

    fun findStatusEffects(characterId: Long): List<CharacterStatusEffectEntity> =
        queryFactory.selectFrom(characterStatusEffectEntity)
            .where(characterStatusEffectEntity.character.id.eq(characterId))
            .orderBy(characterStatusEffectEntity.effectOrder.asc(), characterStatusEffectEntity.id.asc())
            .fetch()

    fun findFaith(characterId: Long): CharacterFaithEntity? =
        queryFactory.selectFrom(characterFaithEntity)
            .where(characterFaithEntity.character.id.eq(characterId))
            .fetchOne()

    fun findPatternOptions(characterId: Long): List<CharacterPatternOptionEntity> =
        queryFactory.selectFrom(characterPatternOptionEntity)
            .where(characterPatternOptionEntity.character.id.eq(characterId))
            .orderBy(characterPatternOptionEntity.optionType.asc(), characterPatternOptionEntity.optionOrder.asc())
            .fetch()

    fun findEquipmentCandidates(characterId: Long): List<CharacterEquipmentCandidateEntity> =
        queryFactory.selectFrom(characterEquipmentCandidateEntity)
            .where(characterEquipmentCandidateEntity.character.id.eq(characterId))
            .orderBy(characterEquipmentCandidateEntity.candidateOrder.asc(), characterEquipmentCandidateEntity.id.asc())
            .fetch()

    fun findPatternSlot(characterId: Long, slotCode: String): CharacterPatternSlotEntity? =
        queryFactory.selectFrom(characterPatternSlotEntity)
            .where(
                characterPatternSlotEntity.character.id.eq(characterId),
                characterPatternSlotEntity.slotCode.eq(slotCode),
            )
            .fetchOne()

    fun findSavedPatternRows(patternSlotId: Long): List<CharacterSavedPatternRowEntity> =
        queryFactory.selectFrom(characterSavedPatternRowEntity)
            .where(characterSavedPatternRowEntity.patternSlot.id.eq(patternSlotId))
            .orderBy(characterSavedPatternRowEntity.rowIndex.asc(), characterSavedPatternRowEntity.id.asc())
            .fetch()

    fun findEquipmentSavedSlot(characterId: Long, slotNumber: Int): CharacterEquipmentSavedSlotEntity? =
        queryFactory.selectFrom(characterEquipmentSavedSlotEntity)
            .where(
                characterEquipmentSavedSlotEntity.character.id.eq(characterId),
                characterEquipmentSavedSlotEntity.slotNumber.eq(slotNumber),
            )
            .fetchOne()

    fun findEquipmentSavedItems(savedSlotId: Long): List<CharacterEquipmentSavedItemEntity> =
        queryFactory.selectFrom(characterEquipmentSavedItemEntity)
            .where(characterEquipmentSavedItemEntity.equipmentSavedSlot.id.eq(savedSlotId))
            .orderBy(characterEquipmentSavedItemEntity.itemOrder.asc(), characterEquipmentSavedItemEntity.id.asc())
            .fetch()
    /**
     * 계정의 캐릭터 핵심 행을 이름과 DB ID 오름차순으로 안정적으로 조회한다.
     */
    fun findAllByAccountId(accountId: Long): List<CharacterEntity> =
        queryFactory
            .selectFrom(characterEntity)
            .where(characterEntity.account.id.eq(accountId))
            .orderBy(characterEntity.name.asc(), characterEntity.id.asc())
            .fetch()

    /**
     * 계정과 HOF 원본 캐릭터 ID가 모두 일치하는 핵심 행을 조회한다.
     */
    fun findByAccountIdAndHofCharacterId(
        accountId: Long,
        hofCharacterId: String,
    ): CharacterEntity? =
        queryFactory
            .selectFrom(characterEntity)
            .where(
                characterEntity.account.id.eq(accountId),
                characterEntity.hofCharacterId.eq(hofCharacterId),
            )
            .fetchOne()

    fun findByAccountIdAndId(accountId: Long, characterId: Long): CharacterEntity? =
        queryFactory.selectFrom(characterEntity)
            .where(characterEntity.account.id.eq(accountId), characterEntity.id.eq(characterId))
            .fetchOne()

    /**
     * 전투 요청의 여러 HOF 캐릭터 ID에 해당하는 계정 소유 캐릭터를 한 번에 조회한다.
     */
    fun findByAccountIdAndHofCharacterIds(
        accountId: Long,
        hofCharacterIds: Collection<String>,
    ): List<CharacterEntity> {
        if (hofCharacterIds.isEmpty()) return emptyList()

        return queryFactory
            .selectFrom(characterEntity)
            .where(
                characterEntity.account.id.eq(accountId),
                characterEntity.hofCharacterId.`in`(hofCharacterIds),
            )
            .orderBy(characterEntity.id.asc())
            .fetch()
    }

    /**
     * 전체 행을 적재하지 않고 계정의 저장 캐릭터 수만 조회한다.
     */
    fun countByAccountId(accountId: Long): Long =
        queryFactory
            .select(characterEntity.count())
            .from(characterEntity)
            .where(characterEntity.account.id.eq(accountId))
            .fetchOne() ?: 0L

    /**
     * 여러 캐릭터의 상태 문장을 부모 ID와 원본 줄 순서대로 한 번에 조회한다.
     */
    fun findStatusLinesByCharacterIds(characterIds: Collection<Long>): List<CharacterStatusLineEntity> {
        if (characterIds.isEmpty()) return emptyList()

        return queryFactory
            .selectFrom(characterStatusLineEntity)
            .where(characterStatusLineEntity.character.id.`in`(characterIds))
            .orderBy(
                characterStatusLineEntity.character.id.asc(),
                characterStatusLineEntity.lineOrder.asc(),
                characterStatusLineEntity.id.asc(),
            )
            .fetch()
    }

    /**
     * 여러 캐릭터의 패턴 슬롯을 부모 ID와 슬롯 코드 순서대로 한 번에 조회한다.
     */
    fun findPatternSlotsByCharacterIds(characterIds: Collection<Long>): List<CharacterPatternSlotEntity> {
        if (characterIds.isEmpty()) return emptyList()

        return queryFactory
            .selectFrom(characterPatternSlotEntity)
            .where(characterPatternSlotEntity.character.id.`in`(characterIds))
            .orderBy(
                characterPatternSlotEntity.character.id.asc(),
                characterPatternSlotEntity.slotCode.asc(),
                characterPatternSlotEntity.id.asc(),
            )
            .fetch()
    }

    /**
     * 여러 캐릭터의 일대일 스탯 행을 부모 ID 순서로 한 번에 조회한다.
     */
    fun findStatsByCharacterIds(characterIds: Collection<Long>): List<CharacterStatsEntity> {
        if (characterIds.isEmpty()) return emptyList()

        return queryFactory
            .selectFrom(characterStatsEntity)
            .where(characterStatsEntity.character.id.`in`(characterIds))
            .orderBy(characterStatsEntity.character.id.asc())
            .fetch()
    }

    /**
     * 캐릭터 한 명의 일대일 스탯 행을 조회하며 저장 전이면 `null`을 반환한다.
     */
    fun findStatsByCharacterId(characterId: Long): CharacterStatsEntity? =
        queryFactory
            .selectFrom(characterStatsEntity)
            .where(characterStatsEntity.character.id.eq(characterId))
            .fetchOne()

    /**
     * 여러 캐릭터의 행동 패턴을 부모 ID와 원본 행 번호 순서대로 한 번에 조회한다.
     */
    fun findActionPatternsByCharacterIds(characterIds: Collection<Long>): List<CharacterActionPatternEntity> {
        if (characterIds.isEmpty()) return emptyList()

        return queryFactory
            .selectFrom(characterActionPatternEntity)
            .where(characterActionPatternEntity.character.id.`in`(characterIds))
            .orderBy(
                characterActionPatternEntity.character.id.asc(),
                characterActionPatternEntity.rowIndex.asc(),
                characterActionPatternEntity.id.asc(),
            )
            .fetch()
    }

    /**
     * 여러 캐릭터의 일대일 가드 설정을 부모 ID 순서로 한 번에 조회한다.
     */
    fun findGuardSettingsByCharacterIds(characterIds: Collection<Long>): List<CharacterGuardSettingEntity> {
        if (characterIds.isEmpty()) return emptyList()

        return queryFactory
            .selectFrom(characterGuardSettingEntity)
            .where(characterGuardSettingEntity.character.id.`in`(characterIds))
            .orderBy(characterGuardSettingEntity.character.id.asc())
            .fetch()
    }

    /**
     * 캐릭터 한 명의 일대일 가드 설정을 조회하며 저장 전이면 `null`을 반환한다.
     */
    fun findGuardSettingByCharacterId(characterId: Long): CharacterGuardSettingEntity? =
        queryFactory
            .selectFrom(characterGuardSettingEntity)
            .where(characterGuardSettingEntity.character.id.eq(characterId))
            .fetchOne()

    /**
     * 여러 캐릭터의 위치 선택지를 부모 ID와 원본 표시 순서대로 한 번에 조회한다.
     */
    fun findPositionChoicesByCharacterIds(characterIds: Collection<Long>): List<CharacterPositionChoiceEntity> {
        if (characterIds.isEmpty()) return emptyList()

        return queryFactory
            .selectFrom(characterPositionChoiceEntity)
            .where(characterPositionChoiceEntity.character.id.`in`(characterIds))
            .orderBy(
                characterPositionChoiceEntity.character.id.asc(),
                characterPositionChoiceEntity.choiceOrder.asc(),
                characterPositionChoiceEntity.id.asc(),
            )
            .fetch()
    }

    /**
     * 여러 캐릭터의 장비를 부모 ID와 원본 표시 순서대로 한 번에 조회한다.
     */
    fun findEquipmentByCharacterIds(characterIds: Collection<Long>): List<CharacterEquipmentEntity> {
        if (characterIds.isEmpty()) return emptyList()

        return queryFactory
            .selectFrom(characterEquipmentEntity)
            .where(characterEquipmentEntity.character.id.`in`(characterIds))
            .orderBy(
                characterEquipmentEntity.character.id.asc(),
                characterEquipmentEntity.equipmentOrder.asc(),
                characterEquipmentEntity.id.asc(),
            )
            .fetch()
    }

    /**
     * 여러 캐릭터의 스킬을 부모, 스킬 종류, 원본 표시 순서대로 한 번에 조회한다.
     */
    fun findSkillsByCharacterIds(characterIds: Collection<Long>): List<CharacterSkillEntity> {
        if (characterIds.isEmpty()) return emptyList()

        return queryFactory
            .selectFrom(characterSkillEntity)
            .where(characterSkillEntity.character.id.`in`(characterIds))
            .orderBy(
                characterSkillEntity.character.id.asc(),
                characterSkillEntity.skillType.asc(),
                characterSkillEntity.skillOrder.asc(),
                characterSkillEntity.id.asc(),
            )
            .fetch()
    }
}
