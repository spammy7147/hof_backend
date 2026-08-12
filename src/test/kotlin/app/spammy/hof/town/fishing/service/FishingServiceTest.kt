package app.spammy.hof.town.fishing.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.battle.dto.BattleMapResponse
import app.spammy.hof.battle.model.BattleMapKeyMode
import app.spammy.hof.battle.service.BattleMapService
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.external.client.AccountHofGateway
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.external.parser.LoginStateParser
import app.spammy.hof.town.common.model.TownFeatureId
import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.common.parser.HofResultParser
import app.spammy.hof.town.common.service.ResolvedTownLocation
import app.spammy.hof.town.common.service.TownActionGuard
import app.spammy.hof.town.common.service.TownAuthenticatedExecutor
import app.spammy.hof.town.common.service.TownLocationResolver
import app.spammy.hof.town.fishing.model.FishingAction
import app.spammy.hof.town.fishing.dto.FishingExchangeRequest
import app.spammy.hof.town.fishing.parser.FishingPageParser
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.mockito.Mockito

class FishingServiceTest {
    private val accounts = Mockito.mock(AccountQueryRepository::class.java)
    private val cookies = Mockito.mock(CookieQueryRepository::class.java)
    private val gateway = Mockito.mock(AccountHofGateway::class.java)
    private val locations = Mockito.mock(TownLocationResolver::class.java)
    private val battleMaps = Mockito.mock(BattleMapService::class.java)
    private val service = FishingService(
        executor = TownAuthenticatedExecutor(
            accounts, cookies, HofRequestFactory(), gateway, LoginStateParser(), HofFormParser(),
            HofResultParser(), TownActionGuard(),
        ),
        locationResolver = locations,
        parser = FishingPageParser(),
        battleMaps = battleMaps,
    )

    @Test
    fun `낚시 페이지에 전투 링크가 없어도 전투 탭에서 현재 출현한 낚시 맵을 연결한다`() {
        val html = fixture("monster.html").replace(Regex("<a href=\"\\?menu=hunt&amp;common=fishing_12\">전투</a>"), "")
        stubFishing(html)
        Mockito.`when`(battleMaps.findCurrentlyObservedMaps(7L, "battle_map"))
            .thenReturn(listOf(observedMap("fishing_eel", "Fishing- 전기 뱀장어", "낚시(적정 레벨: ??-??)")))

        val response = service.load(7L)

        assertEquals(true, response.blockedByBattle)
        assertEquals("battle_map", response.battleTarget?.categoryId)
        assertEquals("fishing_eel", response.battleTarget?.mapCode)
        assertEquals("Fishing- 전기 뱀장어", response.battleTarget?.name)
    }

    @Test
    fun `낚시 맵은 현재 관측 순서의 첫 맵 하나를 사용한다`() {
        val html = fixture("monster.html").replace(Regex("<a href=\"\\?menu=hunt&amp;common=fishing_12\">전투</a>"), "")
        stubFishing(html)
        Mockito.`when`(battleMaps.findCurrentlyObservedMaps(7L, "battle_map"))
            .thenReturn(listOf(
                observedMap("field", "고블린 부락", "일반"),
                observedMap("fishing_eel", "Fishing- 전기 뱀장어", "낚시"),
                observedMap("fishing_shark", "Fishing- 상어", "낚시"),
            ))

        val response = service.load(7L)

        assertEquals("fishing_eel", response.battleTarget?.mapCode)
    }

    @Test
    fun `전투 출몰 상태에서는 HOF 낚시 form을 제출하지 않는다`() {
        val html = fixture("monster.html")
        stubAccount()
        Mockito.`when`(locations.resolve(TownFeatureId.FISHING, null)).thenReturn(
            ResolvedTownLocation(TownFeatureId.FISHING, URL),
        )
        Mockito.`when`(gateway.execute(Mockito.eq(7L), anyRequest(), anyCookies()))
            .thenReturn(HofHttpResponse(200, URL, html, emptyMap()))

        val error = assertFailsWith<ApiException> { service.act(7L, FishingAction.START) }

        assertEquals(ErrorCode.INVALID_REQUEST, error.errorCode)
        Mockito.verify(gateway, Mockito.times(1)).execute(Mockito.eq(7L), anyRequest(), anyCookies())
    }

    @Test
    fun `낚시 교환 분류 전환은 원격 제출 없이 같은 정적 카탈로그를 투영한다`() {
        val initial = fixture("exchange.html")
        stubExchange(initial)

        val response = service.loadExchangeCategory(7L, "type_create:armor")

        assertEquals("type_create:armor", response.currentCategoryId)
        val requests = captureRequests()
        assertEquals(listOf(HofHttpMethod.GET), requests.map(HofRequest::method))
    }

    @Test
    fun `낚시 교환은 현재 분류와 실제 품목 계약 및 수량만 제출한다`() {
        val html = fixture("exchange.html")
        stubExchange(html, html)

        service.exchange(7L, FishingExchangeRequest("rank-fish", "type_create:useitem", 3))

        val posted = captureRequests().last()
        assertEquals(CREATE_URL, posted.url)
        assertEquals(listOf("ItemNo", "list_type", "ItemT", "amount", "Create", "Create"), posted.formEntries.map { it.name })
        assertEquals(listOf("rank-fish", "useitem", "0", "3", "Create", "1"), posted.formEntries.map { it.value })
    }

    @Test
    fun `정적 교환 목록 계약이 손상되면 빈 정상 응답으로 숨기지 않는다`() {
        val malformed = fixture("exchange.html").replace("case \"weapon\": html = '';", "case \"weapon\": html = window.renderCatalog();")
        stubExchange(malformed)

        val error = assertFailsWith<ApiException> { service.loadExchange(7L) }

        assertEquals(ErrorCode.HOF_REQUEST_FAILED, error.errorCode)
        assertEquals(listOf(HofHttpMethod.GET), captureRequests().map(HofRequest::method))
    }

    @Test
    fun `임의 javascript ItemT는 선택 가능하게 만들거나 제출하지 않는다`() {
        val arbitrary = fixture("exchange.html").replace(
            "document.getElementById(\"ItemT\").value=0",
            "window.pickRecipe(0)",
        )
        stubExchange(arbitrary)

        val error = assertFailsWith<ApiException> {
            service.exchange(7L, FishingExchangeRequest("rank-fish", "type_create:useitem", 3))
        }

        assertEquals(ErrorCode.INVALID_REQUEST, error.errorCode)
        assertEquals(listOf(HofHttpMethod.GET), captureRequests().map(HofRequest::method))
    }

    private fun stubAccount() {
        Mockito.`when`(accounts.findById(7L)).thenReturn(HofAccountEntity(7L, "fisher", "encrypted", Instant.EPOCH))
        Mockito.`when`(cookies.findValueMapByAccountId(7L)).thenReturn(mapOf("PHPSESSID" to "session"))
    }

    private fun stubFishing(html: String) {
        stubAccount()
        Mockito.`when`(locations.resolve(TownFeatureId.FISHING, null)).thenReturn(
            ResolvedTownLocation(TownFeatureId.FISHING, URL),
        )
        Mockito.`when`(gateway.execute(Mockito.eq(7L), anyRequest(), anyCookies()))
            .thenReturn(HofHttpResponse(200, URL, html, emptyMap()))
    }

    private fun observedMap(mapCode: String, name: String, groupName: String) = BattleMapResponse(
        categoryId = "battle_map",
        mapCode = mapCode,
        name = name,
        groupName = groupName,
        groupOrder = 0,
        mapOrder = 0,
        recommendedLevel = null,
        availableCount = null,
        attemptCount = null,
        winCount = null,
        cooldownRemainingText = null,
        cooldownRemainingSeconds = null,
        keyMode = BattleMapKeyMode.UNKNOWN,
        keyCount = null,
        requiredTime = null,
        enabled = true,
        resolved = true,
        iconUrl = null,
        rawHref = "?menu=hunt&common=$mapCode",
    )

    private fun stubExchange(vararg responses: String) {
        stubAccount()
        Mockito.`when`(locations.resolve(TownFeatureId.FISHING_EXCHANGE, null)).thenReturn(
            ResolvedTownLocation(TownFeatureId.FISHING_EXCHANGE, EXCHANGE_URL),
        )
        val values = responses.map { HofHttpResponse(200, EXCHANGE_URL, it, emptyMap()) }.toTypedArray()
        Mockito.`when`(gateway.execute(Mockito.eq(7L), anyRequest(), anyCookies()))
            .thenReturn(values.first(), *values.drop(1).toTypedArray())
    }

    private fun captureRequests(): List<HofRequest> = Mockito.mockingDetails(gateway).invocations.mapNotNull { invocation ->
        invocation.arguments.getOrNull(1) as? HofRequest
    }

    private fun fixture(name: String): String = checkNotNull(javaClass.getResource("/fixtures/town/fishing/$name")).readText()
    private fun anyRequest(): HofRequest = Mockito.any(HofRequest::class.java) ?: HofRequest(HofHttpMethod.GET, URL)
    private fun anyCookies(): Map<String, String> = Mockito.anyMap<String, String>() ?: emptyMap()

    private companion object {
        const val URL = "http://sic.zerosic.com/ZeroHOF/index.php?menu=fishing"
        const val EXCHANGE_URL = "http://sic.zerosic.com/ZeroHOF/index.php?menu=createF"
        const val CREATE_URL = "http://sic.zerosic.com/ZeroHOF/index.php?menu=createF"
    }
}
