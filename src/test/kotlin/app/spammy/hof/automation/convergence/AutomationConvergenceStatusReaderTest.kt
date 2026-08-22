package app.spammy.hof.automation.convergence

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.automation.entity.AutomationEntryEntity
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.repository.AutomationEntryCommandRepository
import app.spammy.hof.common.persistence.QueryDslConfig
import jakarta.persistence.EntityManager
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

@DataJpaTest
@ActiveProfiles("test")
@Import(QueryDslConfig::class, JpaConvergenceStore::class, JpaAutomationConvergenceStatusReader::class)
class AutomationConvergenceStatusReaderTest {
    @Autowired private lateinit var accounts: HofAccountRepository
    @Autowired private lateinit var entries: AutomationEntryCommandRepository
    @Autowired private lateinit var store: JpaConvergenceStore
    @Autowired private lateinit var reader: JpaAutomationConvergenceStatusReader
    @Autowired private lateinit var entityManager: EntityManager

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
            "policy-v1",
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
