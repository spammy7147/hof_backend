package app.spammy.hof.character.repository

import app.spammy.hof.character.entity.CharacterSyncFailureEntity
import app.spammy.hof.character.entity.CharacterSyncJobEntity
import app.spammy.hof.character.entity.QCharacterSyncFailureEntity.characterSyncFailureEntity
import app.spammy.hof.character.entity.QCharacterSyncJobEntity.characterSyncJobEntity
import com.querydsl.jpa.impl.JPAQueryFactory
import org.springframework.stereotype.Repository

@Repository
class CharacterSyncJobQueryRepository(
    private val queryFactory: JPAQueryFactory,
) {
    /**
     * 기본 키가 일치하는 캐릭터 동기화 job을 조회하며 없으면 `null`을 반환한다.
     */
    fun findById(id: Long): CharacterSyncJobEntity? =
        queryFactory
            .selectFrom(characterSyncJobEntity)
            .where(characterSyncJobEntity.id.eq(id))
            .fetchOne()

    /**
     * 계정 소유권과 job ID가 모두 일치하는 동기화 job을 조회한다.
     */
    fun findByAccountIdAndId(
        accountId: Long,
        id: Long,
    ): CharacterSyncJobEntity? =
        queryFactory
            .selectFrom(characterSyncJobEntity)
            .where(
                characterSyncJobEntity.account.id.eq(accountId),
                characterSyncJobEntity.id.eq(id),
            )
            .fetchOne()

    /**
     * 동기화 job의 실패 캐릭터 행을 기록 순서와 ID 오름차순으로 조회한다.
     */
    fun findFailuresByJobId(syncJobId: Long): List<CharacterSyncFailureEntity> =
        queryFactory
            .selectFrom(characterSyncFailureEntity)
            .where(characterSyncFailureEntity.syncJob.id.eq(syncJobId))
            .orderBy(
                characterSyncFailureEntity.failureOrder.asc(),
                characterSyncFailureEntity.id.asc(),
            )
            .fetch()

    /**
     * 같은 job에 동일한 실패 캐릭터가 이미 기록됐는지 행 단위로 조회한다.
     */
    fun findFailureByJobIdAndHofCharacterId(
        syncJobId: Long,
        hofCharacterId: String,
    ): CharacterSyncFailureEntity? =
        queryFactory
            .selectFrom(characterSyncFailureEntity)
            .where(
                characterSyncFailureEntity.syncJob.id.eq(syncJobId),
                characterSyncFailureEntity.hofCharacterId.eq(hofCharacterId),
            )
            .fetchOne()
}
