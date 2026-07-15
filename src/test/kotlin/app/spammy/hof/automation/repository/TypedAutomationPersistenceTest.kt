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
                actionKind = "QUEST_CLAIM", schemaVersion = 1, payloadJson = encoded.json,
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
}
