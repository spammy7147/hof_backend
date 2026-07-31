package app.spammy.hof.quest.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.captcha.service.CaptchaService
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.external.client.AccountHofGateway
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.external.model.HofRequestOrigin
import app.spammy.hof.external.parser.LoginStateParser
import app.spammy.hof.quest.parser.QuestPageParser
import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.common.parser.HofResultParser
import app.spammy.hof.town.common.service.TownActionGuard
import app.spammy.hof.town.common.service.TownAuthenticatedExecutor
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.mockito.Mockito

class QuestGatewayServiceTest {
    private val accounts = Mockito.mock(AccountQueryRepository::class.java)
    private val cookies = Mockito.mock(CookieQueryRepository::class.java)
    private val gateway = Mockito.mock(AccountHofGateway::class.java)
    private val captcha = Mockito.mock(CaptchaService::class.java)
    private val service = QuestGatewayService(
        TownAuthenticatedExecutor(
            accounts,
            cookies,
            HofRequestFactory(),
            gateway,
            LoginStateParser(),
            HofFormParser(),
            HofResultParser(),
            TownActionGuard(),
            captcha,
        ),
        QuestPageParser(),
    )

    @Test
    fun `manual accept revalidates the exact observed HOF link and preserves automation origin`() {
        stubAccount()
        val requests = mutableListOf<HofRequest>()
        Mockito.`when`(gateway.execute(Mockito.eq(9L), anyRequest(), anyCookies())).thenAnswer { invocation ->
            requests += invocation.getArgument<HofRequest>(1)
            if (requests.size == 1) response(available("?menu=quest&amp;action=get&amp;no=R%2B10"))
            else response("<div id='result'>수락했습니다.</div>")
        }

        service.accept(9L, "R+10", HofRequestOrigin.AUTOMATION)

        assertEquals(2, requests.size)
        assertEquals(HofRequestOrigin.AUTOMATION, requests[0].origin)
        assertEquals(HofRequestOrigin.AUTOMATION, requests[1].origin)
        assertEquals(listOf("action" to "get", "no" to "R+10"), requests[1].formEntries.map { it.name to it.value })
    }

    @Test
    fun `manual accept rejects duplicate observed query values before action request`() {
        stubAccount()
        Mockito.`when`(gateway.execute(Mockito.eq(9L), anyRequest(), anyCookies()))
            .thenReturn(response(available("?menu=quest&amp;action=get&amp;no=351&amp;no=forged")))

        assertFailsWith<ApiException> { service.accept(9L, "351") }

        Mockito.verify(gateway, Mockito.times(1)).execute(Mockito.eq(9L), anyRequest(), anyCookies())
    }

    private fun stubAccount() {
        Mockito.`when`(accounts.findById(9L)).thenReturn(HofAccountEntity(9L, "quest-user", "encrypted", Instant.EPOCH))
        Mockito.`when`(cookies.findValueMapByAccountId(9L)).thenReturn(mapOf("PHPSESSID" to "session"))
    }

    private fun available(href: String) = """
        <div id="contents"><h4>수락 가능한 퀘스트</h4><table><tr>
          <td class="td7s">[0351] 마을 지하 수로</td><td><a href="$href">수락</a></td>
        </tr></table></div>
    """.trimIndent()

    private fun response(body: String) = HofHttpResponse(200, QUEST_URL, body, emptyMap())
    private fun anyRequest(): HofRequest = Mockito.any(HofRequest::class.java) ?: HofRequest(HofHttpMethod.GET, QUEST_URL)
    private fun anyCookies(): Map<String, String> = Mockito.anyMap<String, String>() ?: emptyMap()

    private companion object { const val QUEST_URL = "http://sic.zerosic.com/ZeroHOF/index.php?menu=quest" }
}
