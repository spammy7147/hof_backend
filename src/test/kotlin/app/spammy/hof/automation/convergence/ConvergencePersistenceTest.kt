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
import kotlin.test.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.transaction.TestTransaction
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

@DataJpaTest
@ActiveProfiles("test")
@Import(QueryDslConfig::class, JpaConvergenceStore::class, JpaAutomationConvergenceShadowRecorder::class)
class ConvergencePersistenceTest {
    @Autowired private lateinit var accounts: HofAccountRepository
    @Autowired private lateinit var entries: AutomationEntryCommandRepository
    @Autowired private lateinit var store: JpaConvergenceStore
    @Autowired private lateinit var shadowRecorder: JpaAutomationConvergenceShadowRecorder
    @Autowired private lateinit var entityManager: EntityManager
    @Autowired private lateinit var transactions: PlatformTransactionManager

    @ParameterizedTest
    @ValueSource(strings = ["MANUAL", "BASELINE", "RAID"])
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `보류 해제와 직접 적용이 경합해도 확정 결과를 옛 entity로 덮어쓰지 않는다`(release: String) {
        val now = Instant.parse("2026-09-10T00:00:00Z")
        val transaction = TransactionTemplate(transactions)
        val module = DefaultAutomationActionConvergenceModule(store, TimeProvider { now.plusSeconds(2) })
        val (account, entry, attempt) = transaction.execute {
            val (account, entry) = fixture("release-race-$release", now)
            val selected = SelectedAutomationAction(entry.id, "release-race-$release", AutomationActionKind.RAID_REGISTER,
                AutomationIsolationScope(AutomationIsolationScopeKind.RAID_ENTRY, "raid-a"), ProductionActionEvidenceInterpreter.VERSION_1, "old-baseline")
            val attempt = store.createOrGet(account.id, selected, now)
            attempt.result = ActionConvergenceResult.HELD
            attempt.submittedAt = now
            attempt.finishedAt = now
            attempt.reasonCode = "RESULT_UNOBSERVED"
            store.save(attempt)
            Triple(account, entry, attempt)
        }
        val releaseRead = CountDownLatch(1)
        val commitRelease = CountDownLatch(1)
        try {
            Executors.newFixedThreadPool(2).use { executor ->
                val releasing = executor.submit {
                    transaction.executeWithoutResult {
                        when (release) {
                            "MANUAL" -> assertTrue(module.allowFreshDecision(account.id, attempt.attemptId, now.plusSeconds(1)))
                            "BASELINE" -> assertEquals(1, module.observeAuthoritativeBaselines(account.id,
                                attempt.selection.scope, setOf("new-baseline"), now.plusSeconds(1)))
                            else -> assertEquals(1, module.allowRaidRegistrationFreshDecision(account.id, entry.id, "raid-a", now.plusSeconds(1)))
                        }
                        releaseRead.countDown()
                        check(commitRelease.await(15, TimeUnit.SECONDS))
                    }
                }
                var applying: java.util.concurrent.Future<*>? = null
                try {
                    assertTrue(releaseRead.await(10, TimeUnit.SECONDS))
                    applying = executor.submit {
                        module.record(attempt.attemptId, AutomationActionEvidence.DirectApplied(now.plusSeconds(2), "applied"))
                    }
                    try {
                        applying.get(2, TimeUnit.SECONDS)
                    } catch (_: java.util.concurrent.TimeoutException) {
                        // 보류 해제가 잠금을 보유하면 직접 결과는 그 commit 다음에 저장된다.
                    }
                } finally {
                    commitRelease.countDown()
                    try {
                        releasing.get(10, TimeUnit.SECONDS)
                    } catch (error: java.util.concurrent.ExecutionException) {
                        // 기존 @Version은 오래된 보류 해제의 flush를 거절해 직접 결과를 보존한다.
                        if (error.cause !is org.springframework.orm.ObjectOptimisticLockingFailureException) throw error
                    }
                    applying?.get(10, TimeUnit.SECONDS)
                }
            }
            val result = assertNotNull(store.get(attempt.attemptId))
            assertEquals(ActionConvergenceResult.APPLIED, result.result)
            assertEquals("DIRECT_RESPONSE_APPLIED", result.reasonCode)
            assertEquals(now.plusSeconds(2), result.finishedAt)
            assertEquals(false, result.active)
            assertTrue(store.findSuppressedBaselines(account.id).isEmpty())
        } finally {
            transaction.executeWithoutResult {
                entityManager.createNativeQuery("delete from hof_accounts where id = :accountId")
                    .setParameter("accountId", account.id).executeUpdate()
            }
        }
    }

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

        val replay = store.createOrGet(account.id,
            selection.copy(policyVersion = "new-policy", baselineFingerprint = "new-baseline"), now.plusSeconds(1))
        val module = DefaultAutomationActionConvergenceModule(store, TimeProvider { now.plusSeconds(10) })
        val probe = kotlin.test.assertIs<ConvergenceDirective.Probe>(module.resumeDue(account.id))

        assertEquals(selection, probe.selection)
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
        TestTransaction.flagForCommit()
        TestTransaction.end()
        TestTransaction.start()
        try {
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
        } finally {
            TestTransaction.end()
            TestTransaction.start()
            entityManager.createNativeQuery("delete from hof_accounts where id = :accountId")
                .setParameter("accountId", account.id).executeUpdate()
            TestTransaction.flagForCommit()
            TestTransaction.end()
        }
    }

    @Test
    fun `미지원 과거 보류는 재로딩 뒤에도 최신 관측과 신청 자격 갱신으로 해제하지 않는다`() {
        val now = Instant.parse("2026-09-10T00:00:00Z")
        val (account, entry) = fixture("unsupported-policy-held", now)
        val records = listOf(ActionConvergenceResult.HELD, ActionConvergenceResult.RESULT_UNOBSERVED).map { result ->
            val selected = selection(entry.id, "unknown-$result", "raid-$result").copy(
                actionKind = AutomationActionKind.RAID_REGISTER,
                scope = AutomationIsolationScope(AutomationIsolationScopeKind.RAID_ENTRY, "raid-$result"),
                policyVersion = "unsupported-fixture-version",
            )
            store.createOrGet(account.id, selected, now).also {
                it.result = result
                it.finishedAt = now
                it.reasonCode = "PENDING_BUDGET_EXHAUSTED"
                store.save(it)
                entityManager.flush()
            }
        }
        entityManager.clear()
        val reloaded = JpaConvergenceStore(entityManager)
        assertEquals(records.map { it.selection.scope }.toSet(), reloaded.findPolicyHeldScopes(account.id))
        for (record in records) {
            assertEquals(0, reloaded.releaseSupersededSuppressions(account.id, record.selection.scope,
                setOf("current-baseline"), now.plusSeconds(1)))
            assertEquals(0, reloaded.releaseRaidRegistrationSuppressions(account.id, entry.id,
                record.selection.scope.key, now.plusSeconds(1)))
            entityManager.flush()
            entityManager.clear()
            assertEquals("PENDING_BUDGET_EXHAUSTED", reloaded.get(record.attemptId)?.reasonCode)
            assertEquals(setOf(record.selection.baselineFingerprint),
                reloaded.findSuppressedBaselines(account.id)[record.selection.scope])
            assertEquals(true, reloaded.releaseSuppression(account.id, record.attemptId, now.plusSeconds(2)))
        }
        entityManager.flush()
        entityManager.clear()
        assertEquals(emptySet(), reloaded.findPolicyHeldScopes(account.id))
    }

    @Test
    fun `신청 보류 해제는 계정 항목 대상 행동을 제한하고 재로딩과 재처리 뒤에도 유지된다`() {
        val now = Instant.parse("2026-09-04T00:00:00Z")
        val (account, entry) = fixture("raid-release", now)
        val (otherAccount, otherEntry) = fixture("other-raid-release", now)
        fun held(owner: Long, entryId: Long, identity: String, raidId: String, kind: AutomationActionKind): ActionConvergenceRecord {
            val record = store.createOrGet(owner, SelectedAutomationAction(entryId, identity, kind,
                AutomationIsolationScope(AutomationIsolationScopeKind.RAID_ENTRY, raidId), ProductionActionEvidenceInterpreter.VERSION_1, identity), now)
            record.result = ActionConvergenceResult.HELD
            record.finishedAt = now
            store.save(record)
            return record
        }
        val registration = held(account.id, entry.id, "register", "raid-a", AutomationActionKind.RAID_REGISTER)
        val battle = held(account.id, entry.id, "battle", "raid-a", AutomationActionKind.RAID_BATTLE)
        val anotherTarget = held(account.id, entry.id, "other-target", "raid-b", AutomationActionKind.RAID_REGISTER)
        val foreign = held(otherAccount.id, otherEntry.id, "foreign", "raid-a", AutomationActionKind.RAID_REGISTER)
        assertEquals(0, store.releaseRaidRegistrationSuppressions(account.id, otherEntry.id, "raid-a", now))
        assertEquals(1, store.releaseRaidRegistrationSuppressions(account.id, entry.id, "raid-a", now.plusSeconds(1)))
        entityManager.flush()
        entityManager.clear()
        val reloaded = JpaConvergenceStore(entityManager)
        assertEquals(0, reloaded.releaseRaidRegistrationSuppressions(account.id, entry.id, "raid-a", now.plusSeconds(2)))
        assertEquals(ActionConvergenceResult.HELD, reloaded.get(registration.attemptId)?.result)
        assertEquals("RAID_REGISTRATION_FRESH_DECISION_RELEASED", reloaded.get(registration.attemptId)?.reasonCode)
        assertEquals(setOf(battle.selection.baselineFingerprint), reloaded.findSuppressedBaselines(account.id)[battle.selection.scope])
        assertEquals(setOf(anotherTarget.selection.baselineFingerprint), reloaded.findSuppressedBaselines(account.id)[anotherTarget.selection.scope])
        assertEquals(setOf(foreign.selection.baselineFingerprint), reloaded.findSuppressedBaselines(otherAccount.id)[foreign.selection.scope])
        reloaded.createOrGet(account.id, registration.selection, now.plusSeconds(3))
        assertEquals(setOf(battle.selection.baselineFingerprint), reloaded.findSuppressedBaselines(account.id)[battle.selection.scope])
    }

    @Test
    fun `같은 퀘스트의 현재 미션 보류를 모두 보존하고 진행도가 바뀐 미션만 영구 해제한다`() {
        val now = Instant.parse("2026-09-04T00:00:00Z")
        val (account, entry) = fixture("quest-mission-baselines", now)
        val scope = AutomationIsolationScope(AutomationIsolationScopeKind.QUEST_TARGET, "quest-a")
        val held = listOf("mission-a", "mission-b").map { baseline ->
            store.createOrGet(account.id, SelectedAutomationAction(entry.id, baseline,
                AutomationActionKind.QUEST_BATTLE, scope, ProductionActionEvidenceInterpreter.VERSION_1, baseline), now).also {
                it.result = ActionConvergenceResult.HELD
                it.finishedAt = now
                store.save(it)
                entityManager.flush()
            }
        }
        val module = DefaultAutomationActionConvergenceModule(store, TimeProvider { now })
        assertEquals(0, module.observeAuthoritativeBaselines(account.id, scope, setOf("mission-a", "mission-b"), now))
        entityManager.flush()
        entityManager.clear()
        assertEquals(setOf("mission-a", "mission-b"), store.findSuppressedBaselines(account.id)[scope])

        assertEquals(1, module.observeAuthoritativeBaselines(account.id, scope, setOf("mission-a-progressed", "mission-b"), now))
        entityManager.flush()
        entityManager.clear()
        assertEquals(setOf("mission-b"), store.findSuppressedBaselines(account.id)[scope])
        held.forEach { assertEquals(ActionConvergenceResult.HELD, store.get(it.attemptId)?.result) }
        assertEquals(0, module.observeAuthoritativeBaselines(account.id, scope, setOf("mission-a", "mission-b"), now))
        assertEquals(setOf("mission-b"), store.findSuppressedBaselines(account.id)[scope])
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
        policyVersion = ProductionActionEvidenceInterpreter.VERSION_1,
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
        policyVersion = ProductionActionEvidenceInterpreter.VERSION_1,
        observedAt = at,
    )
}
