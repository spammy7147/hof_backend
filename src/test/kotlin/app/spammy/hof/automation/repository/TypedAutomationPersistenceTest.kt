package app.spammy.hof.automation.repository

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.automation.entity.AutomationEntryEntity
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.BattleAutomationDailyProgressEntity
import app.spammy.hof.automation.entity.BattleAutomationMapEntity
import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.automation.entity.TypedAutomationActionRunEntity
import app.spammy.hof.automation.entity.TypedAutomationActionStatus
import app.spammy.hof.automation.entity.TypedAutomationLifecycle
import app.spammy.hof.automation.entity.TypedAutomationRuntimeStateEntity
import app.spammy.hof.automation.outbox.AutomationOutboxService
import app.spammy.hof.automation.service.StoredTypedActionPayload
import app.spammy.hof.automation.service.StoredTypedAutomationActionCodec
import app.spammy.hof.automation.service.StoredTypedAutomationActionV1
import app.spammy.hof.automation.service.TypedAutomationLifecycleBridge
import app.spammy.hof.automation.service.TypedAutomationRuntimeService
import app.spammy.hof.automation.service.TypedRuntimeClaim
import app.spammy.hof.common.persistence.QueryDslConfig
import app.spammy.hof.common.time.TimeProvider
import jakarta.persistence.EntityManager
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import org.mockito.Mockito
import tools.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles

@DataJpaTest
@ActiveProfiles("test")
@Import(QueryDslConfig::class, TypedAutomationQueryRepository::class)
class TypedAutomationPersistenceTest {
    @Autowired private lateinit var accountRepository: HofAccountRepository
    @Autowired private lateinit var entryRepository: AutomationEntryCommandRepository
    @Autowired private lateinit var battleMapRepository: BattleAutomationMapCommandRepository
    @Autowired private lateinit var battleProgressRepository: BattleAutomationDailyProgressCommandRepository
    @Autowired private lateinit var queryRepository: TypedAutomationQueryRepository
    @Autowired private lateinit var runtimeRepository: TypedAutomationRuntimeStateCommandRepository
    @Autowired private lateinit var actionRepository: TypedAutomationActionRunCommandRepository
    @Autowired private lateinit var entityManager: EntityManager

    @Test
    fun storesOneEntryPerTypeAndReadsEntriesInPriorityOrder() {
        val now = Instant.parse("2026-07-15T00:00:00Z")
        val account = newAccount("typed-order", now)
        entryRepository.saveAll(
            listOf(
                newEntry(account, AutomationType.ADVENTURE_MAP, priority = 20, now),
                newEntry(account, AutomationType.QUEST, priority = 0, now),
                newEntry(account, AutomationType.BATTLE_MAP, priority = 10, now),
            ),
        )
        entityManager.flush()
        entityManager.clear()

        assertEquals(
            listOf(AutomationType.QUEST, AutomationType.BATTLE_MAP, AutomationType.ADVENTURE_MAP),
            queryRepository.findEntries(account.id).map(AutomationEntryEntity::type),
        )
    }

    @Test
    fun recoverableRuntimeQueryIncludesRunningIdleAndDueRowsButExcludesInactiveOrNotYetDueRows() {
        val now = Instant.parse("2026-07-16T00:00:00Z")
        val idle = newAccount("typed-recovery-idle", now)
        val dueRetry = newAccount("typed-recovery-due", now)
        val expiredLease = newAccount("typed-recovery-expired-lease", now)
        val futureRetry = newAccount("typed-recovery-future", now)
        val validLease = newAccount("typed-recovery-valid-lease", now)
        val paused = newAccount("typed-recovery-paused", now)
        val stopped = newAccount("typed-recovery-stopped", now)
        runtimeRepository.saveAll(
            listOf(
                runtime(idle, TypedAutomationLifecycle.RUNNING, now),
                runtime(dueRetry, TypedAutomationLifecycle.RUNNING, now, nextAttemptAt = now),
                runtime(expiredLease, TypedAutomationLifecycle.RUNNING, now, leaseToken = "expired", leaseUntil = now),
                runtime(futureRetry, TypedAutomationLifecycle.RUNNING, now, nextAttemptAt = now.plusSeconds(1)),
                runtime(validLease, TypedAutomationLifecycle.RUNNING, now, leaseToken = "valid", leaseUntil = now.plusSeconds(1)),
                runtime(paused, TypedAutomationLifecycle.PAUSED, now),
                runtime(stopped, TypedAutomationLifecycle.STOPPED, now, stopReason = "NETWORK"),
            ),
        )
        entityManager.flush()
        entityManager.clear()

        assertEquals(
            listOf(idle.id, dueRetry.id, expiredLease.id).sorted(),
            queryRepository.findRecoverableRuntimeAccountIds(now),
        )
    }

    @Test
    fun rejectsDuplicateTypeForTheSameAccount() {
        val now = Instant.parse("2026-07-15T00:00:00Z")
        val account = newAccount("typed-duplicate", now)
        entryRepository.save(newEntry(account, AutomationType.QUEST, priority = 0, now))

        assertFailsWith<DataIntegrityViolationException> {
            entryRepository.save(newEntry(account, AutomationType.QUEST, priority = 1, now))
            entryRepository.flush()
        }
    }

    @Test
    fun scopesBattleWinsByKoreaDateSourceAndMapCode() {
        val now = Instant.parse("2026-07-15T00:00:00Z")
        val account = newAccount("typed-progress", now)
        val entry = entryRepository.save(newEntry(account, AutomationType.BATTLE_MAP, priority = 0, now))
        battleProgressRepository.save(
            BattleAutomationDailyProgressEntity(
                account = account,
                progressDate = LocalDate.parse("2026-07-15"),
                categoryId = "battle_map",
                source = "battle_map",
                mapCode = "gb0",
                successfulRuns = 4,
                updatedAt = now,
            ),
        )
        entityManager.flush()
        entityManager.clear()

        assertEquals(
            4,
            queryRepository.findBattleWins(
                accountId = account.id,
                progressDate = LocalDate.parse("2026-07-15"),
                source = "battle_map",
                mapCode = "gb0",
            ),
        )
        assertEquals(
            0,
            queryRepository.findBattleWins(
                accountId = account.id,
                progressDate = LocalDate.parse("2026-07-16"),
                source = "battle_map",
                mapCode = "gb0",
            ),
        )
    }

    @Test
    fun rejectsZeroDailyTargetForBattleAutomationMap() {
        val now = Instant.parse("2026-07-15T00:00:00Z")
        val account = newAccount("typed-zero-target", now)
        val entry = entryRepository.save(newEntry(account, AutomationType.BATTLE_MAP, priority = 0, now))

        assertFailsWith<DataIntegrityViolationException> {
            battleMapRepository.save(
                BattleAutomationMapEntity(
                    entry = entry,
                    categoryId = "battle_map",
                    mapCode = "gb0",
                    dailyTargetCount = 0,
                    presetMode = PresetSelectionMode.PRIMARY,
                    executionOrder = 0,
                ),
            )
            battleMapRepository.flush()
        }
    }

    @Test
    fun claimsDetachedPreparedActionWithFetchedEntryAndAccountForIntegrityVerification() {
        val now = Instant.parse("2026-07-16T00:00:00Z")
        val account = newAccount("typed-detached-action", now)
        val entry = entryRepository.save(newEntry(account, AutomationType.QUEST, 0, now))
        runtimeRepository.save(TypedAutomationRuntimeStateEntity(account.id, account, TypedAutomationLifecycle.RUNNING, createdAt = now, updatedAt = now))
        val codec = StoredTypedAutomationActionCodec(jacksonObjectMapper())
        val stored = StoredTypedAutomationActionV1(entry.id, "detached-execution", StoredTypedActionPayload.QuestClaim("quest", "claim"))
        val encoded = codec.encode(stored)
        actionRepository.save(
            TypedAutomationActionRunEntity(
                account = account, entry = entry, executionIdentity = stored.executionIdentity,
                actionKind = "QUEST_CLAIM", schemaVersion = StoredTypedAutomationActionCodec.SCHEMA_VERSION,
                payloadJson = encoded.json,
                actionFingerprint = encoded.fingerprint, status = TypedAutomationActionStatus.PREPARED,
                leaseToken = "old-token", createdAt = now, updatedAt = now,
            ),
        )
        entityManager.flush()
        entityManager.clear()
        val runtime = TypedAutomationRuntimeService(
            queryRepository, actionRepository, codec, TimeProvider { now },
            Mockito.mock(TypedAutomationLifecycleBridge::class.java), Mockito.mock(AutomationOutboxService::class.java),
        )

        val claim = assertIs<TypedRuntimeClaim.Acquired>(runtime.claim(account.id))
        val prepared = requireNotNull(claim.preparedAction)
        entityManager.flush()
        entityManager.clear()

        assertEquals(stored, codec.verifyPersisted(prepared, account.id))
    }

    @Test
    fun deletingEntryKeepsSubmittingActionClaimableAndStoredEntryIdentityVerifiable() {
        val now = Instant.parse("2026-07-16T00:00:00Z")
        val account = newAccount("typed-delete-active", now)
        val entry = entryRepository.save(newEntry(account, AutomationType.QUEST, 0, now))
        val codec = StoredTypedAutomationActionCodec(jacksonObjectMapper())
        val stored = StoredTypedAutomationActionV1(entry.id, "delete-active", StoredTypedActionPayload.QuestClaim("quest", "claim"))
        val encoded = codec.encode(stored)
        val actionId = actionRepository.save(
            TypedAutomationActionRunEntity(
                account = account, entry = entry, executionIdentity = stored.executionIdentity,
                actionKind = "QUEST_CLAIM", schemaVersion = StoredTypedAutomationActionCodec.SCHEMA_VERSION,
                payloadJson = encoded.json,
                actionFingerprint = encoded.fingerprint, status = TypedAutomationActionStatus.SUBMITTING,
                leaseToken = "token", createdAt = now, updatedAt = now,
            ),
        ).id
        entityManager.flush()
        entityManager.clear()

        entryRepository.delete(requireNotNull(queryRepository.findEntry(account.id, entry.id)))
        entityManager.flush()
        entityManager.clear()

        val action = requireNotNull(queryRepository.findActiveTypedAction(account.id))
        assertEquals(actionId, action.id)
        assertEquals(null, action.entry)
        assertEquals(stored, codec.verifyPersisted(action, account.id))
    }

    @Test
    fun stoppedActionLookupUsesTheExactIdAndIsAccountScoped() {
        val now = Instant.parse("2026-07-16T00:00:00Z")
        val account = newAccount("typed-stopped-action", now)
        val otherAccount = newAccount("typed-stopped-action-other", now)
        runtimeRepository.save(
            TypedAutomationRuntimeStateEntity(
                account.id,
                account,
                TypedAutomationLifecycle.STOPPED,
                stopReason = "NETWORK",
                createdAt = now,
                updatedAt = now.plusSeconds(4),
            ),
        )
        val entry = entryRepository.save(newEntry(account, AutomationType.BATTLE_MAP, 0, now))
        val otherEntry = entryRepository.save(newEntry(otherAccount, AutomationType.BATTLE_MAP, 0, now))
        actionRepository.save(
            action(account, entry, "failed", TypedAutomationActionStatus.FAILED, now.plusSeconds(1)),
        )
        val expected = actionRepository.save(
            action(account, entry, "ambiguous", TypedAutomationActionStatus.AMBIGUOUS, now.plusSeconds(2)),
        )
        actionRepository.save(
            action(account, entry, "newer-failed", TypedAutomationActionStatus.FAILED, now.plusSeconds(5)),
        )
        actionRepository.save(
            action(account, entry, "succeeded", TypedAutomationActionStatus.SUCCEEDED, now.plusSeconds(3)),
        )
        actionRepository.save(
            action(otherAccount, otherEntry, "other-failed", TypedAutomationActionStatus.FAILED, now.plusSeconds(4)),
        )
        entityManager.flush()
        entityManager.clear()

        val actual = requireNotNull(queryRepository.findStoppedTypedAction(account.id, expected.id))

        assertEquals(expected.id, actual.id)
        assertEquals(TypedAutomationActionStatus.AMBIGUOUS, actual.status)
        assertEquals(account.id, actual.account.id)
        assertEquals(null, queryRepository.findStoppedTypedAction(otherAccount.id, expected.id))
    }

    @Test
    fun exactStoppedActionSurvivesDeletionOfItsAutomationEntry() {
        val now = Instant.parse("2026-07-16T00:00:00Z")
        val account = newAccount("typed-stopped-deleted-entry", now)
        runtimeRepository.save(
            TypedAutomationRuntimeStateEntity(
                account.id,
                account,
                TypedAutomationLifecycle.STOPPED,
                stopReason = "FATAL",
                createdAt = now,
                updatedAt = now.plusSeconds(1),
            ),
        )
        val entry = entryRepository.save(newEntry(account, AutomationType.ADVENTURE_MAP, 0, now))
        val expected = actionRepository.save(
            action(account, entry, "deleted-entry", TypedAutomationActionStatus.FAILED, now.plusSeconds(1)),
        )
        entityManager.flush()
        entityManager.clear()

        entryRepository.delete(requireNotNull(queryRepository.findEntry(account.id, entry.id)))
        entityManager.flush()
        entityManager.clear()

        val actual = requireNotNull(queryRepository.findStoppedTypedAction(account.id, expected.id))
        assertEquals(expected.id, actual.id)
        assertEquals(null, actual.entry)
    }

    @Test
    fun stoppedActionLookupRejectsSucceededRows() {
        val now = Instant.parse("2026-07-16T00:00:00Z")
        val account = newAccount("typed-stopped-succeeded", now)
        val entry = entryRepository.save(newEntry(account, AutomationType.BATTLE_MAP, 0, now))
        val succeeded = actionRepository.save(
            action(account, entry, "succeeded-only", TypedAutomationActionStatus.SUCCEEDED, now),
        )
        entityManager.flush()
        entityManager.clear()

        assertEquals(null, queryRepository.findStoppedTypedAction(account.id, succeeded.id))
    }

    @Test
    fun deletingAndRecreatingBattleEntryDoesNotResetSameDayProgress() {
        val now = Instant.parse("2026-07-16T00:00:00Z")
        val account = newAccount("typed-progress-recreate", now)
        val original = entryRepository.save(newEntry(account, AutomationType.BATTLE_MAP, 0, now))
        battleProgressRepository.save(
            BattleAutomationDailyProgressEntity(
                account = account, progressDate = LocalDate.parse("2026-07-16"), categoryId = "battle_map",
                mapCode = "gb0", source = "battle_map", successfulRuns = 4, updatedAt = now,
            ),
        )
        entityManager.flush()

        entryRepository.delete(original)
        entityManager.flush()
        entryRepository.save(newEntry(account, AutomationType.BATTLE_MAP, 0, now.plusSeconds(1)))
        entityManager.flush()
        entityManager.clear()

        assertEquals(
            4,
            queryRepository.findBattleWins(
                account.id, LocalDate.parse("2026-07-16"), "battle_map", "gb0",
            ),
        )
    }

    private fun newAccount(loginId: String, now: Instant): HofAccountEntity =
        accountRepository.save(
            HofAccountEntity(loginId = loginId, encryptedPassword = "encrypted", createdAt = now),
        )

    private fun newEntry(
        account: HofAccountEntity,
        type: AutomationType,
        priority: Int,
        now: Instant,
    ) = AutomationEntryEntity(
        account = account,
        type = type,
        priority = priority,
        enabled = true,
        createdAt = now,
        updatedAt = now,
    )

    private fun runtime(
        account: HofAccountEntity,
        lifecycle: TypedAutomationLifecycle,
        now: Instant,
        nextAttemptAt: Instant? = null,
        leaseToken: String? = null,
        leaseUntil: Instant? = null,
        stopReason: String? = null,
    ) = TypedAutomationRuntimeStateEntity(
        account.id,
        account,
        lifecycle,
        stopReason = stopReason,
        nextAttemptAt = nextAttemptAt,
        leaseToken = leaseToken,
        leaseUntil = leaseUntil,
        createdAt = now,
        updatedAt = now,
    )

    private fun action(
        account: HofAccountEntity,
        entry: AutomationEntryEntity,
        identity: String,
        status: TypedAutomationActionStatus,
        now: Instant,
    ) = TypedAutomationActionRunEntity(
        account = account,
        entry = entry,
        executionIdentity = identity,
        actionKind = entry.type.name,
        schemaVersion = StoredTypedAutomationActionCodec.SCHEMA_VERSION,
        payloadJson = "{}",
        actionFingerprint = identity.padEnd(64, 'a').take(64),
        status = status,
        leaseToken = "lease-$identity",
        createdAt = now,
        finishedAt = now,
        updatedAt = now,
    )
}
