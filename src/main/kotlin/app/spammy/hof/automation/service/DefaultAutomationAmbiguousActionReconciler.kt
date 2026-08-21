package app.spammy.hof.automation.service

import org.springframework.stereotype.Service

/** Compatibility boundary removed in ticket #12 after every action family has moved to its lifecycle module. */
@Service
class DefaultAutomationAmbiguousActionReconciler : AutomationAmbiguousActionReconciler {
    override fun reconcile(
        accountId: Long,
        action: StoredTypedAutomationAction,
    ): AmbiguousActionResolution =
        error("Stored action ${action.payload.kind()} belongs to the action lifecycle module.")
}
