package app.spammy.hof.town.shop

import app.spammy.hof.town.common.service.TownAuthenticatedExecutor
import app.spammy.hof.town.common.service.TownLocationResolver
import app.spammy.hof.town.shop.catalog.ShopId
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
    private val parser = Mockito.mock(ShopPageParser::class.java)
    private val refresh = Mockito.mock(ShopCatalogRefreshService::class.java)
    private val queries = Mockito.mock(ShopQueryRepository::class.java)
    private val service = ShopService(executor, locations, parser, refresh, queries, Clock.fixed(NOW, ZoneOffset.UTC))

    @Test
    fun `모든 상점 탭은 사용자 HOF 요청이나 captcha 없이 저장된 공용 목록을 반환한다`() {
        ShopId.entries.forEach { shopId ->
            Mockito.`when`(queries.findActiveItems(shopId.name)).thenReturn(listOf(cachedItem(shopId)))
            Mockito.`when`(refresh.lastSuccessAt(shopId)).thenReturn(NOW)

            val response = service.loadShop(7L, shopId)

            assertEquals(listOf("${shopId.name} Item"), response.items.map { it.label })
            assertEquals(shopId.pathValue, response.shopId)
        }
        Mockito.verify(executor, Mockito.times(ShopId.entries.size)).requireSession(7L)
    }

    @Test
    fun `공용 목록이 없어도 사용자 계정으로 HOF를 요청하지 않는다`() {
        Mockito.`when`(queries.findActiveItems(ShopId.DARK.name)).thenReturn(emptyList())
        Mockito.`when`(refresh.lastSuccessAt(ShopId.DARK)).thenReturn(null)

        val response = service.loadShop(7L, ShopId.DARK)

        assertEquals(0, response.items.size)
        Mockito.verify(executor).requireSession(7L)
        Mockito.verify(refresh, Mockito.never()).refreshIfDue(7L, ShopId.DARK)
    }

    private fun cachedItem(shopId: ShopId) = ShopCatalogItemEntity(
        id = ShopCatalogItemId(shopId.name, "${shopId.pathValue}-item"),
        name = "${shopId.name} Item",
        price = 100,
        lastSeenAt = NOW,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-01T00:00:00Z")
    }
}
