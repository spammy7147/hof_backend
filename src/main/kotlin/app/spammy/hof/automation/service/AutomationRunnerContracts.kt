package app.spammy.hof.automation.service

import app.spammy.hof.quest.model.QuestSnapshot
import java.time.Instant

interface TypedAutomationSnapshotLoader {
    fun loadTyped(accountId: Long): AutomationCoordinatorSnapshot
    fun loadEntry(
        accountId: Long,
        entryId: Long,
        targetKey: String? = null,
        questOverride: List<QuestSnapshot>? = null,
    ): AutomationCoordinatorEntry
}

fun interface TypedAutomationActionExecutor {
    fun execute(accountId: Long, action: StoredTypedAutomationAction): TypedAutomationExecution
}

sealed interface TypedAutomationExecution {
    data object Completed : TypedAutomationExecution

    data class BattleCompleted(
        val categoryId: String,
        val mapCode: String,
    ) : TypedAutomationExecution

    data class SharedCooldown(
        val categoryId: String,
        val mapCode: String,
        val retryAt: Instant,
    ) : TypedAutomationExecution
}

class SafeRetryableAutomationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class FatalAutomationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class AmbiguousAutomationSubmissionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class AutomationConfigurationException(
    override val message: String = "전투에 사용할 파티를 선택해 주세요.",
) : RuntimeException(message)
