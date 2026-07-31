package app.spammy.hof.town.crafting

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.captcha.service.CaptchaService
import app.spammy.hof.external.client.AccountHofGateway
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.model.*
import app.spammy.hof.external.parser.LoginStateParser
import app.spammy.hof.town.common.model.TownFeatureId
import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.common.parser.HofResultParser
import app.spammy.hof.town.common.service.*
import app.spammy.hof.town.crafting.dto.RefineRequest
import app.spammy.hof.town.crafting.dto.WorkbaseStartRequest
import app.spammy.hof.town.crafting.model.CraftingMode
import app.spammy.hof.town.crafting.parser.CraftingPageParser
import app.spammy.hof.town.crafting.service.CraftingService
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import org.mockito.Mockito

class CraftingServiceTest {
    private val accounts = Mockito.mock(AccountQueryRepository::class.java)
    private val cookies = Mockito.mock(CookieQueryRepository::class.java)
    private val gateway = Mockito.mock(AccountHofGateway::class.java)
    private val captcha = Mockito.mock(CaptchaService::class.java)
    private val locations = Mockito.mock(TownLocationResolver::class.java)
    private val service = CraftingService(
        TownAuthenticatedExecutor(accounts, cookies, HofRequestFactory(), gateway, LoginStateParser(), HofFormParser(), HofResultParser(), TownActionGuard(), captcha),
        locations,
        CraftingPageParser(),
    )

    @Test fun `작업 시작은 최신 GET의 radio와 strict ItemT만 한 번 제출한다`() {
        val html = fixture("workbase.html")
        stub(TownFeatureId.WORKBASE, WORK_URL, html)

        service.startWorkbase(7L, WorkbaseStartRequest("work-1", "type_create:weapon", 2))

        val requests = captureRequests()
        assertEquals(listOf(HofHttpMethod.GET, HofHttpMethod.POST), requests.map(HofRequest::method))
        assertEquals(listOf("type_create", "ItemT", "amount", "ItemNo", "Create"), requests.last().formEntries.map { it.name })
        assertEquals(listOf("weapon", "17", "2", "coat01", "Create"), requests.last().formEntries.map { it.value })
    }

    @Test fun `장로대장간은 최신 timesA 기본값과 허용 timesB만 제출한다`() {
        val html = fixture("veteran.html")
        stub(TownFeatureId.VETERAN_SMITHY, VETERAN_URL, html)

        service.refine(7L, CraftingMode.VETERAN, RefineRequest("vet-1", "type:weapon", 3))

        val posted = captureRequests().last()
        assertEquals(listOf("type", "timesA", "timesB", "item_no", "refine"), posted.formEntries.map { it.name })
        assertEquals(listOf("weapon", "safe", "3", "mask09", "Refine"), posted.formEntries.map { it.value })
    }

    private fun stub(feature: TownFeatureId, url: String, html: String) {
        Mockito.`when`(accounts.findById(7L)).thenReturn(HofAccountEntity(7L, "crafter", "encrypted", Instant.EPOCH))
        Mockito.`when`(cookies.findValueMapByAccountId(7L)).thenReturn(mapOf("PHPSESSID" to "session"))
        Mockito.`when`(locations.resolve(feature, null)).thenReturn(ResolvedTownLocation(feature, url))
        Mockito.`when`(gateway.execute(Mockito.eq(7L), anyRequest(), anyCookies())).thenReturn(
            HofHttpResponse(200, url, html, emptyMap()),
            HofHttpResponse(200, url, html, emptyMap()),
        )
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
    }
}
