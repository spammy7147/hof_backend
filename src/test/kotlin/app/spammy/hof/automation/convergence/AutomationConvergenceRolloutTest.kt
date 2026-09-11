package app.spammy.hof.automation.convergence

import app.spammy.hof.common.time.TimeProvider
import java.time.Instant
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutomationConvergenceRolloutTest {
    private val now = Instant.parse("2026-08-22T00:00:00Z")

    @ParameterizedTest
    @ValueSource(strings = ["HELD", "NOT_APPLIED", "UNSUPPORTED_POLICY"])
    fun `늦은 직접 응답은 보류를 해소하지만 미적용과 비지원 정책을 바꾸거나 중복 기록하지 않는다`(terminal: String) {
        var currentTime = now
        val production = InMemoryConvergenceStore()
        val durable = mutableListOf<DurableShadowEvaluation>()
        val evaluator = DefaultAutomationConvergenceShadowEvaluator(
            TimeProvider { currentTime }, AutomationConvergenceShadowRecorder(durable::add), production,
        )
        val selection = SelectedAutomationAction(
            entryId = 5L, executionIdentity = "late-after-$terminal", actionKind = AutomationActionKind.QUEST_CLAIM,
            scope = AutomationIsolationScope(AutomationIsolationScopeKind.QUEST_TARGET, "quest"),
            policyVersion = if (terminal == "UNSUPPORTED_POLICY") "unsupported" else ProductionActionEvidenceInterpreter.VERSION_1,
            baselineFingerprint = "claimable",
        )
        evaluator.selected(7L, selection)
        when (terminal) {
            "HELD" -> {
                evaluator.observe(7L, selection.executionIdentity,
                    AutomationActionEvidence.IncompleteObservation(now, "원래 응답 확인 대기"), LegacyConvergenceDecision.RECONCILING)
                currentTime = now.plusSeconds(121)
                evaluator.observe(7L, selection.executionIdentity,
                    AutomationActionEvidence.SameState(currentTime, "claimable"), LegacyConvergenceDecision.HELD)
            }
            "NOT_APPLIED" -> evaluator.observe(7L, selection.executionIdentity,
                AutomationActionEvidence.DirectRejected(now, "신청 거절"), LegacyConvergenceDecision.RESUBMIT)
            "UNSUPPORTED_POLICY" -> evaluator.observe(7L, selection.executionIdentity,
                AutomationActionEvidence.DirectApplied(now, "claim-applied"), LegacyConvergenceDecision.APPLIED)
        }
        assertEquals(if (terminal == "NOT_APPLIED") ActionConvergenceResult.NOT_APPLIED else ActionConvergenceResult.HELD,
            durable.last().newResult)
        val recordedBeforeLateResult = durable.size
        val direct = AutomationActionEvidence.DirectApplied(now, "claim-applied")
        val late = evaluator.observe(7L, selection.executionIdentity, direct, LegacyConvergenceDecision.APPLIED)
        if (terminal == "HELD") {
            assertEquals(ActionConvergenceResult.APPLIED, assertNotNull(late).newResult)
            assertEquals(now, durable.last().observedAt)
        } else assertNull(late)

        assertNull(evaluator.observe(7L, selection.executionIdentity, direct, LegacyConvergenceDecision.APPLIED))
        assertNull(evaluator.observe(8L, selection.executionIdentity, direct, LegacyConvergenceDecision.APPLIED))
        assertNull(evaluator.observe(7L, "unknown-execution", direct, LegacyConvergenceDecision.APPLIED))
        assertNull(evaluator.observe(7L, selection.executionIdentity,
            AutomationActionEvidence.SameState(currentTime, "claimable"), LegacyConvergenceDecision.RECONCILING))
        assertEquals(recordedBeforeLateResult + if (terminal == "HELD") 1 else 0, durable.size)
        assertNull(production.get(7L, selection.executionIdentity))
        assertNull(production.activeBattleGate(7L))
    }

    @Test
    fun `실제 관문이 해제된 뒤 새 전투의 shadow 비교를 재개하고 열린 관문은 유지한다`() {
        val production = InMemoryConvergenceStore()
        val durable = mutableListOf<DurableShadowEvaluation>()
        val evaluator = DefaultAutomationConvergenceShadowEvaluator(
            TimeProvider { now }, AutomationConvergenceShadowRecorder(durable::add), production,
        )
        val selection = SelectedAutomationAction(
            entryId = 5L, executionIdentity = "before-captcha", actionKind = AutomationActionKind.UNION_BATTLE,
            scope = AutomationIsolationScope(AutomationIsolationScopeKind.UNION_ENTRY, "5"),
            policyVersion = ProductionActionEvidenceInterpreter.VERSION_1, baselineFingerprint = "baseline",
        )
        evaluator.selected(7L, selection)
        production.openBattleGate(7L, null, "CAPTCHA_REQUIRED", now)
        assertNotNull(evaluator.observe(7L, selection.executionIdentity,
            AutomationActionEvidence.BattleGateRequired(now, null, "CAPTCHA_REQUIRED"), LegacyConvergenceDecision.HELD))

        val next = selection.copy(executionIdentity = "after-captcha")
        evaluator.selected(7L, next)
        assertNull(evaluator.observe(7L, next.executionIdentity,
            AutomationActionEvidence.DirectApplied(now, "battle-finished"), LegacyConvergenceDecision.APPLIED))
        assertNotNull(production.activeBattleGate(7L))
        assertEquals(1, durable.size)

        val home = selection.copy(executionIdentity = "home-during-captcha", actionKind = AutomationActionKind.HOME_ACCEPT,
            scope = AutomationIsolationScope(AutomationIsolationScopeKind.HOME_TARGET, "home"))
        evaluator.selected(7L, home)
        assertEquals(ActionConvergenceResult.APPLIED, evaluator.observe(7L, home.executionIdentity,
            AutomationActionEvidence.DirectApplied(now, "home-accepted"), LegacyConvergenceDecision.APPLIED)?.newResult)
        assertNotNull(production.activeBattleGate(7L))

        production.releaseBattleGate(7L, now)
        evaluator.selected(7L, next)
        assertEquals(ActionConvergenceResult.APPLIED, evaluator.observe(7L, next.executionIdentity,
            AutomationActionEvidence.DirectApplied(now, "battle-finished"), LegacyConvergenceDecision.APPLIED)?.newResult)
        assertEquals(3, durable.size)
        assertTrue(production.findActiveScopes(7L).isEmpty())
        assertNull(production.activeBattleGate(7L))
    }

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
        val durable = mutableListOf<DurableShadowEvaluation>()
        val evaluator = DefaultAutomationConvergenceShadowEvaluator(
            TimeProvider { now },
            AutomationConvergenceShadowRecorder(durable::add),
        )
        val selection = SelectedAutomationAction(
            entryId = 5L,
            executionIdentity = "shadow-union-1",
            actionKind = AutomationActionKind.UNION_BATTLE,
            scope = AutomationIsolationScope(AutomationIsolationScopeKind.UNION_ENTRY, "5"),
            policyVersion = ProductionActionEvidenceInterpreter.VERSION_1,
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
        assertEquals(1, durable.size)
        assertEquals(64, durable.single().executionIdentityHash.length)
        assertFalse(durable.single().executionIdentityHash.contains(selection.executionIdentity))
        assertEquals("INCOMPLETE", durable.single().evidenceCompleteness)
        assertEquals(64, durable.single().responseShapeFingerprint.length)
        assertEquals("IncompleteObservation|authoritative=false", durable.single().sanitizedSnippet)
        assertFalse(durable.single().shapeDiffers)
    }

    @Test
    fun `shadow agrees when a fresh authoritative state supersedes the stored action`() {
        val evaluator = DefaultAutomationConvergenceShadowEvaluator(TimeProvider { now })
        val selection = SelectedAutomationAction(
            entryId = 5L,
            executionIdentity = "shadow-raid-reward-1",
            actionKind = AutomationActionKind.RAID_REWARD,
            scope = AutomationIsolationScope(AutomationIsolationScopeKind.RAID_ENTRY, "RaidGoblin"),
            policyVersion = ProductionActionEvidenceInterpreter.VERSION_1,
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

    @Test
    fun `새 실행의 shadow 비교와 이전 실행의 늦은 직접 응답을 각각 보존한다`() {
        val durable = mutableListOf<DurableShadowEvaluation>()
        val evaluator = DefaultAutomationConvergenceShadowEvaluator(
            TimeProvider { now },
            AutomationConvergenceShadowRecorder(durable::add),
        )
        val previous = SelectedAutomationAction(
            entryId = 5L,
            executionIdentity = "shadow-quest-previous",
            actionKind = AutomationActionKind.QUEST_CLAIM,
            scope = AutomationIsolationScope(AutomationIsolationScopeKind.QUEST_TARGET, "quest"),
            policyVersion = ProductionActionEvidenceInterpreter.VERSION_1,
            baselineFingerprint = "claimable-v1",
        )
        val replacement = previous.copy(
            executionIdentity = "shadow-quest-replacement",
            baselineFingerprint = "claimable-v2",
        )
        evaluator.selected(7L, previous)
        evaluator.observe(
            7L,
            previous.executionIdentity,
            AutomationActionEvidence.IncompleteObservation(now, "result pending"),
            LegacyConvergenceDecision.RECONCILING,
        )

        evaluator.selected(7L, replacement)
        val replacementEvaluation = evaluator.observe(
            7L,
            replacement.executionIdentity,
            AutomationActionEvidence.DirectApplied(now, "claim-applied"),
            LegacyConvergenceDecision.APPLIED,
        )

        assertEquals(ActionConvergenceResult.APPLIED, replacementEvaluation?.newResult)
        assertEquals(
            listOf(
                LegacyConvergenceDecision.RECONCILING,
                LegacyConvergenceDecision.SUPERSEDED,
                LegacyConvergenceDecision.APPLIED,
            ),
            durable.map(DurableShadowEvaluation::legacyDecision),
        )
        assertEquals(
            ActionConvergenceResult.APPLIED,
            evaluator.observe(
                7L,
                previous.executionIdentity,
                AutomationActionEvidence.DirectApplied(now, "late-old-result"),
                LegacyConvergenceDecision.APPLIED,
            )?.newResult,
        )
        assertEquals(listOf(ActionConvergenceResult.PENDING, ActionConvergenceResult.SUPERSEDED,
            ActionConvergenceResult.APPLIED, ActionConvergenceResult.APPLIED), durable.map { it.newResult })
        assertNull(evaluator.observe(7L, previous.executionIdentity,
            AutomationActionEvidence.DirectApplied(now, "late-old-result"), LegacyConvergenceDecision.APPLIED))
        assertNull(evaluator.observe(7L, replacement.executionIdentity,
            AutomationActionEvidence.DirectApplied(now, "claim-applied"), LegacyConvergenceDecision.APPLIED))
        assertEquals(4, durable.size)
    }

    @Test
    fun `shadow separates decision agreement from unexpected evidence shape`() {
        val durable = mutableListOf<DurableShadowEvaluation>()
        val evaluator = DefaultAutomationConvergenceShadowEvaluator(
            TimeProvider { now },
            AutomationConvergenceShadowRecorder(durable::add),
        )
        val selection = SelectedAutomationAction(
            entryId = 5L,
            executionIdentity = "shadow-shape-1",
            actionKind = AutomationActionKind.QUEST_CLAIM,
            scope = AutomationIsolationScope(AutomationIsolationScopeKind.QUEST_TARGET, "quest"),
            policyVersion = ProductionActionEvidenceInterpreter.VERSION_1,
            baselineFingerprint = "baseline",
        )

        evaluator.selected(7L, selection)
        evaluator.observe(
            7L,
            selection.executionIdentity,
            AutomationActionEvidence.IncompleteObservation(now, "unexpected response"),
            LegacyConvergenceDecision.APPLIED,
        )

        assertTrue(durable.single().shapeDiffers)
        assertTrue(durable.single().resultDiffers)
    }

    @Test
    fun `shadow는 결과가 같아도 production 응답 fingerprint의 알려지지 않은 구조를 분리한다`() {
        val durable = mutableListOf<DurableShadowEvaluation>()
        val evaluator = DefaultAutomationConvergenceShadowEvaluator(
            TimeProvider { now },
            AutomationConvergenceShadowRecorder(durable::add),
        )
        val selection = SelectedAutomationAction(
            entryId = 5L,
            executionIdentity = "shadow-unknown-shape",
            actionKind = AutomationActionKind.QUEST_CLAIM,
            scope = AutomationIsolationScope(AutomationIsolationScopeKind.QUEST_TARGET, "quest"),
            policyVersion = ProductionActionEvidenceInterpreter.VERSION_1,
            baselineFingerprint = "baseline",
        )
        evaluator.selected(7L, selection)

        evaluator.observe(
            7L,
            selection.executionIdentity,
            AutomationActionEvidence.DirectApplied(
                capturedAt = now,
                stateFingerprint = "claim-applied",
                responseShapeFingerprint = ProductionEvidenceShapes.fingerprint("UnknownQuestResponse|newField"),
                sanitizedSnippet = "UnknownQuestResponse|newFieldPresent=true",
            ),
            LegacyConvergenceDecision.APPLIED,
        )

        assertFalse(durable.single().resultDiffers)
        assertTrue(durable.single().shapeDiffers)
    }

    @Test
    fun `shadow는 action별 production 응답 allowlist fingerprint를 정상 구조로 인식한다`() {
        val durable = mutableListOf<DurableShadowEvaluation>()
        val evaluator = DefaultAutomationConvergenceShadowEvaluator(
            TimeProvider { now },
            AutomationConvergenceShadowRecorder(durable::add),
        )
        val selection = SelectedAutomationAction(
            entryId = 5L,
            executionIdentity = "shadow-known-shape",
            actionKind = AutomationActionKind.QUEST_CLAIM,
            scope = AutomationIsolationScope(AutomationIsolationScopeKind.QUEST_TARGET, "quest"),
            policyVersion = ProductionActionEvidenceInterpreter.VERSION_1,
            baselineFingerprint = "baseline",
        )
        evaluator.selected(7L, selection)

        evaluator.observe(
            7L,
            selection.executionIdentity,
            AutomationActionEvidence.DirectApplied(
                capturedAt = now,
                stateFingerprint = "claim-applied",
                responseShapeFingerprint = ProductionEvidenceShapes.fingerprint(
                    ProductionEvidenceShapes.QUEST_RESPONSE,
                ),
                sanitizedSnippet = "QuestResponse|targetPresent=false",
            ),
            LegacyConvergenceDecision.APPLIED,
        )

        assertFalse(durable.single().shapeDiffers)
    }
}
