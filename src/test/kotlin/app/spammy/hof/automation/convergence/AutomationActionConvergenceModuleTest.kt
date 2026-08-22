package app.spammy.hof.automation.convergence

import app.spammy.hof.common.time.TimeProvider
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class AutomationActionConvergenceModuleTest {
    private val clock = MutableTimeProvider(Instant.parse("2026-08-22T00:00:00Z"))
    private val store = InMemoryConvergenceStore()
    private val module: AutomationActionConvergenceModule =
        DefaultAutomationActionConvergenceModule(store, clock)

    @Test
    fun `같은 상태를 다섯 번 관측하면 해당 범위만 보류하고 다른 범위는 제출할 수 있다`() {
        val first = questSelection("quest-a")
        val firstAttempt = assertIs<ConvergenceDirective.Submit>(module.prepare(7L, first)).attemptId

        repeat(4) { index ->
            val wait = assertIs<ConvergenceDirective.WaitUntil>(
                module.record(
                    firstAttempt,
                    AutomationActionEvidence.SameState(
                        capturedAt = clock.now(),
                        stateFingerprint = "same-$index",
                    ),
                ),
            )
            assertEquals(clock.now().plusSeconds(10), wait.at)
            clock.advanceSeconds(10)
        }

        val independent = assertIs<ConvergenceDirective.Submit>(
            module.prepare(7L, questSelection("quest-b")),
        )
        assertNotEquals(firstAttempt, independent.attemptId)

        assertIs<ConvergenceDirective.ContinueSelection>(
            module.record(
                firstAttempt,
                AutomationActionEvidence.SameState(
                    capturedAt = clock.now(),
                    stateFingerprint = "same-final",
                ),
            ),
        )
        assertEquals(ActionConvergenceResult.HELD, store.get(firstAttempt)?.result)
        assertIs<ConvergenceDirective.ContinueSelection>(
            module.prepare(7L, first.copy(executionIdentity = "execution-held-retry")),
        )
        val changedAttempt = assertIs<ConvergenceDirective.Submit>(
            module.prepare(
                7L,
                first.copy(
                    executionIdentity = "execution-held-changed",
                    baselineFingerprint = "baseline-changed",
                ),
            ),
        ).attemptId
        module.record(changedAttempt, AutomationActionEvidence.DirectRejected(clock.now(), "not applied"))
        assertEquals(true, module.allowFreshDecision(7L, firstAttempt, clock.now()))
        assertIs<ConvergenceDirective.Submit>(
            module.prepare(7L, first.copy(executionIdentity = "execution-user-allowed")),
        )
        assertIs<ConvergenceDirective.Submit>(module.prepare(7L, questSelection("quest-b-2")))
    }

    @Test
    fun `네트워크 실패는 관측 횟수에서 제외하지만 이분 예산이 끝나면 보류한다`() {
        val attemptId = assertIs<ConvergenceDirective.Submit>(
            module.prepare(7L, questSelection("quest-network")),
        ).attemptId

        assertIs<ConvergenceDirective.WaitUntil>(
            module.record(
                attemptId,
                AutomationActionEvidence.NetworkFailure(clock.now(), "timeout"),
            ),
        )
        assertEquals(0, store.get(attemptId)?.successfulObservationCount)

        clock.advanceSeconds(120)

        assertIs<ConvergenceDirective.ContinueSelection>(module.resumeDue(7L))
        assertEquals(ActionConvergenceResult.HELD, store.get(attemptId)?.result)
    }

    @Test
    fun `직접 적용과 외부 상태 진전은 서로 다른 terminal 결과다`() {
        val appliedId = assertIs<ConvergenceDirective.Submit>(
            module.prepare(7L, questSelection("quest-applied")),
        ).attemptId
        assertIs<ConvergenceDirective.ContinueSelection>(
            module.record(appliedId, AutomationActionEvidence.DirectApplied(clock.now(), "quest-active")),
        )
        assertEquals(ActionConvergenceResult.APPLIED, store.get(appliedId)?.result)
        assertIs<ConvergenceDirective.ContinueSelection>(
            module.prepare(7L, questSelection("quest-applied")),
        )

        val supersededId = assertIs<ConvergenceDirective.Submit>(
            module.prepare(7L, questSelection("quest-superseded")),
        ).attemptId
        assertIs<ConvergenceDirective.ContinueSelection>(
            module.record(supersededId, AutomationActionEvidence.StateAdvanced(clock.now(), "quest-waiting")),
        )
        assertEquals(ActionConvergenceResult.SUPERSEDED, store.get(supersededId)?.result)
    }

    @Test
    fun `전투 캡차는 전투 관문을 열고 비전투 제출은 계속 허용한다`() {
        val battle = battleSelection("union-entry")
        val attemptId = assertIs<ConvergenceDirective.Submit>(module.prepare(7L, battle)).attemptId

        assertIs<ConvergenceDirective.BattleGateWait>(
            module.record(
                attemptId,
                AutomationActionEvidence.BattleGateRequired(
                    capturedAt = clock.now(),
                    challengeId = 44L,
                    reason = "CAPTCHA_REQUIRED",
                ),
            ),
        )
        assertEquals(ActionConvergenceResult.NOT_APPLIED, store.get(attemptId)?.result)
        assertIs<ConvergenceDirective.BattleGateWait>(module.prepare(7L, battleSelection("map-entry")))
        assertIs<ConvergenceDirective.Submit>(module.prepare(7L, questSelection("quest-noncombat")))

        module.releaseBattleGate(7L, clock.now())

        assertIs<ConvergenceDirective.Submit>(module.prepare(7L, battleSelection("map-entry-after")))
    }

    private fun questSelection(key: String) = SelectedAutomationAction(
        entryId = 12L,
        executionIdentity = "execution-$key",
        actionKind = AutomationActionKind.QUEST_CLAIM,
        scope = AutomationIsolationScope(AutomationIsolationScopeKind.QUEST_TARGET, key),
        policyVersion = "convergence-v1",
        baselineFingerprint = "baseline-$key",
    )

    private fun battleSelection(key: String) = SelectedAutomationAction(
        entryId = 20L,
        executionIdentity = "execution-$key",
        actionKind = AutomationActionKind.UNION_BATTLE,
        scope = AutomationIsolationScope(AutomationIsolationScopeKind.UNION_ENTRY, key),
        policyVersion = "convergence-v1",
        baselineFingerprint = "baseline-$key",
    )

    private class MutableTimeProvider(private var current: Instant) : TimeProvider {
        override fun now(): Instant = current
        fun advanceSeconds(seconds: Long) {
            current = current.plusSeconds(seconds)
        }
    }
}
