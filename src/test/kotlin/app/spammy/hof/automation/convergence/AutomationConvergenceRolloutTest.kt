package app.spammy.hof.automation.convergence

import app.spammy.hof.common.time.TimeProvider
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutomationConvergenceRolloutTest {
    private val now = Instant.parse("2026-08-22T00:00:00Z")

    @Test
    fun `shadow is the safe default and the post switch is independent from reads`() {
        val rollout = AutomationConvergenceRollout(AutomationConvergenceProperties())

        assertTrue(rollout.shadow)
        assertFalse(rollout.active)
        assertTrue(rollout.automationPostsEnabled)

        val stopped = AutomationConvergenceRollout(
            AutomationConvergenceProperties(
                mode = AutomationConvergenceMode.ACTIVE,
                automationPostsEnabled = false,
            ),
        )
        assertTrue(stopped.active)
        assertFalse(stopped.automationPostsEnabled)
    }

    @Test
    fun `shadow evaluates the real evidence stream without writing the production store`() {
        val production = InMemoryConvergenceStore()
        val evaluator = DefaultAutomationConvergenceShadowEvaluator(TimeProvider { now })
        val selection = SelectedAutomationAction(
            entryId = 5L,
            executionIdentity = "shadow-union-1",
            actionKind = AutomationActionKind.UNION_BATTLE,
            scope = AutomationIsolationScope(AutomationIsolationScopeKind.UNION_ENTRY, "5"),
            policyVersion = "v1",
            baselineFingerprint = "baseline",
        )

        evaluator.selected(7L, selection)
        val evaluation = evaluator.observe(
            7L,
            selection.executionIdentity,
            AutomationActionEvidence.IncompleteObservation(now, "response incomplete"),
            LegacyConvergenceDecision.RECONCILING,
        )

        assertEquals(ActionConvergenceResult.PENDING, evaluation?.newResult)
        assertEquals(null, production.findActive(7L, selection.scope))
        assertEquals(1L, evaluator.snapshot().single().count)
    }

    @Test
    fun `shadow agrees when a fresh authoritative state supersedes the stored action`() {
        val evaluator = DefaultAutomationConvergenceShadowEvaluator(TimeProvider { now })
        val selection = SelectedAutomationAction(
            entryId = 5L,
            executionIdentity = "shadow-raid-reward-1",
            actionKind = AutomationActionKind.RAID_REWARD,
            scope = AutomationIsolationScope(AutomationIsolationScopeKind.RAID_ENTRY, "RaidGoblin"),
            policyVersion = "v1",
            baselineFingerprint = "reward-pending",
        )

        evaluator.selected(7L, selection)
        val evaluation = evaluator.observe(
            7L,
            selection.executionIdentity,
            AutomationActionEvidence.StateAdvanced(now, "raid-recruiting"),
            LegacyConvergenceDecision.SUPERSEDED,
        )

        assertEquals(ActionConvergenceResult.SUPERSEDED, evaluation?.newResult)
        assertFalse(requireNotNull(evaluation).differs)
    }
}
