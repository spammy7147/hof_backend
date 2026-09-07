package app.spammy.hof.character.service

import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.automation.entity.TypedAutomationLifecycle
import app.spammy.hof.automation.service.AutomationStopReason
import app.spammy.hof.automation.repository.TypedAutomationQueryRepository
import app.spammy.hof.automation.service.TypedAutomationLifecycleBridge
import app.spammy.hof.character.entity.CharacterOperationType
import app.spammy.hof.character.entity.CharacterRecoveryStatus
import app.spammy.hof.character.repository.CharacterOperationJobQueryRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 작업이 만든 일시정지와 복귀 자격을 같은 DB transaction으로 보존한다. */
@Service
class CharacterOperationAutomation(
    private val accounts: AccountQueryRepository,
    private val jobs: CharacterOperationJobQueryRepository,
    private val runtime: TypedAutomationQueryRepository,
    private val lifecycle: TypedAutomationLifecycleBridge,
    private val time: TimeProvider,
) {
    @Transactional
    fun begin(accountId: Long, jobId: Long) {
        requireNotNull(accounts.findByIdForUpdate(accountId)) { "HOF 계정을 찾지 못했습니다." }
        requireAvailable(accountId, jobId)
        val job = ownedJob(accountId, jobId)
        if (job.automationIntentRevision != null) return
        val state = runtime.lockRuntimeState(accountId)
        val wasRunning = state?.lifecycleStatus == TypedAutomationLifecycle.RUNNING
        val canResume = wasRunning && !state.authSuspended && job.recoveryStatus == CharacterRecoveryStatus.NOT_STARTED
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
        if (job.automationReleased || job.recoveryStatus !in setOf(CharacterRecoveryStatus.NOT_STARTED, CharacterRecoveryStatus.RESTORED)) return
        val state = runtime.lockRuntimeState(accountId)
        job.automationReleased = true
        job.updatedAt = time.now()
        if (job.resumeAutomation && state?.intentRevision == job.automationIntentRevision &&
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
        if (jobs.findConflictingSync(accountId, exceptJobId) != null) {
            throw ApiException(ErrorCode.CHARACTER_RECOVERY_REQUIRED, "진행 중이거나 복원이 필요한 캐릭터 작업이 있습니다. 해당 작업을 먼저 확인해 주세요.")
        }
    }

    private fun ownedJob(accountId: Long, jobId: Long) = requireNotNull(jobs.findByAccountIdAndId(accountId, jobId)).also {
        check(it.operationType != CharacterOperationType.TRANSFER) { "전체 설정 동기화 작업이 아닙니다." }
    }
}
