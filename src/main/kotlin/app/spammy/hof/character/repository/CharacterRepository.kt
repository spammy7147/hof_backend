package app.spammy.hof.character.repository

import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.common.persistence.CommandRepository

/**
 * 캐릭터 핵심 엔티티의 쓰기 작업만 담당하는 Spring Data repository다.
 */
interface CharacterRepository : CommandRepository<CharacterEntity, Long>
