package app.spammy.hof.automation.service

import org.springframework.stereotype.Component

/** Current HOF history has no execution-bound result id, so reload can never prove an exact submitted batch. */
@Component
class ConservativeBattleOutcomeReconciler : BattleOutcomeReconciler {
    override fun reloadRecentAuthoritativeEvidence(action: BattleMapAutomationAction): BattleOutcomeReconciliation =
        BattleOutcomeReconciliation.Unproven(
            "No authoritative HOF result identity can prove execution ${action.executionIdentity}; the battle will not be resent.",
        )
}
