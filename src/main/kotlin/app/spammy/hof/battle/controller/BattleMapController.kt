package app.spammy.hof.battle.controller

import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.battle.dto.BattleMapResponse
import app.spammy.hof.battle.service.BattleMapService
import app.spammy.hof.common.security.CurrentAccountId
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/battle/categories")
/**
 * 카테고리별 전투 맵 목록 API다.
 */
class BattleMapController(
    private val battleMapService: BattleMapService,
    private val sessionRecoveryService: HofSessionRecoveryService,
) {
    /**
     * 선택한 카테고리의 맵 목록을 현재 HOF 계정 상태 기준으로 다시 파싱해 반환한다.
     */
    @GetMapping("/{categoryId}/maps")
    fun findMaps(
        @CurrentAccountId accountId: Long,
        @PathVariable categoryId: String,
    ): List<BattleMapResponse> =
        sessionRecoveryService.execute(accountId) {
            battleMapService.findMaps(accountId = accountId, categoryId = categoryId)
        }
}
