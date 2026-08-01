package app.spammy.hof.town.fishing.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.captcha.service.CaptchaService
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
    private val captcha = Mockito.mock(CaptchaService::class.java)
    private val locations = Mockito.mock(TownLocationResolver::class.java)
    private val service = FishingService(
        executor = TownAuthenticatedExecutor(
            accounts, cookies, HofRequestFactory(), gateway, LoginStateParser(), HofFormParser(),
            HofResultParser(), TownActionGuard(), captcha,
        ),
        locationResolver = locations,
        parser = FishingPageParser(),
    )

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
    fun `낚시 교환 분류 전환은 품목 제작 action 없이 선택한 분류만 보낸다`() {
        val initial = fixture("exchange.html")
        val armor = initial
            .replace("value=\"weapon\" selected", "value=\"weapon\"")
            .replace("value=\"armor\"", "value=\"armor\" selected")
        stubExchange(initial, armor)

        val response = service.loadExchangeCategory(7L, "type_create:armor")

        assertEquals("type_create:armor", response.currentCategoryId)
        val requests = captureRequests()
        assertEquals(listOf(HofHttpMethod.GET, HofHttpMethod.POST), requests.map(HofRequest::method))
        assertEquals(listOf("type_create"), requests.last().formEntries.map { it.name })
        assertEquals(listOf("armor"), requests.last().formEntries.map { it.value })
    }

    @Test
    fun `낚시 교환은 현재 분류와 실제 품목 계약 및 수량만 제출한다`() {
        val html = fixture("exchange.html")
        stubExchange(html, html)

        service.exchange(7L, FishingExchangeRequest("rank-fish", "type_create:weapon", 3))

        val posted = captureRequests().last()
        assertEquals(listOf("type_create", "ItemT", "amount", "ItemNo", "Create"), posted.formEntries.map { it.name })
        assertEquals(listOf("weapon", "fish-17", "3", "rank-fish", "Create"), posted.formEntries.map { it.value })
    }

    private fun stubAccount() {
        Mockito.`when`(accounts.findById(7L)).thenReturn(HofAccountEntity(7L, "fisher", "encrypted", Instant.EPOCH))
        Mockito.`when`(cookies.findValueMapByAccountId(7L)).thenReturn(mapOf("PHPSESSID" to "session"))
    }

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
    }
}
