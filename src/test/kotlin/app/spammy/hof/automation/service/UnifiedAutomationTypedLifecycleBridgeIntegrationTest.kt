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
import app.spammy.hof.auth.service.AccountExecutionAuthorizationReader
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
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.junit.jupiter.api.BeforeEach
import org.mockito.Mockito
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
    AutomationWorkSessionQueryRepository::class,
    AutomationOutboxQueryRepository::class,
    AutomationOutboxService::class,
    TypedAutomationLifecycleBridge::class,
    UnifiedAutomationTypedLifecycleBridgeIntegrationTest.Config::class,
)
class UnifiedAutomationTypedLifecycleBridgeIntegrationTest {
    @Autowired private lateinit var accounts: HofAccountRepository
    @Autowired private lateinit var entries: AutomationEntryCommandRepository
    @Autowired private lateinit var actions: TypedAutomationActionRunCommandRepository
    @Autowired private lateinit var workSessionCommands: AutomationWorkSessionCommandRepository
    @Autowired private lateinit var preflightStates: AdventureDailyPreflightStateCommandRepository
    @Autowired private lateinit var typedQuery: TypedAutomationQueryRepository
    @Autowired private lateinit var preflightQuery: AdventureDailyPreflightQueryRepository
    @Autowired private lateinit var outboxQuery: AutomationOutboxQueryRepository
    @Autowired private lateinit var outbox: AutomationOutboxService
    @Autowired private lateinit var workSessionQuery: AutomationWorkSessionQueryRepository
    @Autowired private lateinit var bridge: TypedAutomationLifecycleBridge
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var entityManager: EntityManager
    @MockitoBean private lateinit var executionAuthorization: AccountExecutionAuthorizationReader

    @BeforeEach
    fun allowExecutionByDefault() {
        Mockito.`when`(executionAuthorization.isExecutionAllowed(Mockito.anyLong())).thenReturn(true)
    }

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
    fun `레이드 복구 재개는 저장 payload 대신 최신 상태를 즉시 읽도록 확인 시각을 당긴다`() {
        val accountId = seed("raid-recovery-resume")
        TransactionTemplate(transactionManager).executeWithoutResult {
            entityManager.createNativeQuery(
                "update automation_entries set automation_type = 'RAID', singleton_type_marker = 'RAID' where account_id = ?1",
            )
                .setParameter(1, accountId).executeUpdate()
            entityManager.createNativeQuery(
                "insert into raid_automation_cycles (account_id,automation_entry_id,raid_id,raid_name,status,open_marker,started_at,updated_at,version," +
                    "battle_recovery_chain_id,battle_recovery_original_execution_identity,battle_recovery_latest_execution_identity," +
                    "battle_recovery_first_ambiguous_at,battle_recovery_last_submitted_at,battle_recovery_retransmission_count," +
                    "battle_recovery_next_check_at,battle_recovery_category_id,battle_recovery_map_code," +
                    "battle_recovery_submitted_from_runnable,battle_recovery_last_observation) " +
                    "select account_id,id,'raid-1','레이드','IN_BATTLE',1,?2,?2,0,'chain-1','execution-1','execution-1'," +
                    "?2,?2,0,?3,'raid','map-1',true,'RESULT_UNOBSERVED' from automation_entries where account_id = ?1",
            ).setParameter(1, accountId)
                .setParameter(2, NOW)
                .setParameter(3, NOW.plusSeconds(300))
                .executeUpdate()
        }

        TransactionTemplate(transactionManager).executeWithoutResult {
            bridge.resume(accountId, "USER_RESUME")
        }

        assertEquals(NOW, requireNotNull(typedQuery.findOpenRaidCycle(accountId)).battleRecoveryNextCheckAt)
        assertEquals("chain-1", typedQuery.findOpenRaidCycle(accountId)?.battleRecoveryChainId)
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
    fun `start resumes a stopped typed runtime and pause then stops new work`() {
        val accountId = seed("stopped-invariant")

        val started = TransactionTemplate(transactionManager).execute { bridge.start(accountId, "USER_START") }
        TransactionTemplate(transactionManager).executeWithoutResult { bridge.pause(accountId, "USER_PAUSE") }

        assertTrue(requireNotNull(started))
        val state = requireNotNull(typedQuery.findRuntimeState(accountId))
        assertEquals(TypedAutomationLifecycle.PAUSED, state.lifecycleStatus)
        assertNull(state.stopReason)
        assertNull(state.stopActionId)
        val events = outboxQuery.findUnpublished(NOW.plusSeconds(1)).filter { it.account.id == accountId }
        assertEquals(1, events.size)
    }

    @Test
    fun `authentication suspension pauses running automation and login resumes only that state`() {
        val accountId = seed("auth-suspend-running")
        TransactionTemplate(transactionManager).executeWithoutResult {
            bridge.start(accountId, "USER_START")
        }

        TransactionTemplate(transactionManager).executeWithoutResult {
            bridge.suspendForAuthentication(accountId, "LAST_APP_SESSION_ENDED")
        }

        requireNotNull(typedQuery.findRuntimeState(accountId)).also { suspended ->
            assertEquals(TypedAutomationLifecycle.PAUSED, suspended.lifecycleStatus)
            assertTrue(suspended.authSuspended)
            assertTrue(suspended.resumeAfterAuth)
        }

        TransactionTemplate(transactionManager).executeWithoutResult {
            bridge.resumeAfterAuthentication(accountId, "APP_SESSION_ACTIVATED")
        }

        requireNotNull(typedQuery.findRuntimeState(accountId)).also { resumed ->
            assertEquals(TypedAutomationLifecycle.RUNNING, resumed.lifecycleStatus)
            assertFalse(resumed.authSuspended)
            assertFalse(resumed.resumeAfterAuth)
        }
    }

    @Test
    fun `start cannot erase authentication suspension before a new login session`() {
        val accountId = seed("auth-suspend-start-guard")
        TransactionTemplate(transactionManager).executeWithoutResult {
            bridge.start(accountId, "USER_START")
            bridge.suspendForAuthentication(accountId, "LAST_APP_SESSION_ENDED")
        }

        val started = TransactionTemplate(transactionManager).execute {
            bridge.start(accountId, "STALE_ACCESS_TOKEN_START")
        }

        assertFalse(started)
        requireNotNull(typedQuery.findRuntimeState(accountId)).also { suspended ->
            assertEquals(TypedAutomationLifecycle.PAUSED, suspended.lifecycleStatus)
            assertTrue(suspended.authSuspended)
            assertTrue(suspended.resumeAfterAuth)
        }
    }

    @Test
    fun `stale access token cannot create a new running runtime after the last refresh family ends`() {
        val accountId = seed("auth-no-runtime-start-guard")
        TransactionTemplate(transactionManager).executeWithoutResult {
            entityManager.createNativeQuery("delete from typed_automation_runtime_states where account_id = ?1")
                .setParameter(1, accountId)
                .executeUpdate()
        }
        Mockito.`when`(executionAuthorization.isExecutionAllowed(accountId)).thenReturn(false)

        val started = TransactionTemplate(transactionManager).execute {
            bridge.start(accountId, "STALE_ACCESS_TOKEN_START")
        }

        assertFalse(requireNotNull(started))
        assertNull(typedQuery.findRuntimeState(accountId))
    }

    @Test
    fun `manual stop during authentication suspension cancels automatic login resume`() {
        val accountId = seed("auth-suspend-manual-stop")
        TransactionTemplate(transactionManager).executeWithoutResult {
            bridge.start(accountId, "USER_START")
            bridge.suspendForAuthentication(accountId, "LAST_APP_SESSION_ENDED")
            bridge.stop(accountId, AutomationStopReason.MANUAL_STOP, "USER_STOP")
            bridge.resumeAfterAuthentication(accountId, "APP_SESSION_ACTIVATED")
        }

        requireNotNull(typedQuery.findRuntimeState(accountId)).also { stopped ->
            assertEquals(TypedAutomationLifecycle.STOPPED, stopped.lifecycleStatus)
            assertFalse(stopped.authSuspended)
            assertFalse(stopped.resumeAfterAuth)
        }
    }

    @Test
    fun `authentication suspension preserves a user paused automation after login`() {
        val accountId = seed("auth-suspend-paused")
        TransactionTemplate(transactionManager).executeWithoutResult {
            bridge.start(accountId, "USER_START")
            bridge.pause(accountId, "USER_PAUSE")
            bridge.suspendForAuthentication(accountId, "LAST_APP_SESSION_ENDED")
        }

        TransactionTemplate(transactionManager).executeWithoutResult {
            bridge.resumeAfterAuthentication(accountId, "APP_SESSION_ACTIVATED")
        }

        requireNotNull(typedQuery.findRuntimeState(accountId)).also { resumed ->
            assertEquals(TypedAutomationLifecycle.PAUSED, resumed.lifecycleStatus)
            assertFalse(resumed.authSuspended)
            assertFalse(resumed.resumeAfterAuth)
        }
    }

    @Test
    fun `manual stop clears a previous action stop context`() {
        val accountId = seed("manual-stop")
        TransactionTemplate(transactionManager).executeWithoutResult {
            val account = requireNotNull(entityManager.find(HofAccountEntity::class.java, accountId))
            val entry = typedQuery.findEntries(accountId).single()
            workSessionCommands.save(
                AutomationWorkSessionEntity(
                    account = account,
                    entry = entry,
                    workType = AutomationWorkType.BATTLE_MAP,
                    targetKey = "queued-map",
                    status = AutomationWorkStatus.WAITING_COOLDOWN,
                    configVersion = "queued-config",
                    nextCheckAt = NOW.plusSeconds(60),
                    createdAt = NOW,
                    updatedAt = NOW,
                ),
            )
            outbox.enqueue(accountId, "QUEUED_BEFORE_STOP")
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
        assertEquals(emptyList(), workSessionQuery.findDue(NOW.plusSeconds(120), 10).filter { it.accountId == accountId })
        assertEquals(emptyList(), outboxQuery.findUnpublished(NOW.plusSeconds(1)).filter { it.account.id == accountId })
    }

    @Test
    fun `manual stop discards a reconciling action so resume starts from a fresh decision`() {
        val accountId = seed("manual-stop-fresh-decision")
        val activeActionId = TransactionTemplate(transactionManager).execute {
            val account = requireNotNull(entityManager.find(HofAccountEntity::class.java, accountId))
            val entry = typedQuery.findEntries(accountId).single()
            val action = actions.save(
                TypedAutomationActionRunEntity(
                    account = account,
                    entry = entry,
                    executionIdentity = "stuck-raid-registration",
                    actionKind = "RAID_TOWN",
                    payloadJson = "{}",
                    actionFingerprint = "b".repeat(64),
                    status = TypedAutomationActionStatus.RECONCILING,
                    leaseToken = "expired-lease",
                    createdAt = NOW,
                    submittedAt = NOW,
                    updatedAt = NOW,
                ),
            )
            requireNotNull(typedQuery.lockRuntimeState(accountId)).apply {
                lifecycleStatus = TypedAutomationLifecycle.RUNNING
                stopReason = AutomationStopReason.AUTHENTICATION.name
                stopActionId = null
                nextAttemptAt = NOW.plusSeconds(300)
                waitReason = AutomationWaitReason.HOF_CONNECTION
            }
            action.id
        }

        TransactionTemplate(transactionManager).executeWithoutResult {
            bridge.stop(accountId, AutomationStopReason.MANUAL_STOP, "USER_STOP")
        }

        assertNull(typedQuery.findActiveTypedAction(accountId))
        val discarded = requireNotNull(typedQuery.findStoppedTypedAction(accountId, requireNotNull(activeActionId)))
        assertEquals(TypedAutomationActionStatus.AMBIGUOUS, discarded.status)
        assertEquals(NOW, discarded.finishedAt)

        TransactionTemplate(transactionManager).executeWithoutResult {
            bridge.resume(accountId, "USER_RESUME")
        }

        assertNull(typedQuery.findActiveTypedAction(accountId))
        assertEquals(TypedAutomationLifecycle.RUNNING, requireNotNull(typedQuery.findRuntimeState(accountId)).lifecycleStatus)
    }

    @Test
    fun `resume makes a parked raid check due immediately so HOF state is read fresh`() {
        val accountId = seed("raid-resume-fresh-check")
        TransactionTemplate(transactionManager).executeWithoutResult {
            val account = requireNotNull(entityManager.find(HofAccountEntity::class.java, accountId))
            val entry = typedQuery.findEntries(accountId).single().also {
                it.type = AutomationType.RAID
                it.singletonTypeMarker = AutomationType.RAID
            }
            workSessionCommands.save(
                AutomationWorkSessionEntity(
                    account = account,
                    entry = entry,
                    workType = AutomationWorkType.RAID,
                    targetKey = "raid-1",
                    status = AutomationWorkStatus.WAITING_COOLDOWN,
                    configVersion = "raid-config",
                    nextCheckAt = NOW.plusSeconds(3_600),
                    createdAt = NOW,
                    updatedAt = NOW,
                ),
            )
        }

        TransactionTemplate(transactionManager).executeWithoutResult {
            bridge.resume(accountId, "USER_RESUME")
        }

        val due = workSessionQuery.findDue(NOW, 10).single { it.accountId == accountId }
        assertEquals(AutomationWorkType.RAID, due.workType)
        assertEquals(NOW, due.nextCheckAt)
    }

    @Test
    fun `pause waits for a reconciling action before becoming paused`() {
        val accountId = seed("pause-preserves-action")
        val activeActionId = TransactionTemplate(transactionManager).execute {
            val account = requireNotNull(entityManager.find(HofAccountEntity::class.java, accountId))
            val entry = typedQuery.findEntries(accountId).single()
            val action = actions.save(
                TypedAutomationActionRunEntity(
                    account = account,
                    entry = entry,
                    executionIdentity = "paused-action",
                    actionKind = "BATTLE_MAP",
                    payloadJson = "{}",
                    actionFingerprint = "c".repeat(64),
                    status = TypedAutomationActionStatus.RECONCILING,
                    leaseToken = "paused-lease",
                    createdAt = NOW,
                    submittedAt = NOW,
                    updatedAt = NOW,
                ),
            )
            requireNotNull(typedQuery.lockRuntimeState(accountId)).apply {
                lifecycleStatus = TypedAutomationLifecycle.RUNNING
                stopReason = null
                stopActionId = null
            }
            action.id
        }

        TransactionTemplate(transactionManager).executeWithoutResult {
            bridge.pause(accountId, "USER_PAUSE")
        }

        assertEquals(activeActionId, typedQuery.findActiveTypedAction(accountId)?.id)
        val state = requireNotNull(typedQuery.findRuntimeState(accountId))
        assertEquals(TypedAutomationLifecycle.DRAINING, state.lifecycleStatus)
        assertEquals(TypedAutomationLifecycle.PAUSED, state.requestedLifecycle)
    }

    @Test
    fun `manual stop immediately stops even when a raid cycle is open`() {
        val accountId = seed("raid-drain")
        TransactionTemplate(transactionManager).executeWithoutResult {
            entityManager.createNativeQuery(
                "update automation_entries set automation_type = 'RAID', singleton_type_marker = 'RAID' where account_id = ?1",
            )
                .setParameter(1, accountId).executeUpdate()
            entityManager.createNativeQuery("update typed_automation_runtime_states set lifecycle_status = 'RUNNING', stop_reason = null, stop_action_id = null where account_id = ?1")
                .setParameter(1, accountId).executeUpdate()
            entityManager.createNativeQuery(
                "insert into raid_automation_cycles (account_id,automation_entry_id,raid_id,raid_name,status,open_marker,started_at,updated_at,version) " +
                    "select account_id,id,'raid-1','레이드','REGISTERED_WAITING',1,?2,?2,0 from automation_entries where account_id = ?1",
            ).setParameter(1, accountId).setParameter(2, NOW).executeUpdate()
            entityManager.createNativeQuery(
                "update raid_automation_cycles set battle_recovery_chain_id = 'chain-1', " +
                    "battle_recovery_original_execution_identity = 'execution-1', " +
                    "battle_recovery_latest_execution_identity = 'execution-1', " +
                    "battle_recovery_first_ambiguous_at = ?2, battle_recovery_last_submitted_at = ?2, " +
                    "battle_recovery_retransmission_count = 0, battle_recovery_next_check_at = ?2, " +
                    "battle_recovery_category_id = 'raid', battle_recovery_map_code = 'map-1', " +
                    "battle_recovery_submitted_from_runnable = true, " +
                    "battle_recovery_last_observation = 'RESULT_UNOBSERVED' where account_id = ?1",
            ).setParameter(1, accountId).setParameter(2, NOW).executeUpdate()
        }

        TransactionTemplate(transactionManager).executeWithoutResult {
            bridge.stop(accountId, AutomationStopReason.MANUAL_STOP, "USER_STOP")
        }

        val state = requireNotNull(typedQuery.findRuntimeState(accountId))
        assertEquals(TypedAutomationLifecycle.STOPPED, state.lifecycleStatus)
        assertNull(state.requestedLifecycle)
        assertEquals(AutomationStopReason.MANUAL_STOP.name, state.stopReason)
        assertNull(requireNotNull(typedQuery.findOpenRaidCycle(accountId)).battleRecoveryChainId)
    }

    @Test
    fun `repeating the same manual stop is idempotent`() {
        val accountId = seed("idempotent-stop")

        TransactionTemplate(transactionManager).executeWithoutResult {
            bridge.stop(accountId, AutomationStopReason.MANUAL_STOP, "USER_STOP")
        }
        TransactionTemplate(transactionManager).executeWithoutResult {
            bridge.stop(accountId, AutomationStopReason.MANUAL_STOP, "USER_STOP")
        }

        val state = requireNotNull(typedQuery.findRuntimeState(accountId))
        assertEquals(TypedAutomationLifecycle.STOPPED, state.lifecycleStatus)
        assertEquals(AutomationStopReason.MANUAL_STOP.name, state.stopReason)
        assertNull(state.stopActionId)
        assertEquals(0, outboxQuery.findUnpublished(NOW.plusSeconds(1)).count { it.account.id == accountId })
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
    fun `captcha answer wakes running runtime for a fresh decision`() {
        val accountId = seed("captcha-running-wake")
        TransactionTemplate(transactionManager).executeWithoutResult {
            entityManager.createNativeQuery(
                "update typed_automation_action_runs set status = 'RECONCILING', finished_at = null, " +
                    "lease_token = 'captcha-lease' where account_id = ?1",
            ).setParameter(1, accountId).executeUpdate()
            entityManager.createNativeQuery(
                "update typed_automation_runtime_states set lifecycle_status = 'RUNNING', " +
                    "stop_reason = null, stop_action_id = null, next_attempt_at = ?1, " +
                    "wait_reason = 'SCHEDULED', lease_token = 'captcha-lease', lease_until = ?2 " +
                    "where account_id = ?3",
            ).setParameter(1, NOW.plusSeconds(300))
                .setParameter(2, NOW.plusSeconds(60))
                .setParameter(3, accountId)
                .executeUpdate()
        }

        val woken = TransactionTemplate(transactionManager).execute {
            bridge.wakeFreshAfterCaptcha(accountId, "CAPTCHA_ANSWERED")
        }

        assertTrue(requireNotNull(woken))
        val state = requireNotNull(typedQuery.findRuntimeState(accountId))
        assertEquals(TypedAutomationLifecycle.RUNNING, state.lifecycleStatus)
        assertNull(state.nextAttemptAt)
        assertNull(state.waitReason)
        assertNull(state.leaseToken)
        assertNull(state.leaseUntil)
        assertNull(typedQuery.findActiveTypedAction(accountId))
        val actionStatus = TransactionTemplate(transactionManager).execute {
            entityManager.createNativeQuery(
                "select status from typed_automation_action_runs where account_id = ?1",
            ).setParameter(1, accountId).singleResult.toString()
        }
        assertEquals(TypedAutomationActionStatus.FAILED.name, actionStatus)
        val events = outboxQuery.findUnpublished(NOW.plusSeconds(1)).filter { it.account.id == accountId }
        assertEquals(1, events.size)
        assertTrue(events.single().payload.contains("\"reason\":\"CAPTCHA_ANSWERED\""))
    }

    @Test
    fun `captcha answer preserves an active non battle action`() {
        val accountId = seed("captcha-running-non-battle")
        TransactionTemplate(transactionManager).executeWithoutResult {
            entityManager.createNativeQuery(
                "update typed_automation_action_runs set action_kind = 'QUEST_CLAIM', status = 'RECONCILING', " +
                    "finished_at = null, lease_token = 'non-battle-lease' where account_id = ?1",
            ).setParameter(1, accountId).executeUpdate()
            entityManager.createNativeQuery(
                "update typed_automation_runtime_states set lifecycle_status = 'RUNNING', stop_reason = null, " +
                    "stop_action_id = null, next_attempt_at = ?1, wait_reason = 'SCHEDULED', " +
                    "lease_token = null, lease_until = null where account_id = ?2",
            ).setParameter(1, NOW.plusSeconds(300)).setParameter(2, accountId).executeUpdate()
        }

        val woken = TransactionTemplate(transactionManager).execute {
            bridge.wakeFreshAfterCaptcha(accountId, "CAPTCHA_ANSWERED")
        }

        assertTrue(requireNotNull(woken))
        val state = requireNotNull(typedQuery.findRuntimeState(accountId))
        assertEquals(NOW.plusSeconds(300), state.nextAttemptAt)
        assertEquals(AutomationWaitReason.SCHEDULED, state.waitReason)
        assertNull(state.leaseToken)
        assertNull(state.leaseUntil)
        assertEquals("QUEST_CLAIM", requireNotNull(typedQuery.findActiveTypedAction(accountId)).actionKind)
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
