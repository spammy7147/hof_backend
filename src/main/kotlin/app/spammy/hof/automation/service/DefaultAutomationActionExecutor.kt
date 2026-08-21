package app.spammy.hof.automation.service

import org.springframework.stereotype.Service

/** Compatibility boundary removed in ticket #12 after every action family has moved to its lifecycle module. */
@Service
class DefaultAutomationActionExecutor : TypedAutomationActionExecutor {
    override fun execute(accountId: Long, action: StoredTypedAutomationAction): TypedAutomationExecution =
        error("Stored action ${action.payload.kind()} belongs to the action lifecycle module.")
}
