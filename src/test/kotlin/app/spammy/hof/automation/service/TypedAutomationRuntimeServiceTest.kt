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
    private val service = TypedAutomationRuntimeService(
        query,
        Mockito.mock(TypedAutomationActionRunCommandRepository::class.java),
        StoredTypedAutomationActionCodec(jacksonObjectMapper()), TimeProvider { now },
        Mockito.mock(TypedAutomationLifecycleBridge::class.java),
    )
    private val account = HofAccountEntity(7, "login", "encrypted", now)

    @Test
    fun `safe failures schedule exact retries then stop network on fourth`() {
        val state = state().apply { leaseToken = "token"; leaseUntil = now.plusSeconds(300) }
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
    }

    @Test
    fun `stale submitting action becomes ambiguous and is never returned for resend`() {
        val state = state().apply { leaseToken = "old"; leaseUntil = now.minusSeconds(1) }
        val entry = AutomationEntryEntity(9, account, AutomationType.BATTLE_MAP, 0, true, now, now)
        val action = TypedAutomationActionRunEntity(
            11, account, entry, "execution", "BATTLE_MAP", 1, "{}", "a".repeat(64),
            TypedAutomationActionStatus.SUBMITTING, leaseToken = "old", createdAt = now, updatedAt = now,
        )
        Mockito.`when`(query.lockRuntimeState(7)).thenReturn(state)
        Mockito.`when`(query.findActiveTypedAction(7)).thenReturn(action)

        assertIs<TypedRuntimeClaim.AmbiguousRecovered>(service.claim(7))
        assertEquals(TypedAutomationActionStatus.AMBIGUOUS, action.status)
        assertEquals(TypedAutomationLifecycle.STOPPED, state.lifecycleStatus)
    }

    @Test
    fun `safe retry sanitizes errors and configuration warnings release lease for bounded recheck`() {
        val state = state().apply { leaseToken = "token"; leaseUntil = now.plusSeconds(300) }
        Mockito.`when`(query.lockRuntimeState(7)).thenReturn(state)

        service.scheduleSafeRetry(7, "token", "password=secret\nnetwork failed")

        assertEquals("password=[redacted] network failed", state.lastError)
        assertNull(state.leaseToken)
        state.leaseToken = "token"
        state.leaseUntil = now.plusSeconds(300)

        val next = service.deferForConfiguration(7, "token", listOf("missing primary", "later warning"))

        assertEquals(now.plusSeconds(300), next)
        assertEquals(next, state.nextAttemptAt)
        assertNull(state.leaseToken)
        assertTrue(requireNotNull(state.warningText).contains("missing primary"))
    }

    private fun state() = TypedAutomationRuntimeStateEntity(7, account, TypedAutomationLifecycle.RUNNING, createdAt = now, updatedAt = now)
}
