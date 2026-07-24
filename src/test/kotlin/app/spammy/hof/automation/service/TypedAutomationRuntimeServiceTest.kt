package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.automation.entity.*
import app.spammy.hof.automation.repository.*
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
    private val actionRepository = Mockito.mock(TypedAutomationActionRunCommandRepository::class.java)
    private val lifecycleBridge = Mockito.mock(TypedAutomationLifecycleBridge::class.java)
    private val outbox = Mockito.mock(app.spammy.hof.automation.outbox.AutomationOutboxService::class.java)
    private val service = TypedAutomationRuntimeService(
        query,
        actionRepository,
        StoredTypedAutomationActionCodec(jacksonObjectMapper()), TimeProvider { now },
        lifecycleBridge,
        outbox,
    )
    private val account = HofAccountEntity(7, "login", "encrypted", now)

    @Test
    fun `safe failures schedule exact retries then stop network on fourth`() {
        val state = state().apply {
            leaseToken = "token"
            leaseUntil = now.plusSeconds(300)
            stopActionId = 99L
        }
        Mockito.`when`(query.lockRuntimeState(7)).thenReturn(state)

        assertEquals(now.plusSeconds(10), service.scheduleSafeRetry(7, "token", "one"))
        state.leaseToken = "token"
        assertEquals(now.plusSeconds(30), service.scheduleSafeRetry(7, "token", "two"))
        state.leaseToken = "token"
        assertEquals(now.plusSeconds(60), service.scheduleSafeRetry(7, "token", "three"))
        state.leaseToken = "token"
        assertEquals(null, service.scheduleSafeRetry(7, "token", "four"))
        assertEquals(TypedAutomationLifecycle.STOPPED, state.lifecycleStatus)
        assertEquals(AutomationStopReason.NETWORK.name, state.stopReason)
        assertNull(state.stopActionId)
    }

    @Test
    fun `stale submitting action is claimed for authoritative reconciliation`() {
        val state = state().apply { leaseToken = "old"; leaseUntil = now.minusSeconds(1) }
        val entry = AutomationEntryEntity(9, account, AutomationType.BATTLE_MAP, 0, true, now, now)
        val action = TypedAutomationActionRunEntity(
            11, account, entry, "execution", "BATTLE_MAP", 1, "{}", "a".repeat(64),
            TypedAutomationActionStatus.SUBMITTING, leaseToken = "old", createdAt = now, updatedAt = now,
        )
        Mockito.`when`(query.lockRuntimeState(7)).thenReturn(state)
        Mockito.`when`(query.findActiveTypedAction(7)).thenReturn(action)

        val claim = assertIs<TypedRuntimeClaim.Acquired>(service.claim(7))
        assertEquals(action.id, claim.preparedAction?.id)
        assertEquals(TypedAutomationActionStatus.RECONCILING, action.status)
        assertEquals(TypedAutomationLifecycle.RUNNING, state.lifecycleStatus)
        assertNull(state.stopActionId)
    }

    @Test
    fun `action stop binds the exact owned action to the stopped runtime`() {
        val state = state().apply { leaseToken = "token"; leaseUntil = now.plusSeconds(300) }
        val entry = AutomationEntryEntity(9, account, AutomationType.BATTLE_MAP, 0, true, now, now)
        val action = TypedAutomationActionRunEntity(
            11, account, entry, "execution", "BATTLE_MAP", 1, "{}", "a".repeat(64),
            TypedAutomationActionStatus.SUBMITTING, leaseToken = "token", createdAt = now, updatedAt = now,
        )
        Mockito.`when`(query.lockRuntimeState(7)).thenReturn(state)
        Mockito.`when`(query.lockTypedAction(action.id)).thenReturn(action)

        assertTrue(service.stop(7, "token", action.id, AutomationStopReason.NETWORK, "connection reset"))

        assertEquals(action.id, state.stopActionId)
        assertEquals(TypedAutomationActionStatus.RECONCILING, action.status)
    }

    @Test
    fun `503 deferral returns submitted action to prepared and keeps runtime running`() {
        val retryAt = now.plusSeconds(30)
        val state = state().apply {
            leaseToken = "token"
            leaseUntil = now.plusSeconds(300)
        }
        val entry = AutomationEntryEntity(9, account, AutomationType.BATTLE_MAP, 0, true, now, now)
        val action = TypedAutomationActionRunEntity(
            15, account, entry, "retry-execution", "BATTLE_MAP", 1, "{}", "e".repeat(64),
            TypedAutomationActionStatus.SUBMITTING, leaseToken = "token", createdAt = now,
            submittedAt = now, updatedAt = now,
        )
        Mockito.`when`(query.lockRuntimeState(7)).thenReturn(state)
        Mockito.`when`(query.lockTypedAction(action.id)).thenReturn(action)

        assertTrue(service.deferSubmittedAction(7, "token", action.id, retryAt, "password=secret\n503"))

        assertEquals(TypedAutomationLifecycle.RUNNING, state.lifecycleStatus)
        assertEquals(retryAt, state.nextAttemptAt)
        assertEquals(AutomationWaitReason.HOF_CONNECTION, state.waitReason)
        assertNull(state.leaseToken)
        assertEquals("password=[redacted] 503", state.lastError)
        assertEquals(TypedAutomationActionStatus.PREPARED, action.status)
        assertEquals(1, action.retryAttempt)
        assertEquals(retryAt, action.nextAttemptAt)
        assertNull(action.submittedAt)
        assertNull(action.finishedAt)
        assertEquals("password=[redacted] 503", action.lastError)
    }

    @Test
    fun `ambiguous failure becomes reconciling and enqueues verification`() {
        val state = state().apply { leaseToken = "token"; leaseUntil = now.plusSeconds(300) }
        val entry = AutomationEntryEntity(9, account, AutomationType.QUEST, 0, true, now, now)
        val action = TypedAutomationActionRunEntity(
            16, account, entry, "quest-accept", "QUEST_ACCEPT", 1, "{}", "f".repeat(64),
            TypedAutomationActionStatus.SUBMITTING, leaseToken = "token", createdAt = now, updatedAt = now,
        )
        Mockito.`when`(query.lockRuntimeState(7)).thenReturn(state)
        Mockito.`when`(query.lockTypedAction(action.id)).thenReturn(action)

        assertTrue(service.markReconcilingAndEnqueueWake(7, "token", action.id, "unknown outcome"))

        assertEquals(TypedAutomationActionStatus.RECONCILING, action.status)
        assertNull(state.leaseToken)
        Mockito.verify(outbox).enqueue(7, "TYPED_AMBIGUOUS_RECONCILE")
    }

    @Test
    fun `reconciliation can resubmit defer or succeed the same stored action`() {
        val retryAt = now.plusSeconds(10)
        val state = state().apply { leaseToken = "token"; leaseUntil = now.plusSeconds(300) }
        val entry = AutomationEntryEntity(9, account, AutomationType.QUEST, 0, true, now, now)
        val action = TypedAutomationActionRunEntity(
            17, account, entry, "quest-accept", "QUEST_ACCEPT", 1, "{}", "a".repeat(64),
            TypedAutomationActionStatus.RECONCILING, leaseToken = "token", createdAt = now, updatedAt = now,
        )
        Mockito.`when`(query.lockRuntimeState(7)).thenReturn(state)
        Mockito.`when`(query.lockTypedAction(action.id)).thenReturn(action)

        assertTrue(service.deferReconciliation(7, "token", action.id, retryAt, "verify later"))
        assertEquals(TypedAutomationActionStatus.RECONCILING, action.status)
        assertEquals(retryAt, state.nextAttemptAt)

        state.leaseToken = "token"
        action.leaseToken = "token"
        assertTrue(service.retryReconciledSubmission(7, "token", action.id, "TYPED_RECONCILED_RESUBMIT"))
        assertEquals(TypedAutomationActionStatus.PREPARED, action.status)
        Mockito.verify(outbox).enqueue(7, "TYPED_RECONCILED_RESUBMIT")

        action.status = TypedAutomationActionStatus.RECONCILING
        state.leaseToken = "token"
        action.leaseToken = "token"
        assertTrue(service.succeedReconciliation(7, "token", action.id, "TYPED_ACTION_COMPLETED"))
        assertEquals(TypedAutomationActionStatus.SUCCEEDED, action.status)
        Mockito.verify(outbox).enqueue(7, "TYPED_ACTION_COMPLETED")
    }

    @Test
    fun `captcha preserves submitted action for reconciliation after answer`() {
        val state = state().apply { leaseToken = "token"; leaseUntil = now.plusSeconds(300) }
        val entry = AutomationEntryEntity(9, account, AutomationType.BATTLE_MAP, 0, true, now, now)
        val action = TypedAutomationActionRunEntity(
            14, account, entry, "captcha-execution", "BATTLE_MAP", 1, "{}", "d".repeat(64),
            TypedAutomationActionStatus.SUBMITTING, leaseToken = "token", createdAt = now, updatedAt = now,
        )
        Mockito.`when`(query.lockRuntimeState(7)).thenReturn(state)
        Mockito.`when`(query.lockTypedAction(action.id)).thenReturn(action)

        assertTrue(service.stop(7, "token", action.id, AutomationStopReason.CAPTCHA, "captcha"))

        assertEquals(action.id, state.stopActionId)
        assertEquals(TypedAutomationActionStatus.RECONCILING, action.status)
        assertEquals(AutomationStopReason.CAPTCHA.name, state.stopReason)
    }

    @Test
    fun `actionless stop clears an earlier stopped action context`() {
        val state = state().apply {
            leaseToken = "token"
            leaseUntil = now.plusSeconds(300)
            stopActionId = 11L
        }
        Mockito.`when`(query.lockRuntimeState(7)).thenReturn(state)

        assertTrue(service.stop(7, "token", null, AutomationStopReason.FATAL, "snapshot failed"))

        assertNull(state.stopActionId)
    }

    @Test
    fun `stop does not bind or mutate an action owned by another account`() {
        val state = state().apply { leaseToken = "token"; leaseUntil = now.plusSeconds(300) }
        val otherAccount = HofAccountEntity(8, "other", "encrypted", now)
        val otherEntry = AutomationEntryEntity(10, otherAccount, AutomationType.BATTLE_MAP, 0, true, now, now)
        val otherAction = TypedAutomationActionRunEntity(
            12, otherAccount, otherEntry, "other-execution", "BATTLE_MAP", 1, "{}", "b".repeat(64),
            TypedAutomationActionStatus.SUBMITTING, leaseToken = "token", createdAt = now, updatedAt = now,
        )
        Mockito.`when`(query.lockRuntimeState(7)).thenReturn(state)
        Mockito.`when`(query.lockTypedAction(otherAction.id)).thenReturn(otherAction)

        assertTrue(service.stop(7, "token", otherAction.id, AutomationStopReason.NETWORK, "connection reset"))

        assertNull(state.stopActionId)
        assertEquals(TypedAutomationActionStatus.SUBMITTING, otherAction.status)
    }

    @Test
    fun `stop does not bind or mutate an already completed action`() {
        val state = state().apply { leaseToken = "token"; leaseUntil = now.plusSeconds(300) }
        val entry = AutomationEntryEntity(9, account, AutomationType.BATTLE_MAP, 0, true, now, now)
        val completed = TypedAutomationActionRunEntity(
            13, account, entry, "completed-execution", "BATTLE_MAP", 1, "{}", "c".repeat(64),
            TypedAutomationActionStatus.SUCCEEDED, leaseToken = "token", createdAt = now, updatedAt = now,
        )
        Mockito.`when`(query.lockRuntimeState(7)).thenReturn(state)
        Mockito.`when`(query.lockTypedAction(completed.id)).thenReturn(completed)

        assertTrue(service.stop(7, "token", completed.id, AutomationStopReason.NETWORK, "late failure"))

        assertNull(state.stopActionId)
        assertEquals(TypedAutomationActionStatus.SUCCEEDED, completed.status)
    }

    @Test
    fun `safe retry sanitizes errors and configuration warnings release lease for bounded recheck`() {
        val state = state().apply { leaseToken = "token"; leaseUntil = now.plusSeconds(300) }
        Mockito.`when`(query.lockRuntimeState(7)).thenReturn(state)

        service.scheduleSafeRetry(7, "token", "password=secret\nnetwork failed")

        assertEquals("password=[redacted] network failed", state.lastError)
        assertEquals(AutomationWaitReason.HOF_CONNECTION, state.waitReason)
        assertNull(state.leaseToken)
        state.leaseToken = "token"
        state.leaseUntil = now.plusSeconds(300)

        val next = service.deferForConfiguration(7, "token", listOf("missing primary", "later warning"))

        assertEquals(now.plusSeconds(300), next)
        assertEquals(next, state.nextAttemptAt)
        assertEquals(AutomationWaitReason.SCHEDULED, state.waitReason)
        assertNull(state.leaseToken)
        assertTrue(requireNotNull(state.warningText).contains("missing primary"))
    }

    @Test
    fun `normal schedules are labeled and due claim clears stale wait metadata`() {
        val state = state().apply {
            leaseToken = "token"
            leaseUntil = now.plusSeconds(300)
        }
        Mockito.`when`(query.lockRuntimeState(7)).thenReturn(state)

        val scheduledAt = now.plusSeconds(300)
        assertTrue(service.releaseWithDiagnostics(7, "token", scheduledAt, emptyList()))
        assertEquals(scheduledAt, state.nextAttemptAt)
        assertEquals(AutomationWaitReason.SCHEDULED, state.waitReason)

        now = scheduledAt
        Mockito.`when`(query.findActiveTypedAction(7)).thenReturn(null)
        assertIs<TypedRuntimeClaim.Acquired>(service.claim(7))
        assertNull(state.nextAttemptAt)
        assertNull(state.waitReason)
    }

    private fun state() = TypedAutomationRuntimeStateEntity(7, account, TypedAutomationLifecycle.RUNNING, createdAt = now, updatedAt = now)
}
