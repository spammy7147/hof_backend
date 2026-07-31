package app.spammy.hof.town.shop

import app.spammy.hof.town.shop.catalog.ParsedShopItem
import app.spammy.hof.town.shop.catalog.ShopId
import app.spammy.hof.town.shop.entity.ShopCatalogItemEntity
import app.spammy.hof.town.shop.repository.ShopCatalogItemRepository
import app.spammy.hof.town.shop.repository.ShopQueryRepository
import app.spammy.hof.town.shop.service.ShopCatalogPersistenceService
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.mockito.Mockito
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

class ShopCatalogPersistenceServiceTest {
    private val catalog = Mockito.mock(ShopCatalogItemRepository::class.java)
    private val queries = Mockito.mock(ShopQueryRepository::class.java)
    private val service = ShopCatalogPersistenceService(catalog, queries)

    @Test
    fun `snapshot replacement is one requires-new transaction and does not mark success after a write failure`() {
        val annotation = ShopCatalogPersistenceService::class.java
            .getMethod("replaceAndMarkSuccess", ShopId::class.java, String::class.java, String::class.java, Instant::class.java, List::class.java)
            .getAnnotation(Transactional::class.java)
        assertEquals(Propagation.REQUIRES_NEW, annotation.propagation)
        Mockito.`when`(catalog.saveAll(anyCatalogItems())).thenThrow(IllegalStateException("write failed"))

        assertFailsWith<IllegalStateException> {
            service.replaceAndMarkSuccess(ShopId.GENERAL, "SHOP_GENERAL", "owner", Instant.EPOCH, listOf(ParsedShopItem("a", "A", null, null, 1)))
        }

        Mockito.verify(queries).deactivateAll("GENERAL")
        Mockito.verify(queries, Mockito.never()).markSuccess(Mockito.anyString(), Mockito.anyString(), anyInstant())
    }

    private fun anyCatalogItems(): Iterable<ShopCatalogItemEntity> = Mockito.any<Iterable<ShopCatalogItemEntity>>() ?: emptyList()
    private fun anyInstant(): Instant = Mockito.any(Instant::class.java) ?: Instant.EPOCH
}
