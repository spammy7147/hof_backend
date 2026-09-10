package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.automation.entity.AutomationEntryEntity
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.AutomationWaitReason
import app.spammy.hof.automation.entity.QuestAutomationCycleEntity
import app.spammy.hof.automation.entity.TypedAutomationActionRunEntity
import app.spammy.hof.automation.entity.TypedAutomationActionStatus
import app.spammy.hof.automation.entity.TypedAutomationLifecycle
import app.spammy.hof.automation.entity.TypedAutomationRuntimeStateEntity
import app.spammy.hof.automation.outbox.AutomationOutboxService
import app.spammy.hof.automation.repository.TypedAutomationActionRunCommandRepository
import app.spammy.hof.automation.repository.TypedAutomationQueryRepository
import app.spammy.hof.common.time.TimeProvider
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import org.mockito.Mockito
import tools.jackson.module.kotlin.jacksonObjectMapper

class TypedAutomationRuntimeServiceTest {
    private var now = Instant.parse("2026-07-16T00:00:00Z")
    private val query = Mockito.mock(TypedAutomationQueryRepository::class.java)
    private val actions = Mockito.mock(TypedAutomationActionRunCommandRepository::class.java)
    private val lifecycleBridge = Mockito.mock(TypedAutomationLifecycleBridge::class.java)
    private val outbox = Mockito.mock(AutomationOutboxService::class.java)
    private val codec = StoredTypedAutomationActionCodec(jacksonObjectMapper())
    private val service = TypedAutomationRuntimeService(
        query,
        actions,
        codec,
        TimeProvider { now },
        lifecycleBridge,
        outbox,
        Mockito.mock(app.spammy.hof.character.repository.CharacterOperationJobQueryRepository::class.java),
        app.spammy.hof.external.config.HofRequestProperties(
            automationMinimumInterval = java.time.Duration.ofSeconds(7),
        ),
    )
    private val account = HofAccountEntity(7, "login", "encrypted", now)

    @Test
    fun `round completion enqueues the next round using the request interval`() {
        val state = state()
        val execution = acquire(state, null)

        val result = service.complete(execution, TypedRuntimeOutcome.RoundCompleted(listOf("missing preset")))

        assertTrue(result.applied)
        assertEquals(now.plusSeconds(7), result.nextAttemptAt)
        assertEquals(AutomationWaitReason.LOOP_INTERVAL, state.waitReason)
        assertEquals("missing preset", state.warningText)
        Mockito.verify(outbox).enqueue(7, "TYPED_NEXT_ROUND", now.plusSeconds(7))
    }

    @Test
    fun `expired lease cannot enqueue another round`() {
        val state = state()
        val execution = acquire(state, null)
        state.leaseToken = "new-owner"
        assertFalse(service.complete(execution, TypedRuntimeOutcome.RoundCompleted()).applied)
        Mockito.verifyNoInteractions(outbox)
    }

    @Test
    fun `authentication suspension still lets an in-flight draining action reach reconciliation`() {
        val draining = state().apply {
            lifecycleStatus = TypedAutomationLifecycle.DRAINING
            authSuspended = true
        }
        Mockito.`when`(query.findRuntimeState(7)).thenReturn(draining)

        assertTrue(service.isRunning(7))

        draining.lifecycleStatus = TypedAutomationLifecycle.PAUSED
        assertFalse(service.isRunning(7))
    }

    @Test
    fun `acquisition exposes a verified checkpoint without lease or persistence row`() {
        val state = state().apply { leaseToken = "old"; leaseUntil = now.minusSeconds(1) }
        val fixture = action(TypedAutomationActionStatus.PREPARED)
        stubActive(state, fixture.row)

        val acquired = assertIs<TypedRuntimeAcquisition.Acquired>(service.acquire(7))

        assertEquals(fixture.stored, acquired.execution.checkpoint?.storedAction)
        assertEquals(TypedRuntimeCheckpointPhase.PREPARED, acquired.execution.checkpoint?.phase)
        assertTrue(acquired.execution::class.java.declaredFields.none { it.name == "row" })
    }

    @Test
    fun `acquisition enriches a legacy quest suppression with the current cycle`() {
        val state = state().apply { leaseToken = "old"; leaseUntil = now.minusSeconds(1) }
        val fixture = action(TypedAutomationActionStatus.RECONCILING)
        Mockito.`when`(query.findQuestCycle(7, "quest-1")).thenReturn(
            QuestAutomationCycleEntity(31L, account, "quest-1", 4L),
        )
        stubActive(state, fixture.row)

        val acquired = assertIs<TypedRuntimeAcquisition.Acquired>(service.acquire(7))

        assertEquals("4", acquired.execution.checkpoint?.legacySuppressionEpoch)
        assertEquals(
            "4",
            assertIs<StoredTypedActionPayload.QuestClaim>(
                acquired.execution.checkpoint?.storedAction?.payload,
            ).questCycle,
        )
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = ["automation-action-convergence-v1", "unsupported-fixture-version"])
    fun `정책을 기록한 체크포인트는 현재 퀘스트 사이클을 주입하지 않는다`(version: String) {
        val context = StoredActionPolicyContext(version,
            app.spammy.hof.automation.convergence.AutomationActionKind.QUEST_CLAIM,
            app.spammy.hof.automation.convergence.AutomationIsolationScope(
                app.spammy.hof.automation.convergence.AutomationIsolationScopeKind.QUEST_TARGET, "quest-1"),
            "a".repeat(64))
        val fixture = action(TypedAutomationActionStatus.RECONCILING, policyContext = context)
        val originalJson = fixture.row.payloadJson
        val originalFingerprint = fixture.row.actionFingerprint
        Mockito.`when`(query.findQuestCycle(7, "quest-1")).thenReturn(
            QuestAutomationCycleEntity(31L, account, "quest-1", 4L))
        stubActive(state(), fixture.row)

        val restored = assertIs<TypedRuntimeAcquisition.Acquired>(service.acquire(7)).execution.checkpoint!!

        assertEquals(fixture.stored, restored.storedAction)
        assertNull(restored.legacySuppressionEpoch)
        assertEquals(originalJson, fixture.row.payloadJson)
        assertEquals(originalFingerprint, fixture.row.actionFingerprint)
    }

    @Test
    fun `acquisition enriches a legacy fishing suppression with its submitted Korea date`() {
        now = Instant.parse("2026-08-23T00:10:00Z")
        val state = state().apply { leaseToken = "old"; leaseUntil = now.minusSeconds(1) }
        val entry = AutomationEntryEntity(15, account, AutomationType.FISHING, 0, true, now, now)
        val stored = StoredTypedAutomationAction(
            entry.id,
            "legacy-fishing",
            StoredTypedActionPayload.FishingTown(
                app.spammy.hof.town.fishing.model.FishingAction.START,
                app.spammy.hof.town.fishing.model.FishingPrimaryAction.START,
                5,
            ),
        )
        val encoded = codec.encode(stored)
        val row = TypedAutomationActionRunEntity(
            26,
            account,
            entry,
            stored.executionIdentity,
            stored.payload.kind(),
            encoded.json,
            encoded.fingerprint,
            TypedAutomationActionStatus.RECONCILING,
            leaseToken = "old",
            createdAt = Instant.parse("2026-08-22T14:50:00Z"),
            submittedAt = Instant.parse("2026-08-22T15:10:00Z"),
            updatedAt = now,
        )
        stubActive(state, row)

        val acquired = assertIs<TypedRuntimeAcquisition.Acquired>(service.acquire(7))

        assertEquals(LocalDate.parse("2026-08-23").toString(), acquired.execution.checkpoint?.legacySuppressionEpoch)
        assertEquals(
            LocalDate.parse("2026-08-23"),
            assertIs<StoredTypedActionPayload.FishingTown>(
                acquired.execution.checkpoint?.storedAction?.payload,
            ).progressDate,
        )
    }

    @Test
    fun `prepare is durable before submission begins`() {
        val state = state()
        val fixture = action(TypedAutomationActionStatus.PREPARED)
        Mockito.`when`(query.lockRuntimeState(7)).thenReturn(state)
        Mockito.`when`(query.findActiveTypedAction(7)).thenReturn(null)
        Mockito.`when`(query.findEntry(7, fixture.entry.id)).thenReturn(fixture.entry)
        Mockito.`when`(actions.save(anyActionRow())).thenAnswer { it.arguments[0] }

        val acquired = assertIs<TypedRuntimeAcquisition.Acquired>(service.acquire(7))
        val prepared = assertIs<TypedRuntimePreparation.Ready>(
            service.persistPrepared(acquired.execution, fixture.stored, listOf("parked warning")),
        )
        val row = Mockito.mockingDetails(actions).invocations
            .single { it.method.name == "save" }.arguments.single() as TypedAutomationActionRunEntity

        assertEquals(TypedAutomationActionStatus.PREPARED, row.status)
        assertNull(row.submittedAt)
        assertEquals("parked warning", state.warningText)

        Mockito.`when`(query.lockTypedAction(row.id)).thenReturn(row)
        val submission = assertIs<TypedRuntimeSubmission.Started>(service.beginSubmission(prepared.execution))

        assertEquals(now, submission.submittedAt)
        assertEquals(TypedAutomationActionStatus.SUBMITTING, row.status)
    }

    @Test
    fun `설정 revision 없는 과거 미전송 행동은 재전송하지 않고 새로 판단한다`() {
        val state = state()
        val fixture = action(TypedAutomationActionStatus.PREPARED, settingsRevision = null)
        val execution = acquire(state, fixture.row)
        assertIs<TypedRuntimeSubmission.Invalidated>(service.beginSubmission(execution))
        assertEquals(TypedAutomationActionStatus.FAILED, fixture.row.status)
        assertNull(fixture.row.submittedAt)
    }

    @Test
    fun `domain success completes submitted checkpoint and queues wake atomically`() {
        val state = state()
        val fixture = action(TypedAutomationActionStatus.PREPARED)
        val execution = acquire(state, fixture.row)
        Mockito.`when`(query.findRuntimeState(7)).thenReturn(state)
        assertIs<TypedRuntimeSubmission.Started>(service.beginSubmission(execution))

        val projection = service.complete(
            execution,
            TypedRuntimeOutcome.ActionSucceeded("TYPED_ACTION_COMPLETED"),
        )

        assertTrue(projection.applied)
        assertEquals(TypedAutomationActionStatus.SUCCEEDED, fixture.row.status)
        assertNull(state.leaseToken)
        Mockito.verify(outbox).enqueue(7, "TYPED_ACTION_COMPLETED")
    }

    @Test
    fun `work cycle boundary clears the lease and queues a fresh decision atomically`() {
        val state = state()
        val execution = acquire(state, null)
        Mockito.`when`(query.findRuntimeState(7)).thenReturn(state)

        val projection = service.complete(
            execution,
            TypedRuntimeOutcome.SelectionChanged("WORK_CYCLE_BOUNDARY"),
        )

        assertTrue(projection.applied)
        assertNull(state.leaseToken)
        assertNull(state.nextAttemptAt)
        Mockito.verify(outbox).enqueue(7, "WORK_CYCLE_BOUNDARY")
    }

    @Test
    fun `START 성공과 CATCH 준비는 하나의 영속 전이로 바뀐다`() {
        val state = state()
        val start = action(TypedAutomationActionStatus.PREPARED)
        val execution = acquire(state, start.row)
        assertIs<TypedRuntimeSubmission.Started>(service.beginSubmission(execution))
        val catch = StoredTypedAutomationAction(
            entryId = start.entry.id,
            executionIdentity = "catch-1",
            payload = StoredTypedActionPayload.FishingTown(
                app.spammy.hof.town.fishing.model.FishingAction.CATCH,
                app.spammy.hof.town.fishing.model.FishingPrimaryAction.CATCH,
                17,
            ),
        )
        Mockito.`when`(query.findEntry(7, start.entry.id)).thenReturn(start.entry)
        Mockito.`when`(actions.save(anyActionRow())).thenAnswer { it.arguments[0] }

        val preparedCatch = assertIs<TypedRuntimePreparation.Ready>(
            service.advanceAppliedActionToPreparedFollowup(execution, catch),
        )
        val catchRow = Mockito.mockingDetails(actions).invocations
            .last { it.method.name == "save" }.arguments.single() as TypedAutomationActionRunEntity

        assertEquals(TypedAutomationActionStatus.SUCCEEDED, start.row.status)
        assertEquals(now, start.row.finishedAt)
        assertEquals(TypedAutomationActionStatus.PREPARED, catchRow.status)
        assertEquals("catch-1", preparedCatch.execution.checkpoint?.storedAction?.executionIdentity)
        Mockito.`when`(query.lockTypedAction(catchRow.id)).thenReturn(catchRow)
        assertIs<TypedRuntimeSubmission.Started>(service.beginSubmission(preparedCatch.execution))
        assertEquals(TypedAutomationActionStatus.SUBMITTING, catchRow.status)
    }

    @Test
    fun `logout after fishing START convergence does not prepare or submit a new CATCH`() {
        val state = state()
        val start = action(TypedAutomationActionStatus.PREPARED)
        val execution = acquire(state, start.row)
        assertIs<TypedRuntimeSubmission.Started>(service.beginSubmission(execution))
        state.lifecycleStatus = TypedAutomationLifecycle.DRAINING
        state.requestedLifecycle = TypedAutomationLifecycle.PAUSED
        state.authSuspended = true
        state.resumeAfterAuth = true
        val catch = StoredTypedAutomationAction(
            entryId = start.entry.id,
            executionIdentity = "catch-after-logout",
            payload = StoredTypedActionPayload.FishingTown(
                app.spammy.hof.town.fishing.model.FishingAction.CATCH,
                app.spammy.hof.town.fishing.model.FishingPrimaryAction.CATCH,
                17,
            ),
        )

        assertIs<TypedRuntimePreparation.Invalidated>(
            service.advanceAppliedActionToPreparedFollowup(execution, catch),
        )

        assertEquals(TypedAutomationActionStatus.SUCCEEDED, start.row.status)
        assertEquals(TypedAutomationLifecycle.PAUSED, state.lifecycleStatus)
        assertTrue(state.authSuspended)
        assertNull(state.leaseToken)
        Mockito.verify(actions, Mockito.never()).save(anyActionRow())
    }

    @Test
    fun `a prepared action cannot cross the durable authentication drain fence`() {
        val state = state()
        val fixture = action(TypedAutomationActionStatus.PREPARED)
        val execution = acquire(state, fixture.row)
        state.lifecycleStatus = TypedAutomationLifecycle.DRAINING
        state.requestedLifecycle = TypedAutomationLifecycle.PAUSED
        state.authSuspended = true

        assertIs<TypedRuntimeSubmission.Invalidated>(service.beginSubmission(execution))

        assertEquals(TypedAutomationActionStatus.FAILED, fixture.row.status)
        assertEquals(TypedAutomationLifecycle.PAUSED, state.lifecycleStatus)
        assertNull(state.leaseToken)
    }

    @Test
    fun `ambiguous submission moves checkpoint to reconciliation`() {
        val state = state()
        val fixture = action(TypedAutomationActionStatus.PREPARED)
        val execution = acquire(state, fixture.row)
        assertIs<TypedRuntimeSubmission.Started>(service.beginSubmission(execution))

        val projection = service.complete(
            execution,
            TypedRuntimeOutcome.SubmissionAmbiguous("unknown outcome"),
        )

        assertTrue(projection.applied)
        assertEquals(TypedAutomationActionStatus.RECONCILING, fixture.row.status)
        assertNull(state.leaseToken)
        Mockito.verify(outbox).enqueue(7, "TYPED_AMBIGUOUS_RECONCILE")
    }

    @Test
    fun `제출 직전 사전조건이 변경된 행동은 종료하고 즉시 새로 판단한다`() {
        val state = state().apply {
            retryAttempt = 4
            lastError = "stale failure"
            waitReason = AutomationWaitReason.SCHEDULED
        }
        val fixture = action(TypedAutomationActionStatus.PREPARED)
        val execution = acquire(state, fixture.row)
        assertIs<TypedRuntimeSubmission.Started>(service.beginSubmission(execution))

        val projection = service.complete(
            execution,
            TypedRuntimeOutcome.ActionSuperseded(
                "최신 상태에서 저장 행동의 사전조건이 사라졌습니다.",
                "TYPED_ACTION_SUPERSEDED",
            ),
        )

        assertTrue(projection.applied)
        assertEquals(TypedAutomationActionStatus.FAILED, fixture.row.status)
        assertEquals(now, fixture.row.finishedAt)
        assertEquals(0, state.retryAttempt)
        assertNull(state.nextAttemptAt)
        assertNull(state.waitReason)
        assertNull(state.lastError)
        assertEquals("최신 상태에서 저장 행동의 사전조건이 사라졌습니다.", state.warningText)
        Mockito.verify(outbox).enqueue(7, "TYPED_ACTION_SUPERSEDED")
    }

    @Test
    fun `submitted HOF deferral returns checkpoint to prepared and sanitizes diagnostics`() {
        val retryAt = now.plusSeconds(30)
        val state = state()
        val fixture = action(TypedAutomationActionStatus.PREPARED)
        val execution = acquire(state, fixture.row)
        assertIs<TypedRuntimeSubmission.Started>(service.beginSubmission(execution))

        val projection = service.complete(
            execution,
            TypedRuntimeOutcome.SubmissionDeferred(retryAt, "password=secret\n503"),
        )

        assertEquals(retryAt, projection.nextAttemptAt)
        assertEquals(TypedAutomationActionStatus.PREPARED, fixture.row.status)
        assertEquals(1, fixture.row.retryAttempt)
        assertNull(fixture.row.submittedAt)
        assertEquals("password=[redacted] 503", state.lastError)
        assertEquals(AutomationWaitReason.HOF_CONNECTION, state.waitReason)

        now = retryAt
        val resumed = assertIs<TypedRuntimeAcquisition.Acquired>(service.acquire(7))
        assertEquals(true, resumed.execution.checkpoint?.deferredSubmissionRetry)
    }

    @Test
    fun `logout discards a deferred action that never reached HOF and completes the drain`() {
        val retryAt = now.plusSeconds(30)
        val state = state()
        val fixture = action(TypedAutomationActionStatus.PREPARED)
        val execution = acquire(state, fixture.row)
        assertIs<TypedRuntimeSubmission.Started>(service.beginSubmission(execution))
        state.lifecycleStatus = TypedAutomationLifecycle.DRAINING
        state.requestedLifecycle = TypedAutomationLifecycle.PAUSED
        state.authSuspended = true
        state.resumeAfterAuth = true

        val projection = service.complete(
            execution,
            TypedRuntimeOutcome.SubmissionDeferred(retryAt, "not submitted"),
        )

        assertTrue(projection.applied)
        assertEquals(TypedAutomationActionStatus.FAILED, fixture.row.status)
        assertNull(fixture.row.submittedAt)
        assertEquals(TypedAutomationLifecycle.PAUSED, state.lifecycleStatus)
        assertTrue(state.authSuspended)
        assertNull(state.nextAttemptAt)
        assertNull(state.leaseToken)
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = [false, true])
    fun `전투 캡차는 저장 행동을 종료하고 전송 여부를 보존하며 관문 판단을 깨운다`(submissionAttempted: Boolean) {
        val state = state()
        val fixture = action(TypedAutomationActionStatus.PREPARED)
        val execution = acquire(state, fixture.row)
        assertIs<TypedRuntimeSubmission.Started>(service.beginSubmission(execution))

        val projection = service.complete(
            execution,
            TypedRuntimeOutcome.BattleGateBlocked(
                warning = "captcha",
                wakeReason = "TYPED_BATTLE_GATE",
                submissionAttempted = submissionAttempted,
            ),
        )

        assertNull(projection.nextAttemptAt)
        assertEquals(TypedAutomationActionStatus.FAILED, fixture.row.status)
        assertEquals(if (submissionAttempted) now else null, fixture.row.submittedAt)
        assertEquals(now, fixture.row.finishedAt)
        assertNull(state.stopReason)
        assertEquals(TypedAutomationLifecycle.RUNNING, state.lifecycleStatus)
        Mockito.verify(outbox).enqueue(7L, "TYPED_BATTLE_GATE")
    }

    @Test
    fun `reconciliation deferral persists its successful observation budget`() {
        val state = state()
        val fixture = action(TypedAutomationActionStatus.RECONCILING)
        val execution = acquire(state, fixture.row)
        val retryAt = now.plusSeconds(10)

        assertTrue(
            service.complete(
                execution,
                TypedRuntimeOutcome.ReconciliationDeferred(retryAt, "verify later"),
            ).applied,
        )
        assertEquals(TypedAutomationActionStatus.RECONCILING, fixture.row.status)
        assertEquals(retryAt, state.nextAttemptAt)
        assertEquals(1, fixture.row.reconciliationObservationCount)
        assertEquals(now, fixture.row.reconciliationFirstPendingAt)

    }

    @Test
    fun `reconciliation applied closes the stored action as succeeded`() {
        val state = state()
        val fixture = action(TypedAutomationActionStatus.RECONCILING)
        val execution = acquire(state, fixture.row)

        assertTrue(
            service.complete(
                execution,
                TypedRuntimeOutcome.ReconciliationApplied("TYPED_ACTION_COMPLETED"),
            ).applied,
        )
        assertEquals(TypedAutomationActionStatus.SUCCEEDED, fixture.row.status)
        Mockito.verify(outbox).enqueue(7, "TYPED_ACTION_COMPLETED")
    }

    @Test
    fun `network reconciliation deferral starts time budget without counting a successful observation`() {
        val state = state()
        val fixture = action(TypedAutomationActionStatus.RECONCILING)
        val execution = acquire(state, fixture.row)
        val retryAt = now.plusSeconds(10)

        assertTrue(
            service.complete(
                execution,
                TypedRuntimeOutcome.ReconciliationDeferred(
                    retryAt,
                    "HOF unavailable",
                    successfulObservation = false,
                ),
            ).applied,
        )

        assertEquals(0, fixture.row.reconciliationObservationCount)
        assertEquals(now, fixture.row.reconciliationFirstPendingAt)
        assertEquals(retryAt, fixture.row.nextAttemptAt)
    }

    @Test
    fun `ambiguous handoff terminates shared checkpoint and preserves warning`() {
        val state = state()
        val fixture = action(TypedAutomationActionStatus.RECONCILING)
        val execution = acquire(state, fixture.row)

        assertTrue(
            service.complete(
                execution,
                TypedRuntimeOutcome.AmbiguousHandoff(
                    "레이드 전투 결과 미확정",
                    "RAID_BATTLE_RECOVERY_STARTED",
                    successfulObservationCount = 5,
                ),
            ).applied,
        )

        assertEquals(TypedAutomationActionStatus.AMBIGUOUS, fixture.row.status)
        assertEquals(5, fixture.row.reconciliationObservationCount)
        assertEquals("레이드 전투 결과 미확정", state.warningText)
        Mockito.verify(outbox).enqueue(7, "RAID_BATTLE_RECOVERY_STARTED")
    }

    @Test
    fun `configuration outcome releases right and preserves warnings for bounded recheck`() {
        val state = state()
        val execution = acquire(state, null)

        val projection = service.complete(
            execution,
            TypedRuntimeOutcome.ConfigurationWait(listOf("missing primary", "later warning")),
        )

        assertTrue(projection.applied)
        assertEquals(now.plusSeconds(300), projection.nextAttemptAt)
        assertEquals(AutomationWaitReason.SCHEDULED, state.waitReason)
        assertTrue(requireNotNull(state.warningText).contains("missing primary"))
        assertNull(state.leaseToken)
    }

    @Test
    fun `safe retries remain running with capped delay`() {
        val state = state()
        val expected = listOf(10L, 30L, 60L, 300L)

        expected.forEachIndexed { index, seconds ->
            val execution = acquire(state, null)
            val projection = service.complete(
                execution,
                TypedRuntimeOutcome.SafeRetry("failure-${index + 1}"),
            )
            assertEquals(now.plusSeconds(seconds), projection.nextAttemptAt)
            now = requireNotNull(projection.nextAttemptAt)
        }

        assertEquals(TypedAutomationLifecycle.RUNNING, state.lifecycleStatus)
        assertEquals(AutomationStopReason.NETWORK.name, state.stopReason)
        assertEquals(4, state.retryAttempt)
    }

    @Test
    fun `stale submitting action is acquired as reconciliation checkpoint`() {
        val state = state().apply { leaseToken = "old"; leaseUntil = now.minusSeconds(1) }
        val fixture = action(TypedAutomationActionStatus.SUBMITTING).also {
            it.row.leaseToken = "old"
            it.row.submittedAt = now.minusSeconds(30)
        }
        stubActive(state, fixture.row)

        val execution = assertIs<TypedRuntimeAcquisition.Acquired>(service.acquire(7)).execution

        assertEquals(TypedRuntimeCheckpointPhase.RECONCILING, execution.checkpoint?.phase)
        assertEquals(TypedAutomationActionStatus.RECONCILING, fixture.row.status)
        assertEquals(now.minusSeconds(30), execution.checkpoint?.firstPendingAt)
        assertEquals(now.minusSeconds(30), fixture.row.reconciliationFirstPendingAt)
        assertEquals(0, execution.checkpoint?.successfulObservationCount)
    }

    @Test
    fun `retryable failure starts reconciliation time budget at submission time`() {
        val submittedAt = now
        val state = state()
        val fixture = action(TypedAutomationActionStatus.PREPARED)
        val execution = acquire(state, fixture.row)
        assertIs<TypedRuntimeSubmission.Started>(service.beginSubmission(execution))
        now = submittedAt.plusSeconds(119)

        assertTrue(
            service.complete(
                execution,
                TypedRuntimeOutcome.RetryableFailure(
                    AutomationStopReason.FATAL,
                    "submission response was lost",
                ),
            ).applied,
        )

        assertEquals(TypedAutomationActionStatus.RECONCILING, fixture.row.status)
        assertEquals(submittedAt, fixture.row.reconciliationFirstPendingAt)
        assertEquals(0, fixture.row.reconciliationObservationCount)
    }

    @Test
    fun `corrupt checkpoint is isolated before caller receives an execution right`() {
        val state = state()
        val fixture = action(TypedAutomationActionStatus.PREPARED)
        val corrupt = TypedAutomationActionRunEntity(
            fixture.row.id,
            account,
            fixture.entry,
            fixture.stored.executionIdentity,
            fixture.stored.payload.kind(),
            "{}",
            fixture.row.actionFingerprint,
            TypedAutomationActionStatus.PREPARED,
            leaseToken = "old",
            createdAt = now,
            updatedAt = now,
        )
        stubActive(state, corrupt)

        val acquisition = assertIs<TypedRuntimeAcquisition.RetryScheduled>(service.acquire(7))

        assertEquals(now.plusSeconds(10), acquisition.retryAt)
        assertEquals(TypedAutomationActionStatus.FAILED, corrupt.status)
        assertNull(state.leaseToken)
    }

    @Test
    fun `finishing current action completes requested pause without another wake`() {
        val state = state()
        val fixture = action(TypedAutomationActionStatus.PREPARED)
        val execution = acquire(state, fixture.row)
        assertIs<TypedRuntimeSubmission.Started>(service.beginSubmission(execution))
        state.lifecycleStatus = TypedAutomationLifecycle.DRAINING
        state.requestedLifecycle = TypedAutomationLifecycle.PAUSED

        assertTrue(
            service.complete(
                execution,
                TypedRuntimeOutcome.ActionSucceeded("TYPED_ACTION_COMPLETED"),
            ).applied,
        )

        assertEquals(TypedAutomationLifecycle.PAUSED, state.lifecycleStatus)
        assertNull(state.requestedLifecycle)
        Mockito.verifyNoInteractions(outbox)
    }

    @Test
    fun `successful action can preserve warning owned by a parked higher priority entry`() {
        val state = state().apply { warningText = "레이드 전투 프리셋 구성을 확인해 주세요." }
        val fixture = action(TypedAutomationActionStatus.PREPARED)
        val execution = acquire(state, fixture.row)
        Mockito.`when`(query.findRuntimeState(7)).thenReturn(state)
        assertIs<TypedRuntimeSubmission.Started>(service.beginSubmission(execution))

        service.complete(
            execution,
            TypedRuntimeOutcome.ActionSucceeded("TYPED_ACTION_COMPLETED", warnings = null),
        )

        assertEquals("레이드 전투 프리셋 구성을 확인해 주세요.", state.warningText)
    }

    @Test
    fun `stale execution right cannot project an outcome`() {
        val state = state()
        val execution = acquire(state, null)
        state.leaseToken = "new-owner"

        val projection = service.complete(execution, TypedRuntimeOutcome.Idle)

        assertEquals(false, projection.applied)
        assertEquals("new-owner", state.leaseToken)
    }

    @Test
    fun `scheduled wait is labeled and due acquisition clears stale metadata`() {
        val state = state()
        val execution = acquire(state, null)
        val scheduledAt = now.plusSeconds(300)

        assertTrue(
            service.complete(
                execution,
                TypedRuntimeOutcome.ScheduledWait(
                    scheduledAt,
                    AutomationWaitReason.SCHEDULED,
                ),
            ).applied,
        )
        assertEquals(scheduledAt, state.nextAttemptAt)

        now = scheduledAt
        assertIs<TypedRuntimeAcquisition.Acquired>(service.acquire(7))
        assertNull(state.nextAttemptAt)
        assertNull(state.waitReason)
    }

    private fun acquire(
        state: TypedAutomationRuntimeStateEntity,
        row: TypedAutomationActionRunEntity?,
    ): TypedRuntimeExecutionRight {
        stubActive(state, row)
        val execution = assertIs<TypedRuntimeAcquisition.Acquired>(service.acquire(7)).execution
        row?.let { Mockito.`when`(query.lockTypedAction(it.id)).thenReturn(it) }
        return execution
    }

    private fun stubActive(
        state: TypedAutomationRuntimeStateEntity,
        row: TypedAutomationActionRunEntity?,
    ) {
        Mockito.`when`(query.lockRuntimeState(7)).thenReturn(state)
        Mockito.`when`(query.findActiveTypedAction(7)).thenReturn(row)
        row?.entry?.let { entry -> Mockito.`when`(query.findEntry(7, entry.id)).thenReturn(entry) }
    }

    private fun action(status: TypedAutomationActionStatus, settingsRevision: Long? = 0, policyContext: StoredActionPolicyContext? = null): ActionFixture {
        val entry = AutomationEntryEntity(9, account, AutomationType.QUEST, 0, true, now, now)
        val stored = StoredTypedAutomationAction(
            entryId = entry.id,
            executionIdentity = "quest-claim",
            payload = StoredTypedActionPayload.QuestClaim("quest-1", "claim-1"),
            settingsRevision = settingsRevision,
            policyContext = policyContext,
        )
        val encoded = codec.encode(stored)
        return ActionFixture(
            entry,
            stored,
            TypedAutomationActionRunEntity(
                25,
                account,
                entry,
                stored.executionIdentity,
                stored.payload.kind(),
                encoded.json,
                encoded.fingerprint,
                status,
                leaseToken = "old",
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private fun state() = TypedAutomationRuntimeStateEntity(
        7,
        account,
        TypedAutomationLifecycle.RUNNING,
        createdAt = now,
        updatedAt = now,
    )

    private fun anyActionRow(): TypedAutomationActionRunEntity =
        Mockito.any(TypedAutomationActionRunEntity::class.java) ?: action(TypedAutomationActionStatus.PREPARED).row

    private data class ActionFixture(
        val entry: AutomationEntryEntity,
        val stored: StoredTypedAutomationAction,
        val row: TypedAutomationActionRunEntity,
    )
}
