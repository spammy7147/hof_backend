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
    fun aggregatesOutcomesFundsExperienceAndLootQuantities() {
        val account = savedAccount("battle-query-stats")
        val otherAccount = savedAccount("battle-query-stats-other")
        val victory = savedLog(account, EARLIER, "VICTORY", funds = 100, experience = 20)
        val defeat = savedLog(account, LATER, "DEFEAT", funds = null, experience = 5)
        savedLog(account, LATER.plusSeconds(1), "DRAW", funds = 30, experience = null)
        savedLog(account, LATER.plusSeconds(2), "UNKNOWN", funds = null, experience = null)
        val foreign = savedLog(otherAccount, LATER, "VICTORY", funds = 9_999, experience = 9_999)
        lootRepository.saveAll(
            listOf(
                BattleLogLootEntity(battleLog = victory, displayOrder = 0, name = "Steel", quantity = 2, rawText = "Steel x 2"),
                BattleLogLootEntity(battleLog = victory, displayOrder = 1, name = "Bone", quantity = 1, rawText = "Bone"),
                BattleLogLootEntity(battleLog = defeat, displayOrder = 0, name = "Cloth", quantity = 4, rawText = "Cloth x 4"),
                BattleLogLootEntity(battleLog = foreign, displayOrder = 0, name = "Foreign", quantity = 100, rawText = "Foreign x 100"),
            ),
        )
        lootRepository.flush()

        val stats = queryRepository.findStats(account.id)

        assertEquals(4L, stats.totalBattles)
        assertEquals(1L, stats.victories)
        assertEquals(1L, stats.defeats)
        assertEquals(1L, stats.draws)
        assertEquals(1L, stats.unknowns)
        assertEquals(130L, stats.totalFunds)
        assertEquals(25L, stats.totalExperience)
        assertEquals(7L, queryRepository.sumLootQuantity(account.id))
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
    ): BattleLogEntity =
        logRepository.save(
            BattleLogEntity(
                account = account,
                battleMap = null,
                categoryIdSnapshot = "battle_map",
                mapCodeSnapshot = "query-map",
                mapNameSnapshot = "Query Map",
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
