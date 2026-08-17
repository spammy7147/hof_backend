package app.spammy.hof.character.repository

import app.spammy.hof.character.entity.CharacterOperationJobEntity
import app.spammy.hof.character.entity.CharacterOperationStatus
import app.spammy.hof.character.entity.QCharacterOperationJobEntity.characterOperationJobEntity
import app.spammy.hof.common.persistence.CommandRepository
import com.querydsl.jpa.impl.JPAQueryFactory
import org.springframework.stereotype.Repository

interface CharacterOperationJobCommandRepository : CommandRepository<CharacterOperationJobEntity, Long>

@Repository
class CharacterOperationJobQueryRepository(private val queryFactory: JPAQueryFactory) {
    fun findIncomplete(): List<CharacterOperationJobEntity> = queryFactory
        .selectFrom(characterOperationJobEntity)
        .where(
            characterOperationJobEntity.status.`in`(
                CharacterOperationStatus.PENDING,
                CharacterOperationStatus.RUNNING,
            ),
        )
        .orderBy(characterOperationJobEntity.id.asc())
        .fetch()

    fun findById(id: Long): CharacterOperationJobEntity? = queryFactory
        .selectFrom(characterOperationJobEntity)
        .where(characterOperationJobEntity.id.eq(id))
        .fetchOne()

    fun findByAccountIdAndId(accountId: Long, id: Long): CharacterOperationJobEntity? = queryFactory
        .selectFrom(characterOperationJobEntity)
        .where(
            characterOperationJobEntity.account.id.eq(accountId),
            characterOperationJobEntity.id.eq(id),
        )
        .fetchOne()
}
