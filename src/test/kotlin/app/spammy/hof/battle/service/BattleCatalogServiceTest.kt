package app.spammy.hof.battle.service

import app.spammy.hof.battle.model.BattleCategoryId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BattleCatalogServiceTest {
    private val service = BattleCatalogService()

    @Test
    fun findCategoriesReturnsBattleTabCategoriesInDisplayOrder() {
        val categories = service.findCategories()

        assertEquals(
            listOf(
                BattleCategoryId.BATTLE_MAP,
                BattleCategoryId.ADVENTURE_MAP,
                BattleCategoryId.UNION,
                BattleCategoryId.SCENARIO_OCEAN,
                BattleCategoryId.RAID,
            ),
            categories.map { it.id },
        )
        assertEquals(listOf(10, 20, 30, 40, 50), categories.map { it.order })
        assertTrue(categories.all { it.enabled })
    }
}
