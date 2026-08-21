package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.raid.RaidCycleOutcome
import app.spammy.hof.quest.model.QuestSnapshot
import java.time.Instant

interface TypedAutomationSnapshotLoader {
    fun loadEntry(
        accountId: Long,
        entryId: Long,
        targetKey: String? = null,
        questOverride: List<QuestSnapshot>? = null,
    ): AutomationEntrySnapshot
}

data class AutomationEntrySnapshot(
    val id: Long,
    val type: AutomationType,
    val quest: QuestAutomationSnapshot? = null,
    val battle: BattleMapAutomationSnapshot? = null,
    val adventure: AdventureMapAutomationSnapshot? = null,
    val union: UnionAutomationSnapshot? = null,
    val fishing: FishingAutomationSnapshot? = null,
    val homeQuest: HomeQuestAutomationSnapshot? = null,
)

enum class AutomationDecisionOutcome {
    SELECTED,
    SKIPPED,
    WAITING,
    CONFIGURATION_WARNING,
    CYCLE_COMPLETED,
    CYCLE_ABORTED,
    FATAL,
}

data class AutomationEvaluationTrace(
    val sequence: Int,
    val entryId: Long,
    val type: AutomationType,
    val outcome: AutomationDecisionOutcome,
    val reasonCode: String,
    val message: String,
    val nextRunAt: Instant? = null,
    val actionKind: String? = null,
    val targetKey: String? = null,
    val targetName: String? = null,
    val presetId: Long? = null,
)

sealed interface AutomationCoordination {
    val warnings: List<String>
    val trace: List<AutomationEvaluationTrace>

    data class Runnable(
        val entryId: Long,
        val action: PreparedAutomationAction,
        override val warnings: List<String>,
        override val trace: List<AutomationEvaluationTrace> = emptyList(),
    ) : AutomationCoordination

    data class Fatal(
        val reason: AutomationStopReason,
        val message: String,
        override val warnings: List<String>,
        override val trace: List<AutomationEvaluationTrace> = emptyList(),
    ) : AutomationCoordination

    data class Unavailable(
        val nextRunAt: Instant,
        override val warnings: List<String>,
        override val trace: List<AutomationEvaluationTrace> = emptyList(),
        val waitScope: AutomationWaitScope = AutomationWaitScope.RELEASE_OTHER_AUTOMATIONS,
    ) : AutomationCoordination

    data class Idle(
        override val warnings: List<String>,
        override val trace: List<AutomationEvaluationTrace> = emptyList(),
        val workTransition: AutomationWorkTransition? = null,
    ) : AutomationCoordination
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

    data class RaidCycleFinished(
        val outcome: RaidCycleOutcome,
    ) : TypedAutomationExecution
}

class SafeRetryableAutomationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class FatalAutomationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class AmbiguousAutomationSubmissionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class AutomationConfigurationException(
    override val message: String = "전투에 사용할 파티를 선택해 주세요.",
) : RuntimeException(message)
