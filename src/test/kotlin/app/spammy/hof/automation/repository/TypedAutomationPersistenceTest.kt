package app.spammy.hof.automation.repository

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.automation.entity.AutomationEntryEntity
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.BattleAutomationDailyProgressEntity
import app.spammy.hof.common.persistence.QueryDslConfig
import jakarta.persistence.EntityManager
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    @Autowired private lateinit var battleProgressRepository: BattleAutomationDailyProgressCommandRepository
    @Autowired private lateinit var queryRepository: TypedAutomationQueryRepository
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
