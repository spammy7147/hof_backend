package app.spammy.hof.battle.controller

import app.spammy.hof.account.service.HofAccountService
import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.battle.dto.BattlePatternLoadRequest
import app.spammy.hof.battle.dto.BattleResultResponse
import app.spammy.hof.battle.dto.BattleSideResponse
import app.spammy.hof.battle.dto.RunBattleRequest
import app.spammy.hof.battle.service.BattleRunService
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import kotlin.test.assertEquals

class BattleRunControllerTest {
    private val battleRunService = Mockito.mock(BattleRunService::class.java)
    private val accountService = Mockito.mock(HofAccountService::class.java)
    private val controller = BattleRunController(
        battleRunService,
        HofSessionRecoveryService(accountService),
    )

    @Test
    fun expiredHofSessionReauthenticatesAndRetriesBattle() {
        val request = RunBattleRequest(
            categoryId = "battle_map",
            mapCode = "snow22",
            characterIds = listOf("1683198503393759"),
            patternLoads = listOf(BattlePatternLoadRequest(characterId = "1683198503393759", slot = 0)),
        )
        val response = BattleResultResponse(
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
        )
        Mockito.`when`(battleRunService.runBattle(accountId = 1L, request = request))
            .thenThrow(ApiException(ErrorCode.HOF_SESSION_EXPIRED, "expired"))
            .thenReturn(response)

        val actual = controller.runBattle(accountId = 1L, request = request)

        assertEquals("VICTORY", actual.outcome)
        Mockito.verify(accountService).reauthenticate(1L)
        Mockito.verify(battleRunService, Mockito.times(2)).runBattle(accountId = 1L, request = request)
    }
}
