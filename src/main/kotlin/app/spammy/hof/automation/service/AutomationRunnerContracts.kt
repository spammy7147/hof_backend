package app.spammy.hof.automation.service

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
