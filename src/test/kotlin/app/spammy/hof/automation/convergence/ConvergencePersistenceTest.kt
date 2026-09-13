package app.spammy.hof.automation.convergence

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.automation.entity.AutomationEntryEntity
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.repository.AutomationEntryCommandRepository
import app.spammy.hof.automation.repository.AutomationConvergenceShadowQueryRepository
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
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.CsvSource
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
@Import(QueryDslConfig::class, JpaConvergenceStore::class, JpaAutomationConvergenceShadowRecorder::class,
    AutomationConvergenceShadowQueryRepository::class)
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
            assertEquals(0, reloaded.releaseRaidSuppressions(account.id, entry.id,
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

    @ParameterizedTest
    @EnumSource(value = AutomationActionKind::class, names = ["RAID_REGISTER", "RAID_REFRESH"])
    fun `레이드 보류 해제는 계정 항목 대상 행동을 제한하고 재로딩과 재처리 뒤에도 유지된다`(kind: AutomationActionKind) {
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
        val registration = held(account.id, entry.id, "register", "raid-a", kind)
        val battle = held(account.id, entry.id, "battle", "raid-a", AutomationActionKind.RAID_BATTLE)
        val anotherTarget = held(account.id, entry.id, "other-target", "raid-b", kind)
        val foreign = held(otherAccount.id, otherEntry.id, "foreign", "raid-a", kind)
        assertEquals(0, store.releaseRaidSuppressions(account.id, otherEntry.id, "raid-a", now, kind))
        assertEquals(1, store.releaseRaidSuppressions(account.id, entry.id, "raid-a", now.plusSeconds(1), kind))
        entityManager.flush()
        entityManager.clear()
        val reloaded = JpaConvergenceStore(entityManager)
        assertEquals(0, reloaded.releaseRaidSuppressions(account.id, entry.id, "raid-a", now.plusSeconds(2), kind))
        assertEquals(ActionConvergenceResult.HELD, reloaded.get(registration.attemptId)?.result)
        assertEquals(if (kind == AutomationActionKind.RAID_REFRESH) "RAID_REFRESH_FRESH_DECISION_RELEASED"
            else "RAID_REGISTRATION_FRESH_DECISION_RELEASED", reloaded.get(registration.attemptId)?.reasonCode)
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

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `shadow 판정기 재생성 뒤에도 다섯 번째 성공 관측에서 같은 보류 결과를 저장한다`() {
        val now = Instant.parse("2026-09-11T00:00:00Z")
        var currentTime = now
        val clock = TimeProvider { currentTime }
        val transaction = TransactionTemplate(transactions)
        val (continuousAccount, continuousEntry) = requireNotNull(transaction.execute {
            fixture("shadow-budget-continuous", now)
        })
        val (restoredAccount, restoredEntry) = requireNotNull(transaction.execute {
            fixture("shadow-budget-restored", now)
        })
        val accountIds = listOf(continuousAccount.id, restoredAccount.id)
        try {
            val continuousSelection = selection(continuousEntry.id, "continuous-execution", "quest")
            val restoredSelection = selection(restoredEntry.id, "restored-execution", "quest")
            val continuous = DefaultAutomationConvergenceShadowEvaluator(clock, shadowRecorder, store)
            var restored = DefaultAutomationConvergenceShadowEvaluator(clock, shadowRecorder, store)
            continuous.selected(continuousAccount.id, continuousSelection)
            restored.selected(restoredAccount.id, restoredSelection)
            repeat(4) { index ->
                currentTime = now.plusSeconds(index.toLong())
                val evidence = AutomationActionEvidence.SameState(currentTime, "baseline-quest")
                assertEquals(ActionConvergenceResult.PENDING, continuous.observe(
                    continuousAccount.id, continuousSelection.executionIdentity,
                    evidence, LegacyConvergenceDecision.RECONCILING,
                )?.newResult)
                assertEquals(ActionConvergenceResult.PENDING, restored.observe(
                    restoredAccount.id, restoredSelection.executionIdentity,
                    evidence, LegacyConvergenceDecision.RECONCILING,
                )?.newResult)
            }
            fun storedTrace(accountId: Long) = requireNotNull(transaction.execute {
                entityManager.createQuery(
                    "select shadow from AutomationConvergenceShadowEvaluationEntity shadow " +
                        "where shadow.accountId = :accountId order by shadow.createdAt",
                    AutomationConvergenceShadowEvaluationEntity::class.java,
                ).setParameter("accountId", accountId).resultList.map {
                    Triple(it.newResult, it.newReasonCode, it.createdAt)
                }
            })
            assertEquals(4, storedTrace(restoredAccount.id).size)
            restored = DefaultAutomationConvergenceShadowEvaluator(clock, shadowRecorder, store)
            restored.selected(restoredAccount.id, restoredSelection)
            currentTime = now.plusSeconds(4)
            val lastEvidence = AutomationActionEvidence.SameState(currentTime, "baseline-quest")
            assertEquals(ActionConvergenceResult.HELD, continuous.observe(
                continuousAccount.id, continuousSelection.executionIdentity,
                lastEvidence, LegacyConvergenceDecision.HELD,
            )?.newResult)
            val afterRestart = restored.observe(
                restoredAccount.id, restoredSelection.executionIdentity,
                lastEvidence, LegacyConvergenceDecision.HELD,
            )
            val continuousTrace = storedTrace(continuousAccount.id)
            val restoredTrace = storedTrace(restoredAccount.id)
            assertEquals(5, continuousTrace.size)
            assertEquals("PENDING_BUDGET_EXHAUSTED", continuousTrace.last().second)
            assertEquals(0L, transaction.execute {
                entityManager.createQuery(
                    "select count(attempt) from AutomationActionAttemptEntity attempt " +
                        "where attempt.account.id in :accountIds", Long::class.javaObjectType,
                ).setParameter("accountIds", accountIds).singleResult
            })
            assertEquals(continuousTrace, restoredTrace, "저장된 SHADOW 결과·이유·시각이 재생성으로 달라지면 안 된다")
            assertEquals(ActionConvergenceResult.HELD, afterRestart?.newResult)
        } finally {
            transaction.executeWithoutResult {
                entityManager.createNativeQuery("delete from hof_accounts where id in (:accountIds)")
                    .setParameter("accountIds", accountIds).executeUpdate()
            }
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `shadow 재생성 후 네트워크 실패는 관측 횟수를 늘리지 않고 원래 시간 예산을 끝낸다`() {
        val now = Instant.parse("2026-09-11T00:00:00Z")
        var currentTime = now
        val clock = TimeProvider { currentTime }
        val transaction = TransactionTemplate(transactions)
        val (account, entry) = requireNotNull(transaction.execute { fixture("shadow-network-budget", now) })
        try {
            val selected = selection(entry.id, "network-budget", "quest")
            var evaluator = DefaultAutomationConvergenceShadowEvaluator(clock, shadowRecorder, store)
            evaluator.selected(account.id, selected)
            assertEquals(ActionConvergenceResult.PENDING, evaluator.observe(account.id, selected.executionIdentity,
                AutomationActionEvidence.SameState(now, "baseline-quest"), LegacyConvergenceDecision.RECONCILING)?.newResult)
            for ((seconds, expected) in listOf(110L to ActionConvergenceResult.PENDING,
                119L to ActionConvergenceResult.PENDING, 120L to ActionConvergenceResult.HELD)) {
                currentTime = now.plusSeconds(seconds)
                evaluator = DefaultAutomationConvergenceShadowEvaluator(clock, shadowRecorder, store)
                evaluator.selected(account.id, selected)
                assertEquals(expected, evaluator.observe(account.id, selected.executionIdentity,
                    AutomationActionEvidence.NetworkFailure(currentTime, "fixture network failure"), LegacyConvergenceDecision.RECONCILING)?.newResult)
            }
            val rows = requireNotNull(transaction.execute {
                entityManager.createQuery(
                    "select shadow from AutomationConvergenceShadowEvaluationEntity shadow " +
                        "where shadow.accountId = :accountId order by shadow.recordedSequence",
                    AutomationConvergenceShadowEvaluationEntity::class.java,
                ).setParameter("accountId", account.id).resultList
            })
            assertEquals(listOf(1, 1, 1, 1), rows.map { it.successfulObservationCount })
            assertEquals(listOf(now, now, now, now), rows.map { it.firstPendingAt })
            assertEquals("PENDING_BUDGET_EXHAUSTED", rows.last().newReasonCode)
        } finally {
            transaction.executeWithoutResult {
                entityManager.createNativeQuery("delete from hof_accounts where id = :accountId")
                    .setParameter("accountId", account.id).executeUpdate()
            }
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `shadow 종결 뒤 늦은 원래 직접 응답은 저장 순서대로 복원하고 독립 범위는 새로 판단한다`() {
        val now = Instant.parse("2026-09-11T00:00:00Z")
        var currentTime = now
        val clock = TimeProvider { currentTime }
        val transaction = TransactionTemplate(transactions)
        val (account, entry) = requireNotNull(transaction.execute { fixture("shadow-late-terminal", now) })
        try {
            val selected = selection(entry.id, "late-terminal", "quest")
            var evaluator = DefaultAutomationConvergenceShadowEvaluator(clock, shadowRecorder, store)
            evaluator.selected(account.id, selected)
            repeat(5) { index ->
                currentTime = now.plusSeconds(10L + index)
                evaluator.observe(account.id, selected.executionIdentity,
                    AutomationActionEvidence.SameState(currentTime, "baseline-quest"), LegacyConvergenceDecision.RECONCILING)
            }
            evaluator = DefaultAutomationConvergenceShadowEvaluator(clock, shadowRecorder, store)
            evaluator.selected(account.id, selected)
            assertEquals(null, evaluator.observe(account.id, selected.executionIdentity,
                AutomationActionEvidence.NetworkFailure(currentTime, "fixture network failure"), LegacyConvergenceDecision.RECONCILING))
            assertEquals(ActionConvergenceResult.APPLIED, evaluator.observe(account.id, selected.executionIdentity,
                AutomationActionEvidence.DirectApplied(now, "original-response"), LegacyConvergenceDecision.APPLIED)?.newResult)
            evaluator = DefaultAutomationConvergenceShadowEvaluator(clock, shadowRecorder, store)
            evaluator.selected(account.id, selected.copy(policyVersion = "unsupported-new-policy"))
            assertEquals(null, evaluator.observe(account.id, selected.executionIdentity,
                AutomationActionEvidence.DirectApplied(now, "original-response"), LegacyConvergenceDecision.APPLIED))
            val independent = selection(entry.id, "independent-execution", "independent-quest")
            evaluator.selected(account.id, independent)
            assertEquals(ActionConvergenceResult.PENDING, evaluator.observe(account.id, independent.executionIdentity,
                AutomationActionEvidence.SameState(currentTime, "baseline-independent-quest"),
                LegacyConvergenceDecision.RECONCILING)?.newResult)
            transaction.executeWithoutResult {
                val rows = entityManager.createQuery(
                    "select shadow from AutomationConvergenceShadowEvaluationEntity shadow " +
                        "where shadow.accountId = :accountId order by shadow.recordedSequence",
                    AutomationConvergenceShadowEvaluationEntity::class.java,
                ).setParameter("accountId", account.id).resultList
                assertEquals(7, rows.size)
                assertEquals(ActionConvergenceResult.HELD, rows[4].newResult)
                assertEquals(ActionConvergenceResult.APPLIED, rows[5].newResult)
                assertEquals(now, rows[5].createdAt)
                assertTrue(rows[5].createdAt.isBefore(rows[4].createdAt))
                assertEquals(ProductionActionEvidenceInterpreter.VERSION_1, rows[5].policyVersion)
                assertEquals(1, rows[6].successfulObservationCount)
                assertEquals(0L, entityManager.createQuery(
                    "select count(attempt) from AutomationActionAttemptEntity attempt where attempt.account.id = :accountId",
                    Long::class.javaObjectType,
                ).setParameter("accountId", account.id).singleResult)
            }
        } finally {
            transaction.executeWithoutResult {
                entityManager.createNativeQuery("delete from hof_accounts where id = :accountId")
                    .setParameter("accountId", account.id).executeUpdate()
            }
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `shadow 재생성은 진행도 기준 새 판단으로 해제한 과거 보류를 다시 활성화하지 않는다`() {
        val now = Instant.parse("2026-09-11T00:00:00Z")
        var currentTime = now
        val clock = TimeProvider { currentTime }
        val transaction = TransactionTemplate(transactions)
        val fixtures = listOf(false, true).map { restart ->
            restart to requireNotNull(transaction.execute { fixture("shadow-fresh-decision-$restart", now) })
        }
        val accountIds = fixtures.map { it.second.first.id }
        try {
            val traces = fixtures.map { (restart, fixture) ->
                val (account, entry) = fixture
                val original = selection(entry.id, "original-battle", "quest")
                    .copy(actionKind = AutomationActionKind.QUEST_BATTLE)
                currentTime = now
                var evaluator = DefaultAutomationConvergenceShadowEvaluator(clock, shadowRecorder, store)
                evaluator.selected(account.id, original)
                assertEquals(ActionConvergenceResult.RESULT_UNOBSERVED, evaluator.observe(
                    account.id, original.executionIdentity,
                    AutomationActionEvidence.ResultUnobservedFreshDecision(now, "complete quest progress"),
                    LegacyConvergenceDecision.RESULT_UNOBSERVED,
                )?.newResult)

                if (restart) evaluator = DefaultAutomationConvergenceShadowEvaluator(clock, shadowRecorder, store)
                evaluator.selected(account.id, original)
                currentTime = now.plusSeconds(1)
                val next = original.copy(executionIdentity = "next-battle")
                evaluator.selected(account.id, next)
                assertEquals(ActionConvergenceResult.PENDING, evaluator.observe(
                    account.id, next.executionIdentity,
                    AutomationActionEvidence.SameState(currentTime, original.baselineFingerprint),
                    LegacyConvergenceDecision.RECONCILING,
                )?.newResult, "재시작=$restart: 해제한 과거 보류가 같은 기준 상태의 다음 행동 비교를 막으면 안 된다")
                currentTime = now.plusSeconds(2)
                assertEquals(ActionConvergenceResult.APPLIED, evaluator.observe(
                    account.id, next.executionIdentity,
                    AutomationActionEvidence.DirectApplied(currentTime, "next-battle-response"),
                    LegacyConvergenceDecision.APPLIED,
                )?.newResult)
                requireNotNull(transaction.execute {
                    entityManager.createQuery(
                        "select shadow from AutomationConvergenceShadowEvaluationEntity shadow " +
                            "where shadow.accountId = :accountId order by shadow.recordedSequence",
                        AutomationConvergenceShadowEvaluationEntity::class.java,
                    ).setParameter("accountId", account.id).resultList.map {
                        Triple(it.newResult, it.newReasonCode, it.successfulObservationCount)
                    }
                })
            }
            assertEquals(listOf(
                Triple(ActionConvergenceResult.RESULT_UNOBSERVED, "RESULT_UNOBSERVED_FRESH_DECISION", 0),
                Triple(ActionConvergenceResult.PENDING, "AUTHORITATIVE_STATE_UNCHANGED", 1),
                Triple(ActionConvergenceResult.APPLIED, "DIRECT_RESPONSE_APPLIED", 1),
            ), traces.first())
            assertEquals(traces.first(), traces.last())
            assertEquals(0L, transaction.execute {
                entityManager.createQuery(
                    "select count(attempt) from AutomationActionAttemptEntity attempt where attempt.account.id in :accountIds",
                    Long::class.javaObjectType,
                ).setParameter("accountIds", accountIds).singleResult
            })
        } finally {
            transaction.executeWithoutResult {
                entityManager.createNativeQuery("delete from hof_accounts where id in (:accountIds)")
                    .setParameter("accountIds", accountIds).executeUpdate()
            }
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `shadow 재생성 뒤 같은 범위의 새 행동 선택은 이전 행동 대체 기록을 보존한다`() {
        val now = Instant.parse("2026-09-11T00:00:00Z")
        var currentTime = now
        val clock = TimeProvider { currentTime }
        val transaction = TransactionTemplate(transactions)
        val fixtures = listOf(false, true).map { restart ->
            restart to requireNotNull(transaction.execute { fixture("shadow-replacement-$restart", now) })
        }
        val accountIds = fixtures.map { it.second.first.id }
        try {
            val traces = fixtures.map { (restart, fixture) ->
                val (account, entry) = fixture
                val original = selection(entry.id, "original-action", "same-quest")
                currentTime = now
                var evaluator = DefaultAutomationConvergenceShadowEvaluator(clock, shadowRecorder, store)
                evaluator.selected(account.id, original)
                assertEquals(ActionConvergenceResult.PENDING, evaluator.observe(
                    account.id, original.executionIdentity,
                    AutomationActionEvidence.SameState(now, original.baselineFingerprint),
                    LegacyConvergenceDecision.RECONCILING,
                )?.newResult)
                if (restart) evaluator = DefaultAutomationConvergenceShadowEvaluator(clock, shadowRecorder, store)
                currentTime = now.plusSeconds(1)
                val next = original.copy(executionIdentity = "next-action")
                evaluator.selected(account.id, next)
                assertEquals(ActionConvergenceResult.APPLIED, evaluator.observe(
                    account.id, next.executionIdentity,
                    AutomationActionEvidence.DirectApplied(currentTime, "next-action-response"),
                    LegacyConvergenceDecision.APPLIED,
                )?.newResult)
                requireNotNull(transaction.execute {
                    entityManager.createQuery(
                        "select shadow from AutomationConvergenceShadowEvaluationEntity shadow " +
                            "where shadow.accountId = :accountId order by shadow.recordedSequence",
                        AutomationConvergenceShadowEvaluationEntity::class.java,
                    ).setParameter("accountId", account.id).resultList.map {
                        Triple(it.newResult, it.newReasonCode, it.createdAt)
                    }
                })
            }
            assertEquals(listOf(
                Triple(ActionConvergenceResult.PENDING, "AUTHORITATIVE_STATE_UNCHANGED", now),
                Triple(ActionConvergenceResult.SUPERSEDED, "AUTHORITATIVE_STATE_ADVANCED", now.plusSeconds(1)),
                Triple(ActionConvergenceResult.APPLIED, "DIRECT_RESPONSE_APPLIED", now.plusSeconds(1)),
            ), traces.first())
            assertEquals(traces.first(), traces.last(), "재시작 뒤에도 이전 행동의 대체를 누락하지 않는다")
            assertEquals(0L, transaction.execute {
                entityManager.createQuery(
                    "select count(attempt) from AutomationActionAttemptEntity attempt where attempt.account.id in :accountIds",
                    Long::class.javaObjectType,
                ).setParameter("accountIds", accountIds).singleResult
            })
        } finally {
            transaction.executeWithoutResult {
                entityManager.createNativeQuery("delete from hof_accounts where id in (:accountIds)")
                    .setParameter("accountIds", accountIds).executeUpdate()
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `종결한 이전 shadow 행동을 다시 선택해도 현재 행동의 결과 확인을 대체하지 않는다`(restart: Boolean) {
        val now = Instant.parse("2026-09-11T00:00:00Z")
        var currentTime = now
        val clock = TimeProvider { currentTime }
        val transaction = TransactionTemplate(transactions)
        val (account, entry) = requireNotNull(transaction.execute { fixture("shadow-old-selection-$restart", now) })
        try {
            val original = selection(entry.id, "old-execution", "same-quest")
            val next = original.copy(executionIdentity = "new-execution")
            var evaluator = DefaultAutomationConvergenceShadowEvaluator(clock, shadowRecorder, store)
            evaluator.selected(account.id, original)
            evaluator.observe(account.id, original.executionIdentity,
                AutomationActionEvidence.SameState(now, original.baselineFingerprint), LegacyConvergenceDecision.RECONCILING)
            currentTime = now.plusSeconds(1)
            evaluator.selected(account.id, next)
            assertEquals(ActionConvergenceResult.PENDING, evaluator.observe(account.id, next.executionIdentity,
                AutomationActionEvidence.SameState(currentTime, next.baselineFingerprint),
                LegacyConvergenceDecision.RECONCILING)?.newResult)

            if (restart) {
                evaluator = DefaultAutomationConvergenceShadowEvaluator(clock, shadowRecorder, store)
                evaluator.selected(account.id, next)
            }
            evaluator.selected(account.id, original.copy(
                actionKind = AutomationActionKind.HOME_ACCEPT,
                scope = AutomationIsolationScope(AutomationIsolationScopeKind.HOME_TARGET, "changed-scope"),
                policyVersion = "unsupported-replacement", baselineFingerprint = "changed-baseline",
                observationOnly = true,
            ))
            assertEquals(ActionConvergenceResult.APPLIED, evaluator.observe(account.id, original.executionIdentity,
                AutomationActionEvidence.DirectApplied(now, "old-original-response"), LegacyConvergenceDecision.APPLIED)?.newResult)
            evaluator.selected(account.id, next)
            currentTime = now.plusSeconds(2)
            assertEquals(ActionConvergenceResult.PENDING, evaluator.observe(account.id, next.executionIdentity,
                AutomationActionEvidence.SameState(currentTime, next.baselineFingerprint),
                LegacyConvergenceDecision.RECONCILING)?.newResult)
            transaction.executeWithoutResult {
                val rows = entityManager.createQuery(
                    "select shadow from AutomationConvergenceShadowEvaluationEntity shadow " +
                        "where shadow.accountId = :accountId order by shadow.recordedSequence",
                    AutomationConvergenceShadowEvaluationEntity::class.java,
                ).setParameter("accountId", account.id).resultList
                assertEquals(listOf(ActionConvergenceResult.PENDING, ActionConvergenceResult.SUPERSEDED,
                    ActionConvergenceResult.PENDING, ActionConvergenceResult.APPLIED, ActionConvergenceResult.PENDING),
                    rows.map { it.newResult })
                assertEquals(2, rows.last().successfulObservationCount)
                assertEquals(now.plusSeconds(1), rows.last().firstPendingAt)
                assertEquals(original.actionKind, rows[3].actionKind)
                assertEquals(original.scope.kind, rows[3].scopeKind)
                assertEquals(rows[0].scopeKeyHash, rows[3].scopeKeyHash)
                assertEquals(rows[0].baselineFingerprintHash, rows[3].baselineFingerprintHash)
                assertEquals(original.policyVersion, rows[3].policyVersion)
                assertEquals(false, rows[3].observationOnly)
                assertEquals(entry.id, rows[3].selectionEntryId)
            }
        } finally {
            transaction.executeWithoutResult {
                entityManager.createNativeQuery("delete from hof_accounts where id = :accountId")
                    .setParameter("accountId", account.id).executeUpdate()
            }
        }
    }

    @ParameterizedTest
    @CsvSource("false, false", "true, false", "false, true", "true, true")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `사용자가 해제한 보류는 항목 삭제와 shadow 재시작 뒤에도 새 행동 비교를 막지 않는다`(
        restart: Boolean,
        deleteEntry: Boolean,
    ) {
        val now = Instant.parse("2026-09-11T00:00:00Z")
        var currentTime = now
        val clock = TimeProvider { currentTime }
        val transaction = TransactionTemplate(transactions)
        val (account, entry) = requireNotNull(transaction.execute {
            fixture("shadow-user-release-$restart-$deleteEntry", now)
        })
        try {
            val original = selection(entry.id, "original-manual-release", "same-quest")
            var evaluator = DefaultAutomationConvergenceShadowEvaluator(clock, shadowRecorder, store)
            evaluator.selected(account.id, original)
            repeat(5) { index ->
                currentTime = now.plusSeconds(index.toLong())
                assertEquals(if (index == 4) ActionConvergenceResult.HELD else ActionConvergenceResult.PENDING,
                    evaluator.observe(account.id, original.executionIdentity,
                        AutomationActionEvidence.SameState(currentTime, original.baselineFingerprint),
                        LegacyConvergenceDecision.RECONCILING)?.newResult)
            }
            val canonical = DefaultAutomationActionConvergenceModule(store, clock)
            canonical.holdUnresolved(account.id, original,
                AutomationActionEvidence.ResultUnobserved(currentTime, "result budget exhausted"), 5, now)
            val attempt = assertNotNull(store.get(account.id, original.executionIdentity))
            assertEquals(setOf(original.baselineFingerprint), store.findSuppressedBaselines(account.id)[original.scope])
            currentTime = now.plusSeconds(6)
            assertTrue(canonical.allowFreshDecision(account.id, attempt.attemptId, currentTime))
            assertTrue(store.findSuppressedBaselines(account.id).isEmpty())

            val nextEntryId = if (deleteEntry) requireNotNull(transaction.execute {
                entries.delete(entry)
                entries.flush()
                entries.save(AutomationEntryEntity(
                    account = account, type = AutomationType.QUEST, priority = 0,
                    enabled = true, createdAt = currentTime, updatedAt = currentTime,
                )).id
            }) else entry.id
            if (restart) evaluator = DefaultAutomationConvergenceShadowEvaluator(clock, shadowRecorder, store)
            val next = original.copy(entryId = nextEntryId, executionIdentity = "after-manual-release")
            evaluator.selected(account.id, next)
            assertEquals(ActionConvergenceResult.APPLIED, evaluator.observe(account.id, next.executionIdentity,
                AutomationActionEvidence.DirectApplied(currentTime, "new-original-response"),
                LegacyConvergenceDecision.APPLIED)?.newResult)
            assertEquals(if (deleteEntry) null else ActionConvergenceResult.HELD, store.get(attempt.attemptId)?.result)
            assertEquals(ActionConvergenceResult.HELD, transaction.execute {
                entityManager.createQuery(
                    "select convergence.result from ActionConvergenceEntity convergence " +
                        "where convergence.attempt.id = :attemptId", ActionConvergenceResult::class.java,
                ).setParameter("attemptId", attempt.attemptId).singleResult
            })
            assertEquals(null, store.get(account.id, next.executionIdentity), "SHADOW는 production 시도를 추가하지 않는다")
        } finally {
            transaction.executeWithoutResult {
                entityManager.createNativeQuery("delete from hof_accounts where id = :accountId")
                    .setParameter("accountId", account.id).executeUpdate()
            }
        }
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
