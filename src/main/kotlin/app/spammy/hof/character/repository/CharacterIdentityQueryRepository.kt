package app.spammy.hof.character.repository

import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.entity.CharacterHofIdHistoryEntity
import app.spammy.hof.character.entity.QCharacterEntity.characterEntity
import app.spammy.hof.character.entity.QCharacterHofIdHistoryEntity.characterHofIdHistoryEntity
import com.querydsl.jpa.impl.JPAQueryFactory
import org.springframework.stereotype.Repository

/** 현재 ID와 과거 ID 모두에서 동일한 안정 캐릭터 행을 찾는 조회 경계다. */
@Repository
class CharacterIdentityQueryRepository(
    private val queryFactory: JPAQueryFactory,
) {
    fun findByAccountIdAndAnyHofCharacterId(
        accountId: Long,
        hofCharacterId: String,
    ): CharacterEntity? =
        queryFactory
            .select(characterEntity)
            .from(characterHofIdHistoryEntity)
            .join(characterHofIdHistoryEntity.character, characterEntity)
            .where(
                characterHofIdHistoryEntity.account.id.eq(accountId),
                characterHofIdHistoryEntity.hofCharacterId.eq(hofCharacterId),
            )
            .fetchOne()

    fun findOpenHistory(characterId: Long): CharacterHofIdHistoryEntity? =
        queryFactory
            .selectFrom(characterHofIdHistoryEntity)
            .where(
                characterHofIdHistoryEntity.character.id.eq(characterId),
                characterHofIdHistoryEntity.validTo.isNull,
            )
            .fetchOne()

    fun findHistory(characterId: Long): List<CharacterHofIdHistoryEntity> =
        queryFactory
            .selectFrom(characterHofIdHistoryEntity)
            .where(characterHofIdHistoryEntity.character.id.eq(characterId))
            .orderBy(characterHofIdHistoryEntity.validFrom.asc(), characterHofIdHistoryEntity.id.asc())
            .fetch()
}
