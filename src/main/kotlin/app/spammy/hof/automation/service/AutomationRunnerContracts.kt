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

/**
 * 실행 시도 한 번을 식별하는 불변 토큰이다.
 *
 * 같은 action row를 재시도할 때 [attemptCount]가 증가하므로, 이전 외부 요청의 늦은 응답은 현재 시도와
 * 구분된다. HOF가 idempotency key를 제공하지 않아 외부 요청 자체는 at-least-once이지만, 늦은 응답이
 * 최신 checkpoint를 성공·실패로 덮어쓰는 일은 이 토큰으로 차단한다.
 */
data class AutomationExecutionToken(
    val actionId: Long,
    val attemptCount: Int,
)

/** 저장 payload와 레거시 준비 필요 여부를 함께 전달하는 기존 action이다. */
data class RetryableAutomationAction(
    val actionId: Long,
    val payload: AutomationExecutionPayload,
    val requiresPreparation: Boolean = false,
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

fun interface TypedAutomationSnapshotLoader {
    fun loadTyped(accountId: Long): AutomationCoordinatorSnapshot
}

fun interface TypedAutomationActionExecutor {
    fun execute(accountId: Long, action: StoredTypedAutomationActionV1)
}

class SafeRetryableAutomationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class FatalAutomationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class AmbiguousAutomationSubmissionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class AutomationConfigurationException(
    override val message: String = "전투에 사용할 파티를 선택해 주세요.",
) : RuntimeException(message)
