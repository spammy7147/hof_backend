package app.spammy.hof.automation.convergence

import app.spammy.hof.automation.raid.RaidRewardResultKind
import app.spammy.hof.quest.model.QuestState
import app.spammy.hof.town.fishing.model.FishingOutcome
import app.spammy.hof.town.fishing.model.FishingPrimaryAction
import app.spammy.hof.town.home.model.HomeQuestState
import java.time.Instant
import org.springframework.stereotype.Component

enum class ActionEvidenceSource {
    DIRECT_RESPONSE,
    LIFECYCLE_RESULT,
    ACCOUNT_OBSERVATION,
    SHARED_OBSERVATION,
}

enum class ObservationCompleteness {
    COMPLETE,
    INCOMPLETE,
    WRONG_PAGE,
}

enum class ObservationFreshness {
    FRESH,
    CACHED,
}

sealed interface ActionObservedState {
    val fingerprint: String
}

data class LifecycleResultObservedState(
    override val fingerprint: String,
    val actionKind: AutomationActionKind,
    val resultKind: String,
) : ActionObservedState

data class QuestObservedState(
    override val fingerprint: String,
    val present: Boolean,
    val state: QuestState?,
    val actionNo: String?,
    val progressCurrent: Int? = null,
) : ActionObservedState

data class HomeQuestObservedState(
    override val fingerprint: String,
    val present: Boolean,
    val state: HomeQuestState?,
    val actionId: String?,
) : ActionObservedState

data class BattleObservedState(
    override val fingerprint: String,
    val targetPresent: Boolean,
    val runnable: Boolean,
    val terminalOutcomes: List<String>,
    val cooldownStarted: Boolean = false,
) : ActionObservedState

data class UnionObservedState(
    override val fingerprint: String,
    val mapPresent: Boolean,
    val personalCooldown: Boolean,
    val terminalOutcomes: List<String> = emptyList(),
) : ActionObservedState

data class FishingObservedState(
    override val fingerprint: String,
    val primaryAction: FishingPrimaryAction,
    val remainingCasts: Int?,
    val lastOutcome: FishingOutcome?,
    val blockedByBattle: Boolean,
) : ActionObservedState

data class RaidObservedState(
    override val fingerprint: String,
    val joined: Boolean,
    val sharedStatus: String,
    val personalCooldown: Boolean = false,
    val rewardAvailable: Boolean? = null,
    val rewardResult: RaidRewardResultKind? = null,
    val terminalOutcomes: List<String> = emptyList(),
) : ActionObservedState

data class ActionPolicyObservation(
    val capturedAt: Instant,
    val source: ActionEvidenceSource,
    val completeness: ObservationCompleteness,
    val freshness: ObservationFreshness,
    val state: ActionObservedState?,
    val explicitRejected: Boolean = false,
    val rejectionReason: String? = null,
    /** Intentionally not sufficient without an action-specific poststate. */
    val genericSuccess: Boolean = false,
    val actionSuccessMarker: Boolean = false,
    val battleGateChallengeId: Long? = null,
    val battleGateReason: String? = null,
    val responseShapeFingerprint: String? = null,
    val sanitizedSnippet: String? = null,
)

fun interface ActionEvidencePolicies {
    fun evaluate(
        selection: SelectedAutomationAction,
        observation: ActionPolicyObservation,
    ): AutomationActionEvidence
}

@Component
class DefaultActionEvidencePolicies : ActionEvidencePolicies {
    override fun evaluate(
        selection: SelectedAutomationAction,
        observation: ActionPolicyObservation,
    ): AutomationActionEvidence {
        observation.battleGateReason?.let { reason ->
            return AutomationActionEvidence.BattleGateRequired(
                observation.capturedAt,
                observation.battleGateChallengeId,
                reason,
                observation.responseShapeFingerprint,
                observation.sanitizedSnippet,
            )
        }
        if (observation.explicitRejected) {
            return AutomationActionEvidence.DirectRejected(
                observation.capturedAt,
                observation.rejectionReason ?: "ACTION_REJECTED",
                observation.responseShapeFingerprint,
                observation.sanitizedSnippet,
            )
        }
        if (
            observation.completeness != ObservationCompleteness.COMPLETE ||
            observation.freshness != ObservationFreshness.FRESH
        ) {
            return AutomationActionEvidence.IncompleteObservation(
                observation.capturedAt,
                "${observation.completeness.name}_${observation.freshness.name}",
                responseShapeFingerprint = observation.responseShapeFingerprint,
                sanitizedSnippet = observation.sanitizedSnippet,
            )
        }
        val state = observation.state ?: return AutomationActionEvidence.IncompleteObservation(
            observation.capturedAt,
            "OBSERVED_STATE_MISSING",
            responseShapeFingerprint = observation.responseShapeFingerprint,
            sanitizedSnippet = observation.sanitizedSnippet,
        )
        if (observation.source == ActionEvidenceSource.LIFECYCLE_RESULT) {
            val lifecycle = state as? LifecycleResultObservedState
                ?: return incomplete(observation, "LIFECYCLE_RESULT_STATE_EXPECTED")
            if (
                lifecycle.actionKind != selection.actionKind ||
                !lifecycleResultProvesApplied(selection.actionKind, lifecycle.resultKind)
            ) {
                return incomplete(observation, "LIFECYCLE_RESULT_NOT_AUTHORITATIVE")
            }
            return directApplied(observation, lifecycle)
        }
        if (selection.actionKind.battle && state is BattleObservedState) {
            return evaluateBattle(selection, observation, state)
        }
        return when (selection.actionKind) {
            AutomationActionKind.QUEST_ACCEPT,
            AutomationActionKind.QUEST_CLAIM,
            -> evaluateQuest(selection, observation, state)
            AutomationActionKind.HOME_ACCEPT,
            AutomationActionKind.HOME_CLAIM,
            -> evaluateHome(selection, observation, state)
            AutomationActionKind.QUEST_BATTLE,
            AutomationActionKind.MAP_BATTLE,
            AutomationActionKind.ADVENTURE_BATTLE,
            AutomationActionKind.FISHING_OBSTRUCTION_BATTLE,
            -> evaluateBattle(selection, observation, state)
            AutomationActionKind.UNION_BATTLE -> evaluateUnion(selection, observation, state)
            AutomationActionKind.FISHING_START,
            AutomationActionKind.FISHING_CATCH,
            -> evaluateFishing(selection, observation, state)
            AutomationActionKind.RAID_RESET,
            AutomationActionKind.RAID_REGISTER,
            AutomationActionKind.RAID_START,
            AutomationActionKind.RAID_REWARD,
            AutomationActionKind.RAID_REFRESH,
            AutomationActionKind.RAID_BATTLE,
            AutomationActionKind.RAID_CYCLE_ABORT,
            -> evaluateRaid(selection, observation, state)
        }
    }

    private fun evaluateQuest(
        selection: SelectedAutomationAction,
        observation: ActionPolicyObservation,
        observed: ActionObservedState,
    ): AutomationActionEvidence {
        val state = observed as? QuestObservedState ?: return incomplete(observation, "QUEST_STATE_EXPECTED")
        val directApplied = observation.source == ActionEvidenceSource.DIRECT_RESPONSE && when (selection.actionKind) {
            AutomationActionKind.QUEST_ACCEPT -> state.present && state.state in setOf(
                QuestState.ACTIVE,
                QuestState.CLAIMABLE,
                QuestState.COMPLETED,
            )
            AutomationActionKind.QUEST_CLAIM -> !state.present || state.state in setOf(
                QuestState.COMPLETED,
                QuestState.UNAVAILABLE,
            )
            else -> false
        }
        return if (directApplied) directApplied(observation, state) else changedOrSame(selection, observation, state)
    }

    private fun evaluateHome(
        selection: SelectedAutomationAction,
        observation: ActionPolicyObservation,
        observed: ActionObservedState,
    ): AutomationActionEvidence {
        val state = observed as? HomeQuestObservedState ?: return incomplete(observation, "HOME_STATE_EXPECTED")
        val directApplied = observation.source == ActionEvidenceSource.DIRECT_RESPONSE && when (selection.actionKind) {
            AutomationActionKind.HOME_ACCEPT -> state.present && state.state in setOf(
                HomeQuestState.ACTIVE,
                HomeQuestState.CLAIMABLE,
            )
            AutomationActionKind.HOME_CLAIM -> state.present &&
                state.state in setOf(HomeQuestState.WAITING, HomeQuestState.COMPLETED)
            else -> false
        }
        return if (directApplied) directApplied(observation, state) else changedOrSame(selection, observation, state)
    }

    private fun evaluateBattle(
        selection: SelectedAutomationAction,
        observation: ActionPolicyObservation,
        observed: ActionObservedState,
    ): AutomationActionEvidence {
        val state = observed as? BattleObservedState ?: return incomplete(observation, "BATTLE_STATE_EXPECTED")
        if (
            observation.source == ActionEvidenceSource.DIRECT_RESPONSE &&
            state.terminalOutcomes.isNotEmpty() &&
            state.terminalOutcomes.all(::terminalBattleOutcome)
        ) {
            return directApplied(observation, state)
        }
        return changedOrSame(selection, observation, state)
    }

    private fun evaluateUnion(
        selection: SelectedAutomationAction,
        observation: ActionPolicyObservation,
        observed: ActionObservedState,
    ): AutomationActionEvidence {
        val state = observed as? UnionObservedState ?: return incomplete(observation, "UNION_STATE_EXPECTED")
        if (
            observation.source == ActionEvidenceSource.DIRECT_RESPONSE &&
            state.terminalOutcomes.isNotEmpty() &&
            state.terminalOutcomes.all(::terminalBattleOutcome)
        ) {
            return directApplied(observation, state)
        }
        if (!state.mapPresent) {
            return stateAdvanced(observation, state)
        }
        return changedOrSame(selection, observation, state)
    }

    private fun evaluateFishing(
        selection: SelectedAutomationAction,
        observation: ActionPolicyObservation,
        observed: ActionObservedState,
    ): AutomationActionEvidence {
        val state = observed as? FishingObservedState ?: return incomplete(observation, "FISHING_STATE_EXPECTED")
        if (state.blockedByBattle) {
            if (
                observation.source == ActionEvidenceSource.DIRECT_RESPONSE &&
                selection.actionKind == AutomationActionKind.FISHING_CATCH
            ) {
                return directApplied(observation, state)
            }
            return stateAdvanced(observation, state)
        }
        val directApplied = observation.source == ActionEvidenceSource.DIRECT_RESPONSE && when (selection.actionKind) {
            AutomationActionKind.FISHING_START -> state.primaryAction == FishingPrimaryAction.CATCH ||
                state.lastOutcome == FishingOutcome.STARTED
            AutomationActionKind.FISHING_CATCH -> state.lastOutcome in setOf(
                FishingOutcome.CAUGHT,
                FishingOutcome.ESCAPED,
            ) || state.primaryAction == FishingPrimaryAction.START
            else -> false
        }
        return if (directApplied) directApplied(observation, state) else changedOrSame(selection, observation, state)
    }

    private fun evaluateRaid(
        selection: SelectedAutomationAction,
        observation: ActionPolicyObservation,
        observed: ActionObservedState,
    ): AutomationActionEvidence {
        val state = observed as? RaidObservedState ?: return incomplete(observation, "RAID_STATE_EXPECTED")
        val direct = observation.source == ActionEvidenceSource.DIRECT_RESPONSE
        val applied = direct && when (selection.actionKind) {
            AutomationActionKind.RAID_REGISTER -> state.joined
            AutomationActionKind.RAID_START -> observation.actionSuccessMarker &&
                state.joined && state.sharedStatus == "IN_BATTLE"
            AutomationActionKind.RAID_RESET -> !state.joined &&
                state.sharedStatus in setOf("ABSENT", "RECRUITING", "READY", "WAITING")
            AutomationActionKind.RAID_REWARD -> state.rewardResult != null
            AutomationActionKind.RAID_REFRESH -> observation.actionSuccessMarker
            AutomationActionKind.RAID_BATTLE -> state.terminalOutcomes.isNotEmpty() &&
                state.terminalOutcomes.all(::terminalBattleOutcome)
            AutomationActionKind.RAID_CYCLE_ABORT -> observation.actionSuccessMarker
            else -> false
        }
        if (applied) return directApplied(observation, state)
        if (!state.joined && state.sharedStatus in setOf("IN_BATTLE", "COMPLETED")) {
            return stateAdvanced(observation, state)
        }
        if (state.sharedStatus == "COMPLETED") {
            return stateAdvanced(observation, state)
        }
        // START는 참가 중 READY에서만 제출하므로, 같은 단계는 응답 지문이 달라도 미확정이다.
        if (selection.actionKind == AutomationActionKind.RAID_START && state.joined && state.sharedStatus == "READY") {
            return sameState(observation, state)
        }
        return changedOrSame(selection, observation, state)
    }

    private fun changedOrSame(
        selection: SelectedAutomationAction,
        observation: ActionPolicyObservation,
        state: ActionObservedState,
    ): AutomationActionEvidence = if (state.fingerprint != selection.baselineFingerprint) {
        stateAdvanced(observation, state)
    } else {
        sameState(observation, state)
    }

    private fun sameState(
        observation: ActionPolicyObservation,
        state: ActionObservedState,
    ) = AutomationActionEvidence.SameState(
        observation.capturedAt,
        state.fingerprint,
        observation.responseShapeFingerprint,
        observation.sanitizedSnippet,
    )

    private fun stateAdvanced(
        observation: ActionPolicyObservation,
        state: ActionObservedState,
    ) = AutomationActionEvidence.StateAdvanced(
        observation.capturedAt,
        state.fingerprint,
        observation.responseShapeFingerprint,
        observation.sanitizedSnippet,
    )

    private fun directApplied(
        observation: ActionPolicyObservation,
        state: ActionObservedState,
    ) = AutomationActionEvidence.DirectApplied(
        observation.capturedAt,
        state.fingerprint,
        observation.responseShapeFingerprint,
        observation.sanitizedSnippet,
    )

    private fun incomplete(
        observation: ActionPolicyObservation,
        reason: String,
    ) = AutomationActionEvidence.IncompleteObservation(
        observation.capturedAt,
        reason,
        responseShapeFingerprint = observation.responseShapeFingerprint,
        sanitizedSnippet = observation.sanitizedSnippet,
    )

    private fun terminalBattleOutcome(value: String): Boolean = value in setOf("VICTORY", "DEFEAT", "DRAW")

    private fun lifecycleResultProvesApplied(actionKind: AutomationActionKind, resultKind: String): Boolean = when (
        resultKind
    ) {
        "Completed" -> actionKind == AutomationActionKind.RAID_CYCLE_ABORT
        "ReconciledApplied" -> true
        "BattleCompleted" -> actionKind.battle
        "RaidCycleFinished" -> actionKind in RAID_ACTION_KINDS
        else -> false
    }

    private companion object {
        val RAID_ACTION_KINDS: Set<AutomationActionKind> = setOf(
            AutomationActionKind.RAID_RESET,
            AutomationActionKind.RAID_REGISTER,
            AutomationActionKind.RAID_START,
            AutomationActionKind.RAID_REWARD,
            AutomationActionKind.RAID_REFRESH,
            AutomationActionKind.RAID_BATTLE,
            AutomationActionKind.RAID_CYCLE_ABORT,
        )
    }
}
