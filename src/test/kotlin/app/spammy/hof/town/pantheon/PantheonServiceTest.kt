package app.spammy.hof.town.pantheon

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
import app.spammy.hof.town.pantheon.dto.PantheonActionRequest
import app.spammy.hof.town.pantheon.model.ShrineAction
import app.spammy.hof.town.pantheon.parser.PantheonParser
import app.spammy.hof.town.pantheon.service.PantheonService
import java.time.Instant
import kotlin.test.*
import org.mockito.Mockito

class PantheonServiceTest {
    private val parser = PantheonParser()
    private val forms = HofFormParser()

    @Test
    fun `신전 거리 응답은 각 temple에서 관측된 서로 다른 action만 해당 카드에 포함한다`() {
        val street = """
            <ul>
              <li>마르두크의 전당 - <a href='?menu=marduktemple'>군신 마르두크(Marduk)</a></li>
              <li>카즘의 창고 - <a href='?menu=kazmtemple'>재주꾼 카즘(Kazm)</a></li>
            </ul>
        """.trimIndent()
        val marduk = fixture("detail.html").replace("?menu=pantheon&amp;shrine=Marduk", "?menu=marduktemple")
        val kazm = """
            <h4>카즘의 창고</h4>
            <form method='post' action='?menu=kazmtemple'>
              <input type='hidden' name='nonce' value='kazm'>
              <input type='submit' name='buy' value='사제 아이템을 구입한다(12,000 Funds)'>
            </form>
        """.trimIndent()
        val context = service(STREET_URL to street, ACTUAL_DETAIL_URL to marduk, KAZM_DETAIL_URL to kazm)

        val response = context.service.street(7L)

        val mardukCard = response.shrines.single { it.name == "군신 마르두크" }
        val kazmCard = response.shrines.single { it.name == "재주꾼 카즘" }
        assertTrue(mardukCard.actions.any { it.type == ShrineAction.DONATE_PERCENT })
        assertEquals(listOf(ShrineAction.BUY_PRIEST_ITEM), kazmCard.actions.map { it.type })
        assertEquals(12_000L, kazmCard.actions.single().costFunds)
        assertEquals(listOf(STREET_URL, ACTUAL_DETAIL_URL, KAZM_DETAIL_URL), context.requests().map(HofRequest::url))
    }

    @Test
    fun `form action은 최신 거리와 상세를 다시 읽고 hidden과 submit을 한 번만 보낸다`() {
        val street = fixture("street.html")
        val detail = fixture("detail.html")
        val shrine = parser.parseStreet(street, STREET_URL).shrines.first { it.detailUrl == DETAIL_URL }
        val action = parser.parseDetail(shrine.id, detail, DETAIL_URL, forms.parse(detail, DETAIL_URL))
            .actions.single { it.type == ShrineAction.BUY_PRIEST_ITEM }
        val context = service(STREET_URL to street, DETAIL_URL to detail, DETAIL_URL to detail)

        context.service.action(7L, shrine.id, PantheonActionRequest(action.id))

        val requests = context.requests()
        assertEquals(listOf(HofHttpMethod.GET, HofHttpMethod.GET, HofHttpMethod.POST), requests.map(HofRequest::method))
        assertEquals(listOf("nonce", "buy"), requests.last().formEntries.map(HofFormField::name))
        assertEquals(listOf("a", "사제 아이템을 구입한다(10,000 Funds)"), requests.last().formEntries.map(HofFormField::value))
    }

    @Test
    fun `href action은 최신 상세에서 관측한 query만 한 번 보낸다`() {
        val street = fixture("street.html")
        val detail = fixture("detail.html")
        val shrine = parser.parseStreet(street, STREET_URL).shrines.first { it.detailUrl == DETAIL_URL }
        val action = parser.parseDetail(shrine.id, detail, DETAIL_URL, forms.parse(detail, DETAIL_URL))
            .actions.single { it.type == ShrineAction.CHECK_DOCTRINE }
        val context = service(STREET_URL to street, DETAIL_URL to detail, DETAIL_URL to detail)

        context.service.action(7L, shrine.id, PantheonActionRequest(action.id))

        val requests = context.requests()
        assertEquals(listOf(HofHttpMethod.GET, HofHttpMethod.GET, HofHttpMethod.GET), requests.map(HofRequest::method))
        assertEquals(listOf(HofFormField("doctrine", "read")), requests.last().formEntries)
    }

    @Test
    fun `신전 링크가 바뀐 오래된 shrine id는 상세 요청 전에 거부한다`() {
        val oldStreet = fixture("street.html")
        val oldId = parser.parseStreet(oldStreet, STREET_URL).shrines.first { it.detailUrl == DETAIL_URL }.id
        val changedStreet = oldStreet.replace("shrine=Marduk", "shrine=Marduk2")
        val context = service(STREET_URL to changedStreet)

        assertFailsWith<app.spammy.hof.common.error.ApiException> {
            context.service.action(7L, oldId, PantheonActionRequest("0".repeat(32)))
        }
        assertEquals(1, context.requests().size)
    }

    @Test
    fun `상세 href가 바뀐 오래된 action id는 실행 요청 전에 거부한다`() {
        val street = fixture("street.html")
        val detail = fixture("detail.html")
        val shrine = parser.parseStreet(street, STREET_URL).shrines.first { it.detailUrl == DETAIL_URL }
        val oldAction = parser.parseDetail(shrine.id, detail, DETAIL_URL, forms.parse(detail, DETAIL_URL))
            .actions.single { it.type == ShrineAction.CHECK_DOCTRINE }
        val changedDetail = detail.replace("doctrine=read", "doctrine=changed")
        val context = service(STREET_URL to street, DETAIL_URL to changedDetail)

        assertFailsWith<app.spammy.hof.common.error.ApiException> {
            context.service.action(7L, shrine.id, PantheonActionRequest(oldAction.id))
        }
        assertEquals(2, context.requests().size)
    }

    private fun service(vararg responses: Pair<String, String>): Context {
        val accounts = Mockito.mock(AccountQueryRepository::class.java)
        val cookies = Mockito.mock(CookieQueryRepository::class.java)
        val gateway = Mockito.mock(AccountHofGateway::class.java)
        val locations = Mockito.mock(TownLocationResolver::class.java)
        Mockito.`when`(accounts.findById(7L)).thenReturn(HofAccountEntity(7L, "pantheon", "encrypted", Instant.EPOCH))
        Mockito.`when`(cookies.findValueMapByAccountId(7L)).thenReturn(mapOf("PHPSESSID" to "session"))
        Mockito.`when`(locations.resolve(TownFeatureId.PANTHEON, null)).thenReturn(ResolvedTownLocation(TownFeatureId.PANTHEON, STREET_URL))
        val values = responses.map { (url, html) -> HofHttpResponse(200, url, html, emptyMap()) }.toTypedArray()
        Mockito.`when`(gateway.execute(Mockito.eq(7L), anyRequest(), anyCookies())).thenReturn(values.first(), *values.drop(1).toTypedArray())
        val executor = TownAuthenticatedExecutor(
            accounts, cookies, HofRequestFactory(), gateway, LoginStateParser(), forms,
            HofResultParser(), TownActionGuard(), app.spammy.hof.town.common.service.AccountHofMutationFence(),
        )
        return Context(PantheonService(executor, locations, parser), gateway)
    }

    private data class Context(val service: PantheonService, val gateway: AccountHofGateway) {
        fun requests() = Mockito.mockingDetails(gateway).invocations.mapNotNull { it.arguments.getOrNull(1) as? HofRequest }
    }

    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/fixtures/town/pantheon/$name")).readText()
    private fun anyRequest(): HofRequest = Mockito.any(HofRequest::class.java) ?: HofRequest(HofHttpMethod.GET, STREET_URL)
    private fun anyCookies(): Map<String, String> = Mockito.anyMap<String, String>() ?: emptyMap()

    private companion object {
        const val STREET_URL = "https://hof.zerosic.com/index.php?menu=pantheon"
        const val DETAIL_URL = "$STREET_URL&shrine=Marduk"
        const val ACTUAL_DETAIL_URL = "https://hof.zerosic.com/index.php?menu=marduktemple"
        const val KAZM_DETAIL_URL = "https://hof.zerosic.com/index.php?menu=kazmtemple"
    }
}
