package app.spammy.hof.automation.service

import app.spammy.hof.automation.dto.AutomationJobResponse
import app.spammy.hof.automation.dto.CreateAutomationJobRequest
import app.spammy.hof.automation.entity.AutomationJobEntity
import app.spammy.hof.automation.repository.AutomationJobQueryRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
/**
 * 이전 자동화 job 기록의 읽기 호환성만 제공한다.
 *
 * 실행 모델은 typed 통합 자동화로 교체됐으므로 모든 레거시 job 변경은 명시적인 410으로 거부한다.
 */
class AutomationJobService(
    private val automationJobQueryRepository: AutomationJobQueryRepository,
) {
    /** 레거시 job 생성은 종료됐으며 typed 통합 자동화 설정/상태 API를 사용해야 한다. */
    fun create(
        @Suppress("UNUSED_PARAMETER") accountId: Long,
        @Suppress("UNUSED_PARAMETER") request: CreateAutomationJobRequest,
    ): AutomationJobResponse = legacyMutationRetired()

    /** 마이그레이션 전 기록을 읽는 클라이언트를 위해 현재 상태 조회만 보존한다. */
    @Transactional(readOnly = true)
    fun findCurrent(accountId: Long): AutomationJobResponse? =
        automationJobQueryRepository.findCurrentByAccountIdAndStatuses(accountId, ACTIVE_STATUSES)
            ?.toResponse()

    /** 레거시 job 상태 변경은 모두 종료됐다. */
    fun pause(
        @Suppress("UNUSED_PARAMETER") accountId: Long,
        @Suppress("UNUSED_PARAMETER") jobId: Long,
    ): AutomationJobResponse = legacyMutationRetired()

    /** 레거시 job 상태 변경은 모두 종료됐다. */
    fun resume(
        @Suppress("UNUSED_PARAMETER") accountId: Long,
        @Suppress("UNUSED_PARAMETER") jobId: Long,
    ): AutomationJobResponse = legacyMutationRetired()

    /** 레거시 job 상태 변경은 모두 종료됐다. */
    fun cancel(
        @Suppress("UNUSED_PARAMETER") accountId: Long,
        @Suppress("UNUSED_PARAMETER") jobId: Long,
    ): AutomationJobResponse = legacyMutationRetired()

    private fun legacyMutationRetired(): Nothing =
        throw ApiException(
            ErrorCode.LEGACY_AUTOMATION_RETIRED,
            "이전 자동화 job 실행 API는 종료되었습니다. 통합 자동화 API를 사용해주세요.",
        )

    private fun AutomationJobEntity.toResponse(): AutomationJobResponse =
        AutomationJobResponse(
            id = id,
            accountId = account.id,
            profileId = profile.id,
            status = status,
            currentStepIndex = currentStepIndex,
            message = message,
            createdAt = createdAt.toString(),
            startedAt = startedAt?.toString(),
            updatedAt = updatedAt.toString(),
            finishedAt = finishedAt?.toString(),
        )

    private companion object {
        val ACTIVE_STATUSES = setOf("PENDING", "RUNNING", "WAITING_CAPTCHA", "WAITING_CONFIG", "WAITING_LOGIN", "PAUSED")
    }
}
