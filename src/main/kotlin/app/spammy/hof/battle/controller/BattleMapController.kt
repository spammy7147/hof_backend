package app.spammy.hof.battle.controller

import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.battle.dto.BattleMapResponse
import app.spammy.hof.battle.model.BattleCategoryId
import app.spammy.hof.battle.service.BattleMapCatalogService
import app.spammy.hof.battle.service.BattleMapService
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
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
    private val catalogService: BattleMapCatalogService,
) {
    /** HOF 요청 없이 저장된 맵 전체를 먼저 보여준다. */
    @GetMapping("/{categoryId}/maps/catalog")
    fun findCatalog(
        @CurrentAccountId accountId: Long,
        @PathVariable categoryId: String,
    ): List<BattleMapResponse> {
        if (BattleCategoryId.fromValue(categoryId) == null) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "지원하지 않는 전투 카테고리입니다.")
        }
        return catalogService.findKnownByCategory(accountId, categoryId).map(BattleMapResponse::from)
    }

    /** 이번 원본 응답에서 확인된 상태만 전투 탭의 선택 가능 여부에 사용한다. */
    @GetMapping("/{categoryId}/maps/availability")
    fun findAvailability(
        @CurrentAccountId accountId: Long,
        @PathVariable categoryId: String,
    ): List<BattleMapResponse> = sessionRecoveryService.execute(accountId) {
        val observation = battleMapService.observeCurrentlyAvailableMaps(accountId, categoryId)
        if (!observation.pageComplete) {
            throw ApiException(ErrorCode.HOF_REQUEST_FAILED, "맵의 전투 가능 상태를 확인하지 못했습니다. 다시 시도해 주세요.")
        }
        observation.maps
    }

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
