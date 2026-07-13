package app.spammy.hof.automation.service

import app.spammy.hof.automation.policy.AutomationDecision
import app.spammy.hof.automation.policy.AutomationSnapshot

data class RunnableAutomationJob(
    val jobId: Long,
    val accountId: Long,
    val currentStepIndex: Int,
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
