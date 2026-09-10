package app.spammy.hof.automation.convergence

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.automation.outbox.AutomationOutboxService
import app.spammy.hof.automation.entity.AutomationEntryEntity
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.repository.AutomationEntryCommandRepository
import app.spammy.hof.automation.repository.TypedAutomationQueryRepository
import app.spammy.hof.automation.service.AutomationDirectResponseStore
import app.spammy.hof.automation.service.StoredTypedAutomationActionCodec
import app.spammy.hof.common.persistence.QueryDslConfig
import app.spammy.hof.common.time.TimeProvider
import jakarta.persistence.EntityManager
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Bean
import org.springframework.boot.test.context.TestConfiguration
import tools.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.test.context.ActiveProfiles

@DataJpaTest
@ActiveProfiles("test")
@Import(QueryDslConfig::class, JpaConvergenceStore::class, JpaAutomationConvergenceStatusReader::class,
    AutomationDirectResponseStore::class, TypedAutomationQueryRepository::class, JpaEvidenceCaseRecorder::class,
    AccountQueryRepository::class, AutomationOutboxService::class,
    StoredTypedAutomationActionCodec::class, AutomationConvergenceStatusReaderTest.Config::class)
class AutomationConvergenceStatusReaderTest {
    @TestConfiguration
    class Config {
        @Bean fun mapper() = jacksonObjectMapper()
        @Bean fun clock() = TimeProvider { Instant.parse("2026-09-10T10:00:00Z") }
    }
    @Autowired private lateinit var accounts: HofAccountRepository
    @Autowired private lateinit var entries: AutomationEntryCommandRepository
    @Autowired private lateinit var store: JpaConvergenceStore
    @Autowired private lateinit var reader: JpaAutomationConvergenceStatusReader
    @Autowired private lateinit var entityManager: EntityManager

    @Test
    fun `미지원 정책 보류는 전송과 관측을 꾸미지 않고 진단과 실제 해제 조건을 제공한다`() {
        val now = Instant.parse("2026-09-10T08:00:00Z")
        val account = accounts.save(HofAccountEntity(loginId = "unsupported-status", encryptedPassword = "encrypted", createdAt = now))
        val entry = entries.save(AutomationEntryEntity(account = account, type = AutomationType.QUEST, priority = 0,
            enabled = true, createdAt = now, updatedAt = now))
        val selected = SelectedAutomationAction(entry.id, "unknown-status", AutomationActionKind.QUEST_CLAIM,
            AutomationIsolationScope(AutomationIsolationScopeKind.QUEST_TARGET, "quest-a"), "unsupported-fixture-version", "baseline")
        val module = DefaultAutomationActionConvergenceModule(store, TimeProvider { now }, JpaEvidenceCaseRecorder(entityManager))
        module.prepare(account.id, selected)
        entityManager.flush()
        entityManager.clear()
        val status = reader.read(account.id).items.single()
        assertEquals(ActionConvergenceResult.HELD, status.result)
        assertEquals("POLICY_VERSION_UNSUPPORTED", status.reasonCode)
        assertEquals("저장된 행동의 판정 규칙을 사용할 수 없어 이 범위의 자동 실행을 보류했습니다.", status.reasonMessage)
        assertEquals("사용자가 새 행동 판단을 허용하면 보류 해제", status.releaseCondition)
        assertEquals(true, status.canAllowFreshDecision)
        val evidence = assertNotNull(entityManager.find(AutomationEvidenceCaseEntity::class.java, status.evidenceCaseId))
        assertEquals("POLICY_UNAVAILABLE", evidence.evidenceSource)
        assertEquals("unsupported-fixture-version", evidence.policyVersion)
        assertEquals(null, evidence.observationCompleteness)
        assertEquals(null, evidence.observationFreshness)
        assertEquals(null, assertNotNull(evidence.attempt).submittedAt)
        assertEquals(0, status.successfulObservationCount)
        val record = assertNotNull(store.get(status.attemptId))
        record.result = ActionConvergenceResult.RESULT_UNOBSERVED
        record.reasonCode = "PENDING_BUDGET_EXHAUSTED"
        store.save(record)
        entityManager.flush()
        entityManager.clear()
        val past = reader.read(account.id).items.single()
        assertEquals(status.reasonMessage, past.reasonMessage)
        assertEquals(status.releaseCondition, past.releaseCondition)
        assertEquals("PENDING_BUDGET_EXHAUSTED", past.reasonCode)
    }

    @Test
    fun `battle gate와 범위별 pending 진단을 함께 조회한다`() {
        val now = Instant.parse("2026-08-22T00:00:00Z")
        val account = accounts.save(HofAccountEntity(
            loginId = "status-user",
            encryptedPassword = "encrypted",
            createdAt = now,
        ))
        val entry = entries.save(AutomationEntryEntity(
            account = account,
            type = AutomationType.QUEST,
            priority = 0,
            enabled = true,
            createdAt = now,
            updatedAt = now,
        ))
        val record = store.createOrGet(account.id, SelectedAutomationAction(
            entry.id,
            "execution-status",
            AutomationActionKind.QUEST_CLAIM,
            AutomationIsolationScope(AutomationIsolationScopeKind.QUEST_TARGET, "quest-a"),
            ProductionActionEvidenceInterpreter.VERSION_1,
            "baseline-a",
        ), now)
        record.result = ActionConvergenceResult.PENDING
        record.firstPendingAt = now
        record.nextProbeAt = now.plusSeconds(10)
        record.successfulObservationCount = 2
        record.reasonCode = "AUTHORITATIVE_STATE_UNCHANGED"
        store.save(record)
        store.openBattleGate(account.id, 91L, "CAPTCHA_REQUIRED", now)
        entityManager.flush()
        entityManager.clear()

        val status = reader.read(account.id)

        assertEquals(91L, assertNotNull(status.battleGate).challengeId)
        assertEquals(1, status.items.size)
        assertEquals(ActionConvergenceResult.PENDING, status.items.single().result)
        assertEquals(2, status.items.single().successfulObservationCount)
        assertEquals("퀘스트 대상 quest-a", status.items.single().impactScope)
    }
}
