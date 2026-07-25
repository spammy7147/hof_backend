package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.automation.entity.*
import app.spammy.hof.automation.outbox.AutomationOutboxQueryRepository
import app.spammy.hof.automation.outbox.AutomationOutboxService
import app.spammy.hof.automation.repository.*
import app.spammy.hof.common.persistence.QueryDslConfig
import app.spammy.hof.common.time.TimeProvider
import java.time.Instant
import java.time.LocalDate
import jakarta.persistence.EntityManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.test.context.ActiveProfiles
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper

@DataJpaTest
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
    QueryDslConfig::class,
    AccountQueryRepository::class,
    TypedAutomationQueryRepository::class,
    AdventureDailyPreflightQueryRepository::class,
    AutomationOutboxQueryRepository::class,
    AutomationOutboxService::class,
    TypedAutomationLifecycleBridge::class,
    UnifiedAutomationTypedLifecycleBridgeIntegrationTest.Config::class,
)
class UnifiedAutomationTypedLifecycleBridgeIntegrationTest {
    @Autowired private lateinit var accounts: HofAccountRepository
    @Autowired private lateinit var entries: AutomationEntryCommandRepository
    @Autowired private lateinit var actions: TypedAutomationActionRunCommandRepository
    @Autowired private lateinit var preflightStates: AdventureDailyPreflightStateCommandRepository
    @Autowired private lateinit var typedQuery: TypedAutomationQueryRepository
    @Autowired private lateinit var preflightQuery: AdventureDailyPreflightQueryRepository
    @Autowired private lateinit var outboxQuery: AutomationOutboxQueryRepository
    @Autowired private lateinit var bridge: TypedAutomationLifecycleBridge
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var entityManager: EntityManager

    @Test
    fun `resume atomically updates typed and preflight state and persists durable wake`() {
        val accountId = seed("commit")
        TransactionTemplate(transactionManager).executeWithoutResult {
            requireNotNull(typedQuery.lockRuntimeState(accountId)).apply {
                nextAttemptAt = NOW.plusSeconds(300)
                waitReason = AutomationWaitReason.SCHEDULED
            }
        }

        TransactionTemplate(transactionManager).executeWithoutResult {
            bridge.resume(accountId, "USER_RESUME")
        }

        val typed = requireNotNull(typedQuery.findRuntimeState(accountId))
        assertEquals(TypedAutomationLifecycle.RUNNING, typed.lifecycleStatus)
        assertNull(typed.stopReason)
        assertNull(typed.stopActionId)
        assertNull(typed.nextAttemptAt)
        assertNull(typed.waitReason)
        assertNull(typed.leaseToken)
        val preflight = requireNotNull(preflightQuery.findState(accountId))
        assertEquals(0, preflight.failedAttempts)
        assertNull(preflight.stopReason)
        assertNull(preflight.inFlightToken)
        val events = outboxQuery.findUnpublished(NOW.plusSeconds(1)).filter { it.account.id == accountId }
        assertEquals(1, events.size)
        assertTrue(events.single().payload.contains("\"reason\":\"USER_RESUME\""))
    }

    @Test
    fun `rollback changes no lifecycle state and emits no wake without any in-memory callback`() {
        val accountId = seed("rollback")

        assertFailsWith<ForcedRollback> {
            TransactionTemplate(transactionManager).executeWithoutResult {
                bridge.resume(accountId, "USER_RESUME")
                throw ForcedRollback()
            }
        }

        val typed = requireNotNull(typedQuery.findRuntimeState(accountId))
        assertEquals(TypedAutomationLifecycle.STOPPED, typed.lifecycleStatus)
        assertEquals(AutomationStopReason.NETWORK.name, typed.stopReason)
        assertTrue(typed.stopActionId != null)
        val preflight = requireNotNull(preflightQuery.findState(accountId))
        assertEquals(3, preflight.failedAttempts)
        assertEquals("NETWORK", preflight.stopReason)
        assertEquals("preflight-token", preflight.inFlightToken)
        assertEquals(emptyList(), outboxQuery.findUnpublished(NOW.plusSeconds(1)).filter { it.account.id == accountId })
    }

    @Test
    fun `start and pause cannot clear a stopped typed runtime`() {
        val accountId = seed("stopped-invariant")

        val started = TransactionTemplate(transactionManager).execute { bridge.start(accountId, "USER_START") }
        TransactionTemplate(transactionManager).executeWithoutResult { bridge.pause(accountId, "USER_PAUSE") }

        assertFalse(requireNotNull(started))
        val state = requireNotNull(typedQuery.findRuntimeState(accountId))
        assertEquals(TypedAutomationLifecycle.STOPPED, state.lifecycleStatus)
        assertEquals(AutomationStopReason.NETWORK.name, state.stopReason)
        assertTrue(state.stopActionId != null)
        val events = outboxQuery.findUnpublished(NOW.plusSeconds(1)).filter { it.account.id == accountId }
        assertEquals(1, events.size)
    }

    @Test
    fun `manual stop clears a previous action stop context`() {
        val accountId = seed("manual-stop")
        TransactionTemplate(transactionManager).executeWithoutResult {
            requireNotNull(typedQuery.lockRuntimeState(accountId)).apply {
                nextAttemptAt = NOW.plusSeconds(300)
                waitReason = AutomationWaitReason.HOF_CONNECTION
            }
        }

        TransactionTemplate(transactionManager).executeWithoutResult {
            bridge.stop(accountId, AutomationStopReason.MANUAL_STOP, "USER_STOP")
        }

        val state = requireNotNull(typedQuery.findRuntimeState(accountId))
        assertEquals(TypedAutomationLifecycle.STOPPED, state.lifecycleStatus)
        assertEquals(AutomationStopReason.MANUAL_STOP.name, state.stopReason)
        assertNull(state.stopActionId)
        assertNull(state.nextAttemptAt)
        assertNull(state.waitReason)
    }

    @Test
    fun `repeating the same terminal stop is idempotent and emits no wake`() {
        val accountId = seed("idempotent-stop")

        TransactionTemplate(transactionManager).executeWithoutResult {
            bridge.stop(accountId, AutomationStopReason.NETWORK, "PREFLIGHT_STOPPED")
        }

        val state = requireNotNull(typedQuery.findRuntimeState(accountId))
        assertEquals(TypedAutomationLifecycle.STOPPED, state.lifecycleStatus)
        assertEquals(AutomationStopReason.NETWORK.name, state.stopReason)
        assertTrue(state.stopActionId != null)
        assertEquals(
            emptyList(),
            outboxQuery.findUnpublished(NOW.plusSeconds(1)).filter { it.account.id == accountId },
        )
    }

    @Test
    fun `captcha answer resumes only captcha stopped runtime and clears preflight atomically`() {
        val accountId = seed("captcha-resume")
        setStopReason(accountId, AutomationStopReason.CAPTCHA)

        val resumed = TransactionTemplate(transactionManager).execute {
            bridge.resumeIfStoppedForCaptcha(accountId, "CAPTCHA_ANSWERED")
        }

        assertTrue(requireNotNull(resumed))
        val state = requireNotNull(typedQuery.findRuntimeState(accountId))
        assertEquals(TypedAutomationLifecycle.RUNNING, state.lifecycleStatus)
        assertNull(state.stopReason)
        assertNull(state.stopActionId)
        val preflight = requireNotNull(preflightQuery.findState(accountId))
        assertEquals(0, preflight.failedAttempts)
        assertNull(preflight.stopReason)
        assertNull(preflight.inFlightToken)
        val events = outboxQuery.findUnpublished(NOW.plusSeconds(1)).filter { it.account.id == accountId }
        assertEquals(1, events.size)
        assertTrue(events.single().payload.contains("\"reason\":\"CAPTCHA_ANSWERED\""))
    }

    @Test
    fun `captcha answer never resumes network manual or authentication stops`() {
        listOf(
            AutomationStopReason.NETWORK,
            AutomationStopReason.MANUAL_STOP,
            AutomationStopReason.AUTHENTICATION,
        ).forEach { reason ->
            val accountId = seed("captcha-guard-${reason.name.lowercase()}")
            setStopReason(accountId, reason)

            val resumed = TransactionTemplate(transactionManager).execute {
                bridge.resumeIfStoppedForCaptcha(accountId, "CAPTCHA_ANSWERED")
            }

            assertFalse(requireNotNull(resumed))
            val state = requireNotNull(typedQuery.findRuntimeState(accountId))
            assertEquals(TypedAutomationLifecycle.STOPPED, state.lifecycleStatus)
            assertEquals(reason.name, state.stopReason)
            assertEquals(
                emptyList(),
                outboxQuery.findUnpublished(NOW.plusSeconds(1)).filter { it.account.id == accountId },
            )
        }
    }

    private fun setStopReason(accountId: Long, reason: AutomationStopReason) {
        TransactionTemplate(transactionManager).executeWithoutResult {
            entityManager.createNativeQuery(
                "update typed_automation_runtime_states set stop_reason = ?1 where account_id = ?2",
            ).setParameter(1, reason.name).setParameter(2, accountId).executeUpdate()
        }
    }

    private fun seed(suffix: String): Long = TransactionTemplate(transactionManager).execute {
        val account = accounts.save(HofAccountEntity(loginId = "lifecycle-$suffix", encryptedPassword = "encrypted", createdAt = NOW))
        val entry = entries.save(
            AutomationEntryEntity(
                account = account,
                type = AutomationType.BATTLE_MAP,
                priority = 0,
                enabled = true,
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )
        val action = actions.save(
            TypedAutomationActionRunEntity(
                account = account,
                entry = entry,
                executionIdentity = "stopped-action-$suffix",
                actionKind = "BATTLE_MAP",
                schemaVersion = StoredTypedAutomationActionCodec.SCHEMA_VERSION,
                payloadJson = "{}",
                actionFingerprint = "a".repeat(64),
                status = TypedAutomationActionStatus.FAILED,
                leaseToken = "old-token",
                createdAt = NOW,
                finishedAt = NOW,
                updatedAt = NOW,
            ),
        )
        entityManager.flush()
        entityManager.createNativeQuery(
            "insert into typed_automation_runtime_states (account_id,lifecycle_status,stop_reason,stop_action_id,retry_attempt,created_at,updated_at,version) values (?1,'STOPPED','NETWORK',?2,0,?3,?3,0)",
        ).setParameter(1, account.id).setParameter(2, action.id).setParameter(3, NOW).executeUpdate()
        preflightStates.save(AdventureDailyPreflightStateEntity(account = account, refreshDate = LocalDate.parse("2026-07-16"), failedAttempts = 3, nextAttemptAt = NOW.plusSeconds(30), stopReason = "NETWORK", inFlightToken = "preflight-token", inFlightUntil = NOW.plusSeconds(300), updatedAt = NOW))
        account.id
    }

    private class ForcedRollback : RuntimeException()

    @TestConfiguration(proxyBeanMethods = false)
    class Config {
        @Bean fun objectMapper(): ObjectMapper = jacksonObjectMapper()
        @Bean fun timeProvider(): TimeProvider = TimeProvider { NOW }
    }

    private companion object { val NOW: Instant = Instant.parse("2026-07-16T00:00:00Z") }
}
