package app.spammy.hof.character.service

import app.spammy.hof.character.entity.CharacterSyncJobEntity
import app.spammy.hof.character.repository.CharacterSyncJobRepository
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.character.repository.CharacterSyncJobQueryRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 자식 작업과 전체 진행 기록을 함께 저장한 뒤 원격 변경을 시작한다. */
@Service
class CharacterBatchDeepSync(
    private val operations: CharacterOperationJobService,
    private val jobs: CharacterSyncJobRepository,
    private val queries: CharacterSyncJobQueryRepository,
    private val accounts: AccountQueryRepository,
) {
    @Transactional
    fun prepare(job: CharacterSyncJobEntity, characterId: Long): Long {
        checkNotNull(accounts.findByIdForUpdate(job.account.id))
        job.currentOperationJobId?.let { return it }
        val operationId = operations.prepareDeepSync(job.account.id, characterId)
        job.currentOperationJobId = operationId
        saveProgress(job)
        return operationId
    }

    @Transactional
    fun saveProgress(job: CharacterSyncJobEntity) {
        checkNotNull(accounts.findByIdForUpdate(job.account.id))
        // 별도 요청으로 저장한 중지 의도를 오래된 진행 객체가 지우지 않게 한다.
        queries.findById(job.id)?.let { job.stopRequested = it.stopRequested }
        jobs.save(job)
    }

    fun current(accountId: Long, operationId: Long) = operations.find(accountId, operationId)

    fun execute(accountId: Long, operationId: Long) = operations.executePreparedDeepSync(accountId, operationId)
}
