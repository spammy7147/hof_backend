package app.spammy.hof.automation.service

import app.spammy.hof.quest.model.QuestSnapshot

/**
 * 일반 퀘스트의 다음 행동 판단과 실행 결과 반영을 함께 소유하는 경계다.
 *
 * 외부 HOF 호출은 공통 행동 수명주기가 수행하고, 이 module은 호출 전 의도와
 * 호출 후 권위 관측을 같은 규칙으로 연결한다.
 */
interface QuestWorkCycleModule {
    fun decideNext(snapshot: QuestAutomationSnapshot): QuestDirective

    fun recordObservedResult(
        accountId: Long,
        attempt: QuestAttempt,
        observation: QuestResultObservation,
    ): QuestRecordResult
}

sealed interface QuestDirective {
    data class Execute(val action: QuestAction) : QuestDirective
    data class WaitUntil(
        val nextRunAt: java.time.Instant,
        val reasonCode: String,
        val message: String,
    ) : QuestDirective
    data class Recheck(
        val at: java.time.Instant,
        val reasonCode: String,
        val message: String,
    ) : QuestDirective
    data class WaitForResource(
        val resourceName: String,
        val missingCount: Int?,
    ) : QuestDirective
    data object WaitForUnknownCooldown : QuestDirective
    data class WaitForConfiguration(
        val message: String,
        val reasonCode: String,
    ) : QuestDirective
    data object CompleteWork : QuestDirective
    data class Hold(val message: String, val reasonCode: String) : QuestDirective
    data class Fatal(val reason: AutomationStopReason, val message: String) : QuestDirective
    data object Skip : QuestDirective
}

sealed interface QuestAttempt {
    val resultIdentity: String
    val questKey: String

    data class Accept(
        override val resultIdentity: String,
        override val questKey: String,
        val actionNo: String,
    ) : QuestAttempt

    data class Claim(
        override val resultIdentity: String,
        override val questKey: String,
        val actionNo: String,
    ) : QuestAttempt

    data class Battle(
        override val resultIdentity: String,
        val action: QuestAction.Battle,
    ) : QuestAttempt {
        override val questKey: String = action.questKey
    }
}

sealed interface QuestResultObservation {
    data class Page(
        val quests: List<QuestSnapshot>,
        val complete: Boolean = false,
    ) : QuestResultObservation
    data class BattleRounds(val outcomes: List<BattleAutomationRoundOutcome>) : QuestResultObservation
}

sealed interface QuestRecordResult {
    data class Recorded(val questCycle: String? = null) : QuestRecordResult
    data class NotApplied(val message: String) : QuestRecordResult
    data class NeedsRecheck(val message: String) : QuestRecordResult
    /** 이전 전투 결과를 귀속하지 않아도 최신 퀘스트 진행도에서 다음 행동을 판단할 수 있다. */
    data class FreshDecision(val message: String) : QuestRecordResult
}

sealed interface QuestProgressReconciliation {
    data object Applied : QuestProgressReconciliation
    data object Stale : QuestProgressReconciliation
}
