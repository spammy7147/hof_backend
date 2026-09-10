package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.automation.entity.*
import app.spammy.hof.automation.outbox.*
import app.spammy.hof.automation.repository.*
import app.spammy.hof.common.persistence.QueryDslConfig
import app.spammy.hof.common.time.TimeProvider
import jakarta.persistence.EntityManager
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.mockito.Mockito
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.util.AopTestUtils
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper

@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
    app.spammy.hof.character.repository.CharacterOperationJobQueryRepository::class,
    QueryDslConfig::class,
    AccountQueryRepository::class,
    TypedAutomationQueryRepository::class,
    AutomationOutboxQueryRepository::class,
    AutomationOutboxService::class,
    StoredTypedAutomationActionCodec::class,
    TypedAutomationRuntimeService::class,
    AutomationDirectResponseStore::class,
    app.spammy.hof.automation.convergence.JpaEvidenceCaseRecorder::class,
    TypedRuntimeWakeAtomicityIntegrationTest.Config::class,
)
class TypedRuntimeWakeAtomicityIntegrationTest {
    @Autowired private lateinit var accounts: HofAccountRepository
    @Autowired private lateinit var entries: AutomationEntryCommandRepository
    @Autowired private lateinit var states: TypedAutomationRuntimeStateCommandRepository
    @Autowired private lateinit var actions: TypedAutomationActionRunCommandRepository
    @Autowired private lateinit var typed: TypedAutomationQueryRepository
    @Autowired private lateinit var outbox: AutomationOutboxQueryRepository
    @Autowired private lateinit var runtime: TypedAutomationRuntimeService
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var entityManager: EntityManager
    @Autowired private lateinit var codec: StoredTypedAutomationActionCodec

    @Test
    fun `round completion commits the ordinary interval and durable wake together`() {
        val accountId = seedStateOnly("durable-round")
        val execution = acquire(accountId)
        val result = runtime.complete(execution, TypedRuntimeOutcome.RoundCompleted())

        val state = requireNotNull(typed.findRuntimeState(accountId))
        assertEquals(NOW.plusSeconds(3), result.nextAttemptAt)
        assertEquals(AutomationWaitReason.LOOP_INTERVAL, state.waitReason)
        assertEquals(0, outbox.findUnpublished(NOW.plusSeconds(1)).count { it.account.id == accountId })
        assertEquals(1, outbox.findUnpublished(NOW.plusSeconds(3)).count { it.account.id == accountId })
        assertIs<TypedRuntimeAcquisition.Busy>(runtime.acquire(accountId))
    }

    @Test
    fun `success commits action runtime release and wake row in one transaction`() {
        val fixture = seed("atomic-success", TypedAutomationActionStatus.PREPARED)
        val execution = acquire(fixture.accountId)
        assertIs<TypedRuntimeSubmission.Started>(runtime.beginSubmission(execution))

        assertEquals(
            true,
            runtime.complete(
                execution,
                TypedRuntimeOutcome.ActionSucceeded("TYPED_ACTION_COMPLETED"),
            ).applied,
        )

        assertEquals("SUCCEEDED", actionStatus(fixture.actionId))
        val state = requireNotNull(typed.findRuntimeState(fixture.accountId))
        assertNull(state.leaseToken)
        assertEquals(1, outbox.findUnpublished(NOW.plusSeconds(1)).count { it.account.id == fixture.accountId })
    }

    @Test
    fun `새 판단과 미래 결과 재확인은 함께 저장되고 중복 종료는 예약을 추가하지 않는다`() {
        val accountId = seedStateOnly("immediate-and-probe")
        val execution = acquire(accountId)
        val probeAt = NOW.plusSeconds(30)
        val outcome = TypedRuntimeOutcome.SelectionChanged("TYPED_CONVERGENCE_CONTINUE")

        assertTrue(runtime.complete(execution, outcome, convergenceRecheckAt = probeAt).applied)

        assertNull(typed.findRuntimeState(accountId)?.nextAttemptAt)
        assertNull(typed.findRuntimeState(accountId)?.leaseToken)
        val immediate = outbox.findUnpublished(NOW).filter { it.account.id == accountId }
        assertEquals(1, immediate.size)
        assertEquals("TYPED_CONVERGENCE_CONTINUE", jacksonObjectMapper().readTree(immediate.single().payload)["reason"].asString())
        val all = outbox.findUnpublished(probeAt).filter { it.account.id == accountId }
        assertEquals(2, all.size)
        val probe = all.single { it.availableAt == probeAt }
        assertEquals("TYPED_CONVERGENCE_PROBE", jacksonObjectMapper().readTree(probe.payload)["reason"].asString())
        assertEquals(false, runtime.complete(execution, outcome, convergenceRecheckAt = probeAt).applied)
        assertEquals(2, outbox.findUnpublished(probeAt).count { it.account.id == accountId })
    }

    @ParameterizedTest
    @ValueSource(strings = ["SCHEDULED", "PREFLIGHT_BUSY", "PREFLIGHT_RETRY", "POST_KILL_SWITCH", "RETRY", "INTEGRITY", "UNSUBMITTED", "RECONCILE", "RECONCILE_HOF"])
    fun `종료만 호출해도 기존 사유와 시각으로 영속 예약을 남긴다`(path: String) {
        val fixture = seed("completion-$path", if (path.startsWith("RECONCILE")) TypedAutomationActionStatus.RECONCILING else TypedAutomationActionStatus.PREPARED)
        val execution = acquire(fixture.accountId)
        if (path == "UNSUBMITTED") assertIs<TypedRuntimeSubmission.Started>(runtime.beginSubmission(execution))
        val future = NOW.plusSeconds(30)
        val outcome = when (path) {
            "SCHEDULED" -> TypedRuntimeOutcome.ScheduledWait(future, AutomationWaitReason.SCHEDULED)
            "PREFLIGHT_BUSY" -> TypedRuntimeOutcome.ScheduledWait(future, AutomationWaitReason.SCHEDULED, wakeReason = "DAILY_PREFLIGHT_BUSY")
            "PREFLIGHT_RETRY" -> TypedRuntimeOutcome.ScheduledWait(future, AutomationWaitReason.HOF_CONNECTION, wakeReason = "DAILY_PREFLIGHT_RETRY")
            "POST_KILL_SWITCH" -> TypedRuntimeOutcome.ScheduledWait(future, AutomationWaitReason.SCHEDULED, wakeReason = "AUTOMATION_POST_KILL_SWITCH")
            "RETRY" -> TypedRuntimeOutcome.RetryableFailure(AutomationStopReason.NETWORK, "network unavailable")
            "INTEGRITY" -> TypedRuntimeOutcome.IntegrityFailure("invalid checkpoint")
            "UNSUBMITTED" -> TypedRuntimeOutcome.UnsubmittedFailure("battle was not submitted")
            "RECONCILE" -> TypedRuntimeOutcome.ReconciliationDeferred(future, "waiting for result")
            else -> TypedRuntimeOutcome.ReconciliationDeferred(future, "HOF unavailable", successfulObservation = false, wakeReason = "HOF_503_COOLDOWN")
        }
        val expectedAt = if (path in setOf("RETRY", "INTEGRITY", "UNSUBMITTED")) NOW.plusSeconds(10) else future
        val expectedReason = when (path) {
            "SCHEDULED" -> "TYPED_UNAVAILABLE"
            "PREFLIGHT_BUSY" -> "DAILY_PREFLIGHT_BUSY"
            "PREFLIGHT_RETRY" -> "DAILY_PREFLIGHT_RETRY"
            "POST_KILL_SWITCH" -> "AUTOMATION_POST_KILL_SWITCH"
            "RETRY", "INTEGRITY" -> "TYPED_AUTOMATIC_RETRY"
            "RECONCILE" -> "TYPED_RECONCILE_RETRY"
            else -> "HOF_503_COOLDOWN"
        }

        val result = runtime.complete(execution, outcome)

        assertTrue(result.applied)
        assertEquals(expectedAt, result.nextAttemptAt)
        assertEquals(expectedAt, typed.findRuntimeState(fixture.accountId)?.nextAttemptAt)
        assertNull(typed.findRuntimeState(fixture.accountId)?.leaseToken)
        assertEquals(0, outbox.findUnpublished(expectedAt.minusMillis(1)).count { it.account.id == fixture.accountId })
        val wake = outbox.findUnpublished(expectedAt).single { it.account.id == fixture.accountId }
        assertEquals(expectedAt, wake.availableAt)
        assertEquals(expectedReason, jacksonObjectMapper().readTree(wake.payload)["reason"].asString())
        assertEquals(false, runtime.complete(execution, outcome).applied)
        assertEquals(1, outbox.findUnpublished(expectedAt).count { it.account.id == fixture.accountId })
    }

    @ParameterizedTest
    @ValueSource(strings = ["WAIT", "RETRY", "IMMEDIATE_AND_PROBE"])
    fun `종료 트랜잭션이 롤백되면 새 대기와 예약도 함께 사라진다`(path: String) {
        val accountId = seedStateOnly("rollback-$path")
        val execution = acquire(accountId)
        val lease = typed.findRuntimeState(accountId)?.leaseToken
        val target = AopTestUtils.getTargetObject<TypedAutomationRuntimeService>(runtime)
        val outcome = when (path) {
            "WAIT" -> TypedRuntimeOutcome.ScheduledWait(NOW.plusSeconds(30), AutomationWaitReason.SCHEDULED)
            "RETRY" -> TypedRuntimeOutcome.SafeRetry("network unavailable")
            else -> TypedRuntimeOutcome.SelectionChanged("TYPED_CONVERGENCE_CONTINUE")
        }

        assertFailsWith<ForcedRollback> {
            TransactionTemplate(transactionManager).executeWithoutResult {
                target.complete(execution, outcome, convergenceRecheckAt = NOW.plusSeconds(60))
                throw ForcedRollback()
            }
        }

        assertEquals(lease, typed.findRuntimeState(accountId)?.leaseToken)
        assertNull(typed.findRuntimeState(accountId)?.nextAttemptAt)
        assertEquals(0, outbox.findUnpublished(NOW.plusSeconds(300)).count { it.account.id == accountId })
    }

    @ParameterizedTest
    @ValueSource(strings = ["PAUSED", "STOPPED", "AUTH_SUSPENDED"])
    fun `미전송 종료는 최신 정지나 인증 중단을 반영하고 새 예약을 남기지 않는다`(boundary: String) {
        val fixture = seed("deferred-$boundary", TypedAutomationActionStatus.PREPARED)
        val execution = acquire(fixture.accountId)
        assertIs<TypedRuntimeSubmission.Started>(runtime.beginSubmission(execution))
        TransactionTemplate(transactionManager).executeWithoutResult {
            requireNotNull(typed.lockRuntimeState(fixture.accountId)).apply {
                if (boundary == "AUTH_SUSPENDED") authSuspended = true
                else {
                    lifecycleStatus = TypedAutomationLifecycle.DRAINING
                    requestedLifecycle = TypedAutomationLifecycle.valueOf(boundary)
                }
            }
        }

        val result = runtime.complete(execution, TypedRuntimeOutcome.SubmissionDeferred(NOW.plusSeconds(30), "not submitted"),
            convergenceRecheckAt = NOW.plusSeconds(60))

        assertTrue(result.applied)
        assertNull(result.nextAttemptAt)
        assertEquals("FAILED", actionStatus(fixture.actionId))
        assertNull(typed.findRuntimeState(fixture.accountId)?.nextAttemptAt)
        assertEquals(0, outbox.findUnpublished(NOW.plusSeconds(300)).count { it.account.id == fixture.accountId })
        assertIs<TypedRuntimeAcquisition.Inactive>(runtime.acquire(fixture.accountId))
    }

    @Test
    fun `인증 중단의 결과 수렴 대기는 다음 관측 예약을 보존한다`() {
        val fixture = seed("draining-reconciliation", TypedAutomationActionStatus.RECONCILING)
        val execution = acquire(fixture.accountId)
        TransactionTemplate(transactionManager).executeWithoutResult {
            requireNotNull(typed.lockRuntimeState(fixture.accountId)).apply {
                lifecycleStatus = TypedAutomationLifecycle.DRAINING
                requestedLifecycle = TypedAutomationLifecycle.PAUSED
                authSuspended = true
            }
        }

        assertTrue(runtime.complete(execution, TypedRuntimeOutcome.ReconciliationDeferred(NOW.plusSeconds(30), "pending result")).applied)

        assertEquals("RECONCILING", actionStatus(fixture.actionId))
        assertEquals(TypedAutomationLifecycle.DRAINING, typed.findRuntimeState(fixture.accountId)?.lifecycleStatus)
        assertEquals(1, outbox.findUnpublished(NOW.plusSeconds(30)).count { it.account.id == fixture.accountId })
    }

    @Test
    fun `outer rollback of transition core rolls back action runtime and wake row together`() {
        val fixture = seed("atomic-rollback", TypedAutomationActionStatus.PREPARED)
        val execution = acquire(fixture.accountId)
        assertIs<TypedRuntimeSubmission.Started>(runtime.beginSubmission(execution))
        val leaseToken = requireNotNull(typed.findRuntimeState(fixture.accountId)?.leaseToken)
        val target = AopTestUtils.getTargetObject<TypedAutomationRuntimeService>(runtime)

        assertFailsWith<ForcedRollback> {
            TransactionTemplate(transactionManager).executeWithoutResult {
                target.complete(
                    execution,
                    TypedRuntimeOutcome.ActionSucceeded("TYPED_ACTION_COMPLETED"),
                )
                throw ForcedRollback()
            }
        }

        assertEquals("SUBMITTING", actionStatus(fixture.actionId))
        assertEquals(leaseToken, typed.findRuntimeState(fixture.accountId)?.leaseToken)
        assertEquals(0, outbox.findUnpublished(NOW.plusSeconds(1)).count { it.account.id == fixture.accountId })
    }

    @Test
    fun `configuration recovery commits lease release and durable wake together`() {
        val fixture = seed("atomic-release", TypedAutomationActionStatus.PREPARED)
        val execution = acquire(fixture.accountId)

        assertEquals(
            true,
            runtime.complete(
                execution,
                TypedRuntimeOutcome.SelectionChanged("TYPED_CONFIG_RELOAD"),
            ).applied,
        )

        assertNull(typed.findRuntimeState(fixture.accountId)?.leaseToken)
        assertEquals(1, outbox.findUnpublished(NOW.plusSeconds(1)).count { it.account.id == fixture.accountId })
    }

    @Test
    fun `ambiguous submission durably enters reconciliation with verification wake`() {
        val fixture = seed("atomic-reconciliation", TypedAutomationActionStatus.PREPARED)
        val execution = acquire(fixture.accountId)
        assertIs<TypedRuntimeSubmission.Started>(runtime.beginSubmission(execution))

        assertTrue(
            runtime.complete(
                execution,
                TypedRuntimeOutcome.SubmissionAmbiguous("unknown result"),
            ).applied,
        )

        assertEquals("RECONCILING", actionStatus(fixture.actionId))
        assertNull(typed.findRuntimeState(fixture.accountId)?.leaseToken)
        assertEquals(1, outbox.findUnpublished(NOW.plusSeconds(1)).count { it.account.id == fixture.accountId })
    }

    @Test
    fun `reconciliation handoff durably terminates checkpoint as ambiguous`() {
        val fixture = seed("atomic-handoff", TypedAutomationActionStatus.RECONCILING)
        val execution = acquire(fixture.accountId)

        assertTrue(
            runtime.complete(
                execution,
                TypedRuntimeOutcome.AmbiguousHandoff(
                    "레이드 전투 결과 미확정",
                    "RAID_BATTLE_RECOVERY_STARTED",
                ),
            ).applied,
        )

        assertEquals("AMBIGUOUS", actionStatus(fixture.actionId))
        assertEquals("레이드 전투 결과 미확정", typed.findRuntimeState(fixture.accountId)?.warningText)
        assertEquals(1, outbox.findUnpublished(NOW.plusSeconds(1)).count { it.account.id == fixture.accountId })
    }

    @Test
    fun `submitted deferral durably returns checkpoint to prepared with retry time`() {
        val fixture = seed("atomic-deferral", TypedAutomationActionStatus.PREPARED)
        val execution = acquire(fixture.accountId)
        assertIs<TypedRuntimeSubmission.Started>(runtime.beginSubmission(execution))
        val retryAt = NOW.plusSeconds(30)

        val projection = runtime.complete(
            execution,
            TypedRuntimeOutcome.SubmissionDeferred(retryAt, "HOF 503"),
        )

        assertEquals(retryAt, projection.nextAttemptAt)
        assertEquals("PREPARED", actionStatus(fixture.actionId))
        assertEquals(retryAt, typed.findRuntimeState(fixture.accountId)?.nextAttemptAt)
        assertEquals(0, outbox.findUnpublished(NOW.plusSeconds(1)).count { it.account.id == fixture.accountId })
        assertEquals(1, outbox.findUnpublished(retryAt).count { it.account.id == fixture.accountId })
    }

    @Test
    fun `expired submitting lease is durably reclaimed as reconciliation`() {
        val fixture = seed("expired-submission", TypedAutomationActionStatus.SUBMITTING)

        val execution = acquire(fixture.accountId)

        assertEquals(TypedRuntimeCheckpointPhase.RECONCILING, execution.checkpoint?.phase)
        assertEquals("RECONCILING", actionStatus(fixture.actionId))
        assertTrue(requireNotNull(typed.findRuntimeState(fixture.accountId)?.leaseUntil).isAfter(NOW))
    }

    @Test
    fun `safe retry durably releases lease and preserves sanitized diagnostic`() {
        val accountId = seedStateOnly("durable-safe-retry")
        val execution = acquire(accountId)

        val projection = runtime.complete(
            execution,
            TypedRuntimeOutcome.SafeRetry("password=secret\nnetwork failed"),
        )

        val state = requireNotNull(typed.findRuntimeState(accountId))
        assertEquals(NOW.plusSeconds(10), projection.nextAttemptAt)
        assertEquals(NOW.plusSeconds(10), state.nextAttemptAt)
        assertEquals("password=[redacted] network failed", state.lastError)
        assertNull(state.leaseToken)
        assertEquals(1, outbox.findUnpublished(NOW.plusSeconds(10)).count { it.account.id == accountId })
    }

    @Test
    fun `action completion durably finishes requested pause without wake`() {
        val fixture = seed("durable-pause", TypedAutomationActionStatus.PREPARED)
        val execution = acquire(fixture.accountId)
        assertIs<TypedRuntimeSubmission.Started>(runtime.beginSubmission(execution))
        TransactionTemplate(transactionManager).executeWithoutResult {
            requireNotNull(typed.lockRuntimeState(fixture.accountId)).apply {
                lifecycleStatus = TypedAutomationLifecycle.DRAINING
                requestedLifecycle = TypedAutomationLifecycle.PAUSED
            }
        }

        assertTrue(
            runtime.complete(
                execution,
                TypedRuntimeOutcome.ActionSucceeded("TYPED_ACTION_COMPLETED"),
            ).applied,
        )

        val state = requireNotNull(typed.findRuntimeState(fixture.accountId))
        assertEquals(TypedAutomationLifecycle.PAUSED, state.lifecycleStatus)
        assertNull(state.requestedLifecycle)
        assertEquals(0, outbox.findUnpublished(NOW.plusSeconds(1)).count { it.account.id == fixture.accountId })
    }

    @Test
    fun `configuration wait durably preserves warnings and bounded recheck`() {
        val accountId = seedStateOnly("durable-configuration")
        val execution = acquire(accountId)

        val projection = runtime.complete(
            execution,
            TypedRuntimeOutcome.ConfigurationWait(listOf("missing primary", "later warning")),
        )

        val state = requireNotNull(typed.findRuntimeState(accountId))
        assertEquals(NOW.plusSeconds(300), projection.nextAttemptAt)
        assertEquals(AutomationWaitReason.SCHEDULED, state.waitReason)
        assertEquals("missing primary\nlater warning", state.warningText)
        assertNull(state.leaseToken)
        assertEquals(1, outbox.findUnpublished(NOW.plusSeconds(300)).count { it.account.id == accountId })
    }

    @Test
    fun `idle release remains discoverable by periodic recovery after runner clears its next time`() {
        val fixture = seed("idle-periodic-recovery", TypedAutomationActionStatus.PREPARED)
        val execution = acquire(fixture.accountId)

        assertEquals(true, runtime.complete(execution, TypedRuntimeOutcome.Idle).applied)

        val state = requireNotNull(typed.findRuntimeState(fixture.accountId))
        assertNull(state.nextAttemptAt)
        assertNull(state.leaseToken)
        assertTrue(typed.findRecoverableRuntimeAccountIds(NOW).contains(fixture.accountId))
    }

    private fun seed(login: String, status: TypedAutomationActionStatus): Fixture = TransactionTemplate(transactionManager).execute {
        val account = accounts.save(HofAccountEntity(loginId = login, encryptedPassword = "encrypted", createdAt = NOW))
        val entry = entries.save(AutomationEntryEntity(account = account, type = AutomationType.QUEST, priority = 0, enabled = true, createdAt = NOW, updatedAt = NOW))
        states.save(TypedAutomationRuntimeStateEntity(
            account.id,
            account,
            TypedAutomationLifecycle.RUNNING,
            leaseToken = "old-token".takeIf { status == TypedAutomationActionStatus.SUBMITTING },
            leaseUntil = NOW.minusSeconds(1).takeIf { status == TypedAutomationActionStatus.SUBMITTING },
            createdAt = NOW,
            updatedAt = NOW,
        ))
        val stored = StoredTypedAutomationAction(entry.id, "execution-$login", StoredTypedActionPayload.QuestClaim("quest", "claim"), settingsRevision = entry.settingsRevision)
        val encoded = codec.encode(stored)
        val action = actions.save(TypedAutomationActionRunEntity(
            account = account, entry = entry, executionIdentity = stored.executionIdentity, actionKind = "QUEST_CLAIM",
            payloadJson = encoded.json, actionFingerprint = encoded.fingerprint, status = status,
            leaseToken = "old-token", createdAt = NOW, submittedAt = NOW.takeIf { status == TypedAutomationActionStatus.SUBMITTING }, updatedAt = NOW,
        ))
        entityManager.flush()
        Fixture(account.id, action.id)
    }

    private fun seedStateOnly(login: String): Long = TransactionTemplate(transactionManager).execute {
        val account = accounts.save(
            HofAccountEntity(loginId = login, encryptedPassword = "encrypted", createdAt = NOW),
        )
        states.save(
            TypedAutomationRuntimeStateEntity(
                account.id,
                account,
                TypedAutomationLifecycle.RUNNING,
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )
        entityManager.flush()
        account.id
    }

    private fun actionStatus(actionId: Long): String = TransactionTemplate(transactionManager).execute {
        entityManager.createNativeQuery("select status from typed_automation_action_runs where id=?1")
            .setParameter(1, actionId).singleResult.toString()
    }

    private fun acquire(accountId: Long): TypedRuntimeExecutionRight =
        assertIs<TypedRuntimeAcquisition.Acquired>(runtime.acquire(accountId)).execution

    data class Fixture(val accountId: Long, val actionId: Long)
    private class ForcedRollback : RuntimeException()

    @TestConfiguration(proxyBeanMethods = false)
    class Config {
        @Bean fun objectMapper(): ObjectMapper = jacksonObjectMapper()
        @Bean fun timeProvider(): TimeProvider = TimeProvider { NOW }
        @Bean fun lifecycleBridge(): TypedAutomationLifecycleBridge = Mockito.mock(TypedAutomationLifecycleBridge::class.java)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-16T00:00:00Z")
    }
}
