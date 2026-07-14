package app.spammy.hof.automation.service

import app.spammy.hof.automation.policy.AutomationDecision
import app.spammy.hof.automation.policy.AutomationSnapshot
import app.spammy.hof.battle.dto.RunBattleRequest

data class RunnableAutomationJob(
    val jobId: Long,
    val accountId: Long,
    val currentStepIndex: Int,
    val retryAction: RetryableAutomationAction? = null,
)

/** 재정렬·설정 변경과 무관하게 저장된 payload 그대로 다시 실행해야 하는 기존 action이다. */
data class RetryableAutomationAction(
    val actionId: Long,
    val payload: AutomationExecutionPayload,
)

/**
 * 외부 HOF 요청 직전에 모든 가변 설정을 해석해 고정한 실행 payload다.
 *
 * 전투는 캐릭터 ID, 패턴 슬롯, 전투 횟수까지 포함한 [resolvedBattleRequest]를 저장한다. 따라서 action
 * 재시도 전에 프리셋이나 캐릭터 패턴이 바뀌어도 이미 시작한 action은 같은 요청을 사용한다. 퀘스트는
 * [resolvedActionNo]를 저장해 수락·완료 링크가 후속 설정 조회에 의해 바뀌지 않게 한다.
 */
data class AutomationExecutionPayload(
    val decision: AutomationDecision,
    val resolvedBattleRequest: RunBattleRequest? = null,
    val resolvedActionNo: String? = null,
)

fun interface AutomationSnapshotLoader {
    fun load(accountId: Long): AutomationSnapshot
}

interface AutomationActionExecutor {
    /** DB 설정만 읽어 외부 side effect 없이 실제 실행 요청을 고정한다. */
    fun prepare(accountId: Long, decision: AutomationDecision): AutomationExecutionPayload

    /** 준비 단계에서 고정한 요청만 실행하고 민감정보가 제거된 JSON 결과 요약을 반환한다. */
    fun execute(accountId: Long, payload: AutomationExecutionPayload): String
}

class AutomationConfigurationException(
    override val message: String = "전투에 사용할 파티를 선택해 주세요.",
) : RuntimeException(message)
