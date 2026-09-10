package app.spammy.hof.automation.convergence

import java.time.Instant

enum class ActionConvergenceResult {
    APPLIED,
    NOT_APPLIED,
    SUPERSEDED,
    PENDING,
    HELD,
    RESULT_UNOBSERVED,
}

enum class AutomationActionKind(val battle: Boolean = false) {
    QUEST_ACCEPT,
    QUEST_CLAIM,
    QUEST_BATTLE(true),
    HOME_ACCEPT,
    HOME_CLAIM,
    MAP_BATTLE(true),
    ADVENTURE_BATTLE(true),
    UNION_BATTLE(true),
    FISHING_START,
    FISHING_CATCH,
    FISHING_OBSTRUCTION_BATTLE(true),
    RAID_RESET,
    RAID_REGISTER,
    RAID_START,
    RAID_REWARD,
    RAID_REFRESH,
    RAID_BATTLE(true),
    RAID_CYCLE_ABORT,
}

enum class AutomationIsolationScopeKind {
    QUEST_TARGET,
    HOME_TARGET,
    BATTLE_COOLDOWN_SCOPE,
    UNION_ENTRY,
    FISHING_ENTRY,
    RAID_ENTRY,
}

data class AutomationIsolationScope(
    val kind: AutomationIsolationScopeKind,
    val key: String,
) {
    init {
        require(key.isNotBlank()) { "Automation isolation scope key must not be blank." }
    }
}

data class AuthoritativeConvergenceBaseline(
    val scope: AutomationIsolationScope,
    val fingerprint: String,
)

data class SelectedAutomationAction(
    val entryId: Long,
    val executionIdentity: String,
    val actionKind: AutomationActionKind,
    val scope: AutomationIsolationScope,
    val policyVersion: String,
    val baselineFingerprint: String,
    /** 제출 payload 없이 selector가 최신 GET 관측만 반복하는 gap 상태다. */
    val observationOnly: Boolean = false,
) {
    init {
        require(entryId > 0) { "Automation entry id must be positive." }
        require(executionIdentity.isNotBlank()) { "Execution identity must not be blank." }
        require(policyVersion.isNotBlank()) { "Policy version must not be blank." }
        require(baselineFingerprint.isNotBlank()) { "Baseline fingerprint must not be blank." }
    }
}

sealed interface AutomationActionEvidence {
    val capturedAt: Instant
    val responseShapeFingerprint: String?
        get() = null
    val sanitizedSnippet: String?
        get() = null

    data class DirectApplied(
        override val capturedAt: Instant,
        val stateFingerprint: String,
        override val responseShapeFingerprint: String? = null,
        override val sanitizedSnippet: String? = null,
    ) : AutomationActionEvidence

    data class DirectRejected(
        override val capturedAt: Instant,
        val reason: String,
        override val responseShapeFingerprint: String? = null,
        override val sanitizedSnippet: String? = null,
    ) : AutomationActionEvidence

    data class StateAdvanced(
        override val capturedAt: Instant,
        val stateFingerprint: String,
        override val responseShapeFingerprint: String? = null,
        override val sanitizedSnippet: String? = null,
    ) : AutomationActionEvidence

    data class SameState(
        override val capturedAt: Instant,
        val stateFingerprint: String,
        override val responseShapeFingerprint: String? = null,
        override val sanitizedSnippet: String? = null,
    ) : AutomationActionEvidence

    data class IncompleteObservation(
        override val capturedAt: Instant,
        val reason: String,
        /** 완전한 권위 페이지에서 필수 식별자만 누락된 경우 관측 예산에 포함한다. */
        val authoritative: Boolean = false,
        override val responseShapeFingerprint: String? = null,
        override val sanitizedSnippet: String? = null,
    ) : AutomationActionEvidence

    data class NetworkFailure(
        override val capturedAt: Instant,
        val reason: String,
        override val responseShapeFingerprint: String? = null,
        override val sanitizedSnippet: String? = null,
    ) : AutomationActionEvidence

    data class ResultUnobserved(
        override val capturedAt: Instant,
        val reason: String,
        override val responseShapeFingerprint: String? = null,
        override val sanitizedSnippet: String? = null,
    ) : AutomationActionEvidence

    /** 이전 결과는 미관측으로 닫되 최신 완전 목표 상태가 새 선택을 안전하게 허용한다. */
    data class ResultUnobservedFreshDecision(
        override val capturedAt: Instant,
        val reason: String,
        override val responseShapeFingerprint: String? = null,
        override val sanitizedSnippet: String? = null,
    ) : AutomationActionEvidence

    data class BattleGateRequired(
        override val capturedAt: Instant,
        val challengeId: Long?,
        val reason: String,
        override val responseShapeFingerprint: String? = null,
        override val sanitizedSnippet: String? = null,
        val submissionAttempted: Boolean = true,
    ) : AutomationActionEvidence
}

sealed interface ConvergenceDirective {
    data class Submit(val attemptId: Long, val selection: SelectedAutomationAction) : ConvergenceDirective

    data class Probe(
        val attemptId: Long,
        val selection: SelectedAutomationAction,
    ) : ConvergenceDirective {
        val executionIdentity: String get() = selection.executionIdentity
        val entryId: Long get() = selection.entryId
    }

    data class WaitUntil(
        val at: Instant,
        val scope: AutomationIsolationScope,
    ) : ConvergenceDirective

    data class BattleGateWait(
        val openedAt: Instant,
        val reason: String,
    ) : ConvergenceDirective

    data object ContinueSelection : ConvergenceDirective
}

data class ActionConvergenceRecord(
    val attemptId: Long,
    val accountId: Long,
    val selection: SelectedAutomationAction,
    var result: ActionConvergenceResult? = ActionConvergenceResult.PENDING,
    var submittedAt: Instant? = null,
    var successfulObservationCount: Int = 0,
    var firstPendingAt: Instant? = null,
    var nextProbeAt: Instant? = null,
    var reasonCode: String? = null,
    var finishedAt: Instant? = null,
    var updatedAt: Instant,
) {
    val active: Boolean
        get() = result == ActionConvergenceResult.PENDING || result == null
}

data class AccountBattleGate(
    val accountId: Long,
    val challengeId: Long?,
    val reason: String,
    val openedAt: Instant,
    var resolvedAt: Instant? = null,
) {
    val active: Boolean
        get() = resolvedAt == null
}
