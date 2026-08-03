package app.spammy.hof.battle.repository

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.battle.entity.BattleLogEntity
import app.spammy.hof.battle.entity.BattleLogLootEntity
import app.spammy.hof.battle.entity.BattleLogParticipantEntity
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.repository.CharacterRepository
import app.spammy.hof.common.persistence.QueryDslConfig
import jakarta.persistence.EntityManagerFactory
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.hibernate.SessionFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

@DataJpaTest
@ActiveProfiles("test")
@Import(QueryDslConfig::class, BattleLogQueryRepository::class)
class BattleLogQueryRepositoryTest {
    @Autowired
    private lateinit var accountRepository: HofAccountRepository

    @Autowired
    private lateinit var characterRepository: CharacterRepository

    @Autowired
    private lateinit var logRepository: BattleLogRepository

    @Autowired
    private lateinit var participantRepository: BattleLogParticipantCommandRepository

    @Autowired
    private lateinit var lootRepository: BattleLogLootCommandRepository

    @Autowired
    private lateinit var queryRepository: BattleLogQueryRepository

    @Autowired
    private lateinit var entityManagerFactory: EntityManagerFactory

    @Test
    fun recentUsesStableParentPaginationAndBoundsBothChildCollections() {
        val account = savedAccount("battle-query-recent")
        val character = savedCharacter(account)
        val oldest = savedLog(account, EARLIER, "VICTORY")
        val firstTie = savedLog(account, LATER, "DEFEAT")
        val secondTie = savedLog(account, LATER, "DRAW")

        listOf(oldest, firstTie, secondTie).forEachIndexed { logIndex, log ->
            participantRepository.saveAll(
                (4 downTo 0).map { slotIndex ->
                    BattleLogParticipantEntity(
                        battleLog = log,
                        slotIndex = slotIndex,
                        character = character.takeIf { slotIndex == 0 },
                        hofCharacterIdSnapshot = "log-$logIndex-hof-$slotIndex",
                        characterNameSnapshot = "log-$logIndex-name-$slotIndex",
                    )
                },
            )
            lootRepository.save(
                BattleLogLootEntity(
                    battleLog = log,
                    displayOrder = 0,
                    name = "loot-$logIndex",
                    quantity = logIndex + 1,
                    rawText = "loot-$logIndex x ${logIndex + 1}",
                ),
            )
        }
        lootRepository.flush()

        val statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics
        statistics.isStatisticsEnabled = true
        statistics.clear()

        val recent = queryRepository.findRecent(account.id, 2)
        val executedQueries = statistics.queryExecutionCount

        assertEquals(4L, executedQueries)
        assertEquals(listOf(secondTie.id, firstTie.id), recent.map { it.log.id })
        assertTrue(recent.all { it.participants.map { participant -> participant.slotIndex } == listOf(0, 1, 2, 3, 4) })
        assertEquals(listOf("log-2-hof-0", "log-1-hof-0"), recent.map { it.participants.first().hofCharacterIdSnapshot })
        assertEquals(listOf("loot-2", "loot-1"), recent.map { it.loots.single().name })
        assertTrue(recent.flatMap { it.participants }.none { it.battleLog.id == oldest.id })
        assertTrue(recent.flatMap { it.loots }.none { it.battleLog.id == oldest.id })
    }

    @Test
    fun preservesParticipantSnapshotsWhenCharacterForeignKeyIsNull() {
        val account = savedAccount("battle-query-null-character")
        val log = savedLog(account, LATER, "UNKNOWN")
        participantRepository.save(
            BattleLogParticipantEntity(
                battleLog = log,
                slotIndex = 0,
                character = null,
                hofCharacterIdSnapshot = "deleted-hof-id",
                characterNameSnapshot = "삭제된 캐릭터",
            ),
        )
        participantRepository.flush()

        val participant = queryRepository.findRecent(account.id, 1).single().participants.single()

        assertNull(participant.character)
        assertEquals("deleted-hof-id", participant.hofCharacterIdSnapshot)
        assertEquals("삭제된 캐릭터", participant.characterNameSnapshot)
        assertEquals(1L, queryRepository.countParticipants(log.id))
    }

    @Test
    fun filtersAndOffsetsRecentLogsWithoutMixingAccounts() {
        val account = savedAccount("battle-query-filter")
        val otherAccount = savedAccount("battle-query-filter-other")
        val olderDefeat = savedLog(account, EARLIER, "DEFEAT")
        val newerDefeat = savedLog(account, LATER, "DEFEAT")
        savedLog(account, LATER.plusSeconds(1), "VICTORY")
        savedLog(otherAccount, LATER.plusSeconds(2), "DEFEAT")

        val filtered = queryRepository.findRecent(account.id, limit = 1, offset = 1, outcome = "DEFEAT")

        assertEquals(listOf(olderDefeat.id), filtered.map { it.log.id })
        assertTrue(filtered.none { it.log.id == newerDefeat.id })
    }

    @Test
    fun sumsPeriodFundsAndGroupsAdventureMapDefeatsAndDraws() {
        val account = savedAccount("battle-query-periods")
        val otherAccount = savedAccount("battle-query-periods-other")
        savedLog(account, EARLIER, "VICTORY", funds = 100)
        savedLog(account, LATER, "DEFEAT", funds = 30, categoryId = "adventure_map", mapCode = "snow22", mapName = "얼어붙은 산")
        savedLog(account, LATER.plusSeconds(1), "DRAW", funds = null, categoryId = "adventure_map", mapCode = "snow22", mapName = "얼어붙은 산")
        savedLog(account, LATER.plusSeconds(2), "DEFEAT", funds = 20, categoryId = "adventure_map", mapCode = "desert01", mapName = "사막")
        savedLog(account, LATER.plusSeconds(3), "DEFEAT", categoryId = "battle_map")
        savedLog(otherAccount, LATER.plusSeconds(4), "DEFEAT", funds = 9_999, categoryId = "adventure_map")

        assertEquals(50L, queryRepository.sumFundsSince(account.id, LATER))
        assertEquals(150L, queryRepository.sumFundsSince(account.id, EARLIER))
        assertEquals(
            listOf(
                AdventureMapOutcomeStatsProjection("snow22", "얼어붙은 산", defeats = 1, draws = 1),
                AdventureMapOutcomeStatsProjection("desert01", "사막", defeats = 1, draws = 0),
            ),
            queryRepository.findAdventureMapOutcomeStats(account.id),
        )
    }

    private fun savedAccount(loginId: String): HofAccountEntity =
        accountRepository.save(
            HofAccountEntity(
                loginId = loginId,
                encryptedPassword = "encrypted",
                createdAt = EARLIER,
            ),
        )

    private fun savedCharacter(account: HofAccountEntity): CharacterEntity =
        characterRepository.save(
            CharacterEntity(
                account = account,
                hofCharacterId = "query-character",
                name = "조회 캐릭터",
                job = "검사",
                updatedAt = EARLIER,
            ),
        )

    private fun savedLog(
        account: HofAccountEntity,
        createdAt: Instant,
        outcome: String,
        funds: Int? = null,
        experience: Int? = null,
        categoryId: String = "battle_map",
        mapCode: String = "query-map",
        mapName: String = "Query Map",
    ): BattleLogEntity =
        logRepository.save(
            BattleLogEntity(
                account = account,
                battleMap = null,
                categoryIdSnapshot = categoryId,
                mapCodeSnapshot = mapCode,
                mapNameSnapshot = mapName,
                outcome = outcome,
                title = "query result",
                turns = 1,
                funds = funds,
                experience = experience,
                quest = null,
                enemyHpCurrent = null,
                enemyHpMax = null,
                enemySurvivorsAlive = null,
                enemySurvivorsMax = null,
                enemyTotalDamage = null,
                enemyTurnCurrent = null,
                enemyTurnMax = null,
                allyHpCurrent = null,
                allyHpMax = null,
                allySurvivorsAlive = null,
                allySurvivorsMax = null,
                allyTotalDamage = null,
                allyTurnCurrent = null,
                allyTurnMax = null,
                rawLogUrl = null,
                createdAt = createdAt,
            ),
        )

    private companion object {
        val EARLIER: Instant = Instant.parse("2026-07-12T00:00:00Z")
        val LATER: Instant = Instant.parse("2026-07-12T01:00:00Z")
    }
}
