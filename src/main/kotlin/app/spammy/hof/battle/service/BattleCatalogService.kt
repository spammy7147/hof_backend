package app.spammy.hof.battle.service

import app.spammy.hof.battle.model.BattleCategory
import app.spammy.hof.battle.model.BattleCategoryId
import org.springframework.stereotype.Service

@Service
/**
 * 앱에서 지원하는 전투 카테고리 목록을 제공한다.
 */
class BattleCatalogService {
    /**
     * 전투 탭의 최상위 카테고리 목록을 반환한다.
     */
    fun findCategories(): List<BattleCategory> = categories

    private companion object {
        val categories = listOf(
            BattleCategory(
                id = BattleCategoryId.BATTLE_MAP,
                label = "전투맵",
                description = "기본 전투 맵",
                order = 10,
                enabled = true,
            ),
            BattleCategory(
                id = BattleCategoryId.ADVENTURE_MAP,
                label = "모험맵",
                description = "모험 지역 전투 맵",
                order = 20,
                enabled = true,
            ),
            BattleCategory(
                id = BattleCategoryId.UNION,
                label = "유니온",
                description = "유니온 전투",
                order = 30,
                enabled = true,
            ),
            BattleCategory(
                id = BattleCategoryId.SCENARIO_OCEAN,
                label = "시나리오-대해",
                description = "대해 시나리오 전투",
                order = 40,
                enabled = true,
            ),
            BattleCategory(
                id = BattleCategoryId.RAID,
                label = "레이드",
                description = "레이드 신청과 관리",
                order = 50,
                enabled = true,
            ),
        )
    }
}
