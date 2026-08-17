package app.spammy.hof.character.repository

import app.spammy.hof.character.entity.CharacterActionPatternEntity
import app.spammy.hof.character.entity.CharacterEquipmentEntity
import app.spammy.hof.character.entity.CharacterEquipmentCandidateEntity
import app.spammy.hof.character.entity.CharacterEquipmentSavedItemEntity
import app.spammy.hof.character.entity.CharacterEquipmentSavedSlotEntity
import app.spammy.hof.character.entity.CharacterFaithEntity
import app.spammy.hof.character.entity.CharacterGuardSettingEntity
import app.spammy.hof.character.entity.CharacterPatternSlotEntity
import app.spammy.hof.character.entity.CharacterPatternOptionEntity
import app.spammy.hof.character.entity.CharacterPositionChoiceEntity
import app.spammy.hof.character.entity.CharacterSkillEntity
import app.spammy.hof.character.entity.CharacterSavedPatternRowEntity
import app.spammy.hof.character.entity.CharacterSectionSyncStateEntity
import app.spammy.hof.character.entity.CharacterSectionSyncStateId
import app.spammy.hof.character.entity.CharacterStatsEntity
import app.spammy.hof.character.entity.CharacterStatusEffectEntity
import app.spammy.hof.character.entity.CharacterStatusLineEntity
import app.spammy.hof.character.entity.CharacterSyncFailureEntity
import app.spammy.hof.common.persistence.CommandRepository

/** 캐릭터 스탯 행의 쓰기 작업만 담당한다. */
interface CharacterStatsCommandRepository : CommandRepository<CharacterStatsEntity, Long>

/** 캐릭터 상태 문장 행의 쓰기 작업만 담당한다. */
interface CharacterStatusLineCommandRepository : CommandRepository<CharacterStatusLineEntity, Long>

/** 캐릭터 패턴 슬롯 행의 쓰기 작업만 담당한다. */
interface CharacterPatternSlotCommandRepository : CommandRepository<CharacterPatternSlotEntity, Long>

/** 캐릭터 행동 패턴 행의 쓰기 작업만 담당한다. */
interface CharacterActionPatternCommandRepository : CommandRepository<CharacterActionPatternEntity, Long>

/** 캐릭터 가드 설정 행의 쓰기 작업만 담당한다. */
interface CharacterGuardSettingCommandRepository : CommandRepository<CharacterGuardSettingEntity, Long>

/** 캐릭터 위치 선택지 행의 쓰기 작업만 담당한다. */
interface CharacterPositionChoiceCommandRepository : CommandRepository<CharacterPositionChoiceEntity, Long>

/** 캐릭터 장비 행의 쓰기 작업만 담당한다. */
interface CharacterEquipmentCommandRepository : CommandRepository<CharacterEquipmentEntity, Long>

/** 캐릭터 스킬 행의 쓰기 작업만 담당한다. */
interface CharacterSkillCommandRepository : CommandRepository<CharacterSkillEntity, Long>

interface CharacterSectionSyncStateCommandRepository :
    CommandRepository<CharacterSectionSyncStateEntity, CharacterSectionSyncStateId>

interface CharacterStatusEffectCommandRepository : CommandRepository<CharacterStatusEffectEntity, Long>

interface CharacterFaithCommandRepository : CommandRepository<CharacterFaithEntity, Long>

interface CharacterPatternOptionCommandRepository : CommandRepository<CharacterPatternOptionEntity, Long>

interface CharacterEquipmentCandidateCommandRepository : CommandRepository<CharacterEquipmentCandidateEntity, Long>

interface CharacterSavedPatternRowCommandRepository : CommandRepository<CharacterSavedPatternRowEntity, Long>

interface CharacterEquipmentSavedSlotCommandRepository : CommandRepository<CharacterEquipmentSavedSlotEntity, Long>

interface CharacterEquipmentSavedItemCommandRepository : CommandRepository<CharacterEquipmentSavedItemEntity, Long>

/** 캐릭터 동기화 실패 행의 쓰기 작업만 담당한다. */
interface CharacterSyncFailureCommandRepository : CommandRepository<CharacterSyncFailureEntity, Long>
