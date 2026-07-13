package app.spammy.hof.automation.dto

/**
 * 자동화 job을 새로 만들 때 앱이 보내는 요청 DTO다.
 */
data class CreateAutomationJobRequest(
    val profileId: Long,
)

/**
 * 현재 진행 중이거나 저장된 자동화 job 상태를 앱으로 내려주는 응답 DTO다.
 */
data class AutomationJobResponse(
    val id: Long,
    val accountId: Long,
    val profileId: Long,
    val status: String,
    val currentStepIndex: Int,
    val message: String?,
    val createdAt: String,
    val startedAt: String?,
    val updatedAt: String,
    val finishedAt: String?,
)
