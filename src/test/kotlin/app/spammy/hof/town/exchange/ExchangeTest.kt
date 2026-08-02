package app.spammy.hof.town.exchange

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.external.client.*
import app.spammy.hof.external.model.*
import app.spammy.hof.external.parser.LoginStateParser
import app.spammy.hof.town.common.model.TownFeatureId
import app.spammy.hof.town.common.parser.*
import app.spammy.hof.town.common.service.*
import app.spammy.hof.town.exchange.dto.*
import app.spammy.hof.town.exchange.model.*
import app.spammy.hof.town.exchange.parser.ExchangePageParser
import app.spammy.hof.town.exchange.service.ExchangeService
import java.time.Instant
import kotlin.test.*
import org.mockito.Mockito

class ExchangeTest {
    private val forms = HofFormParser()
    private val parser = ExchangePageParser()

    @Test fun `교환상점은 동적 category와 radio 없는 행을 선택 불가로 보존한다`() {
        val value = parse("emblem.html", ExchangeMode.EMBLEM)
        assertEquals("전부(all)", value.categories.single { it.current }.label)
        assertFalse(value.rows.single { it.label.contains("보유 재료 부족") }.selectable)
        val muramasa = value.rows.single { it.label.contains("Muramasa") }
        assertTrue(muramasa.selectable)
        assertEquals("Muramasa (Sword)", muramasa.label)
        assertEquals("Atk:154 / h:7 / M:Metal / +6 Katana 필요", muramasa.detail)
        assertEquals(999, muramasa.maxQuantity)
    }

    @Test fun `유물 등급 교환은 대상 선택을 허용하지 않고 1개만 HOF에 위임한다`() {
        val value = parse("legacy.html", ExchangeMode.LEGACY)
        assertEquals(2, value.gradeActions.size)
        assertTrue(value.gradeActions.all { it.consumedItemsPerPress == 1 && !it.allowsTargetSelection })
        assertEquals(LEGACY_AUTOMATIC_TARGET_WARNING, value.warning)
        assertTrue(value.history.any { it.contains("Hunter's") })
    }

    @Test fun `앤의 가게는 실제 두 action과 후보만 구조화한다`() {
        val value = parse("ann.html", ExchangeMode.ANN)
        assertEquals(setOf(AnnAction.MODIFY_ITEM, AnnAction.GIVE_GIFT), value.annActions.map { it.type }.toSet())
        assertEquals("+10 Guardian's Goblin Robos Right Arm", value.annActions.single { it.type == AnnAction.MODIFY_ITEM }.rows.single().label)
        assertTrue(value.annActions.single { it.type == AnnAction.GIVE_GIFT }.rows.isEmpty())
    }

    @Test fun `동적 category 전환은 관측한 select 이름과 안전 hidden만 제출한다`() {
        val initial = fixture("emblem.html").replace("type_create", "catalog_kind")
        val transitioned = initial.replace("value=\"all\" selected", "value=\"all\"").replace("value=\"weapon\"", "value=\"weapon\" selected")
        val context = service(TownFeatureId.EMBLEM_SHOP, EMBLEM_URL, initial, transitioned)
        val response = context.service.loadCategory(7L, ExchangeMode.EMBLEM, "catalog_kind:weapon")
        assertEquals("catalog_kind:weapon", response.currentCategoryId)
        val post = context.requests().last()
        assertEquals(listOf("nonce", "catalog_kind"), post.formEntries.map { it.name })
        assertEquals(listOf("fresh-e", "weapon"), post.formEntries.map { it.value })
    }

    @Test fun `교환은 최신 GET radio의 strict ItemT와 현재 category를 scalar로 제출한다`() {
        val html = fixture("emblem.html")
        val context = service(TownFeatureId.EMBLEM_SHOP, EMBLEM_URL, html)
        context.service.trade(7L, ExchangeMode.EMBLEM, ExchangeTradeRequest("emblem-1", "type_create:all", 9))
        val post = context.requests().last()
        assertEquals(
            mapOf("nonce" to "fresh-e", "ItemT" to "37", "list_type" to "all", "amount" to "9", "ItemNo" to "mura", "Create" to "Create"),
            post.formEntries.associate { it.name to it.value },
        )
    }

    @Test fun `교환 radio의 임의 javascript는 ItemT로 실행하거나 선택 가능하게 만들지 않는다`() {
        val html = fixture("emblem.html").replace(
            "document.getElementById('ItemT').value='37'",
            "window.pickRecipe('37')",
        )
        val page = parser.parse(ExchangeMode.EMBLEM, html, BASE, forms.parse(html, BASE))
        assertFalse(page.rows.single { it.label.contains("Muramasa") }.selectable)
    }

    @Test fun `유물 등급 버튼은 후보나 대상 없이 최신 form을 한 번만 제출한다`() {
        val html = fixture("legacy.html")
        val context = service(TownFeatureId.LEGACY_SHOP, LEGACY_URL, html)
        val action = parse("legacy.html", ExchangeMode.LEGACY).gradeActions.single { it.label.startsWith("Junk") }
        context.service.exchangeLegacyGrade(7L, LegacyGradeExchangeRequest(action.id))
        val requests = context.requests()
        assertEquals(listOf(HofHttpMethod.GET, HofHttpMethod.POST), requests.map { it.method })
        assertEquals(listOf("nonce", "GradeExchange"), requests.last().formEntries.map { it.name })
        assertEquals("Junk 등급 장비!", requests.last().formEntries.last().value)
        assertFalse(requests.last().formEntries.any { it.name == "item_no" })
    }

    @Test fun `앤 action은 최신 GET의 해당 candidate만 제출한다`() {
        val html = fixture("ann.html")
        val context = service(TownFeatureId.ANN_SHOP, ANN_URL, html)
        context.service.annAction(7L, AnnActionRequest(AnnAction.GIVE_GIFT))
        assertEquals(listOf("nonce", "Create"), context.requests().last().formEntries.map { it.name })
    }

    private fun parse(name: String, mode: ExchangeMode) = fixture(name).let { parser.parse(mode, it, BASE, forms.parse(it, BASE)) }
    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/fixtures/town/exchange/$name")).readText()
    private fun service(feature: TownFeatureId, url: String, vararg html: String): Context {
        val accounts = Mockito.mock(AccountQueryRepository::class.java)
        val cookies = Mockito.mock(CookieQueryRepository::class.java)
        val gateway = Mockito.mock(AccountHofGateway::class.java)
        val locations = Mockito.mock(TownLocationResolver::class.java)
        Mockito.`when`(accounts.findById(7L)).thenReturn(HofAccountEntity(7L, "exchange", "encrypted", Instant.EPOCH))
        Mockito.`when`(cookies.findValueMapByAccountId(7L)).thenReturn(mapOf("PHPSESSID" to "session"))
        Mockito.`when`(locations.resolve(feature, null)).thenReturn(ResolvedTownLocation(feature, url))
        Mockito.`when`(gateway.execute(Mockito.eq(7L), anyRequest(), anyCookies())).thenReturn(
            HofHttpResponse(200, url, html.first(), emptyMap()),
            *html.drop(1).map { HofHttpResponse(200, url, it, emptyMap()) }.toTypedArray(),
        )
        val executor = TownAuthenticatedExecutor(accounts, cookies, HofRequestFactory(), gateway, LoginStateParser(), forms, HofResultParser(), TownActionGuard())
        return Context(ExchangeService(executor, locations, parser), gateway)
    }
    private data class Context(val service: ExchangeService, val gateway: AccountHofGateway) {
        fun requests() = Mockito.mockingDetails(gateway).invocations.mapNotNull { it.arguments.getOrNull(1) as? HofRequest }
    }
    private fun anyRequest(): HofRequest = Mockito.any(HofRequest::class.java) ?: HofRequest(HofHttpMethod.GET, BASE)
    private fun anyCookies(): Map<String, String> = Mockito.anyMap<String, String>() ?: emptyMap()
    private companion object {
        const val BASE = "http://sic.zerosic.com/ZeroHOF/index.php"
        const val EMBLEM_URL = "$BASE?menu=create2"
        const val LEGACY_URL = "$BASE?menu=legacy"
        const val ANN_URL = "$BASE?menu=ann"
    }
}
