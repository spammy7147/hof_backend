package app.spammy.hof.automation.convergence

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.automation.entity.AutomationEntryEntity
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.repository.AutomationEntryCommandRepository
import app.spammy.hof.common.persistence.QueryDslConfig
import app.spammy.hof.common.time.TimeProvider
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
@Import(QueryDslConfig::class, JpaConvergenceStore::class, JpaAutomationConvergenceShadowRecorder::class)
class ConvergencePersistenceTest {
    @Autowired private lateinit var accounts: HofAccountRepository
    @Autowired private lateinit var entries: AutomationEntryCommandRepository
    @Autowired private lateinit var store: JpaConvergenceStore
    @Autowired private lateinit var shadowRecorder: JpaAutomationConvergenceShadowRecorder
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
    fun `새 convergence row는 null이 아닌 pending 결과로 저장된다`() {
        val now = Instant.parse("2026-08-22T00:00:00Z")
        val (account, entry) = fixture("convergence-pending", now)

        val created = store.createOrGet(
            account.id,
            selection(entry.id, "execution-pending", "quest-pending"),
            now,
        )
        entityManager.flush()
        entityManager.clear()

        assertEquals(ActionConvergenceResult.PENDING, store.get(created.attemptId)?.result)
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
    fun `동시에 due인 probe는 생성 시각보다 저장 entry 우선순위를 따른다`() {
        val now = Instant.parse("2026-08-22T00:00:00Z")
        val (account, lowerPriority) = fixture("convergence-due-priority", now)
        lowerPriority.priority = 10
        entries.save(lowerPriority)
        val higherPriority = entries.save(
            AutomationEntryEntity(
                account = account,
                type = AutomationType.HOME_QUEST,
                priority = 0,
                enabled = true,
                createdAt = now,
                updatedAt = now,
            ),
        )
        val lower = store.createOrGet(
            account.id,
            selection(lowerPriority.id, "execution-lower", "scope-lower"),
            now.minusSeconds(1),
        ).also {
            it.firstPendingAt = now.minusSeconds(10)
            it.nextProbeAt = now
            store.save(it)
        }
        val higher = store.createOrGet(
            account.id,
            selection(higherPriority.id, "execution-higher", "scope-higher"),
            now,
        ).also {
            it.firstPendingAt = now.minusSeconds(10)
            it.nextProbeAt = now
            store.save(it)
        }
        entityManager.flush()
        entityManager.clear()

        assertEquals(higher.attemptId, store.findDue(account.id, now)?.attemptId)
        assertEquals(ActionConvergenceResult.PENDING, store.get(lower.attemptId)?.result)
    }

    @Test
    fun `pending 중 entry가 삭제되면 고아 시도를 종료하고 다른 scope 판단을 막지 않는다`() {
        val now = Instant.parse("2026-08-22T00:00:00Z")
        val (account, entry) = fixture("convergence-deleted-entry", now)
        val pending = store.createOrGet(
            account.id,
            selection(entry.id, "execution-deleted", "scope-deleted"),
            now,
        ).also {
            it.firstPendingAt = now
            it.nextProbeAt = now
            store.save(it)
        }
        entityManager.flush()
        entityManager.clear()

        entries.delete(requireNotNull(entityManager.find(AutomationEntryEntity::class.java, entry.id)))
        entityManager.flush()
        entityManager.clear()

        assertEquals(1, store.normalizeOrphans(account.id, now.plusSeconds(1)))
        entityManager.flush()
        entityManager.clear()

        assertEquals(null, store.findDue(account.id, now.plusSeconds(1)))
        assertEquals(emptySet(), store.findActiveScopes(account.id))
        assertEquals(null, store.get(pending.attemptId))
        val terminal = entityManager.createNativeQuery(
            "select result, active_marker, reason_code from automation_action_convergences where attempt_id = :id",
        ).setParameter("id", pending.attemptId).singleResult as Array<*>
        assertEquals("SUPERSEDED", terminal[0])
        assertEquals(null, terminal[1])
        assertEquals("AUTOMATION_ENTRY_DELETED", terminal[2])
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

    @Test
    fun `shadow 비교는 redacted 차원을 내구 저장하고 삼십일 뒤 정리한다`() {
        val now = Instant.parse("2026-08-23T00:00:00Z")
        val (account) = fixture("shadow-durable", now)
        val current = shadowEvaluation(account.id, now.minusSeconds(60))
        val expired = shadowEvaluation(account.id, now.minusSeconds(31L * 24 * 60 * 60))

        shadowRecorder.record(current)
        shadowRecorder.record(expired)
        entityManager.flush()
        assertEquals(
            2L,
            entityManager.createQuery(
                "select count(shadow) from AutomationConvergenceShadowEvaluationEntity shadow",
                Long::class.javaObjectType,
            ).singleResult,
        )

        val deleted = AutomationEvidenceRetentionScheduler(entityManager, TimeProvider { now })
            .deleteExpiredEvidence()
        entityManager.flush()

        assertEquals(1, deleted)
        val remaining = entityManager.createQuery(
            "select shadow from AutomationConvergenceShadowEvaluationEntity shadow",
            AutomationConvergenceShadowEvaluationEntity::class.java,
        ).singleResult
        assertEquals(current.executionIdentityHash, remaining.executionIdentityHash)
        assertEquals(current.responseShapeFingerprint, remaining.responseShapeFingerprint)
        assertEquals(current.sanitizedSnippet, remaining.sanitizedSnippet)
        assertEquals(36, remaining.id.length)
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

    private fun shadowEvaluation(accountId: Long, at: Instant) = DurableShadowEvaluation(
        accountId = accountId,
        executionIdentityHash = "a".repeat(64),
        actionKind = AutomationActionKind.HOME_ACCEPT,
        scopeKind = AutomationIsolationScopeKind.HOME_TARGET,
        scopeKeyHash = "b".repeat(64),
        evidenceKind = "IncompleteObservation",
        evidenceCompleteness = "AUTHORITATIVE_IDENTITY_INCOMPLETE",
        responseShapeFingerprint = "c".repeat(64),
        sanitizedSnippet = "IncompleteObservation|authoritative=true",
        legacyDecision = LegacyConvergenceDecision.RECONCILING,
        legacyReasonCode = "OBSERVATION_INCOMPLETE",
        newResult = ActionConvergenceResult.PENDING,
        newReasonCode = "HOME_ACTION_ID_MISSING",
        resultDiffers = false,
        reasonDiffers = true,
        shapeDiffers = false,
        completenessDiffers = false,
        policyVersion = "convergence-v1",
        observedAt = at,
    )
}
