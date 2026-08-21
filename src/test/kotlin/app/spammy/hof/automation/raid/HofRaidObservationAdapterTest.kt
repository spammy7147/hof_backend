package app.spammy.hof.automation.raid

import app.spammy.hof.account.service.HofAccountService
import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.automation.service.HofSessionRecoveryExecutor
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.town.fishing.dto.TownActionResultResponse
import app.spammy.hof.town.raid.dto.RaidBattleTargetResponse
import app.spammy.hof.town.raid.dto.RaidPubRaidResponse
import app.spammy.hof.town.raid.dto.RaidPubResponse
import app.spammy.hof.town.raid.model.RaidAction
import app.spammy.hof.town.raid.model.RaidBattleObservationStatus
import app.spammy.hof.town.raid.model.RaidStatus
import app.spammy.hof.town.raid.service.RaidPubService
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import org.mockito.Mockito

class HofRaidObservationAdapterTest {
    private val now = Instant.parse("2026-08-21T00:00:00Z")

    @Test
    fun `최신 레이드 관측은 RaidPubService가 수행한 단일 raid_hunt 조회만 사용한다`() {
        val raidPubService = Mockito.mock(RaidPubService::class.java)
        val response = RaidPubResponse(
            raids = emptyList(),
            applied = false,
            applyWait = false,
            applyWaitSeconds = null,
            myStatus = null,
            globalActions = emptySet(),
            result = null,
            battleObservationStatus = RaidBattleObservationStatus.INCOMPLETE,
        )
        Mockito.`when`(raidPubService.load(7L)).thenReturn(response)
        val adapter = HofRaidObservationAdapter(
            raidPubService,
            HofSessionRecoveryExecutor(HofSessionRecoveryService(Mockito.mock(HofAccountService::class.java))),
            TimeProvider { now },
        )

        val observation = adapter.read(7L)

        assertEquals(emptyList(), observation.raids)
        assertEquals(now, observation.observedAt)
        assertEquals(true, observation.fresh)

        Mockito.verify(raidPubService).load(7L)
    }

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
            battleObservationStatus = RaidBattleObservationStatus.OBSERVED,
        )

        val observation = HofRaidObservationAdapter(
            Mockito.mock(RaidPubService::class.java),
            HofSessionRecoveryExecutor(HofSessionRecoveryService(Mockito.mock(HofAccountService::class.java))),
            TimeProvider { now },
        ).from(response)

        assertEquals(true, observation.registrationWait)
        assertEquals(120, observation.registrationWaitSeconds)
        assertEquals(setOf(RaidIntentKind.REWARD, RaidIntentKind.REFRESH), observation.globalActions)
        assertEquals(listOf("적용되었습니다."), observation.resultMessages)
        assertEquals(RaidObservedStatus.IN_BATTLE, observation.raids.single().status)
        assertEquals(setOf(RaidIntentKind.START, RaidIntentKind.RESET), observation.raids.single().actions)
        assertEquals(RaidObservedBattle("raid", "raid001", 33), observation.raids.single().battle)
        assertEquals(RaidBattleAvailability.COOLDOWN, observation.raids.single().battleAvailability)
    }

    @Test
    fun `맵이 없는 최신 응답과 해석하지 못한 응답을 서로 다른 관측으로 전달한다`() {
        val target = RaidPubRaidResponse(
            id = "raid-a",
            name = "레이드 A",
            playable = true,
            difficulty = null,
            maxPartySize = 5,
            rewardDamage = null,
            status = RaidStatus.IN_BATTLE,
            statusText = "전투 중",
            waitSeconds = null,
            applicants = listOf("사용자"),
            joined = true,
            actions = emptySet(),
            battleTarget = null,
        )
        val adapter = HofRaidObservationAdapter(
            Mockito.mock(RaidPubService::class.java),
            HofSessionRecoveryExecutor(HofSessionRecoveryService(Mockito.mock(HofAccountService::class.java))),
            TimeProvider { now },
        )

        val absent = adapter.from(RaidPubResponse(
            raids = listOf(target),
            applied = false,
            applyWait = false,
            applyWaitSeconds = null,
            myStatus = null,
            globalActions = emptySet(),
            result = null,
            battleObservationStatus = RaidBattleObservationStatus.ABSENT,
        ))
        val incomplete = adapter.from(RaidPubResponse(
            raids = listOf(target),
            applied = false,
            applyWait = false,
            applyWaitSeconds = null,
            myStatus = null,
            globalActions = emptySet(),
            result = null,
            battleObservationStatus = RaidBattleObservationStatus.INCOMPLETE,
        ))

        assertEquals(RaidBattleAvailability.ABSENT, absent.raids.single().battleAvailability)
        assertEquals(RaidBattleAvailability.INCOMPLETE, incomplete.raids.single().battleAvailability)
    }
}
