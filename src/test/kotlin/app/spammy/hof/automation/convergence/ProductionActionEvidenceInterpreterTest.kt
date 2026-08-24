package app.spammy.hof.automation.convergence

import app.spammy.hof.automation.service.AmbiguousActionResolution
import app.spammy.hof.automation.service.TypedAutomationExecution
import app.spammy.hof.quest.model.QuestState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class ProductionActionEvidenceInterpreterTest {
    private val interpreter = ProductionActionEvidenceInterpreter(DefaultActionEvidencePolicies())
    private val selection = SelectedAutomationAction(
        entryId = 12L,
        executionIdentity = "execution",
        actionKind = AutomationActionKind.QUEST_CLAIM,
        scope = AutomationIsolationScope(AutomationIsolationScopeKind.QUEST_TARGET, "quest"),
        policyVersion = "policy-v1",
        baselineFingerprint = "baseline",
    )

    @Test
    fun `검증된 lifecycle 성공만 policy를 거쳐 applied가 된다`() {
        val evidence = assertIs<AutomationActionEvidence.DirectApplied>(
            interpreter.fromExecution(selection, questClaimCompleted(), NOW),
        )
        assertEquals(64, evidence.responseShapeFingerprint?.length)
        assertEquals(QUEST_CLAIM_SNIPPET, evidence.sanitizedSnippet)
    }

    @Test
    fun `reconcile applied는 적용이고 resubmit은 동일 상태 관측이다`() {
        assertIs<AutomationActionEvidence.DirectApplied>(
            interpreter.fromReconciliation(selection, AmbiguousActionResolution.Applied(), NOW),
        )
        assertIs<AutomationActionEvidence.SameState>(
            interpreter.fromReconciliation(selection, AmbiguousActionResolution.Resubmit, NOW),
        )
    }

    @Test
    fun `공유 cooldown은 행동 성공이 아니라 최신 상태 변경이다`() {
        assertIs<AutomationActionEvidence.StateAdvanced>(
            interpreter.fromExecution(
                selection,
                TypedAutomationExecution.SharedCooldown("battle", "map", NOW.plusSeconds(30)),
                NOW,
            ),
        )
    }

    @Test
    fun `외부가 완료할 수 있는 raid handoff는 즉시 결과 미관측으로 닫지 않는다`() {
        val raid = selection.copy(
            actionKind = AutomationActionKind.RAID_BATTLE,
            scope = AutomationIsolationScope(AutomationIsolationScopeKind.RAID_ENTRY, "raid"),
        )

        val evidence = assertIs<AutomationActionEvidence.IncompleteObservation>(
            interpreter.fromReconciliation(
                raid,
                AmbiguousActionResolution.HandedOff(NOW.plusSeconds(10), "external actor"),
                NOW,
            ),
        )

        assertEquals(true, evidence.authoritative)
    }

    @Test
    fun `shape fingerprint는 실행 identity가 달라도 같은 구조면 안정적이다`() {
        val first = interpreter.fromExecution(selection, questClaimCompleted(), NOW)
        val second = interpreter.fromExecution(
            selection.copy(executionIdentity = "other-execution"),
            questClaimCompleted(),
            NOW.plusSeconds(1),
        )

        assertEquals(first.responseShapeFingerprint, second.responseShapeFingerprint)
        assertNotEquals(first.capturedAt, second.capturedAt)
    }

    @Test
    fun `poststate 없는 일반 Completed는 action 성공 증거가 아니다`() {
        assertIs<AutomationActionEvidence.IncompleteObservation>(
            interpreter.fromExecution(selection, TypedAutomationExecution.Completed, NOW),
        )
    }

    @Test
    fun `구조가 완전한 명시적 거절 응답은 direct rejected 증거가 된다`() {
        val evidence = assertIs<AutomationActionEvidence.DirectRejected>(
            interpreter.fromExecution(
                selection,
                questClaimCompleted().copy(
                    explicitRejected = true,
                    rejectionReason = "QUEST_ACTION_REJECTED",
                ),
                NOW,
            ),
        )

        assertEquals("QUEST_ACTION_REJECTED", evidence.reason)
    }

    @Test
    fun `레이드와 유니온 전투의 terminal direct response는 action kind와 무관하게 applied다`() {
        listOf(
            AutomationActionKind.RAID_BATTLE to AutomationIsolationScopeKind.RAID_ENTRY,
            AutomationActionKind.UNION_BATTLE to AutomationIsolationScopeKind.UNION_ENTRY,
        ).forEach { (actionKind, scopeKind) ->
            val battleSelection = selection.copy(
                actionKind = actionKind,
                scope = AutomationIsolationScope(scopeKind, actionKind.name),
            )

            assertIs<AutomationActionEvidence.DirectApplied>(
                interpreter.fromExecution(
                    battleSelection,
                    TypedAutomationExecution.BattleCompleted(
                        categoryId = actionKind.name,
                        mapCode = "target",
                        terminalOutcomes = listOf("VICTORY", "DEFEAT", "DRAW"),
                    ),
                    NOW,
                ),
                actionKind.name,
            )
        }
    }

    private fun questClaimCompleted() = TypedAutomationExecution.ActionCompleted(
        observedState = QuestObservedState(
            fingerprint = "quest-claim-response",
            present = true,
            state = QuestState.UNAVAILABLE,
            actionNo = null,
        ),
        responseShapeMaterial = ProductionEvidenceShapes.QUEST_RESPONSE,
        sanitizedSnippet = QUEST_CLAIM_SNIPPET,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-22T00:00:00Z")
        const val QUEST_CLAIM_SNIPPET =
            "QuestResponse|targetPresent=true|state=UNAVAILABLE|actionNoPresent=false|progressPresent=false"
    }
}
