package app.spammy.hof.town.crafting

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.external.client.AccountHofGateway
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.model.*
import app.spammy.hof.external.parser.LoginStateParser
import app.spammy.hof.town.common.model.TownFeatureId
import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.common.parser.HofResultParser
import app.spammy.hof.town.common.service.*
import app.spammy.hof.town.crafting.dto.RefineRequest
import app.spammy.hof.town.crafting.dto.ClarisCraftRequest
import app.spammy.hof.town.crafting.dto.CreateCraftRequest
import app.spammy.hof.town.crafting.dto.WorkbaseStartRequest
import app.spammy.hof.town.crafting.model.CraftingMode
import app.spammy.hof.town.crafting.parser.CraftingPageParser
import app.spammy.hof.town.crafting.service.CraftingService
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.mockito.Mockito

class CraftingServiceTest {
    private val accounts = Mockito.mock(AccountQueryRepository::class.java)
    private val cookies = Mockito.mock(CookieQueryRepository::class.java)
    private val gateway = Mockito.mock(AccountHofGateway::class.java)
    private val locations = Mockito.mock(TownLocationResolver::class.java)
    private val service = CraftingService(
        TownAuthenticatedExecutor(accounts, cookies, HofRequestFactory(), gateway, LoginStateParser(), HofFormParser(), HofResultParser(), TownActionGuard()),
        locations,
        CraftingPageParser(),
    )

    @Test fun `작업 시작은 최신 GET의 radio와 strict ItemT만 한 번 제출한다`() {
        val html = fixture("workbase.html")
        stub(TownFeatureId.WORKBASE, WORK_URL, html)

        service.startWorkbase(7L, WorkbaseStartRequest("work-1", "type_create:weapon", 2))

        val requests = captureRequests()
        assertEquals(listOf(HofHttpMethod.GET, HofHttpMethod.POST), requests.map(HofRequest::method))
        assertEquals(listOf("nonce", "type_create", "ItemT", "amount", "ItemNo", "Create"), requests.last().formEntries.map { it.name })
        assertEquals(listOf("fresh-123", "weapon", "17", "2", "coat01", "Create"), requests.last().formEntries.map { it.value })
    }

    @Test fun `장로대장간은 최신 timesA 기본값과 고정 1회를 제출한다`() {
        val html = fixture("veteran.html")
        stub(TownFeatureId.VETERAN_SMITHY, VETERAN_URL, html)

        service.refine(7L, CraftingMode.VETERAN, RefineRequest("vet-1", "type:weapon", 1))

        val posted = captureRequests().last()
        assertEquals(listOf("type", "timesA", "timesB", "item_no", "refine"), posted.formEntries.map { it.name })
        assertEquals(listOf("weapon", "safe", "1", "mask09", "Refine"), posted.formEntries.map { it.value })
    }

    @Test fun `분류 전환은 최신 form의 opaque option과 안전한 hidden만 보내고 작업 submit은 보내지 않는다`() {
        val initial = fixture("workbase.html")
        val armor = initial
            .replace("<option value=\"weapon\" selected>", "<option value=\"weapon\">")
            .replace("<option value=\"armor\">", "<option value=\"armor\" selected>")
            .replace("id=\"work-1\"", "id=\"work-2\"")
            .replace("value=\"coat01\"", "value=\"armor01\"")
            .replace("value='17'", "value='18'")
            .replace("Striker Coat", "Knight Armor")
        stub(TownFeatureId.WORKBASE, WORK_URL, initial, armor)

        val response = service.loadCategory(7L, CraftingMode.WORKBASE, "type_create:armor")

        assertEquals("type_create:armor", response.currentCategoryId)
        assertEquals(true, response.rows.any { it.label.contains("Knight Armor") })
        val requests = captureRequests()
        assertEquals(listOf(HofHttpMethod.GET, HofHttpMethod.POST), requests.map(HofRequest::method))
        assertEquals(listOf("nonce", "type_create"), requests.last().formEntries.map { it.name })
        assertEquals(listOf("fresh-123", "armor"), requests.last().formEntries.map { it.value })
    }

    @Test fun `클라리스 분류 전환은 HOF 제출 없이 정적 목록을 선택한다`() {
        val html = fixture("claris-live.html")
        stub(TownFeatureId.SEWING_SHOP, CLARIS_URL, html)

        val response = service.loadCategory(7L, CraftingMode.CLARIS, "type_create:cloak")

        assertEquals("type_create:cloak", response.currentCategoryId)
        assertEquals(true, response.rows.single { it.label.contains("Dreamweave Cloak") }.selectable)
        assertEquals(listOf(HofHttpMethod.GET), captureRequests().map(HofRequest::method))
    }

    @Test fun `클라리스 제작은 javascript 품목을 제한적으로 변환해 실제 form으로 제출한다`() {
        val html = fixture("claris-live.html")
        stub(TownFeatureId.SEWING_SHOP, CLARIS_URL, html, html)

        service.craftClaris(7L, ClarisCraftRequest("claris-live-cloak", "type_create:cloak", 3))

        val requests = captureRequests()
        assertEquals(listOf(HofHttpMethod.GET, HofHttpMethod.POST), requests.map(HofRequest::method))
        assertEquals(listOf("ItemT", "amount", "Create", "Create", "ItemNo", "list_type"), requests.last().formEntries.map { it.name })
        assertEquals(listOf("42", "3", "Create", "Create", "cloak", "cloak"), requests.last().formEntries.map { it.value })
    }

    @Test fun `작업 완료는 자동 실행 없이 사용자가 호출할 때 최신 WSend form만 제출한다`() {
        val html = fixture("workbase-active.html")
        stub(TownFeatureId.WORKBASE, WORK_URL, html)

        service.completeWorkbase(7L)

        val requests = captureRequests()
        assertEquals(listOf(HofHttpMethod.GET, HofHttpMethod.POST), requests.map(HofRequest::method))
        assertEquals(listOf("WSend"), requests.last().formEntries.map { it.name })
    }

    @Test fun `관측되지 않은 분류 id는 HOF에 두 번째 요청을 보내기 전에 거부한다`() {
        val html = fixture("workbase.html")
        stub(TownFeatureId.WORKBASE, WORK_URL, html)

        assertFailsWith<app.spammy.hof.common.error.ApiException> {
            service.loadCategory(7L, CraftingMode.WORKBASE, "type_create:forged")
        }

        assertEquals(listOf(HofHttpMethod.GET), captureRequests().map(HofRequest::method))
    }

    @Test fun `제작공방은 추가 소재 없이도 실행하고 경고 코드를 반환한다`() {
        val html = fixture("create.html")
        stub(TownFeatureId.CREATE_WORKSHOP, CREATE_URL, html)

        val response = service.create(7L, CreateCraftRequest("recipe-1", "type_create:weapon", 2))

        assertEquals("NO_ADDITIONAL_MATERIAL", response.warningCode)
        val posted = captureRequests().last()
        assertEquals(listOf("type_create", "ItemT", "amount", "ItemNo", "Create"), posted.formEntries.map { it.name })
        assertEquals(false, posted.formEntries.any { it.name == "AddMaterial" })
    }

    private fun stub(feature: TownFeatureId, url: String, vararg responses: String) {
        Mockito.`when`(accounts.findById(7L)).thenReturn(HofAccountEntity(7L, "crafter", "encrypted", Instant.EPOCH))
        Mockito.`when`(cookies.findValueMapByAccountId(7L)).thenReturn(mapOf("PHPSESSID" to "session"))
        Mockito.`when`(locations.resolve(feature, null)).thenReturn(ResolvedTownLocation(feature, url))
        val values = responses.map { HofHttpResponse(200, url, it, emptyMap()) }.toTypedArray()
        Mockito.`when`(gateway.execute(Mockito.eq(7L), anyRequest(), anyCookies())).thenReturn(values.first(), *values.drop(1).toTypedArray())
    }
    private fun captureRequests(): List<HofRequest> {
        return Mockito.mockingDetails(gateway).invocations.mapNotNull { invocation ->
            invocation.arguments.getOrNull(1) as? HofRequest
        }
    }
    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/fixtures/town/crafting/$name")).readText()
    private fun anyRequest(): HofRequest = Mockito.any(HofRequest::class.java) ?: HofRequest(HofHttpMethod.GET, WORK_URL)
    private fun anyCookies(): Map<String, String> = Mockito.anyMap<String, String>() ?: emptyMap()
    private companion object {
        const val WORK_URL = "http://sic.zerosic.com/ZeroHOF/index.php?menu=workbase"
        const val VETERAN_URL = "http://sic.zerosic.com/ZeroHOF/index.php?menu=refine2"
        const val CREATE_URL = "http://sic.zerosic.com/ZeroHOF/index.php?menu=create"
        const val CLARIS_URL = "http://sic.zerosic.com/ZeroHOF/index.php?menu=sewingshop"
    }
}
