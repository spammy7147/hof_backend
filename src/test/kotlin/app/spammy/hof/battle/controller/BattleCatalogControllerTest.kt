package app.spammy.hof.battle.controller

import app.spammy.hof.battle.model.BattleCategory
import app.spammy.hof.battle.model.BattleCategoryId
import app.spammy.hof.battle.service.BattleCatalogService
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import kotlin.test.assertEquals

class BattleCatalogControllerTest {
    private val battleCatalogService = Mockito.mock(BattleCatalogService::class.java)
    private val controller = BattleCatalogController(battleCatalogService)

    @Test
    fun findCategoriesDelegatesToService() {
        Mockito.`when`(battleCatalogService.findCategories())
            .thenReturn(
                listOf(
                    BattleCategory(
                        id = BattleCategoryId.BATTLE_MAP,
                        label = "전투맵",
                        description = "일반 전투 맵",
                        order = 10,
                        enabled = true,
                    ),
                ),
            )

        val response = controller.findCategories()

        assertEquals(1, response.size)
        assertEquals("battle_map", response[0].id)
        assertEquals("전투맵", response[0].label)
        Mockito.verify(battleCatalogService).findCategories()
    }
}
