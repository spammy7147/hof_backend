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
import kotlin.test.assertFalse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

@DataJpaTest
@ActiveProfiles("test")
@Import(QueryDslConfig::class, JpaConvergenceStore::class, JpaEvidenceCaseRecorder::class)
class EvidenceCaseRecorderTest {
    @Autowired private lateinit var accounts: HofAccountRepository
    @Autowired private lateinit var entries: AutomationEntryCommandRepository
    @Autowired private lateinit var store: JpaConvergenceStore
    @Autowired private lateinit var recorder: JpaEvidenceCaseRecorder
    @Autowired private lateinit var entityManager: EntityManager

    @Test
    fun `판정 증거는 민감 원문 없이 삼십일 만료 case로 남긴다`() {
        val now = Instant.parse("2026-08-22T00:00:00Z")
        val account = accounts.save(HofAccountEntity(
            loginId = "evidence-user",
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
        val attempt = store.createOrGet(account.id, SelectedAutomationAction(
            entry.id,
            "execution-evidence",
            AutomationActionKind.QUEST_CLAIM,
            AutomationIsolationScope(AutomationIsolationScopeKind.QUEST_TARGET, "quest-a"),
            "policy-v1",
            "baseline-a",
        ), now)

        recorder.record(
            attempt,
            AutomationActionEvidence.SameState(now, "state-a"),
            "AUTHORITATIVE_STATE_UNCHANGED",
        )
        entityManager.flush()
        entityManager.clear()

        val cases = entityManager.createQuery(
            "select evidence from AutomationEvidenceCaseEntity evidence",
            AutomationEvidenceCaseEntity::class.java,
        ).resultList
        assertEquals(1, cases.size)
        assertEquals("state-a", cases.single().stateFingerprint)
        assertEquals(now.plusSeconds(30L * 24 * 60 * 60), cases.single().expiresAt)
        assertEquals("evidence=SameState", cases.single().sanitizedSnippet)
        assertEquals(64, cases.single().responseShapeFingerprint?.length)
        assertFalse(cases.single().sanitizedSnippet.orEmpty().contains("execution-evidence"))
    }
}
