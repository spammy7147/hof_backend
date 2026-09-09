package app.spammy.hof.captcha.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.automation.convergence.ConvergenceStore
import app.spammy.hof.automation.entity.AutomationWaitReason
import app.spammy.hof.automation.entity.TypedAutomationLifecycle
import app.spammy.hof.automation.entity.TypedAutomationRuntimeStateEntity
import app.spammy.hof.captcha.dto.CaptchaPassMaintenanceLastResult
import app.spammy.hof.captcha.dto.CaptchaPassMaintenanceLifecycleState
import app.spammy.hof.captcha.entity.CaptchaChallengeEntity
import app.spammy.hof.captcha.repository.CaptchaQueryRepository
import app.spammy.hof.external.client.HofGateway
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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import app.spammy.hof.account.entity.HofCookieEntity
import app.spammy.hof.automation.service.TypedCaptchaAutomationResumeService
import app.spammy.hof.automation.outbox.AutomationOutboxService
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofHttpResponse
import org.springframework.jdbc.core.JdbcTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@SpringBootTest
@ActiveProfiles("test")
class CaptchaBattleGateResumeIntegrationTest {
    @Autowired private lateinit var resumes: TypedCaptchaAutomationResumeService
    @Autowired private lateinit var jdbc: JdbcTemplate
    @MockitoSpyBean private lateinit var outbox: AutomationOutboxService
    @Autowired private lateinit var entityManager: EntityManager
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @MockitoSpyBean private lateinit var convergenceStore: ConvergenceStore
    @Autowired private lateinit var captchaService: CaptchaService
    @Autowired private lateinit var captchaQueries: CaptchaQueryRepository
    @MockitoBean private lateinit var gateway: HofGateway
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
            inTransaction {
                val challenge = CaptchaChallengeEntity(
                    account = entityManager.find(HofAccountEntity::class.java, accountId),
                    status = "ANSWERED",
                    prompt = "captcha",
                    imageUrl = null,
                    sourceUrl = "https://example.test/captcha",
                    answer = "answer",
                    createdAt = now,
                    answeredAt = now,
                )
                entityManager.persist(challenge)
                automationHook.answered(challenge)
            }

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
            inTransaction {
                val challenge = CaptchaChallengeEntity(
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
                entityManager.persist(challenge)
                automationHook.answered(challenge)
            }

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

    @Test
    fun `답안 commit 이후 관문 해제가 실패해도 유효 통행증 재관측에서 답안 재제출 없이 복구한다`() {
        val now = Instant.parse("2026-09-09T00:00:00Z")
        val (accountId, challengeId) = inTransaction {
            val account = HofAccountEntity(
                loginId = "captcha-resume-retry-${System.nanoTime()}",
                encryptedPassword = "encrypted",
                createdAt = now,
            )
            entityManager.persist(account)
            entityManager.flush()
            entityManager.persist(TypedAutomationRuntimeStateEntity(
                accountId = account.id, account = account,
                lifecycleStatus = TypedAutomationLifecycle.RUNNING,
                nextAttemptAt = now.plusSeconds(300), waitReason = AutomationWaitReason.HOF_CONNECTION,
                createdAt = now, updatedAt = now,
            ))
            val challenge = CaptchaChallengeEntity(
                account = account, status = "READY", prompt = "captcha",
                challengeKind = KIND_VIGILANTE_PASS, imageUrl = null,
                sourceUrl = "https://example.test/captcha", answer = null, createdAt = now, answeredAt = null,
            )
            entityManager.persist(challenge)
            entityManager.flush()
            convergenceStore.openBattleGate(account.id, challenge.id, "CAPTCHA_REQUIRED", now)
            account.id to challenge.id
        }
        try {
            Mockito.doThrow(DataAccessResourceFailureException("controlled resume transaction failure"))
                .doCallRealMethod()
                .`when`(convergenceStore).releaseBattleGate(Mockito.eq(accountId), Mockito.any(Instant::class.java) ?: now)

            assertTrue(captchaService.resolveCurrentPassChallenge(accountId))
            assertEquals("ANSWERED", inTransaction {
                captchaQueries.findOwnedByAccountIdAndId(accountId, challengeId)?.status
            })
            assertNotNull(inTransaction { convergenceStore.activeBattleGate(accountId) })

            assertFalse(captchaService.resolveCurrentPassChallenge(accountId))

            assertNull(inTransaction { convergenceStore.activeBattleGate(accountId) })
            Mockito.verifyNoInteractions(gateway)
        } finally {
            inTransaction {
                entityManager.createNativeQuery("delete from hof_accounts where id = :accountId")
                    .setParameter("accountId", accountId).executeUpdate()
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["MANUAL", "AUTOMATIC", "PREPARE"])
    fun `모든 답안 완료 경로는 재개 실패 뒤 원격 답안을 반복하지 않고 시작 복구한다`(source: String) = withRecoveryAccount { accountId, challengeId ->
        val requests = mutableListOf<HofRequest>()
        Mockito.doAnswer { invocation ->
            val request = invocation.getArgument<HofRequest>(1)
            requests += request
            HofHttpResponse(200, request.url, "<div>통행증을 발급받았습니다.</div>", emptyMap())
        }.`when`(gateway).execute(Mockito.eq(accountId),
            Mockito.any(HofRequest::class.java) ?: HofRequest(HofHttpMethod.GET, "https://example.test"), Mockito.anyMap())
        Mockito.doThrow(DataAccessResourceFailureException("controlled outbox failure"))
            .doCallRealMethod().`when`(outbox).enqueue(accountId, "CAPTCHA_ANSWERED", null)
        when (source) {
            "MANUAL" -> captchaService.submitAnswer(accountId, challengeId, "answer", 0)
            "AUTOMATIC" -> captchaService.submitAutomaticAnswer(accountId, challengeId, CaptchaRecognition("answer", "fixture"), 0)
            else -> captchaService.prepareCurrent(accountId)
        }
        assertEquals("ANSWERED", jdbc.queryForObject("select status from captcha_challenges where id = ?", String::class.java, challengeId))
        assertTrue(pending(challengeId))
        assertNotNull(convergenceStore.activeBattleGate(accountId))
        assertEquals(1, requests.size)
        assertEquals(if (source == "PREPARE") HofHttpMethod.GET else HofHttpMethod.POST, requests.single().method)
        if (source != "PREPARE") assertEquals("answer", requests.single().formFields["captcha"])

        CaptchaAutomationResumeScheduler(resumes).recoverOnStartup()
        CaptchaAutomationResumeScheduler(resumes).runPending()
        assertFalse(captchaService.resolveCurrentPassChallenge(accountId))
        assertFalse(pending(challengeId))
        assertNull(convergenceStore.activeBattleGate(accountId))
        assertEquals(1, wakeCount(accountId), "여러 복구 진입점에서도 의도는 한 번 소비한다.")
        assertEquals(1, requests.size, "저장된 답안은 다시 제출하지 않는다.")
    }

    @ParameterizedTest
    @ValueSource(strings = ["PAUSED", "STOPPED", "DRAINING", "AUTH_RUNNING", "AUTH_CAPTCHA"])
    fun `시작 복구는 관문을 해제해도 사용자 제어와 인증 중단 상태를 변경하지 않는다`(control: String) = withRecoveryAccount { accountId, challengeId ->
        val lifecycle = when (control) { "AUTH_RUNNING" -> "RUNNING"; "AUTH_CAPTCHA" -> "STOPPED"; else -> control }
        jdbc.update("update typed_automation_runtime_states set lifecycle_status = ?, auth_suspended = ?, stop_reason = ? where account_id = ?",
            lifecycle, control.startsWith("AUTH"), if (control == "AUTH_CAPTCHA") "CAPTCHA" else if (control == "STOPPED") "MANUAL_STOP" else null, accountId)
        jdbc.update("update captcha_challenges set status = 'ANSWERED', automation_resume_pending = true where id = ?", challengeId)
        val before = jdbc.queryForMap("select * from typed_automation_runtime_states where account_id = ?", accountId)

        CaptchaAutomationResumeScheduler(resumes).recoverOnStartup()

        assertNull(convergenceStore.activeBattleGate(accountId))
        assertFalse(pending(challengeId))
        assertEquals(before, jdbc.queryForMap("select * from typed_automation_runtime_states where account_id = ?", accountId))
        assertEquals(0, wakeCount(accountId))
        Mockito.verifyNoInteractions(gateway)
    }

    @Test
    fun `새 challenge가 있으면 이전 답안의 지연 복구로 현재 관문을 열지 않는다`() = withRecoveryAccount { accountId, challengeId ->
        jdbc.update("update captcha_challenges set status = 'ANSWERED', automation_resume_pending = true where id = ?", challengeId)
        val newId = inTransaction {
            CaptchaChallengeEntity(account = entityManager.getReference(HofAccountEntity::class.java, accountId),
                status = "READY", prompt = "new captcha", challengeKind = KIND_VIGILANTE_PASS, imageUrl = null,
                sourceUrl = "https://example.test/captcha", answer = null, createdAt = Instant.now(), answeredAt = null)
                .also(entityManager::persist).id
        }
        CaptchaAutomationResumeScheduler(resumes).runPending()
        assertNotNull(convergenceStore.activeBattleGate(accountId))
        assertFalse(pending(challengeId))
        assertEquals(0, wakeCount(accountId))
        assertEquals("READY", jdbc.queryForObject("select status from captcha_challenges where id = ?", String::class.java, newId))
        assertTrue(captchaService.resolveCurrentPassChallenge(accountId))
        assertNull(convergenceStore.activeBattleGate(accountId))
        assertEquals(1, wakeCount(accountId))
        Mockito.verifyNoInteractions(gateway)
    }

    @Test
    fun `동시 복구 transaction은 한 번만 재개 의도를 소비한다`() = withRecoveryAccount { accountId, challengeId ->
        jdbc.update("update captcha_challenges set status = 'ANSWERED', automation_resume_pending = true where id = ?", challengeId)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map { workers.submit<Boolean> {
                ready.countDown()
                check(start.await(5, TimeUnit.SECONDS))
                resumes.resumeAfterCaptcha(accountId)
            } }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertEquals(listOf(false, true), results.map { it.get(10, TimeUnit.SECONDS) }.sorted())
            assertEquals(1, wakeCount(accountId))
            assertFalse(pending(challengeId))
            assertNull(convergenceStore.activeBattleGate(accountId))
            Mockito.verifyNoInteractions(gateway)
        } finally {
            start.countDown()
            workers.shutdownNow()
        }
    }

    private fun pending(challengeId: Long) = jdbc.queryForObject(
        "select automation_resume_pending from captcha_challenges where id = ?", Boolean::class.java, challengeId)!!

    private fun wakeCount(accountId: Long) = jdbc.queryForObject(
        "select count(*) from automation_outbox where account_id = ? and topic = ?", Int::class.java,
        accountId, AutomationOutboxService.WAKEUP_TOPIC)

    private fun withRecoveryAccount(block: (Long, Long) -> Unit) {
        val now = Instant.parse("2026-09-09T00:00:00Z")
        val (accountId, challengeId) = inTransaction {
            val account = HofAccountEntity(loginId = "captcha-resume-${System.nanoTime()}", encryptedPassword = "fixture", createdAt = now)
            entityManager.persist(account)
            entityManager.flush()
            entityManager.persist(TypedAutomationRuntimeStateEntity(accountId = account.id, account = account,
                lifecycleStatus = TypedAutomationLifecycle.RUNNING, nextAttemptAt = now.plusSeconds(300),
                waitReason = AutomationWaitReason.HOF_CONNECTION, createdAt = now, updatedAt = now))
            entityManager.persist(HofCookieEntity(account = account, name = "PHPSESSID", value = "fixture-session", updatedAt = now))
            val challenge = CaptchaChallengeEntity(account = account, status = "READY", prompt = "captcha",
                imageUrl = null, sourceUrl = "https://example.test/captcha", answer = null, createdAt = now, answeredAt = null)
            entityManager.persist(challenge)
            convergenceStore.openBattleGate(account.id, null, "CAPTCHA_REQUIRED", now)
            account.id to challenge.id
        }
        try { block(accountId, challengeId) }
        finally { jdbc.update("delete from hof_accounts where id = ?", accountId) }
    }

    private fun <T> inTransaction(block: () -> T): T =
        TransactionTemplate(transactionManager).execute { block() }
}
