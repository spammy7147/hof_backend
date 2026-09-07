package app.spammy.hof.character.repository

import app.spammy.hof.character.entity.CharacterRecoveryOriginalEntity
import app.spammy.hof.character.entity.QCharacterRecoveryOriginalEntity.characterRecoveryOriginalEntity
import app.spammy.hof.common.persistence.CommandRepository
import com.querydsl.jpa.impl.JPAQueryFactory
import org.springframework.stereotype.Repository

interface CharacterRecoveryCommandRepository : CommandRepository<CharacterRecoveryOriginalEntity, Long>

@Repository
class CharacterRecoveryQueryRepository(private val queryFactory: JPAQueryFactory) {
    fun findByJobId(jobId: Long): CharacterRecoveryOriginalEntity? = queryFactory
        .selectFrom(characterRecoveryOriginalEntity)
        .where(characterRecoveryOriginalEntity.jobId.eq(jobId))
        .fetchOne()
}
