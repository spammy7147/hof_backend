package app.spammy.hof.automation.service

import app.spammy.hof.automation.policy.AutomationDecision
import app.spammy.hof.automation.policy.AutomationSnapshot

data class RunnableAutomationJob(
    val jobId: Long,
    val accountId: Long,
    val currentStepIndex: Int,
    val retryAction: RetryableAutomationAction? = null,
)

/** 재정렬·설정 변경과 무관하게 저장된 payload 그대로 다시 실행해야 하는 기존 action이다. */
data class RetryableAutomationAction(
    val actionId: Long,
    val decision: app.spammy.hof.automation.policy.AutomationDecision,
)

fun interface AutomationSnapshotLoader {
    fun load(accountId: Long): AutomationSnapshot
}

fun interface AutomationActionExecutor {
    /** 결과를 민감정보가 제거된 JSON 요약으로 반환한다. */
    fun execute(accountId: Long, decision: AutomationDecision): String
}

class AutomationConfigurationException(
    override val message: String = "전투에 사용할 파티를 선택해 주세요.",
) : RuntimeException(message)
