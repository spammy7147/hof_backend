package app.spammy.hof.character.service

import app.spammy.hof.character.dto.CharacterOperationJobResponse
import app.spammy.hof.character.dto.CharacterRecoveryPreviewResponse
import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.character.entity.CharacterOperationStatus
import app.spammy.hof.character.entity.CharacterRecoveryStatus
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.town.common.service.AccountHofMutationFence
import org.springframework.stereotype.Service

@Service
class CharacterOperationRecoveryService(
    private val jobs: CharacterOperationJobService,
    private val recovery: CharacterDeepSyncRecovery,
    private val remote: CharacterDeepSyncService,
    private val sessionRecovery: HofSessionRecoveryService,
    private val fence: AccountHofMutationFence,
) {
    /** HOF GET은 DB transaction 밖에서 수행하고, 기록 시 작업 상태를 다시 잠가 확인한다. */
    fun preview(accountId: Long, jobId: Long): CharacterRecoveryPreviewResponse = fence.execute(accountId) {
        val job = requireReviewable(accountId, jobId)
        val page = sessionRecovery.execute(accountId) { remote.observeCurrent(accountId, job.targetCharacterId) }
        capture(page)
        remote.recordCurrentObservation(accountId, job.targetCharacterId, page)
        recovery.recordReview(jobId, accountId, page)
    }

    fun accept(accountId: Long, jobId: Long, confirmationToken: String): CharacterOperationJobResponse = fence.execute(accountId) {
        val job = jobs.find(accountId, jobId)
        val current = if (job.recoveryStatus == CharacterRecoveryStatus.ACCEPTED) null else {
            requireReviewable(accountId, jobId)
            val page = sessionRecovery.execute(accountId) { remote.observeCurrent(accountId, job.targetCharacterId) }
            capture(page).also { remote.recordCurrentObservation(accountId, job.targetCharacterId, page) }
        }
        recovery.acceptReview(jobId, accountId, confirmationToken, current)
        jobs.find(accountId, jobId)
    }

    private fun requireReviewable(accountId: Long, jobId: Long) = jobs.find(accountId, jobId).also {
        if (jobs.isExecuting(jobId) || it.status !in setOf(CharacterOperationStatus.FAILED, CharacterOperationStatus.STOPPED) ||
            it.recoveryStatus !in setOf(CharacterRecoveryStatus.REQUIRED, CharacterRecoveryStatus.RESTORING, CharacterRecoveryStatus.UNAVAILABLE)) {
            throw ApiException(ErrorCode.CHARACTER_RECOVERY_REQUIRED, "작업 실행이 끝난 뒤 현재 상태를 확인해 주세요.")
        }
    }

    private fun capture(page: app.spammy.hof.external.parser.CharacterPageParseResult) = try {
        CharacterRestoreState.capture(page)
    } catch (error: IllegalStateException) {
        throw ApiException(ErrorCode.CHARACTER_RECOVERY_REQUIRED, "현재 서버의 설정을 완전히 확인하지 못했습니다. 다시 확인해 주세요.", error)
    }
}
