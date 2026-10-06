package app.spammy.hof.battle.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.battle.dto.RunBattleRequest
import app.spammy.hof.battle.entity.BattleMapEntity
import app.spammy.hof.battle.repository.BattleLogQueryRepository
import app.spammy.hof.battle.repository.BattleMapQueryRepository
import app.spammy.hof.battle.repository.BattleMapRepository
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.repository.CharacterRepository
import app.spammy.hof.common.persistence.QueryDslConfig
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.model.HofBattleLoot
import app.spammy.hof.external.model.HofBattleOutcome
import app.spammy.hof.external.model.HofBattleResult
import app.spammy.hof.external.model.HofBattleSide
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

@DataJpaTest
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
    QueryDslConfig::class,
    BattleMapQueryRepository::class,
    BattleLogQueryRepository::class,
    BattleLogService::class,
    BattleLogServiceTest.FixedTimeConfig::class,
)
class BattleLogServiceTest {
    @Autowired
    private lateinit var accountRepository: HofAccountRepository

    @Autowired
    private lateinit var characterRepository: CharacterRepository

    @Autowired
    private lateinit var mapRepository: BattleMapRepository

    @Autowired
    private lateinit var queryRepository: BattleLogQueryRepository

    @Autowired
    private lateinit var service: BattleLogService

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun recordPersistsParentParticipantsAndLootsInExactOrder() {
        val account = savedAccount("battle-log-record")
        val battleMap = savedMap("battle_map", "test-snow22", "Frosty Mountain")
        val characters = (0 until 5).map { index ->
            characterRepository.save(
                CharacterEntity(
                    account = account,
                    hofCharacterId = "hof-$index",
                    name = "참가자-$index",
                    job = "검사",
                    updatedAt = NOW,
                ),
            )
        }

        val response = service.record(
            account = account,
            request = request(battleMap.categoryId, battleMap.mapCode, characters),
            characters = characters,
            result = result(
                outcome = HofBattleOutcome.VICTORY,
                funds = 3_660,
                experience = 10_590,
                loots = listOf(
                    HofBattleLoot(name = "Steel Ingot", quantity = 2, rawText = "Steel Ingot x 2"),
                    HofBattleLoot(name = "Bone", quantity = 1, rawText = "Bone"),
                ),
            ),
        )

        inTransaction {
            val stored = queryRepository.findRecent(account.id, 20).single()
            assertEquals(battleMap.id, stored.log.battleMap?.id)
            assertEquals("battle_map", stored.log.categoryIdSnapshot)
            assertEquals("test-snow22", stored.log.mapCodeSnapshot)
            assertEquals("Frosty Mountain", stored.log.mapNameSnapshot)
            assertEquals(listOf(0, 1, 2, 3, 4), stored.participants.map { it.slotIndex })
            assertEquals(characters.map { it.id }, stored.participants.map { it.character?.id })
            assertEquals(characters.map { it.hofCharacterId }, stored.participants.map { it.hofCharacterIdSnapshot })
            assertEquals(characters.map { it.name }, stored.participants.map { it.characterNameSnapshot })
            assertEquals(listOf(0, 1), stored.loots.map { it.displayOrder })
            assertEquals(listOf("Steel Ingot", "Bone"), stored.loots.map { it.name })
            assertEquals(listOf(2, 1), stored.loots.map { it.quantity })
            assertEquals(listOf("Steel Ingot x 2", "Bone"), stored.loots.map { it.rawText })
            assertEquals(5L, queryRepository.countParticipants(stored.log.id))
        }
        assertEquals("Frosty Mountain", response.mapName)
        assertEquals(characters.map { it.hofCharacterId }, response.characterIds)
        assertEquals(characters.map { it.name }, response.characterNames)
        assertEquals("VICTORY", response.outcome)
        assertEquals(3_660, response.funds)
        assertEquals(10_590, response.experience)
        assertEquals(listOf("Steel Ingot x 2", "Bone"), response.loots.map { it.name })
        assertEquals(NOW.toString(), response.createdAt)

        val stats = service.summarize(account.id)
        assertEquals(3_660L, stats.dailyFunds)
        assertEquals(3_660L, stats.weeklyFunds)
        assertEquals(3_660L, stats.monthlyFunds)
        assertEquals(1L, stats.dailyBattles)
        assertEquals(1L, stats.weeklyBattles)
        assertEquals(1L, stats.monthlyBattles)
        assertEquals(emptyList(), stats.adventureMapOutcomes)
    }

    @Test
    fun recordKeepsRequestSnapshotsWhenCatalogMapIsUnavailable() {
        val account = savedAccount("battle-log-missing-map")
        val character = characterRepository.save(
            CharacterEntity(
                account = account,
                hofCharacterId = "missing-map-character",
                name = "기록 보존 참가자",
                job = "검사",
                updatedAt = NOW,
            ),
        )

        val response = service.record(
            account = account,
            request = request("unknown_category", "unknown-code", listOf(character)),
            characters = listOf(character),
            result = result(HofBattleOutcome.UNKNOWN),
        )

        assertEquals("unknown-code", response.mapName)

        inTransaction {
            val stored = queryRepository.findRecent(account.id, 1).single().log
            assertNull(stored.battleMap)
            assertEquals("unknown_category", stored.categoryIdSnapshot)
            assertEquals("unknown-code", stored.mapCodeSnapshot)
            assertEquals("unknown-code", stored.mapNameSnapshot)
        }
    }

    @Test
    fun `패배 HTML은 재조회 가능하며 다른 계정과 승리에는 보관본을 제공하지 않는다`() {
        val account = savedAccount("html-archive")
        val other = savedAccount("html-other")
        val source = """<html><body><div style="display:none" onclick="evil()">1턴 공격 상세</div><table><tr><td>패배</td></tr></table><script>secret()</script><form><input value="password"></form><img src="https://example.com/tracker"></body></html>"""
        val defeat = service.record(account, request("battle_map", "snow22", emptyList()), emptyList(), result(HofBattleOutcome.DEFEAT), source)
        assertTrue(defeat.hasArchivedHtml)
        val html = service.findArchivedHtml(account.id, defeat.id)
        assertTrue(html.contains("1턴 공격 상세"))
        assertTrue(html.contains("<table>"))
        assertFalse(html.contains("display:none"))
        assertFalse(html.contains("onclick"))
        assertFalse(html.contains("secret()"))
        assertFalse(html.contains("password"))
        assertFalse(html.contains("tracker"))
        val forbidden = assertFailsWith<ApiException> { service.findArchivedHtml(other.id, defeat.id) }
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, forbidden.errorCode)
        val victory = service.record(account, request("battle_map", "snow22", emptyList()), emptyList(), result(HofBattleOutcome.VICTORY), source)
        assertFalse(victory.hasArchivedHtml)
        assertFailsWith<ApiException> { service.findArchivedHtml(account.id, victory.id) }
        assertTrue(service.findRecent(account.id).single { it.id == defeat.id }.hasArchivedHtml)
    }

    @Test
    fun fundPeriodsStartAtKoreaMidnightMondayAndFirstDayOfMonth() {
        val periods = battleFundPeriodStarts(Instant.parse("2026-08-05T03:00:00Z"))

        assertEquals(Instant.parse("2026-08-04T15:00:00Z"), periods.day)
        assertEquals(Instant.parse("2026-08-02T15:00:00Z"), periods.week)
        assertEquals(Instant.parse("2026-07-31T15:00:00Z"), periods.month)
    }

    private fun savedAccount(loginId: String): HofAccountEntity =
        accountRepository.save(
            HofAccountEntity(
                loginId = loginId,
                encryptedPassword = "encrypted",
                createdAt = NOW,
            ),
        )

    private fun savedMap(
        categoryId: String,
        mapCode: String,
        name: String,
    ): BattleMapEntity =
        mapRepository.save(
            BattleMapEntity(
                categoryId = categoryId,
                mapCode = mapCode,
                name = name,
                normalizedName = name.lowercase(),
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )

    private fun request(
        categoryId: String,
        mapCode: String,
        characters: List<CharacterEntity>,
    ): RunBattleRequest =
        RunBattleRequest(
            categoryId = categoryId,
            mapCode = mapCode,
            characterIds = characters.map { it.hofCharacterId },
        )

    private fun result(
        outcome: HofBattleOutcome,
        funds: Int? = null,
        experience: Int? = null,
        loots: List<HofBattleLoot> = emptyList(),
    ): HofBattleResult =
        HofBattleResult(
            outcome = outcome,
            title = "전투 결과",
            turns = 5,
            funds = funds,
            experience = experience,
            loots = loots,
            quest = null,
            enemySide = HofBattleSide.EMPTY,
            allySide = HofBattleSide.EMPTY,
        )

    private fun inTransaction(block: () -> Unit) {
        TransactionTemplate(transactionManager).executeWithoutResult { block() }
    }

    @TestConfiguration
    class FixedTimeConfig {
        @Bean
        fun timeProvider(): TimeProvider = TimeProvider { NOW }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-12T00:00:00Z")
    }
}
