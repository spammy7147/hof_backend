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
    fun `체크포인트 복원은 알려진 시각과 관측 횟수를 되돌리지 않고 소진된 횟수 예산을 닫는다`() {
        val selection = questSelection("restored-budget")
        val submittedAt = clock.now()
        val id = assertIs<ConvergenceDirective.Submit>(module.prepare(7L, selection)).attemptId
        repeat(2) {
            module.record(id, AutomationActionEvidence.SameState(clock.now(), "unchanged"))
            clock.advanceSeconds(1)
        }
        val nextProbeAt = requireNotNull(store.get(id)?.nextProbeAt)

        val restored = module.restoreCheckpoint(7L, selection, RestoredActionCheckpoint(clock.now(), 1, clock.now()))

        assertEquals(nextProbeAt, assertIs<ConvergenceDirective.WaitUntil>(restored).at)
        assertEquals(submittedAt, store.get(id)?.submittedAt)
        assertEquals(submittedAt, store.get(id)?.firstPendingAt)
        assertEquals(2, store.get(id)?.successfulObservationCount)

        assertIs<ConvergenceDirective.ContinueSelection>(module.restoreCheckpoint(
            7L, selection, RestoredActionCheckpoint(submittedAt, 5, submittedAt),
        ))
        assertEquals(ActionConvergenceResult.HELD, store.get(id)?.result)
        assertEquals("PENDING_BUDGET_EXHAUSTED", store.get(id)?.reasonCode)
        assertEquals(5, store.get(id)?.successfulObservationCount)
        assertIs<ConvergenceDirective.Submit>(module.prepare(7L, questSelection("independent-after-budget")))
    }

    @Test
    fun `미지원 미전송 시도는 현재 정책으로 재제출하지 않고 원래 문맥으로 보류한다`() {
        val saved = questSelection("unsupported-retry").copy(policyVersion = "unsupported-fixture-version")
        val record = store.createOrGet(7L, saved, clock.now())
        record.result = ActionConvergenceResult.NOT_APPLIED
        record.finishedAt = clock.now()
        record.reasonCode = "BATTLE_PATTERN_PRELOAD_DEFERRED"
        val current = saved.copy(policyVersion = ProductionActionEvidenceInterpreter.VERSION_1)

        assertIs<ConvergenceDirective.ContinueSelection>(module.retryUnsubmitted(7L, current, clock.now()))

        val held = requireNotNull(store.get(record.attemptId))
        assertEquals(ActionConvergenceResult.HELD, held.result)
        assertEquals("POLICY_VERSION_UNSUPPORTED", held.reasonCode)
        assertEquals(saved, held.selection)
        assertEquals(null, held.submittedAt)
        assertEquals(0, held.successfulObservationCount)
        assertEquals(setOf(saved.scope), store.findPolicyHeldScopes(7L))
    }

    @Test
    fun `미지원 과거 보류는 사유와 기준 상태가 달라도 자동 해제하지 않고 독립 범위만 제출한다`() {
        for (result in listOf(ActionConvergenceResult.HELD, ActionConvergenceResult.RESULT_UNOBSERVED)) {
            val saved = questSelection("unknown-$result").copy(
                actionKind = AutomationActionKind.RAID_REGISTER,
                scope = AutomationIsolationScope(AutomationIsolationScopeKind.RAID_ENTRY, "raid-$result"),
                policyVersion = "unsupported-fixture-version",
            )
            val record = store.createOrGet(7L, saved, clock.now()).also {
                it.result = result
                it.reasonCode = "PENDING_BUDGET_EXHAUSTED"
                it.finishedAt = clock.now()
            }
            assertEquals(true, saved.scope in store.findPolicyHeldScopes(7L))
            assertEquals(0, module.observeAuthoritativeBaseline(7L, saved.scope, "current-baseline", clock.now()))
            assertEquals(0, module.allowRaidRegistrationFreshDecision(7L, saved.entryId, saved.scope.key, clock.now()))
            assertEquals("PENDING_BUDGET_EXHAUSTED", store.get(record.attemptId)?.reasonCode)
            val current = saved.copy(executionIdentity = "new-$result", baselineFingerprint = "current-baseline",
                policyVersion = ProductionActionEvidenceInterpreter.VERSION_1)
            assertIs<ConvergenceDirective.ContinueSelection>(module.prepare(7L, current))
            assertIs<ConvergenceDirective.ContinueSelection>(module.retryUnsubmitted(7L, current, clock.now()))
            assertEquals(null, store.get(7L, current.executionIdentity))
            assertIs<ConvergenceDirective.Submit>(module.prepare(7L, questSelection("independent-$result")))
            assertEquals(true, module.allowFreshDecision(7L, record.attemptId, clock.now()))
            assertIs<ConvergenceDirective.Submit>(module.prepare(7L, current))
        }
    }

    @Test
    fun `미지원 새 시도는 제출 문맥을 반환하지 않고 전송 전 보류한다`() {
        val selected = questSelection("unsupported-new").copy(policyVersion = "unsupported-fixture-version")
        assertIs<ConvergenceDirective.ContinueSelection>(module.prepare(7L, selected))
        val held = requireNotNull(store.get(7L, selected.executionIdentity))
        assertEquals(ActionConvergenceResult.HELD, held.result)
        assertEquals("POLICY_VERSION_UNSUPPORTED", held.reasonCode)
        assertEquals(null, held.submittedAt)
        assertEquals(selected, held.selection)
    }

    @Test
    fun `미지원 정책의 응답은 적용과 새 판단으로 바꾸지 않고 기존 최종 결과도 보존한다`() {
        val evidence = listOf(
            AutomationActionEvidence.DirectApplied(clock.now(), "applied"),
            AutomationActionEvidence.ResultUnobservedFreshDecision(clock.now(), "fresh"),
            AutomationActionEvidence.StateAdvanced(clock.now(), "advanced"),
        )
        evidence.forEachIndexed { index, observed ->
            val selected = questSelection("unknown-record-$index").copy(policyVersion = "unsupported-fixture-version")
            val record = store.createOrGet(7L, selected, clock.now())
            var projected = false
            assertIs<ConvergenceDirective.ContinueSelection>(module.record(record.attemptId, observed) { projected = true })
            val held = requireNotNull(store.get(record.attemptId)).copy()
            assertEquals(ActionConvergenceResult.HELD, held.result)
            assertEquals("POLICY_VERSION_UNSUPPORTED", held.reasonCode)
            assertEquals(null, held.submittedAt)
            assertEquals(0, held.successfulObservationCount)
            assertEquals(false, projected)
            assertEquals(true, selected.scope in store.findPolicyHeldScopes(7L))
            module.recordLateApplication(7L, selected.executionIdentity,
                AutomationActionEvidence.DirectApplied(clock.now().plusSeconds(1), "late"))
            assertEquals(held, store.get(record.attemptId))
        }
        listOf(ActionConvergenceResult.APPLIED, ActionConvergenceResult.NOT_APPLIED,
            ActionConvergenceResult.SUPERSEDED, ActionConvergenceResult.RESULT_UNOBSERVED).forEach { result ->
            val selected = questSelection("unknown-final-$result").copy(policyVersion = "unsupported-fixture-version")
            val record = store.createOrGet(7L, selected, clock.now()).also {
                it.result = result
                it.reasonCode = "ORIGINAL_RESULT"
                it.finishedAt = clock.now()
            }
            val before = record.copy()
            module.recordLateApplication(7L, selected.executionIdentity,
                AutomationActionEvidence.DirectApplied(clock.now().plusSeconds(1), "late"))
            assertEquals(before, store.get(record.attemptId))
        }
    }

    @Test
    fun `미지원 보류의 늦은 직접 응답은 최종 결과를 보존하면서 전달된 진단을 남긴다`() {
        val diagnostics = mutableListOf<AutomationActionEvidence>()
        val convergence = DefaultAutomationActionConvergenceModule(store, clock,
            EvidenceCaseRecorder { _, evidence, _ -> diagnostics += evidence })
        val selected = questSelection("unsupported-late-diagnostic").copy(policyVersion = "unsupported-fixture-version")
        val record = store.createOrGet(7L, selected, clock.now()).also {
            it.result = ActionConvergenceResult.HELD
            it.reasonCode = "PENDING_BUDGET_EXHAUSTED"
            it.submittedAt = clock.now().minusSeconds(10)
            it.finishedAt = clock.now()
        }
        val before = record.copy()
        convergence.recordLateApplication(7L, selected.executionIdentity,
            AutomationActionEvidence.DirectApplied(clock.now().plusSeconds(1), "state", "response-shape", "sanitized-response"))
        assertEquals(before, store.get(record.attemptId))
        val diagnostic = assertIs<AutomationActionEvidence.PolicyUnavailable>(diagnostics.single())
        assertEquals("response-shape", diagnostic.responseShapeFingerprint)
        assertEquals("sanitized-response", diagnostic.sanitizedSnippet)
    }

    @Test
    fun `새 시도는 null 결과 없이 pending으로 생성된다`() {
        val attemptId = assertIs<ConvergenceDirective.Submit>(
            module.prepare(7L, questSelection("new-pending")),
        ).attemptId

        assertEquals(ActionConvergenceResult.PENDING, store.get(attemptId)?.result)
    }

    @Test
    fun `제출 전 안전 대기는 active 시도를 닫고 같은 identity 재시도에서 다시 연다`() {
        val selection = questSelection("discard-before-submit")
        val attemptId = assertIs<ConvergenceDirective.Submit>(module.prepare(7L, selection)).attemptId

        assertEquals(
            true,
            module.discardUnsubmitted(
                accountId = 7L,
                selection = selection,
                discardedAt = clock.now(),
                reasonCode = "BATTLE_PATTERN_PRELOAD_DEFERRED",
            ),
        )

        val discarded = requireNotNull(store.get(attemptId))
        assertEquals(ActionConvergenceResult.NOT_APPLIED, discarded.result)
        assertEquals("BATTLE_PATTERN_PRELOAD_DEFERRED", discarded.reasonCode)
        assertEquals(null, store.findActive(7L, selection.scope))

        assertEquals(
            attemptId,
            assertIs<ConvergenceDirective.Submit>(
                module.retryUnsubmitted(7L, selection, clock.now()),
            ).attemptId,
        )
        val retried = requireNotNull(store.get(attemptId))
        assertEquals(ActionConvergenceResult.PENDING, retried.result)
        assertEquals("UNSUBMITTED_RETRY_PREPARED", retried.reasonCode)
        assertEquals(null, retried.finishedAt)
    }

    @Test
    fun `제출된 시도나 다른 identity는 제출 전 폐기 대상으로 닫지 않는다`() {
        val selection = questSelection("do-not-discard-submitted")
        val attemptId = assertIs<ConvergenceDirective.Submit>(module.prepare(7L, selection)).attemptId
        module.record(
            attemptId,
            AutomationActionEvidence.NetworkFailure(clock.now(), "submission response lost"),
        )

        assertEquals(
            false,
            module.discardUnsubmitted(
                accountId = 7L,
                selection = selection.copy(executionIdentity = "different-identity"),
                discardedAt = clock.now(),
                reasonCode = "MUST_NOT_CLOSE",
            ),
        )
        assertEquals(
            false,
            module.discardUnsubmitted(
                accountId = 7L,
                selection = selection,
                discardedAt = clock.now(),
                reasonCode = "MUST_NOT_CLOSE",
            ),
        )
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
    fun `완전한 퀘스트 진행도로 결과 미관측을 닫으면 같은 baseline의 새 판단을 즉시 허용한다`() {
        val selection = questSelection("quest-progress-fresh-decision")
        val attemptId = assertIs<ConvergenceDirective.Submit>(module.prepare(7L, selection)).attemptId

        assertIs<ConvergenceDirective.ContinueSelection>(
            module.record(
                attemptId,
                AutomationActionEvidence.ResultUnobservedFreshDecision(
                    capturedAt = clock.now(),
                    reason = "QUEST_PROGRESS_AUTHORITATIVE",
                    responseShapeFingerprint = "a".repeat(64),
                ),
            ),
        )

        assertEquals(ActionConvergenceResult.RESULT_UNOBSERVED, store.get(attemptId)?.result)
        assertEquals("RESULT_UNOBSERVED_FRESH_DECISION", store.get(attemptId)?.reasonCode)
        assertEquals(emptySet(), store.findSuppressedBaselines(7L)[selection.scope].orEmpty())
        assertIs<ConvergenceDirective.Submit>(
            module.prepare(7L, selection.copy(executionIdentity = "fresh-execution")),
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = [false, true])
    fun `전투 캡차는 전송 여부를 보존하며 관문을 열고 비전투 제출은 계속 허용한다`(submissionAttempted: Boolean) {
        val battle = battleSelection("union-entry")
        val attemptId = assertIs<ConvergenceDirective.Submit>(module.prepare(7L, battle)).attemptId

        assertIs<ConvergenceDirective.BattleGateWait>(
            module.record(
                attemptId,
                AutomationActionEvidence.BattleGateRequired(
                    capturedAt = clock.now(),
                    challengeId = 44L,
                    reason = "CAPTCHA_REQUIRED",
                    submissionAttempted = submissionAttempted,
                ),
            ),
        )
        assertEquals(ActionConvergenceResult.NOT_APPLIED, store.get(attemptId)?.result)
        assertEquals(if (submissionAttempted) clock.now() else null, store.get(attemptId)?.submittedAt)
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
        policyVersion = ProductionActionEvidenceInterpreter.VERSION_1,
        baselineFingerprint = "baseline-$key",
    )

    private fun battleSelection(key: String) = SelectedAutomationAction(
        entryId = 20L,
        executionIdentity = "execution-$key",
        actionKind = AutomationActionKind.UNION_BATTLE,
        scope = AutomationIsolationScope(AutomationIsolationScopeKind.UNION_ENTRY, key),
        policyVersion = ProductionActionEvidenceInterpreter.VERSION_1,
        baselineFingerprint = "baseline-$key",
    )

    private class MutableTimeProvider(private var current: Instant) : TimeProvider {
        override fun now(): Instant = current
        fun advanceSeconds(seconds: Long) {
            current = current.plusSeconds(seconds)
        }
    }
}
