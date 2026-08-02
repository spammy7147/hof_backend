package app.spammy.hof.town.shop

import app.spammy.hof.town.common.service.TownAuthenticatedExecutor
import app.spammy.hof.town.common.service.TownLocationResolver
import app.spammy.hof.external.model.HofRequestOrigin
import app.spammy.hof.town.common.model.ParsedTownPage
import app.spammy.hof.town.common.model.TownFeatureId
import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.common.service.ResolvedTownLocation
import app.spammy.hof.town.shop.catalog.ShopId
import app.spammy.hof.town.shop.dto.ShopResponse
import app.spammy.hof.town.shop.parser.ShopPageParser
import app.spammy.hof.town.shop.service.ShopService
import kotlin.test.Test
import kotlin.test.assertEquals
import org.mockito.Mockito

class ShopServiceTest {
    private val executor = Mockito.mock(TownAuthenticatedExecutor::class.java)
    private val locations = Mockito.mock(TownLocationResolver::class.java)
    private val parser = ShopPageParser()
    private val service = ShopService(executor, locations, parser)

    @Test
    fun `모든 상점은 사용자 HOF 페이지를 호출할 때마다 파싱한다`() {
        ShopId.entries.forEach { shopId ->
            val url = "http://sic.zerosic.com/ZeroHOF/index.php?menu=${shopId.menuCode}"
            val page = HofFormParser().parse(ShopCatalogRefreshServiceTest.SHOP_HTML, url)
            Mockito.`when`(locations.resolve(feature(shopId), null))
                .thenReturn(ResolvedTownLocation(feature(shopId), url))
            Mockito.doAnswer { invocation ->
                invocation.getArgument<(String, String, ParsedTownPage) -> ShopResponse>(3)
                    .invoke(ShopCatalogRefreshServiceTest.SHOP_HTML, url, page)
            }.`when`(executor).loadProjected(
                Mockito.eq(7L), eqString(url), anyOrigin(), anyProjector(),
            )

            val first = service.loadShop(7L, shopId)
            val second = service.loadShop(7L, shopId)

            assertEquals(listOf("Potion", "Bread"), first.items.map { it.label })
            assertEquals(first.items, second.items)
            assertEquals(shopId.pathValue, first.shopId)
            assertEquals(false, first.stale)
            Mockito.verify(executor, Mockito.times(2)).loadProjected(
                Mockito.eq(7L), eqString(url), anyOrigin(), anyProjector(),
            )
        }
    }

    private fun feature(shopId: ShopId) = when (shopId) {
        ShopId.GENERAL -> TownFeatureId.GENERAL_STORE
        ShopId.SUNDRIES -> TownFeatureId.SUNDRIES_STORE
        ShopId.DARK -> TownFeatureId.DARK_STORE
    }

    private fun anyOrigin(): HofRequestOrigin =
        Mockito.any(HofRequestOrigin::class.java) ?: HofRequestOrigin.INTERACTIVE

    private fun anyProjector(): (String, String, ParsedTownPage) -> ShopResponse =
        Mockito.any<(String, String, ParsedTownPage) -> ShopResponse>() ?: { _, _, _ -> error("unused") }

    private fun eqString(value: String): String = Mockito.eq(value) ?: value
}
