package app.spammy.hof.battle.controller

import app.spammy.hof.battle.dto.BattleCategoryResponse
import app.spammy.hof.battle.service.BattleCatalogService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/battle/categories")
/**
 * 전투/모험/유니온 같은 큰 전투 분류 목록 API다.
 */
class BattleCatalogController(
    private val battleCatalogService: BattleCatalogService,
) {
    /**
     * 앱 전투 탭에서 처음 보여줄 카테고리 목록을 반환한다.
     */
    @GetMapping
    fun findCategories(): List<BattleCategoryResponse> =
        battleCatalogService.findCategories().map(BattleCategoryResponse::from)
}
