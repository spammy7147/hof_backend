package app.spammy.hof.captcha.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.automation.convergence.ConvergenceStore
import app.spammy.hof.automation.entity.AutomationWaitReason
import app.spammy.hof.automation.entity.TypedAutomationLifecycle
import app.spammy.hof.automation.entity.TypedAutomationRuntimeStateEntity
import app.spammy.hof.captcha.dto.CaptchaPassMaintenanceLastResult
import app.spammy.hof.captcha.dto.CaptchaPassMaintenanceLifecycleState
import app.spammy.hof.captcha.entity.CaptchaChallengeEntity
import app.spammy.hof.captcha.entity.CaptchaChallengeEntity.Companion.KIND_VIGILANTE_PASS
import jakarta.persistence.EntityManager
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@SpringBootTest
@ActiveProfiles("test")
class CaptchaBattleGateResumeIntegrationTest {
    @Autowired private lateinit var entityManager: EntityManager
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var convergenceStore: ConvergenceStore
    @Autowired private lateinit var automationHook: CaptchaAutomationHook
    @Autowired private lateinit var passMaintenance: CaptchaPassMaintenanceService
    @MockitoBean private lateinit var passStatusRefresher: CaptchaPassStatusRefresher

    @Test
    fun `captcha answer after commit durably releases battle gate and requests a fresh decision`() {
        val now = Instant.parse("2026-08-23T08:00:00Z")
        val accountId = inTransaction {
            val account = HofAccountEntity(
                loginId = "captcha-gate-after-commit-${System.nanoTime()}",
                encryptedPassword = "encrypted",
                createdAt = now,
            )
            entityManager.persist(account)
            entityManager.flush()
            entityManager.persist(
                TypedAutomationRuntimeStateEntity(
                    accountId = account.id,
                    account = account,
                    lifecycleStatus = TypedAutomationLifecycle.RUNNING,
                    nextAttemptAt = now.plusSeconds(300),
                    waitReason = AutomationWaitReason.HOF_CONNECTION,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            convergenceStore.openBattleGate(account.id, 91L, "CAPTCHA_REQUIRED", now)
            account.id
        }

        try {
            assertNotNull(inTransaction { convergenceStore.activeBattleGate(accountId) })
            val challenge = inTransaction {
                CaptchaChallengeEntity(
                    id = 91L,
                    account = entityManager.find(HofAccountEntity::class.java, accountId),
                    status = "ANSWERED",
                    prompt = "captcha",
                    imageUrl = null,
                    sourceUrl = "https://example.test/captcha",
                    answer = "answer",
                    createdAt = now,
                    answeredAt = now,
                )
            }

            inTransaction { automationHook.answered(challenge) }

            assertNull(inTransaction { convergenceStore.activeBattleGate(accountId) })
            val runtime = inTransaction {
                entityManager.createNativeQuery(
                    """
                    select next_attempt_at, wait_reason
                    from typed_automation_runtime_states
                    where account_id = :accountId
                    """.trimIndent(),
                ).setParameter("accountId", accountId).singleResult as Array<*>
            }
            assertNull(runtime[0])
            assertNull(runtime[1])
            assertNotNull(inTransaction {
                entityManager.createNativeQuery(
                    "select resolved_at from automation_account_battle_gates where account_id = :accountId",
                ).setParameter("accountId", accountId).singleResult
            })
        } finally {
            inTransaction {
                entityManager.createNativeQuery("delete from hof_accounts where id = :accountId")
                    .setParameter("accountId", accountId)
                    .executeUpdate()
            }
        }
    }

    @Test
    fun `failed pass confirmation after answer commit durably schedules a retry`() {
        val now = Instant.parse("2026-08-23T08:00:00Z")
        val accountId = inTransaction {
            val account = HofAccountEntity(
                loginId = "captcha-confirmation-after-commit-${System.nanoTime()}",
                encryptedPassword = "encrypted",
                createdAt = now,
            )
            entityManager.persist(account)
            entityManager.flush()
            account.id
        }

        try {
            passMaintenance.setEnabled(accountId, true)
            val claim = assertNotNull(passMaintenance.claimDue(accountId))
            assertTrue(passMaintenance.beginSubmission(accountId, claim.token))
            Mockito.doThrow(IllegalStateException("status refresh failed"))
                .`when`(passStatusRefresher)
                .refresh(accountId)
            val challenge = inTransaction {
                CaptchaChallengeEntity(
                    id = 92L,
                    account = entityManager.find(HofAccountEntity::class.java, accountId),
                    status = "ANSWERED",
                    prompt = CaptchaChallengeParser.VIGILANTE_PASS_PROMPT,
                    challengeKind = KIND_VIGILANTE_PASS,
                    imageUrl = null,
                    sourceUrl = "https://example.test/captcha",
                    answer = "answer",
                    createdAt = now,
                    answeredAt = now,
                )
            }

            inTransaction { automationHook.answered(challenge) }

            val actual = passMaintenance.get(accountId)
            assertEquals(CaptchaPassMaintenanceLastResult.RETRY_SCHEDULED, actual.lastResult)
            assertEquals(CaptchaPassMaintenanceLifecycleState.CONNECTION_RETRY_WAIT, actual.lifecycleState)
            assertNotNull(actual.nextRefreshAt)
            assertFalse(passMaintenance.beginReconciliation(accountId, claim.token))
        } finally {
            inTransaction {
                entityManager.createNativeQuery("delete from hof_accounts where id = :accountId")
                    .setParameter("accountId", accountId)
                    .executeUpdate()
            }
        }
    }

    private fun <T> inTransaction(block: () -> T): T =
        TransactionTemplate(transactionManager).execute { block() }
}
