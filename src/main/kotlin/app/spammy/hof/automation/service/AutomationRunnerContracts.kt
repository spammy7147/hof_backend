package app.spammy.hof.automation.service

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
