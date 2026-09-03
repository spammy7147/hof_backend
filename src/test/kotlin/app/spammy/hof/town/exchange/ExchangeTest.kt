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

    @Test fun `실제 교환상점의 분리된 category form과 정적 품목 목록을 구조화한다`() {
        val html = fixture("emblem-live-categories.html")
        val initial = parser.parse(ExchangeMode.EMBLEM, html, BASE, forms.parse(html, BASE))
        assertEquals(listOf("무기(weapon)", "방어구(armor)", "전부(all)"), initial.categories.map { it.label })
        assertEquals("type_create:weapon", initial.currentCategoryId)
        assertEquals(listOf("Muramasa (Sword)"), initial.rows.map { it.label })

        val armor = parser.parse(
            ExchangeMode.EMBLEM,
            html,
            BASE,
            forms.parse(html, BASE),
            categoryCandidateId = "type_create:armor",
        )
        assertEquals("type_create:armor", armor.currentCategoryId)
        assertEquals(listOf("Guardian Plate (Armor)"), armor.rows.map { it.label })
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

    @Test fun `정적 category 전환은 HOF에 POST하지 않고 해당 품목을 구조화한다`() {
        val html = fixture("emblem-live-categories.html")
        val context = service(TownFeatureId.EMBLEM_SHOP, EMBLEM_URL, html)
        val response = context.service.loadCategory(7L, ExchangeMode.EMBLEM, "type_create:armor")
        assertEquals("type_create:armor", response.currentCategoryId)
        assertEquals(listOf("Guardian Plate (Armor)"), response.rows.map { it.label })
        assertEquals(listOf(HofHttpMethod.GET), context.requests().map { it.method })
    }

    @Test fun `교환은 선택 category를 물질화하고 최신 GET radio의 strict ItemT를 제출한다`() {
        val html = fixture("emblem-live-categories.html")
        val context = service(TownFeatureId.EMBLEM_SHOP, EMBLEM_URL, html)
        val all = parser.parse(
            ExchangeMode.EMBLEM,
            html,
            BASE,
            forms.parse(html, BASE),
            categoryCandidateId = "type_create:all",
        )
        val muramasa = all.rows.single { it.label == "Muramasa (Sword)" }
        context.service.trade(7L, ExchangeMode.EMBLEM, ExchangeTradeRequest(muramasa.id, "type_create:all", 9))
        val post = context.requests().last()
        assertEquals(
            mapOf("ItemT" to "37", "list_type" to "all", "ItemNo" to "mura", "amount" to "9", "Create" to "Create"),
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
        val executor = TownAuthenticatedExecutor(accounts, cookies, HofRequestFactory(), gateway, LoginStateParser(), forms, HofResultParser(), TownActionGuard(), app.spammy.hof.town.common.service.AccountHofMutationFence())
        return Context(ExchangeService(executor, locations, parser), gateway)
    }
    private data class Context(val service: ExchangeService, val gateway: AccountHofGateway) {
        fun requests() = Mockito.mockingDetails(gateway).invocations.mapNotNull { it.arguments.getOrNull(1) as? HofRequest }
    }
    private fun anyRequest(): HofRequest = Mockito.any(HofRequest::class.java) ?: HofRequest(HofHttpMethod.GET, BASE)
    private fun anyCookies(): Map<String, String> = Mockito.anyMap<String, String>() ?: emptyMap()
    private companion object {
        const val BASE = "https://hof.zerosic.com/index.php"
        const val EMBLEM_URL = "$BASE?menu=create2"
        const val LEGACY_URL = "$BASE?menu=legacy"
        const val ANN_URL = "$BASE?menu=ann"
    }
}
