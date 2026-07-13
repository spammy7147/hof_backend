package app.spammy.hof.battle.controller

import app.spammy.hof.battle.dto.BattleLogResponse
import app.spammy.hof.battle.dto.BattleSideResponse
import app.spammy.hof.battle.dto.BattleStatsResponse
import app.spammy.hof.battle.service.BattleLogService
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import kotlin.test.assertEquals

class BattleLogControllerTest {
    private val battleLogService = Mockito.mock(BattleLogService::class.java)
    private val controller = BattleLogController(battleLogService)

    @Test
    fun findRecentLogsDelegatesToService() {
        Mockito.`when`(battleLogService.findRecent(accountId = 1L, limit = 10))
            .thenReturn(
                listOf(
                    BattleLogResponse(
                        id = 99L,
                        accountId = 1L,
                        categoryId = "battle_map",
                        mapCode = "snow22",
                        characterIds = listOf("1683198503393759"),
                        characterNames = listOf("소셜"),
                        outcome = "VICTORY",
                        title = "소셜은(는) 승리했다!",
                        turns = 36,
                        funds = 3660,
                        experience = 10590,
                        loots = emptyList(),
                        quest = null,
                        enemy = BattleSideResponse(null, null, null, null, null, null, null),
                        ally = BattleSideResponse(null, null, null, null, null, null, null),
                        rawLogUrl = null,
                        createdAt = "2026-07-08T00:00:00Z",
                    ),
                ),
            )

        val response = controller.findRecentLogs(accountId = 1L, limit = 10)

        assertEquals(1, response.size)
        assertEquals("snow22", response[0].mapCode)
        Mockito.verify(battleLogService).findRecent(accountId = 1L, limit = 10)
    }

    @Test
    fun summarizeDelegatesToService() {
        Mockito.`when`(battleLogService.summarize(1L))
            .thenReturn(
                BattleStatsResponse(
                    accountId = 1L,
                    totalBattles = 10,
                    victories = 7,
                    defeats = 2,
                    draws = 1,
                    unknowns = 0,
                    winRate = 0.7,
                    totalFunds = 12300,
                    totalExperience = 999,
                    totalLootCount = 4,
                ),
            )

        val response = controller.summarize(accountId = 1L)

        assertEquals(10, response.totalBattles)
        assertEquals(0.7, response.winRate)
        Mockito.verify(battleLogService).summarize(1L)
    }
}
