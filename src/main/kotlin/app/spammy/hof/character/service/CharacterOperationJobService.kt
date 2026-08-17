package app.spammy.hof.character.service

import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.character.dto.CharacterOperationJobResponse
import app.spammy.hof.character.dto.CharacterTransferExecuteRequest
import app.spammy.hof.character.entity.CharacterOperationJobEntity
import app.spammy.hof.character.entity.CharacterOperationStatus
import app.spammy.hof.character.entity.CharacterOperationType
import app.spammy.hof.character.repository.CharacterOperationJobCommandRepository
import app.spammy.hof.character.repository.CharacterOperationJobQueryRepository
import app.spammy.hof.character.repository.CharacterQueryRepository
import app.spammy.hof.character.transfer.CharacterTransferExecutionResult
import app.spammy.hof.character.transfer.CharacterTransferSelection
import app.spammy.hof.character.transfer.CharacterTransferService
import app.spammy.hof.character.transfer.CharacterTransferStepResult
import app.spammy.hof.character.transfer.CharacterTransferStepStatus
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import java.util.concurrent.ConcurrentHashMap
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.task.TaskExecutor
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

/** 깊은 동기화와 설정 가져오기를 HTTP 수명과 분리하고 단계 결과를 영속화한다. */
@Service
class CharacterOperationJobService(
    private val accounts: AccountQueryRepository,
    private val characters: CharacterQueryRepository,
    private val commands: CharacterOperationJobCommandRepository,
    private val queries: CharacterOperationJobQueryRepository,
    private val deepSync: CharacterDeepSyncService,
    private val transfers: CharacterTransferService,
    private val sessionRecovery: HofSessionRecoveryService,
    private val objectMapper: ObjectMapper,
    private val timeProvider: TimeProvider,
    @Qualifier("characterSyncTaskExecutor") private val taskExecutor: TaskExecutor,
) {
    private val running = ConcurrentHashMap.newKeySet<Long>()

    @Transactional
    fun startDeepSync(accountId: Long, characterId: Long): CharacterOperationJobResponse {
        requireOwnedCharacter(accountId, characterId)
        val job = create(accountId, CharacterOperationType.DEEP_SYNC, null, characterId, null)
        startAfterCommit(job.id)
        return job.toResponse()
    }

    @Transactional
    fun startRestore(accountId: Long, characterId: Long): CharacterOperationJobResponse {
        requireOwnedCharacter(accountId, characterId)
        val job = create(accountId, CharacterOperationType.RESTORE, null, characterId, null)
        startAfterCommit(job.id)
        return job.toResponse()
    }

    @Transactional
    fun startTransfer(accountId: Long, request: CharacterTransferExecuteRequest): CharacterOperationJobResponse {
        val selection = request.selection()
        transfers.preview(accountId, selection)
        val job = create(
            accountId,
            CharacterOperationType.TRANSFER,
            request.sourceCharacterId,
            request.targetCharacterId,
            objectMapper.writeValueAsString(request),
        )
        job.completedStepIds = objectMapper.writeValueAsString(request.completedStepIds)
        commands.save(job)
        startAfterCommit(job.id)
        return job.toResponse()
    }

    fun find(accountId: Long, jobId: Long): CharacterOperationJobResponse = load(accountId, jobId).toResponse()

    /** 프로세스가 중간에 내려갔다면 완료 checkpoint 다음부터 다시 계획한다. */
    @EventListener(ApplicationReadyEvent::class)
    fun resumeIncompleteJobs() {
        queries.findIncomplete().forEach { job ->
            if (job.status == CharacterOperationStatus.RUNNING) {
                job.status = CharacterOperationStatus.PENDING
                job.message = "중단된 단계부터 다시 확인합니다."
                job.updatedAt = timeProvider.now()
                commands.save(job)
            }
            start(job.id)
        }
    }

    private fun create(
        accountId: Long,
        type: CharacterOperationType,
        sourceCharacterId: Long?,
        targetCharacterId: Long,
        requestPayload: String?,
    ): CharacterOperationJobEntity {
        val account = accounts.findById(accountId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "HOF 계정을 찾지 못했습니다.")
        val now = timeProvider.now()
        return commands.save(
            CharacterOperationJobEntity(
                account = account,
                operationType = type,
                sourceCharacterId = sourceCharacterId,
                targetCharacterId = targetCharacterId,
                requestPayload = requestPayload,
                startedAt = now,
                updatedAt = now,
            ),
        )
    }

    private fun startAfterCommit(jobId: Long) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            start(jobId)
            return
        }
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = start(jobId)
            },
        )
    }

    private fun start(jobId: Long) {
        if (!running.add(jobId)) return
        try {
            taskExecutor.execute {
                runCatching { runJob(jobId) }
                    .onFailure { fail(jobId, it.message ?: "캐릭터 작업을 완료하지 못했습니다.") }
                running.remove(jobId)
            }
        } catch (error: RuntimeException) {
            running.remove(jobId)
            fail(jobId, error.message ?: "캐릭터 작업을 시작하지 못했습니다.")
        }
    }

    private fun runJob(jobId: Long) {
        val job = queries.findById(jobId) ?: return
        job.status = CharacterOperationStatus.RUNNING
        job.updatedAt = timeProvider.now()
        commands.save(job)
        val accountId = job.account.id
        sessionRecovery.execute(accountId) {
            when (job.operationType) {
                CharacterOperationType.DEEP_SYNC -> runDeepSync(job, accountId)
                CharacterOperationType.RESTORE -> runRestore(job, accountId)
                CharacterOperationType.TRANSFER -> runTransfer(job, accountId)
            }
        }
        job.status = CharacterOperationStatus.COMPLETED
        job.updatedAt = timeProvider.now()
        job.finishedAt = job.updatedAt
        commands.save(job)
    }

    private fun runDeepSync(job: CharacterOperationJobEntity, accountId: Long) {
        val progress = mutableListOf<CharacterDeepSyncProgress>()
        val result = deepSync.synchronize(accountId, job.targetCharacterId) { step ->
            progress += step
            job.progressPayload = objectMapper.writeValueAsString(progress)
            job.updatedAt = timeProvider.now()
            commands.save(job)
        }
        job.resultPayload = objectMapper.writeValueAsString(result)
    }

    private fun runRestore(job: CharacterOperationJobEntity, accountId: Long) {
        val progress = mutableListOf<CharacterDeepSyncProgress>()
        val result = deepSync.restoreAndSynchronize(accountId, job.targetCharacterId) { step ->
            progress += step
            job.progressPayload = objectMapper.writeValueAsString(progress)
            job.updatedAt = timeProvider.now()
            commands.save(job)
        } ?: error("복원된 캐릭터의 현재 HOF 정보를 확인하지 못했습니다.")
        job.resultPayload = objectMapper.writeValueAsString(result)
    }

    private fun runTransfer(job: CharacterOperationJobEntity, accountId: Long) {
        val request = objectMapper.readValue(job.requestPayload, CharacterTransferExecuteRequest::class.java)
        val completed = readStrings(job.completedStepIds).toMutableSet()
        val results = mutableListOf<CharacterTransferStepResult>()
        val result = transfers.execute(accountId, request.selection(), completed) { step ->
            results.removeAll { it.stepId == step.stepId }
            results += step
            if (step.status == CharacterTransferStepStatus.COMPLETED) completed += step.stepId
            job.completedStepIds = objectMapper.writeValueAsString(completed)
            job.progressPayload = objectMapper.writeValueAsString(results)
            job.updatedAt = timeProvider.now()
            commands.save(job)
        }
        job.resultPayload = objectMapper.writeValueAsString(result)
    }

    private fun fail(jobId: Long, message: String) {
        val job = queries.findById(jobId) ?: return
        job.status = CharacterOperationStatus.FAILED
        job.message = message
        job.updatedAt = timeProvider.now()
        job.finishedAt = job.updatedAt
        commands.save(job)
    }

    private fun load(accountId: Long, jobId: Long): CharacterOperationJobEntity =
        queries.findByAccountIdAndId(accountId, jobId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "캐릭터 작업을 찾지 못했습니다.")

    private fun requireOwnedCharacter(accountId: Long, characterId: Long) {
        characters.findByAccountIdAndId(accountId, characterId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "캐릭터를 찾지 못했습니다.")
    }

    private fun CharacterTransferExecuteRequest.selection() = CharacterTransferSelection(
        sourceCharacterId,
        targetCharacterId,
        transfer,
    )

    private fun readStrings(payload: String): Set<String> = objectMapper.readValue(
        payload,
        object : TypeReference<Set<String>>() {},
    )

    private fun CharacterOperationJobEntity.toResponse(): CharacterOperationJobResponse {
        val deep = when {
            operationType == CharacterOperationType.TRANSFER -> null
            resultPayload != null -> objectMapper.readValue(resultPayload, CharacterDeepSyncResponse::class.java)
            else -> CharacterDeepSyncResponse(targetCharacterId, readProgress(progressPayload))
        }
        val transfer = when {
            operationType != CharacterOperationType.TRANSFER -> null
            resultPayload != null -> objectMapper.readValue(resultPayload, CharacterTransferExecutionResult::class.java)
            else -> readTransferProgress(targetCharacterId, progressPayload)
        }
        return CharacterOperationJobResponse(
            id,
            operationType,
            status,
            sourceCharacterId,
            targetCharacterId,
            deep,
            transfer,
            message,
            updatedAt,
            finishedAt,
        )
    }

    private fun readProgress(payload: String): List<CharacterDeepSyncProgress> = objectMapper.readValue(
        payload,
        object : TypeReference<List<CharacterDeepSyncProgress>>() {},
    )

    private fun readTransferProgress(targetCharacterId: Long, payload: String): CharacterTransferExecutionResult {
        val results: List<CharacterTransferStepResult> = objectMapper.readValue(
            payload,
            object : TypeReference<List<CharacterTransferStepResult>>() {},
        )
        val next = results.indexOfFirst { it.status != CharacterTransferStepStatus.COMPLETED }
            .let { if (it < 0) results.size else it }
        return CharacterTransferExecutionResult(targetCharacterId, results, next)
    }
}
