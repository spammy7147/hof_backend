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
import app.spammy.hof.town.shop.entity.ShopCatalogItemEntity
import app.spammy.hof.town.shop.entity.ShopCatalogItemId
import app.spammy.hof.town.shop.parser.ShopPageParser
import app.spammy.hof.town.shop.repository.ShopQueryRepository
import app.spammy.hof.town.shop.service.ShopCatalogRefreshService
import app.spammy.hof.town.shop.service.ShopService
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import org.mockito.Mockito

class ShopServiceTest {
    private val executor = Mockito.mock(TownAuthenticatedExecutor::class.java)
    private val locations = Mockito.mock(TownLocationResolver::class.java)
    private val parser = ShopPageParser()
    private val refresh = Mockito.mock(ShopCatalogRefreshService::class.java)
    private val queries = Mockito.mock(ShopQueryRepository::class.java)
    private val service = ShopService(executor, locations, parser, refresh, queries, Clock.fixed(NOW, ZoneOffset.UTC))

    @Test
    fun `일반상점과 잡화점은 사용자 HOF 요청 없이 저장된 공용 목록을 반환한다`() {
        listOf(ShopId.GENERAL, ShopId.SUNDRIES).forEach { shopId ->
            Mockito.`when`(queries.findActiveItems(shopId.name)).thenReturn(listOf(cachedItem(shopId)))
            Mockito.`when`(refresh.lastSuccessAt(shopId)).thenReturn(NOW)

            val response = service.loadShop(7L, shopId)

            assertEquals(listOf("${shopId.name} Item"), response.items.map { it.label })
            assertEquals(shopId.pathValue, response.shopId)
        }
        Mockito.verifyNoInteractions(executor)
    }

    @Test
    fun `암흑상점은 공용 목록 대신 사용자 HOF 페이지를 매번 파싱한다`() {
        val page = HofFormParser().parse(ShopCatalogRefreshServiceTest.SHOP_HTML, DARK_URL)
        Mockito.`when`(locations.resolve(TownFeatureId.DARK_STORE, null))
            .thenReturn(ResolvedTownLocation(TownFeatureId.DARK_STORE, DARK_URL))
        Mockito.doAnswer { invocation ->
            invocation.getArgument<(String, String, ParsedTownPage) -> ShopResponse>(3)
                .invoke(ShopCatalogRefreshServiceTest.SHOP_HTML, DARK_URL, page)
        }.`when`(executor).loadProjected(
            Mockito.eq(7L),
            eqString(DARK_URL),
            anyOrigin(),
            anyProjector(),
        )

        val first = service.loadShop(7L, ShopId.DARK)
        val second = service.loadShop(7L, ShopId.DARK)

        assertEquals(listOf("Potion", "Bread"), first.items.map { it.label })
        assertEquals(first.items, second.items)
        assertEquals(false, first.stale)
        Mockito.verify(executor, Mockito.times(2)).loadProjected(
            Mockito.eq(7L), eqString(DARK_URL), anyOrigin(), anyProjector(),
        )
        Mockito.verify(queries, Mockito.never()).findActiveItems(ShopId.DARK.name)
    }

    private fun cachedItem(shopId: ShopId) = ShopCatalogItemEntity(
        id = ShopCatalogItemId(shopId.name, "${shopId.pathValue}-item"),
        name = "${shopId.name} Item",
        price = 100,
        lastSeenAt = NOW,
    )

    private fun anyOrigin(): HofRequestOrigin =
        Mockito.any(HofRequestOrigin::class.java) ?: HofRequestOrigin.INTERACTIVE

    private fun anyProjector(): (String, String, ParsedTownPage) -> ShopResponse =
        Mockito.any<(String, String, ParsedTownPage) -> ShopResponse>() ?: { _, _, _ -> error("unused") }

    private fun eqString(value: String): String = Mockito.eq(value) ?: value

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-01T00:00:00Z")
        const val DARK_URL = "http://sic.zerosic.com/ZeroHOF/index.php?menu=sbuy"
    }
}
