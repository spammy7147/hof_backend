package app.spammy.hof.town.shop

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.external.client.AccountHofGateway
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.external.parser.LoginStateParser
import app.spammy.hof.town.common.model.TownFeatureId
import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.common.parser.HofResultParser
import app.spammy.hof.town.common.service.ResolvedTownLocation
import app.spammy.hof.town.common.service.TownActionGuard
import app.spammy.hof.town.common.service.TownAuthenticatedExecutor
import app.spammy.hof.town.common.service.TownLocationResolver
import app.spammy.hof.town.shop.catalog.ShopId
import app.spammy.hof.town.shop.entity.ShopCatalogItemEntity
import app.spammy.hof.town.shop.entity.ShopCatalogItemId
import app.spammy.hof.town.shop.parser.ShopPageParser
import app.spammy.hof.town.shop.repository.ShopQueryRepository
import app.spammy.hof.town.shop.service.ShopCatalogRefreshService
import app.spammy.hof.town.shop.service.ShopCatalogPersistenceService
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.mockito.Mockito

class ShopCatalogRefreshServiceTest {
    private val accounts = Mockito.mock(AccountQueryRepository::class.java)
    private val cookies = Mockito.mock(CookieQueryRepository::class.java)
    private val gateway = Mockito.mock(AccountHofGateway::class.java)
    private val locations = Mockito.mock(TownLocationResolver::class.java)
    private val persistence = Mockito.mock(ShopCatalogPersistenceService::class.java)
    private val queries = Mockito.mock(ShopQueryRepository::class.java)
    private val executor = TownAuthenticatedExecutor(accounts, cookies, HofRequestFactory(), gateway, LoginStateParser(), HofFormParser(), HofResultParser(), TownActionGuard(), app.spammy.hof.town.common.service.AccountHofMutationFence())
    private val service = ShopCatalogRefreshService(executor, locations, ShopPageParser(), persistence, queries, Clock.fixed(NOW, ZoneOffset.UTC))

    @Test
    fun `server lease permits only one daily HOF catalog request`() {
        stubAccount()
        Mockito.`when`(locations.resolve(TownFeatureId.GENERAL_STORE, null)).thenReturn(ResolvedTownLocation(TownFeatureId.GENERAL_STORE, URL))
        Mockito.`when`(persistence.tryAcquire(Mockito.anyString(), Mockito.anyString(), anyInstant(), anyInstant(), anyInstant(), anyInstant())).thenReturn(true, false)
        Mockito.`when`(gateway.execute(Mockito.eq(7L), anyRequest(), anyCookies())).thenReturn(HofHttpResponse(200, URL, SHOP_HTML, emptyMap()))

        service.refreshIfDue(7L, ShopId.GENERAL)
        service.refreshIfDue(8L, ShopId.GENERAL)

        Mockito.verify(gateway, Mockito.times(1)).execute(Mockito.eq(7L), anyRequest(), anyCookies())
        Mockito.verify(persistence).replaceAndMarkSuccess(eqShop(ShopId.GENERAL), eqString("SHOP_GENERAL"), Mockito.anyString(), eqInstant(NOW), anyParsedItems())
        Mockito.verify(persistence, Mockito.times(2)).tryAcquire(Mockito.anyString(), Mockito.anyString(), eqInstant(NOW), anyInstant(), eqInstant(NOW.minusSeconds(86_400)), eqInstant(NOW.minusSeconds(86_400)))
    }

    @Test
    fun `refresh failure preserves stale last-good snapshot`() {
        stubAccount()
        Mockito.`when`(locations.resolve(TownFeatureId.GENERAL_STORE, null)).thenReturn(ResolvedTownLocation(TownFeatureId.GENERAL_STORE, URL))
        Mockito.`when`(persistence.tryAcquire(Mockito.anyString(), Mockito.anyString(), anyInstant(), anyInstant(), anyInstant(), anyInstant())).thenReturn(true)
        Mockito.`when`(gateway.execute(Mockito.eq(7L), anyRequest(), anyCookies())).thenThrow(IllegalStateException("offline"))
        val old = ShopCatalogItemEntity(ShopCatalogItemId("GENERAL", "old"), "old", price = 1, active = true, lastSeenAt = NOW)
        Mockito.`when`(queries.findActiveItems("GENERAL")).thenReturn(listOf(old))

        service.refreshIfDue(7L, ShopId.GENERAL)

        Mockito.verify(persistence, Mockito.never()).replaceAndMarkSuccess(anyShop(), Mockito.anyString(), Mockito.anyString(), anyInstant(), anyParsedItems())
        Mockito.verify(persistence).releaseFailure(eqString("SHOP_GENERAL"), Mockito.anyString())
        assertEquals("old", old.id.itemKey)
    }

    @Test
    fun `captcha-like catalog text is handled as an ordinary parse failure`() {
        stubAccount()
        Mockito.`when`(locations.resolve(TownFeatureId.GENERAL_STORE, null)).thenReturn(ResolvedTownLocation(TownFeatureId.GENERAL_STORE, URL))
        Mockito.`when`(persistence.tryAcquire(Mockito.anyString(), Mockito.anyString(), anyInstant(), anyInstant(), anyInstant(), anyInstant())).thenReturn(true)
        val captchaHtml = "<p>자경단에서 통행증을 발급받아주세요.</p>"
        Mockito.`when`(gateway.execute(Mockito.eq(7L), anyRequest(), anyCookies())).thenReturn(HofHttpResponse(200, URL, captchaHtml, emptyMap()))
        Mockito.`when`(queries.findActiveItems("GENERAL")).thenReturn(listOf(
            ShopCatalogItemEntity(ShopCatalogItemId("GENERAL", "old"), "old", price = 1, lastSeenAt = NOW),
        ))

        service.refreshIfDue(7L, ShopId.GENERAL)

        Mockito.verify(persistence).releaseFailure(eqString("SHOP_GENERAL"), Mockito.anyString())
        Mockito.verify(persistence, Mockito.never()).releaseAuthenticationFailure(Mockito.anyString(), Mockito.anyString())
    }

    @Test
    fun `empty first catalog retries and then stores the first valid snapshot`() {
        stubAccount()
        Mockito.`when`(locations.resolve(TownFeatureId.GENERAL_STORE, null)).thenReturn(ResolvedTownLocation(TownFeatureId.GENERAL_STORE, URL))
        Mockito.`when`(persistence.tryAcquire(Mockito.anyString(), Mockito.anyString(), anyInstant(), anyInstant(), anyInstant(), anyInstant())).thenReturn(true, true)
        val captchaHtml = "<p>자경단에서 통행증을 발급받아주세요.</p>"
        Mockito.`when`(gateway.execute(Mockito.eq(7L), anyRequest(), anyCookies())).thenReturn(
            HofHttpResponse(200, URL, captchaHtml, emptyMap()),
            HofHttpResponse(200, URL, SHOP_HTML, emptyMap()),
        )
        Mockito.`when`(queries.findActiveItems("GENERAL")).thenReturn(emptyList())

        assertFailsWith<IllegalStateException> { service.refreshIfDue(7L, ShopId.GENERAL) }

        service.refreshIfDue(7L, ShopId.GENERAL)

        Mockito.verify(persistence).releaseFailure(eqString("SHOP_GENERAL"), Mockito.anyString())
        Mockito.verify(persistence, Mockito.never()).releaseAuthenticationFailure(Mockito.anyString(), Mockito.anyString())
        Mockito.verify(persistence, Mockito.times(2)).tryAcquire(Mockito.anyString(), Mockito.anyString(), anyInstant(), anyInstant(), anyInstant(), anyInstant())
        Mockito.verify(gateway, Mockito.times(2)).execute(Mockito.eq(7L), anyRequest(), anyCookies())
        Mockito.verify(persistence).replaceAndMarkSuccess(eqShop(ShopId.GENERAL), eqString("SHOP_GENERAL"), Mockito.anyString(), eqInstant(NOW), anyParsedItems())
    }

    @Test
    fun `expired session is released and rethrown even when a stale catalog exists`() {
        Mockito.`when`(accounts.findById(7L)).thenReturn(HofAccountEntity(7L, "shopper", "encrypted", NOW))
        Mockito.`when`(cookies.findValueMapByAccountId(7L)).thenReturn(emptyMap())
        Mockito.`when`(locations.resolve(TownFeatureId.GENERAL_STORE, null)).thenReturn(ResolvedTownLocation(TownFeatureId.GENERAL_STORE, URL))
        Mockito.`when`(persistence.tryAcquire(Mockito.anyString(), Mockito.anyString(), anyInstant(), anyInstant(), anyInstant(), anyInstant())).thenReturn(true)
        Mockito.`when`(queries.findActiveItems("GENERAL")).thenReturn(listOf(
            ShopCatalogItemEntity(ShopCatalogItemId("GENERAL", "old"), "old", price = 1, lastSeenAt = NOW),
        ))

        val error = assertFailsWith<ApiException> { service.refreshIfDue(7L, ShopId.GENERAL) }

        assertEquals(ErrorCode.HOF_SESSION_EXPIRED, error.errorCode)
        Mockito.verify(persistence).releaseAuthenticationFailure(eqString("SHOP_GENERAL"), Mockito.anyString())
    }

    private fun stubAccount() {
        Mockito.`when`(accounts.findById(7L)).thenReturn(HofAccountEntity(7L, "shopper", "encrypted", NOW))
        Mockito.`when`(cookies.findValueMapByAccountId(7L)).thenReturn(mapOf("PHPSESSID" to "session"))
    }
    private fun anyRequest(): HofRequest = Mockito.any(HofRequest::class.java) ?: HofRequest(HofHttpMethod.GET, URL)
    private fun anyCookies(): Map<String, String> = Mockito.anyMap<String, String>() ?: emptyMap()
    private fun anyInstant(): Instant = Mockito.any(Instant::class.java) ?: Instant.EPOCH
    private fun eqInstant(value: Instant): Instant = Mockito.eq(value) ?: value
    private fun anyShop(): ShopId = Mockito.any(ShopId::class.java) ?: ShopId.GENERAL
    private fun eqShop(value: ShopId): ShopId = Mockito.eq(value) ?: value
    private fun anyParsedItems(): List<app.spammy.hof.town.shop.catalog.ParsedShopItem> = Mockito.anyList<app.spammy.hof.town.shop.catalog.ParsedShopItem>() ?: emptyList()
    private fun anyAccount(): HofAccountEntity = Mockito.any(HofAccountEntity::class.java) ?: HofAccountEntity(0, "", "", Instant.EPOCH)
    private fun eqString(value: String): String = Mockito.eq(value) ?: value
    companion object {
        val NOW: Instant = Instant.parse("2026-07-31T00:00:00Z")
        const val URL = "https://hof.zerosic.com/index.php?menu=buy"
        const val SHOP_HTML = """<form action="?menu=buy" method="post"><table>
          <tr><td>$ 100</td><td><input type="checkbox" name="item[]" value="item-a"><input name="qty_a" value="1">Potion (useitem)</td></tr>
          <tr><td>$ 200</td><td><input type="checkbox" name="item[]" value="item-b"><input name="qty_b" value="1">Bread (item)</td></tr>
          </table><button name="Buy" value="Buy">Buy</button></form>"""
    }
}
