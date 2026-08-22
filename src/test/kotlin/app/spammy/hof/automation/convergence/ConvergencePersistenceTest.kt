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
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

@DataJpaTest
@ActiveProfiles("test")
@Import(QueryDslConfig::class, JpaConvergenceStore::class)
class ConvergencePersistenceTest {
    @Autowired private lateinit var accounts: HofAccountRepository
    @Autowired private lateinit var entries: AutomationEntryCommandRepository
    @Autowired private lateinit var store: JpaConvergenceStore
    @Autowired private lateinit var entityManager: EntityManager

    @Test
    fun `행동 시도와 결과 판정을 분리해 저장하고 실행 identity replay를 같은 시도로 복원한다`() {
        val now = Instant.parse("2026-08-22T00:00:00Z")
        val (account, entry) = fixture("convergence-replay", now)
        val selection = selection(entry.id, "execution-replay", "quest-a")

        val created = store.createOrGet(account.id, selection, now)
        created.result = ActionConvergenceResult.PENDING
        created.firstPendingAt = now
        created.nextProbeAt = now.plusSeconds(10)
        created.successfulObservationCount = 1
        created.reasonCode = "AUTHORITATIVE_STATE_UNCHANGED"
        store.save(created)
        entityManager.flush()
        entityManager.clear()

        val replay = store.createOrGet(account.id, selection, now.plusSeconds(1))

        assertEquals(created.attemptId, replay.attemptId)
        assertEquals(selection, replay.selection)
        assertEquals(ActionConvergenceResult.PENDING, replay.result)
        assertEquals(1, replay.successfulObservationCount)
    }

    @Test
    fun `같은 계정과 격리 범위에는 활성 수렴을 하나만 허용한다`() {
        val now = Instant.parse("2026-08-22T00:00:00Z")
        val (account, entry) = fixture("convergence-scope", now)
        val first = store.createOrGet(account.id, selection(entry.id, "execution-first", "quest-a"), now)
        first.result = ActionConvergenceResult.PENDING
        first.firstPendingAt = now
        first.nextProbeAt = now.plusSeconds(10)
        store.save(first)
        entityManager.flush()

        assertFails {
            store.createOrGet(account.id, selection(entry.id, "execution-second", "quest-a"), now)
            entityManager.flush()
        }
    }

    @Test
    fun `계정당 활성 전투 관문을 하나만 유지하고 해소 시각을 보존한다`() {
        val now = Instant.parse("2026-08-22T00:00:00Z")
        val (account) = fixture("convergence-gate", now)

        val first = store.openBattleGate(account.id, 11L, "CAPTCHA_REQUIRED", now)
        val replay = store.openBattleGate(account.id, 22L, "CAPTCHA_REQUIRED_AGAIN", now.plusSeconds(1))

        assertEquals(first.openedAt, replay.openedAt)
        assertEquals(11L, replay.challengeId)
        assertNotNull(store.activeBattleGate(account.id))

        assertEquals(true, store.releaseBattleGate(account.id, now.plusSeconds(30)))
        assertEquals(null, store.activeBattleGate(account.id))
    }

    private fun fixture(loginId: String, now: Instant): Pair<HofAccountEntity, AutomationEntryEntity> {
        val account = accounts.save(
            HofAccountEntity(loginId = loginId, encryptedPassword = "encrypted", createdAt = now),
        )
        val entry = entries.save(
            AutomationEntryEntity(
                account = account,
                type = AutomationType.QUEST,
                priority = 0,
                enabled = true,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return account to entry
    }

    private fun selection(entryId: Long, executionIdentity: String, scopeKey: String) = SelectedAutomationAction(
        entryId = entryId,
        executionIdentity = executionIdentity,
        actionKind = AutomationActionKind.QUEST_CLAIM,
        scope = AutomationIsolationScope(AutomationIsolationScopeKind.QUEST_TARGET, scopeKey),
        policyVersion = "convergence-v1",
        baselineFingerprint = "baseline-$scopeKey",
    )
}
