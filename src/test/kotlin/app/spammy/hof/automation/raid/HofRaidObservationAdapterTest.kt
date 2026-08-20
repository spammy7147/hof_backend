package app.spammy.hof.automation.raid

import app.spammy.hof.account.service.HofAccountService
import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.automation.service.HofSessionRecoveryExecutor
import app.spammy.hof.battle.service.BattleMapService
import app.spammy.hof.town.fishing.dto.TownActionResultResponse
import app.spammy.hof.town.raid.dto.RaidBattleTargetResponse
import app.spammy.hof.town.raid.dto.RaidPubRaidResponse
import app.spammy.hof.town.raid.dto.RaidPubResponse
import app.spammy.hof.town.raid.model.RaidAction
import app.spammy.hof.town.raid.model.RaidStatus
import app.spammy.hof.town.raid.service.RaidPubService
import kotlin.test.Test
import kotlin.test.assertEquals
import org.mockito.Mockito

class HofRaidObservationAdapterTest {
    @Test
    fun `town 응답을 모듈 전용 관측 모델로 변환한다`() {
        val response = RaidPubResponse(
            raids = listOf(
                RaidPubRaidResponse(
                    id = "raid-a",
                    name = "레이드 A",
                    playable = true,
                    difficulty = null,
                    maxPartySize = 5,
                    rewardDamage = null,
                    status = RaidStatus.IN_BATTLE,
                    statusText = "전투 중",
                    waitSeconds = 12,
                    applicants = listOf("사용자"),
                    joined = true,
                    actions = setOf(RaidAction.START, RaidAction.RESET),
                    battleTarget = RaidBattleTargetResponse("raid", "raid001", 33),
                ),
            ),
            applied = true,
            applyWait = true,
            applyWaitSeconds = 120,
            myStatus = "신청 중",
            globalActions = setOf(RaidAction.REWARD, RaidAction.REFRESH),
            result = TownActionResultResponse("SUCCESS", listOf("적용되었습니다."), emptyList()),
        )

        val observation = HofRaidObservationAdapter(
            Mockito.mock(RaidPubService::class.java),
            Mockito.mock(BattleMapService::class.java),
            HofSessionRecoveryExecutor(HofSessionRecoveryService(Mockito.mock(HofAccountService::class.java))),
        ).from(response)

        assertEquals(true, observation.registrationWait)
        assertEquals(120, observation.registrationWaitSeconds)
        assertEquals(setOf(RaidIntentKind.REWARD, RaidIntentKind.REFRESH), observation.globalActions)
        assertEquals(listOf("적용되었습니다."), observation.resultMessages)
        assertEquals(RaidObservedStatus.IN_BATTLE, observation.raids.single().status)
        assertEquals(setOf(RaidIntentKind.START, RaidIntentKind.RESET), observation.raids.single().actions)
        assertEquals(RaidObservedBattle("raid", "raid001", 33), observation.raids.single().battle)
    }
}
