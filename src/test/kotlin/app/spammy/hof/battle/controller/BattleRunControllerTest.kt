package app.spammy.hof.battle.controller

import app.spammy.hof.battle.dto.BattlePatternLoadRequest
import app.spammy.hof.battle.dto.BattleResultResponse
import app.spammy.hof.battle.dto.BattleSideResponse
import app.spammy.hof.battle.dto.RunBattleRequest
import app.spammy.hof.battle.service.BattleRunService
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import kotlin.test.assertEquals

class BattleRunControllerTest {
    private val battleRunService = Mockito.mock(BattleRunService::class.java)
    private val controller = BattleRunController(battleRunService)

    @Test
    fun runBattleDelegatesToService() {
        val request = RunBattleRequest(
            categoryId = "battle_map",
            mapCode = "snow22",
            characterIds = listOf("1683198503393759"),
            patternLoads = listOf(BattlePatternLoadRequest(characterId = "1683198503393759", slot = 0)),
        )
        Mockito.`when`(battleRunService.runBattle(accountId = 1L, request = request))
            .thenReturn(
                BattleResultResponse(
                    outcome = "VICTORY",
                    title = "승리했다!",
                    turns = 36,
                    funds = 3660,
                    experience = 10590,
                    loots = emptyList(),
                    quest = null,
                    enemy = BattleSideResponse(0, 100, 0, 1, 20, null, null),
                    ally = BattleSideResponse(90, 100, 1, 1, 200, 36, 100),
                    rawLogUrl = null,
                ),
            )

        val response = controller.runBattle(accountId = 1L, request = request)

        assertEquals("VICTORY", response.outcome)
        assertEquals(3660, response.funds)
        Mockito.verify(battleRunService).runBattle(accountId = 1L, request = request)
    }
}
