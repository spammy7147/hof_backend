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
    fun `새 시도는 null 결과 없이 pending으로 생성된다`() {
        val attemptId = assertIs<ConvergenceDirective.Submit>(
            module.prepare(7L, questSelection("new-pending")),
        ).attemptId

        assertEquals(ActionConvergenceResult.PENDING, store.get(attemptId)?.result)
    }

    @Test
    fun `기존 null 결과 orphan은 pending으로 정규화하고 즉시 확인한다`() {
        val selection = questSelection("legacy-orphan")
        val orphan = store.createOrGet(7L, selection, clock.now())
        orphan.result = null
        orphan.nextProbeAt = null
        store.save(orphan)

        val probe = assertIs<ConvergenceDirective.Probe>(module.resumeDue(7L))

        assertEquals(orphan.attemptId, probe.attemptId)
        assertEquals(ActionConvergenceResult.PENDING, store.get(orphan.attemptId)?.result)
        assertEquals("ORPHAN_RESULT_RECONCILED", store.get(orphan.attemptId)?.reasonCode)
    }

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
    fun `보류 뒤 다른 최신 상태를 관측하면 새 사이클의 같은 baseline도 다시 허용한다`() {
        val held = questSelection("state-transition")
        val record = store.createOrGet(7L, held, clock.now())
        record.result = ActionConvergenceResult.HELD
        record.finishedAt = clock.now()
        store.save(record)

        assertEquals(
            0,
            module.observeAuthoritativeBaseline(
                7L,
                held.scope,
                held.baselineFingerprint,
                clock.now(),
            ),
        )
        assertIs<ConvergenceDirective.ContinueSelection>(
            module.prepare(7L, held.copy(executionIdentity = "same-state-blocked")),
        )

        assertEquals(
            1,
            module.observeAuthoritativeBaseline(
                7L,
                held.scope,
                "different-authoritative-baseline",
                clock.now(),
            ),
        )

        assertIs<ConvergenceDirective.Submit>(
            module.prepare(7L, held.copy(executionIdentity = "new-cycle-same-state")),
        )
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
    fun `legacy 예산 종료는 전투를 result unobserved로 비전투를 held로 억제한다`() {
        val quest = questSelection("legacy-quest-held")
        assertIs<ConvergenceDirective.ContinueSelection>(
            module.holdUnresolved(
                7L,
                quest,
                AutomationActionEvidence.ResultUnobserved(clock.now(), "quest unresolved"),
                successfulObservationCount = 5,
                firstPendingAt = clock.now().minusSeconds(40),
            ),
        )
        val questRecord = store.createOrGet(7L, quest, clock.now())
        assertEquals(ActionConvergenceResult.HELD, questRecord.result)
        assertEquals(5, questRecord.successfulObservationCount)
        assertEquals(clock.now().minusSeconds(40), questRecord.firstPendingAt)

        val battle = battleSelection("legacy-battle-unobserved")
        assertIs<ConvergenceDirective.ContinueSelection>(
            module.holdUnresolved(
                7L,
                battle,
                AutomationActionEvidence.ResultUnobserved(clock.now(), "battle unresolved"),
                successfulObservationCount = 3,
                firstPendingAt = clock.now().minusSeconds(120),
            ),
        )
        val battleRecord = store.createOrGet(7L, battle, clock.now())
        assertEquals(ActionConvergenceResult.RESULT_UNOBSERVED, battleRecord.result)
        assertEquals(
            setOf(battle.baselineFingerprint),
            store.findSuppressedBaselines(7L)[battle.scope],
        )
    }

    @Test
    fun `제출 없는 권위 관측 gap은 다섯 번 뒤 held가 되고 runtime probe 대상이 아니다`() {
        val selection = questSelection("home-action-gap").copy(observationOnly = true)
        var directive: ConvergenceDirective = ConvergenceDirective.ContinueSelection

        repeat(5) { index ->
            directive = module.observeGap(
                7L,
                selection,
                AutomationActionEvidence.IncompleteObservation(
                    clock.now(),
                    "HOME_ACTION_ID_MISSING",
                    authoritative = true,
                ),
            )
            if (index < 4) clock.advanceSeconds(10)
        }

        assertIs<ConvergenceDirective.ContinueSelection>(directive)
        val record = store.findActive(7L, selection.scope)
        assertEquals(null, record)
        assertEquals(ConvergenceDirective.ContinueSelection, module.resumeDue(7L))
        val held = store.findSuppressedBaselines(7L)[selection.scope].orEmpty()
        assertEquals(setOf(selection.baselineFingerprint), held)
    }

    @Test
    fun `제출 없는 관측 gap이 해소되면 active scope를 닫고 새 행동을 허용한다`() {
        val gap = questSelection("home-action-gap-resolved").copy(observationOnly = true)
        module.observeGap(
            7L,
            gap,
            AutomationActionEvidence.IncompleteObservation(
                clock.now(),
                "HOME_ACTION_ID_MISSING",
                authoritative = true,
            ),
        )
        val gapAttemptId = requireNotNull(store.findActive(7L, gap.scope)).attemptId

        assertEquals(true, module.resolveObservationGap(7L, gap.scope, clock.now()))
        assertEquals(ActionConvergenceResult.SUPERSEDED, store.get(gapAttemptId)?.result)
        assertIs<ConvergenceDirective.Submit>(
            module.prepare(
                7L,
                gap.copy(
                    executionIdentity = "home-action-now-runnable",
                    observationOnly = false,
                    baselineFingerprint = "home|accept|available",
                ),
            ),
        )
    }

    @Test
    fun `같은 scope의 관측 gap identity가 바뀌면 이전 gap을 닫고 새 예산을 시작한다`() {
        val first = questSelection("changing-gap").copy(observationOnly = true)
        module.observeGap(
            7L,
            first,
            AutomationActionEvidence.IncompleteObservation(
                clock.now(),
                "HOME_ACTION_ID_MISSING",
                authoritative = true,
            ),
        )
        val firstAttemptId = requireNotNull(store.findActive(7L, first.scope)).attemptId
        val changed = first.copy(
            executionIdentity = "execution-changing-gap-v2",
            baselineFingerprint = "baseline-changing-gap-v2",
        )

        assertIs<ConvergenceDirective.WaitUntil>(
            module.observeGap(
                7L,
                changed,
                AutomationActionEvidence.IncompleteObservation(
                    clock.now(),
                    "HOME_ACTION_ID_CHANGED",
                    authoritative = true,
                ),
            ),
        )

        assertEquals(ActionConvergenceResult.SUPERSEDED, store.get(firstAttemptId)?.result)
        val current = requireNotNull(store.findActive(7L, first.scope))
        assertEquals(changed.executionIdentity, current.selection.executionIdentity)
        assertEquals(1, current.successfulObservationCount)
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

    @Test
    fun `수렴 시도가 없는 전투 캡차도 관문을 열고 저장 전투 재사용을 막는다`() {
        assertIs<ConvergenceDirective.BattleGateWait>(
            module.requireBattleGate(
                accountId = 7L,
                challengeId = 45L,
                reason = "CAPTCHA_REQUIRED",
                capturedAt = clock.now(),
            ),
        )

        assertIs<ConvergenceDirective.BattleGateWait>(module.prepare(7L, battleSelection("map-shadow")))
        assertIs<ConvergenceDirective.Submit>(module.prepare(7L, questSelection("quest-shadow")))
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
