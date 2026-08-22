package app.spammy.hof.automation.convergence

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutomationConvergenceSelectionGuardTest {
    private val store = InMemoryConvergenceStore()
    private val guard = StoreBackedAutomationConvergenceSelectionGuard(store)

    @Test
    fun `pending 범위와 전투 gate만 막고 독립 비전투 범위는 허용한다`() {
        val now = Instant.parse("2026-08-22T00:00:00Z")
        val pending = selection("quest-a", AutomationActionKind.QUEST_CLAIM)
        val record = store.createOrGet(7L, pending, now)
        record.result = ActionConvergenceResult.PENDING
        record.nextProbeAt = now.plusSeconds(10)
        store.save(record)
        store.openBattleGate(7L, 91L, "CAPTCHA_REQUIRED", now)

        val constraints = guard.constraints(7L)

        assertTrue(constraints.blocks(ConvergenceSelectionPreview(pending.actionKind, pending.scope)))
        assertFalse(constraints.blocks(ConvergenceSelectionPreview(
            AutomationActionKind.QUEST_CLAIM,
            AutomationIsolationScope(AutomationIsolationScopeKind.QUEST_TARGET, "quest-b"),
        )))
        assertTrue(constraints.blocks(ConvergenceSelectionPreview(
            AutomationActionKind.MAP_BATTLE,
            AutomationIsolationScope(AutomationIsolationScopeKind.BATTLE_COOLDOWN_SCOPE, "battle:a:b"),
        )))
    }

    @Test
    fun `held는 같은 baseline만 막고 상태가 바뀐 fresh 행동은 허용한다`() {
        val now = Instant.parse("2026-08-22T00:00:00Z")
        val held = selection("quest-held", AutomationActionKind.QUEST_CLAIM)
        val record = store.createOrGet(7L, held, now)
        record.result = ActionConvergenceResult.HELD
        record.finishedAt = now
        store.save(record)

        val constraints = guard.constraints(7L)

        assertTrue(constraints.blocks(ConvergenceSelectionPreview(
            held.actionKind,
            held.scope,
            held.baselineFingerprint,
        )))
        assertFalse(constraints.blocks(ConvergenceSelectionPreview(
            held.actionKind,
            held.scope,
            "changed-baseline",
        )))
    }

    private fun selection(key: String, kind: AutomationActionKind) = SelectedAutomationAction(
        entryId = 12L,
        executionIdentity = "execution-$key",
        actionKind = kind,
        scope = AutomationIsolationScope(AutomationIsolationScopeKind.QUEST_TARGET, key),
        policyVersion = "policy-v1",
        baselineFingerprint = "baseline-$key",
    )
}
