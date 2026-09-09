package app.spammy.hof.character.repository

import app.spammy.hof.character.entity.CharacterOperationJobEntity
import app.spammy.hof.character.entity.CharacterOperationStatus
import app.spammy.hof.character.entity.CharacterOperationType
import app.spammy.hof.character.entity.CharacterRecoveryStatus
import app.spammy.hof.character.entity.QCharacterOperationJobEntity.characterOperationJobEntity
import app.spammy.hof.character.entity.QCharacterRecoveryOriginalEntity.characterRecoveryOriginalEntity
import app.spammy.hof.common.persistence.CommandRepository
import com.querydsl.jpa.impl.JPAQueryFactory
import org.springframework.stereotype.Repository

interface CharacterOperationJobCommandRepository : CommandRepository<CharacterOperationJobEntity, Long>

@Repository
class CharacterOperationJobQueryRepository(private val queryFactory: JPAQueryFactory) {
    fun findLatestSync(accountId: Long, characterId: Long? = null): CharacterOperationJobEntity? = queryFactory
        .selectFrom(characterOperationJobEntity)
        .where(characterOperationJobEntity.account.id.eq(accountId), syncTypes(),
            characterId?.let { characterOperationJobEntity.targetCharacterId.eq(it) })
        .orderBy(characterOperationJobEntity.id.desc())
        .fetchFirst()

    fun findRestoreAttempts(jobId: Long): Int? = queryFactory.select(characterRecoveryOriginalEntity.restoreAttempts)
        .from(characterRecoveryOriginalEntity)
        .where(characterRecoveryOriginalEntity.jobId.eq(jobId))
        .fetchOne()

    fun findCollectionResult(jobId: Long): Pair<Boolean, String?>? = queryFactory
        .select(characterRecoveryOriginalEntity.collectionComplete, characterRecoveryOriginalEntity.collectionError)
        .from(characterRecoveryOriginalEntity)
        .where(characterRecoveryOriginalEntity.jobId.eq(jobId))
        .fetchOne()?.let { (it.get(characterRecoveryOriginalEntity.collectionComplete) == true) to it.get(characterRecoveryOriginalEntity.collectionError) }

    /** 시작 event 순서와 무관하게 DB에 남은 미복원 작업을 먼저 보호한다. */
    fun hasUnrestoredJob(accountId: Long): Boolean = queryFactory.selectOne()
        .from(characterOperationJobEntity)
        .where(characterOperationJobEntity.account.id.eq(accountId), syncTypes(), unrestored())
        .fetchFirst() != null

    fun hasRecoveryHold(accountId: Long): Boolean = queryFactory.selectOne()
        .from(characterOperationJobEntity)
        .where(characterOperationJobEntity.account.id.eq(accountId), held())
        .fetchFirst() != null

    fun findConflictingJob(accountId: Long, exceptJobId: Long? = null): CharacterOperationJobEntity? = queryFactory
        .selectFrom(characterOperationJobEntity)
        .where(
            characterOperationJobEntity.account.id.eq(accountId),
            exceptJobId?.let { characterOperationJobEntity.id.ne(it) },
            held().or(characterOperationJobEntity.status.`in`(CharacterOperationStatus.PENDING, CharacterOperationStatus.RUNNING)),
        )
        .orderBy(characterOperationJobEntity.id.asc())
        .fetchFirst()

    private fun syncTypes() = characterOperationJobEntity.operationType.`in`(CharacterOperationType.DEEP_SYNC, CharacterOperationType.RESTORE)

    private fun unrestored() = characterOperationJobEntity.recoveryStatus.`in`(
        CharacterRecoveryStatus.REQUIRED, CharacterRecoveryStatus.RESTORING, CharacterRecoveryStatus.UNAVAILABLE,
    ).or(characterOperationJobEntity.recoveryStatus.isNull.and(
        characterOperationJobEntity.status.`in`(CharacterOperationStatus.PENDING, CharacterOperationStatus.RUNNING),
    ))

    private fun held() = unrestored().or(characterOperationJobEntity.automationIntentRevision.isNotNull
        .and(characterOperationJobEntity.automationReleased.isFalse))

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

    fun findPendingAutomationRelease(): List<CharacterOperationJobEntity> = queryFactory
        .selectFrom(characterOperationJobEntity)
        .where(characterOperationJobEntity.automationIntentRevision.isNotNull,
            characterOperationJobEntity.automationReleased.isFalse,
            characterOperationJobEntity.operationType.eq(CharacterOperationType.TRANSFER).or(
                characterOperationJobEntity.recoveryStatus.`in`(CharacterRecoveryStatus.NOT_STARTED, CharacterRecoveryStatus.RESTORED)),
            characterOperationJobEntity.status.notIn(CharacterOperationStatus.PENDING, CharacterOperationStatus.RUNNING))
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
