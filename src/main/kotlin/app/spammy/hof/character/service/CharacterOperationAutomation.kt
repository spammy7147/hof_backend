package app.spammy.hof.character.service

import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.automation.entity.TypedAutomationLifecycle
import app.spammy.hof.automation.service.AutomationStopReason
import app.spammy.hof.automation.repository.TypedAutomationQueryRepository
import app.spammy.hof.automation.service.TypedAutomationLifecycleBridge
import app.spammy.hof.character.entity.CharacterOperationType
import app.spammy.hof.character.entity.CharacterOperationStatus
import app.spammy.hof.character.entity.CharacterRecoveryStatus
import app.spammy.hof.character.transfer.CharacterTransferExecutionResult
import app.spammy.hof.character.transfer.CharacterTransferOutcome
import app.spammy.hof.character.repository.CharacterOperationJobQueryRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

/** 작업이 만든 일시정지와 복귀 자격을 같은 DB transaction으로 보존한다. */
@Service
class CharacterOperationAutomation(
    private val accounts: AccountQueryRepository,
    private val jobs: CharacterOperationJobQueryRepository,
    private val runtime: TypedAutomationQueryRepository,
    private val lifecycle: TypedAutomationLifecycleBridge,
    private val time: TimeProvider,
    private val objectMapper: ObjectMapper,
) {
    @Transactional
    fun begin(accountId: Long, jobId: Long) {
        requireNotNull(accounts.findByIdForUpdate(accountId)) { "HOF 계정을 찾지 못했습니다." }
        requireAvailable(accountId, jobId)
        val job = ownedJob(accountId, jobId)
        if (job.automationIntentRevision != null) return
        val state = runtime.lockRuntimeState(accountId)
        val wasRunning = state?.lifecycleStatus == TypedAutomationLifecycle.RUNNING
        val canResume = wasRunning && !state.authSuspended &&
            (job.operationType == CharacterOperationType.TRANSFER || job.recoveryStatus == CharacterRecoveryStatus.NOT_STARTED)
        if (wasRunning) lifecycle.pause(accountId, "CHARACTER_OPERATION_PAUSE")
        job.automationIntentRevision = state?.intentRevision ?: 0
        job.resumeAutomation = canResume
        job.automationReleased = false
        job.updatedAt = time.now()
    }

    @Transactional(readOnly = true)
    fun isReady(accountId: Long): Boolean = runtime.findRuntimeState(accountId)?.lifecycleStatus
        .let { it == null || it == TypedAutomationLifecycle.PAUSED || it == TypedAutomationLifecycle.STOPPED }

    @Transactional
    fun finish(accountId: Long, jobId: Long) {
        if (accounts.findByIdForUpdate(accountId) == null) return
        val job = ownedJob(accountId, jobId)
        if (job.automationReleased) return
        val settingsConfirmed = if (job.operationType == CharacterOperationType.TRANSFER) {
            // 최종 결과가 저장되기 전에 복귀하면 다음 행동이 임시 설정을 사용할 수 있다.
            if (job.status in setOf(CharacterOperationStatus.PENDING, CharacterOperationStatus.RUNNING)) return
            runCatching {
                job.resultPayload?.let { objectMapper.readValue(it, CharacterTransferExecutionResult::class.java) }
                    ?.let { result ->
                        result.finalSettingsConfirmed ||
                            (result.outcome == CharacterTransferOutcome.PREVIEW_CHANGED && result.results.isEmpty())
                    } == true
            }.getOrDefault(false)
        } else {
            if (job.recoveryStatus !in setOf(CharacterRecoveryStatus.NOT_STARTED, CharacterRecoveryStatus.RESTORED)) return
            true
        }
        val state = runtime.lockRuntimeState(accountId)
        job.automationReleased = true
        job.updatedAt = time.now()
        if (settingsConfirmed && job.resumeAutomation && state?.intentRevision == job.automationIntentRevision &&
            state?.lifecycleStatus == TypedAutomationLifecycle.PAUSED && !state.authSuspended) {
            lifecycle.resume(accountId, "CHARACTER_OPERATION_RESTORED")
        }
    }

    /** 사용자가 현재 상태를 수락하고 정지를 선택했으므로 이전 자동 복귀 자격을 폐기한다. */
    @Transactional
    fun acceptCurrent(accountId: Long, jobId: Long) {
        requireNotNull(accounts.findByIdForUpdate(accountId))
        val job = ownedJob(accountId, jobId)
        lifecycle.stop(accountId, AutomationStopReason.MANUAL_STOP, "CHARACTER_RECOVERY_ACCEPTED")
        job.resumeAutomation = false
        job.automationReleased = true
        job.updatedAt = time.now()
    }

    @Transactional(readOnly = true)
    fun requireAvailable(accountId: Long, exceptJobId: Long? = null) {
        if (jobs.findConflictingJob(accountId, exceptJobId) != null) {
            throw ApiException(ErrorCode.CHARACTER_RECOVERY_REQUIRED, "진행 중이거나 복원이 필요한 캐릭터 작업이 있습니다. 해당 작업을 먼저 확인해 주세요.")
        }
    }

    private fun ownedJob(accountId: Long, jobId: Long) = requireNotNull(jobs.findByAccountIdAndId(accountId, jobId))
}
