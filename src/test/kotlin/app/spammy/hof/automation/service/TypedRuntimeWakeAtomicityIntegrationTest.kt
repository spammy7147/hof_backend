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
import kotlin.test.assertNull
import org.mockito.Mockito
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
    QueryDslConfig::class,
    AccountQueryRepository::class,
    TypedAutomationQueryRepository::class,
    AutomationOutboxQueryRepository::class,
    AutomationOutboxService::class,
    StoredTypedAutomationActionCodec::class,
    TypedAutomationRuntimeService::class,
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
    fun `success commits action runtime release and wake row in one transaction`() {
        val fixture = seed("atomic-success", TypedAutomationActionStatus.SUBMITTING)

        assertEquals(true, runtime.succeedAndEnqueueWake(fixture.accountId, TOKEN, fixture.actionId, "TYPED_ACTION_COMPLETED"))

        assertEquals("SUCCEEDED", actionStatus(fixture.actionId))
        val state = requireNotNull(typed.findRuntimeState(fixture.accountId))
        assertNull(state.leaseToken)
        assertEquals(1, outbox.findUnpublished(NOW.plusSeconds(1)).count { it.account.id == fixture.accountId })
    }

    @Test
    fun `outer rollback of transition core rolls back action runtime and wake row together`() {
        val fixture = seed("atomic-rollback", TypedAutomationActionStatus.SUBMITTING)
        val target = AopTestUtils.getTargetObject<TypedAutomationRuntimeService>(runtime)

        assertFailsWith<ForcedRollback> {
            TransactionTemplate(transactionManager).executeWithoutResult {
                target.succeedAndEnqueueWake(fixture.accountId, TOKEN, fixture.actionId, "TYPED_ACTION_COMPLETED")
                throw ForcedRollback()
            }
        }

        assertEquals("SUBMITTING", actionStatus(fixture.actionId))
        assertEquals(TOKEN, typed.findRuntimeState(fixture.accountId)?.leaseToken)
        assertEquals(0, outbox.findUnpublished(NOW.plusSeconds(1)).count { it.account.id == fixture.accountId })
    }

    @Test
    fun `configuration recovery commits lease release and durable wake together`() {
        val fixture = seed("atomic-release", TypedAutomationActionStatus.PREPARED)

        assertEquals(true, runtime.releaseAndEnqueueWake(fixture.accountId, TOKEN, "TYPED_CONFIG_RELOAD"))

        assertNull(typed.findRuntimeState(fixture.accountId)?.leaseToken)
        assertEquals(1, outbox.findUnpublished(NOW.plusSeconds(1)).count { it.account.id == fixture.accountId })
    }

    private fun seed(login: String, status: TypedAutomationActionStatus): Fixture = TransactionTemplate(transactionManager).execute {
        val account = accounts.save(HofAccountEntity(loginId = login, encryptedPassword = "encrypted", createdAt = NOW))
        val entry = entries.save(AutomationEntryEntity(account = account, type = AutomationType.QUEST, priority = 0, enabled = true, createdAt = NOW, updatedAt = NOW))
        states.save(TypedAutomationRuntimeStateEntity(account.id, account, TypedAutomationLifecycle.RUNNING, leaseToken = TOKEN, leaseUntil = NOW.plusSeconds(300), createdAt = NOW, updatedAt = NOW))
        val stored = StoredTypedAutomationActionV1(entry.id, "execution-$login", StoredTypedActionPayload.QuestClaim("quest", "claim"))
        val encoded = codec.encode(stored)
        val action = actions.save(TypedAutomationActionRunEntity(
            account = account, entry = entry, executionIdentity = stored.executionIdentity, actionKind = "QUEST_CLAIM",
            schemaVersion = 1, payloadJson = encoded.json, actionFingerprint = encoded.fingerprint, status = status,
            leaseToken = TOKEN, createdAt = NOW, submittedAt = NOW.takeIf { status == TypedAutomationActionStatus.SUBMITTING }, updatedAt = NOW,
        ))
        entityManager.flush()
        Fixture(account.id, action.id)
    }

    private fun actionStatus(actionId: Long): String = TransactionTemplate(transactionManager).execute {
        entityManager.createNativeQuery("select status from typed_automation_action_runs where id=?1")
            .setParameter(1, actionId).singleResult.toString()
    }

    data class Fixture(val accountId: Long, val actionId: Long)
    private class ForcedRollback : RuntimeException()

    @TestConfiguration(proxyBeanMethods = false)
    class Config {
        @Bean fun objectMapper(): ObjectMapper = jacksonObjectMapper()
        @Bean fun timeProvider(): TimeProvider = TimeProvider { NOW }
        @Bean fun lifecycleBridge(): TypedAutomationLifecycleBridge = Mockito.mock(TypedAutomationLifecycleBridge::class.java)
    }

    private companion object {
        const val TOKEN = "atomic-token"
        val NOW: Instant = Instant.parse("2026-07-16T00:00:00Z")
    }
}
