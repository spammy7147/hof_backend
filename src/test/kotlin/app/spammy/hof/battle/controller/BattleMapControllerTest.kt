package app.spammy.hof.battle.controller

import app.spammy.hof.account.service.HofAccountService
import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.battle.service.BattleMapCatalogService
import app.spammy.hof.battle.service.BattleMapService
import app.spammy.hof.battle.service.CurrentBattleMapObservation
import app.spammy.hof.battle.service.CurrentBattleMapObservationStatus
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.mockito.Mockito

class BattleMapControllerTest {
    private val mapService = Mockito.mock(BattleMapService::class.java)
    private val catalogService = Mockito.mock(BattleMapCatalogService::class.java)
    private val controller = BattleMapController(
        mapService,
        HofSessionRecoveryService(Mockito.mock(HofAccountService::class.java)),
        catalogService,
    )

    @Test
    fun `불완전한 원본 조회는 과거 카탈로그를 전투 가능한 상태로 반환하지 않는다`() {
        Mockito.`when`(mapService.observeCurrentlyAvailableMaps(1L, "battle_map"))
            .thenReturn(CurrentBattleMapObservation(CurrentBattleMapObservationStatus.INCOMPLETE, emptyList()))

        val error = assertFailsWith<ApiException> { controller.findAvailability(1L, "battle_map") }

        assertEquals(ErrorCode.HOF_REQUEST_FAILED, error.errorCode)
        Mockito.verifyNoInteractions(catalogService)
    }

    @Test
    fun `원본에서 맵 부재가 확인되면 선택 가능한 목록은 비어 있다`() {
        Mockito.`when`(mapService.observeCurrentlyAvailableMaps(1L, "union"))
            .thenReturn(CurrentBattleMapObservation(CurrentBattleMapObservationStatus.ABSENT, emptyList()))

        assertEquals(emptyList(), controller.findAvailability(1L, "union"))
        Mockito.verifyNoInteractions(catalogService)
    }
}
