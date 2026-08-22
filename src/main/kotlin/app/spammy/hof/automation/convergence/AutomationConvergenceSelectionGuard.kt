package app.spammy.hof.automation.convergence

import org.springframework.stereotype.Service

fun interface AutomationConvergenceSelectionGuard {
    fun constraints(accountId: Long): AutomationConvergenceSelectionConstraints
}

data class AutomationConvergenceSelectionConstraints(
    val blockedScopes: Set<AutomationIsolationScope>,
    val battleGateActive: Boolean,
    val suppressedBaselines: Map<AutomationIsolationScope, Set<String>> = emptyMap(),
) {
    fun blocks(preview: ConvergenceSelectionPreview): Boolean =
        preview.scope in blockedScopes ||
            battleGateActive && preview.actionKind.battle ||
            preview.baselineFingerprint?.let { it in suppressedBaselines[preview.scope].orEmpty() } == true

    companion object {
        val NONE = AutomationConvergenceSelectionConstraints(emptySet(), false)
    }
}

@Service
class StoreBackedAutomationConvergenceSelectionGuard(
    private val store: ConvergenceStore,
) : AutomationConvergenceSelectionGuard {
    override fun constraints(accountId: Long) = AutomationConvergenceSelectionConstraints(
        blockedScopes = store.findActiveScopes(accountId),
        battleGateActive = store.activeBattleGate(accountId) != null,
        suppressedBaselines = store.findSuppressedBaselines(accountId),
    )
}
