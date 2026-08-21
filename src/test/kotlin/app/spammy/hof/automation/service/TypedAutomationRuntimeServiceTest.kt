package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.automation.entity.AutomationEntryEntity
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.AutomationWaitReason
import app.spammy.hof.automation.entity.TypedAutomationActionRunEntity
import app.spammy.hof.automation.entity.TypedAutomationActionStatus
import app.spammy.hof.automation.entity.TypedAutomationLifecycle
import app.spammy.hof.automation.entity.TypedAutomationRuntimeStateEntity
import app.spammy.hof.automation.outbox.AutomationOutboxService
import app.spammy.hof.automation.repository.TypedAutomationActionRunCommandRepository
import app.spammy.hof.automation.repository.TypedAutomationQueryRepository
import app.spammy.hof.common.time.TimeProvider
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
    )
    private val account = HofAccountEntity(7, "login", "encrypted", now)

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
    }

    @Test
    fun `retryable submitted failure preserves action for authoritative reconciliation`() {
        val state = state()
        val fixture = action(TypedAutomationActionStatus.PREPARED)
        val execution = acquire(state, fixture.row)
        assertIs<TypedRuntimeSubmission.Started>(service.beginSubmission(execution))

        val projection = service.complete(
            execution,
            TypedRuntimeOutcome.RetryableFailure(
                AutomationStopReason.CAPTCHA,
                "captcha",
            ),
        )

        assertEquals(now.plusSeconds(10), projection.nextAttemptAt)
        assertEquals(TypedAutomationActionStatus.RECONCILING, fixture.row.status)
        assertEquals(AutomationStopReason.CAPTCHA.name, state.stopReason)
        assertEquals(TypedAutomationLifecycle.RUNNING, state.lifecycleStatus)
    }

    @Test
    fun `reconciliation can defer resubmit and succeed through domain outcomes`() {
        val state = state()
        val fixture = action(TypedAutomationActionStatus.RECONCILING)
        var execution = acquire(state, fixture.row)
        val retryAt = now.plusSeconds(10)

        assertTrue(
            service.complete(
                execution,
                TypedRuntimeOutcome.ReconciliationDeferred(retryAt, "verify later"),
            ).applied,
        )
        assertEquals(TypedAutomationActionStatus.RECONCILING, fixture.row.status)
        assertEquals(retryAt, state.nextAttemptAt)

        now = retryAt
        execution = acquire(state, fixture.row)
        assertTrue(
            service.complete(
                execution,
                TypedRuntimeOutcome.ReconciliationResubmit("TYPED_RECONCILED_RESUBMIT"),
            ).applied,
        )
        assertEquals(TypedAutomationActionStatus.PREPARED, fixture.row.status)
        Mockito.verify(outbox).enqueue(7, "TYPED_RECONCILED_RESUBMIT")

        fixture.row.status = TypedAutomationActionStatus.RECONCILING
        execution = acquire(state, fixture.row)
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
                ),
            ).applied,
        )

        assertEquals(TypedAutomationActionStatus.AMBIGUOUS, fixture.row.status)
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
        val state = state().apply {
            lifecycleStatus = TypedAutomationLifecycle.DRAINING
            requestedLifecycle = TypedAutomationLifecycle.PAUSED
        }
        val fixture = action(TypedAutomationActionStatus.PREPARED)
        val execution = acquire(state, fixture.row)
        assertIs<TypedRuntimeSubmission.Started>(service.beginSubmission(execution))

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
    }

    private fun action(status: TypedAutomationActionStatus): ActionFixture {
        val entry = AutomationEntryEntity(9, account, AutomationType.QUEST, 0, true, now, now)
        val stored = StoredTypedAutomationAction(
            entryId = entry.id,
            executionIdentity = "quest-claim",
            payload = StoredTypedActionPayload.QuestClaim("quest-1", "claim-1"),
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
