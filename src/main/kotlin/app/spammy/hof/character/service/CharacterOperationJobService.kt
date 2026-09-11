package app.spammy.hof.character.service

import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.character.dto.CharacterOperationJobResponse
import app.spammy.hof.character.dto.CharacterCollectionStatus
import app.spammy.hof.character.dto.CharacterTransferExecuteRequest
import app.spammy.hof.character.command.CharacterAutomationGate
import app.spammy.hof.character.entity.CharacterOperationJobEntity
import app.spammy.hof.character.entity.CharacterOperationStatus
import app.spammy.hof.character.entity.CharacterOperationType
import app.spammy.hof.character.entity.CharacterRecoveryStatus
import app.spammy.hof.character.repository.CharacterOperationJobCommandRepository
import app.spammy.hof.character.repository.CharacterOperationJobQueryRepository
import app.spammy.hof.character.repository.CharacterQueryRepository
import app.spammy.hof.character.transfer.CharacterTransferExecutionResult
import app.spammy.hof.character.transfer.CharacterTransferSelection
import app.spammy.hof.character.transfer.CharacterTransferService
import app.spammy.hof.character.transfer.CharacterTransferSnapshot
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

/** 외부 요청과 내부 재시작 기록을 분리하며 기존 request_payload 컬럼을 사용한다. */
private data class CharacterTransferJobRequest(
    val request: CharacterTransferExecuteRequest,
    val snapshot: CharacterTransferSnapshot? = null,
)

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
    private val automation: CharacterOperationAutomation,
    private val automationGate: CharacterAutomationGate,
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
            objectMapper.writeValueAsString(CharacterTransferJobRequest(request)),
        )
        job.completedStepIds = objectMapper.writeValueAsString(request.completedStepIds)
        commands.save(job)
        startAfterCommit(job.id)
        return job.toResponse()
    }

    fun find(accountId: Long, jobId: Long): CharacterOperationJobResponse = reconcileRelease(load(accountId, jobId)).toResponse()

    fun findCurrent(accountId: Long, characterId: Long? = null): CharacterOperationJobResponse? {
        characterId?.let { requireOwnedCharacter(accountId, it) }
        val held = queries.findConflictingJob(accountId)?.let(::reconcileRelease)
        // 이 조회는 깊은 동기화 복구 화면의 진입점이다. 가져오기 결과는 해당 작업 조회로 전달한다.
        return (held?.takeUnless { it.operationType == CharacterOperationType.TRANSFER }
            ?: queries.findLatestSync(accountId, characterId))?.let(::reconcileRelease)?.toResponse()
    }

    fun isExecuting(jobId: Long): Boolean = running.contains(jobId)

    @Transactional
    fun retryRecovery(accountId: Long, jobId: Long): CharacterOperationJobResponse {
        accounts.findByIdForUpdate(accountId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "HOF 계정을 찾지 못했습니다.")
        val job = reconcileRelease(load(accountId, jobId))
        if (job.recoveryStatus in setOf(CharacterRecoveryStatus.NOT_STARTED, CharacterRecoveryStatus.RESTORED, CharacterRecoveryStatus.ACCEPTED) ||
            job.status in setOf(CharacterOperationStatus.PENDING, CharacterOperationStatus.RUNNING) || running.contains(jobId)) return job.toResponse()
        val attempts = queries.findRestoreAttempts(jobId)
        if (job.operationType == CharacterOperationType.TRANSFER || attempts == null ||
            job.recoveryStatus !in setOf(CharacterRecoveryStatus.REQUIRED, CharacterRecoveryStatus.RESTORING) ||
            queries.findConflictingJob(accountId, jobId) != null) {
            throw ApiException(ErrorCode.CHARACTER_RECOVERY_REQUIRED, "같은 작업에 보존된 원본이 있어야 복구를 재시도할 수 있습니다. 현재 상태를 확인해 주세요.")
        }
        // 수집을 재시작하지 않고 최초 원본의 복구만 다시 시도한다. 누적 시도 횟수는 보존한다.
        job.recoveryStatus = CharacterRecoveryStatus.RESTORING
        job.restoreAttemptLimit = Math.addExact(attempts, 3)
        job.status = CharacterOperationStatus.PENDING
        job.message = "보존된 최초 원본으로 복구를 다시 확인합니다."
        job.finishedAt = null
        job.recoveryReviewToken = null
        job.recoveryReviewFingerprint = null
        job.recoveryReviewedAt = null
        job.updatedAt = timeProvider.now()
        commands.save(job)
        startAfterCommit(jobId)
        return job.toResponse()
    }

    /** 프로세스가 중간에 내려갔다면 완료 checkpoint 다음부터 다시 계획한다. */
    @EventListener(ApplicationReadyEvent::class)
    fun resumeIncompleteJobs() {
        queries.findPendingAutomationRelease().forEach(::reconcileRelease)
        queries.findIncomplete().forEach { job ->
            if (job.operationType == CharacterOperationType.TRANSFER && job.status == CharacterOperationStatus.RUNNING &&
                !objectMapper.readTree(job.requestPayload).has("request")) {
                fail(job.id, "중단 전 대상의 원래 설정 기록이 없습니다. 원본 서버의 현재 캐릭터 설정을 확인해 주세요.")
                return@forEach
            }
            if (job.operationType != CharacterOperationType.TRANSFER && job.recoveryStatus == null) {
                job.recoveryStatus = CharacterRecoveryStatus.UNAVAILABLE
                job.status = CharacterOperationStatus.FAILED
                job.message = "중단된 작업의 복원 원본이 없습니다. 원본 서버의 현재 캐릭터 설정을 확인해 주세요."
                job.updatedAt = timeProvider.now()
                job.finishedAt = job.updatedAt
                commands.save(job)
                return@forEach
            }
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
        val account = accounts.findByIdForUpdate(accountId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "HOF 계정을 찾지 못했습니다.")
        if (queries.findConflictingJob(accountId) != null) {
            throw ApiException(ErrorCode.CHARACTER_RECOVERY_REQUIRED, "진행 중이거나 복원이 필요한 캐릭터 작업을 먼저 확인해 주세요.")
        }
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
                recoveryStatus = if (type == CharacterOperationType.TRANSFER) null else CharacterRecoveryStatus.NOT_STARTED,
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
                try {
                    runCatching { runJob(jobId) }
                        .onFailure { fail(jobId, it.message ?: "캐릭터 작업을 완료하지 못했습니다.") }
                } finally {
                    running.remove(jobId)
                }
            }
        } catch (error: RuntimeException) {
            running.remove(jobId)
            fail(jobId, error.message ?: "캐릭터 작업을 시작하지 못했습니다.")
        }
    }

    private fun runJob(jobId: Long) {
        val job = queries.findById(jobId) ?: return
        if (job.recoveryStatus == CharacterRecoveryStatus.RESTORED && job.automationReleased) {
            // 자동화가 이미 사용한 현재 상태를 다시 원본과 비교하거나 변경하지 않는다.
            val progress = readProgress(job.progressPayload)
            val completed = progress.any { it.phase == CharacterDeepSyncPhase.COMPLETED }
            job.status = if (completed) CharacterOperationStatus.COMPLETED else CharacterOperationStatus.FAILED
            job.resultPayload = objectMapper.writeValueAsString(CharacterDeepSyncResponse(job.targetCharacterId, progress))
            job.message = if (completed) null else "저장 설정 수집을 완료하지 못했지만 원본 설정 복원은 확인했습니다."
            job.updatedAt = timeProvider.now()
            job.finishedAt = job.updatedAt
            commands.save(job)
            return
        }
        job.status = CharacterOperationStatus.RUNNING
        job.updatedAt = timeProvider.now()
        commands.save(job)
        val accountId = job.account.id
        fun executeAndComplete() {
            val resultPayload = when (job.operationType) {
                CharacterOperationType.DEEP_SYNC -> runDeepSync(job, accountId)
                CharacterOperationType.RESTORE -> runRestore(job, accountId)
                CharacterOperationType.TRANSFER -> sessionRecovery.execute(accountId) { runTransfer(job, accountId) }
            }
            // 복구 checkpoint를 저장한 이후의 상태를 읽어 detached job의 오래된 값으로 덮어쓰지 않는다.
            val completedJob = queries.findById(jobId) ?: return
            completedJob.status = CharacterOperationStatus.COMPLETED
            completedJob.resultPayload = resultPayload
            completedJob.updatedAt = timeProvider.now()
            completedJob.finishedAt = completedJob.updatedAt
            commands.save(completedJob)
        }
        if (job.operationType == CharacterOperationType.TRANSFER) {
            automationGate.executeJob(accountId, jobId,
                { error("자동화 일시정지를 완료하지 못했습니다. 현재 상태를 확인해 주세요.") }, ::executeAndComplete)
        } else {
            executeAndComplete()
        }
    }

    private fun runDeepSync(job: CharacterOperationJobEntity, accountId: Long): String =
        runSync(job) { report -> deepSync.synchronize(accountId, job.targetCharacterId, job.id, report) }

    private fun runRestore(job: CharacterOperationJobEntity, accountId: Long): String =
        runSync(job) { report ->
            deepSync.restoreAndSynchronize(accountId, job.targetCharacterId, job.id, report)
                ?: error("복원된 캐릭터의 현재 HOF 정보를 확인하지 못했습니다.")
        }

    private fun runSync(
        job: CharacterOperationJobEntity,
        synchronize: ((CharacterDeepSyncProgress) -> Unit) -> CharacterDeepSyncResponse,
    ): String {
        val progress = readProgress(job.progressPayload).toMutableList()
        val result = synchronize { step ->
            progress.removeAll {
                it.phase == step.phase && it.patternSlotCode == step.patternSlotCode && it.equipmentSlotNumber == step.equipmentSlotNumber
            }
            progress += step
            val latest = queries.findById(job.id) ?: error("캐릭터 작업을 찾지 못했습니다.")
            latest.progressPayload = objectMapper.writeValueAsString(progress)
            latest.updatedAt = timeProvider.now()
            commands.save(latest)
        }
        return objectMapper.writeValueAsString(result.copy(progress = progress))
    }

    private fun runTransfer(job: CharacterOperationJobEntity, accountId: Long): String {
        val payload = objectMapper.readTree(job.requestPayload)
        val stored = if (payload.has("request")) objectMapper.treeToValue(payload, CharacterTransferJobRequest::class.java)
        else CharacterTransferJobRequest(objectMapper.treeToValue(payload, CharacterTransferExecuteRequest::class.java))
        val request = stored.request
        val completed = readStrings(job.completedStepIds).toMutableSet()
        val results = mutableListOf<CharacterTransferStepResult>()
        val result = transfers.execute(accountId, request.selection(), completed, stored.snapshot, onSnapshot = { snapshot ->
            val latest = queries.findById(job.id) ?: error("캐릭터 작업을 찾지 못했습니다.")
            latest.requestPayload = objectMapper.writeValueAsString(stored.copy(snapshot = snapshot))
            latest.updatedAt = timeProvider.now()
            commands.save(latest)
        }) { step ->
            results.removeAll { it.stepId == step.stepId }
            results += step
            if (step.status == CharacterTransferStepStatus.COMPLETED) completed += step.stepId
            else completed -= step.stepId
            val latest = queries.findById(job.id) ?: error("캐릭터 작업을 찾지 못했습니다.")
            latest.completedStepIds = objectMapper.writeValueAsString(completed)
            latest.progressPayload = objectMapper.writeValueAsString(results)
            latest.updatedAt = timeProvider.now()
            commands.save(latest)
        }
        return objectMapper.writeValueAsString(result)
    }

    private fun fail(jobId: Long, message: String) {
        val job = queries.findById(jobId) ?: return
        // 완료 결과는 복귀 쓰기 실패와 별개다. 남은 보호 해제는 조회·재시작에서 재시도한다.
        if (job.status == CharacterOperationStatus.COMPLETED) return
        job.status = CharacterOperationStatus.FAILED
        job.message = message
        job.updatedAt = timeProvider.now()
        job.finishedAt = job.updatedAt
        commands.save(job)
        if (job.operationType == CharacterOperationType.TRANSFER && job.automationIntentRevision != null) {
            automation.finish(job.account.id, jobId)
        }
    }

    private fun load(accountId: Long, jobId: Long): CharacterOperationJobEntity =
        queries.findByAccountIdAndId(accountId, jobId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "캐릭터 작업을 찾지 못했습니다.")

    private fun reconcileRelease(job: CharacterOperationJobEntity): CharacterOperationJobEntity {
        if (!running.contains(job.id) &&
            job.status !in setOf(CharacterOperationStatus.PENDING, CharacterOperationStatus.RUNNING) &&
            job.automationIntentRevision != null && !job.automationReleased &&
            (job.operationType == CharacterOperationType.TRANSFER ||
                job.recoveryStatus in setOf(CharacterRecoveryStatus.NOT_STARTED, CharacterRecoveryStatus.RESTORED))) {
            // 원격 복원은 이미 확정됐다. 일시 DB 오류로 남은 보호 해제만 재실행한다.
            automation.finish(job.account.id, job.id)
            return load(job.account.id, job.id)
        }
        return job
    }

    private fun requireOwnedCharacter(accountId: Long, characterId: Long) {
        characters.findByAccountIdAndId(accountId, characterId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "캐릭터를 찾지 못했습니다.")
    }

    private fun CharacterTransferExecuteRequest.selection() = CharacterTransferSelection(
        sourceCharacterId,
        targetCharacterId,
        transfer,
        confirmationToken,
    )

    private fun readStrings(payload: String): Set<String> = objectMapper.readValue(
        payload,
        object : TypeReference<Set<String>>() {},
    )

    private fun CharacterOperationJobEntity.toResponse(): CharacterOperationJobResponse {
        val collection = if (operationType == CharacterOperationType.TRANSFER) null else queries.findCollectionResult(id)
        val collectionStatus = when {
            operationType == CharacterOperationType.TRANSFER -> null
            collection?.first == true || status == CharacterOperationStatus.COMPLETED -> CharacterCollectionStatus.COMPLETED
            collection?.second != null -> CharacterCollectionStatus.FAILED
            collection != null -> CharacterCollectionStatus.INCOMPLETE
            recoveryStatus == CharacterRecoveryStatus.NOT_STARTED -> CharacterCollectionStatus.NOT_STARTED
            else -> CharacterCollectionStatus.UNKNOWN
        }
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
            recoveryStatus,
            collectionStatus,
            collection?.second,
            collection != null && recoveryStatus in setOf(CharacterRecoveryStatus.REQUIRED, CharacterRecoveryStatus.RESTORING),
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
