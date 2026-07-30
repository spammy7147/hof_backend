package app.spammy.hof.town.common.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.captcha.dto.CaptchaChallengeResponse
import app.spammy.hof.captcha.service.CaptchaService
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.external.client.AccountHofGateway
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.external.parser.LoginStateParser
import app.spammy.hof.town.common.model.TownActionRequest
import app.spammy.hof.town.common.model.TownActionSelection
import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.common.parser.HofResultParser
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito

class TownAuthenticatedExecutorTest {
    private val accounts = Mockito.mock(AccountQueryRepository::class.java)
    private val cookies = Mockito.mock(CookieQueryRepository::class.java)
    private val gateway = Mockito.mock(AccountHofGateway::class.java)
    private val captchaService = Mockito.mock(CaptchaService::class.java)
    private val executor = TownAuthenticatedExecutor(
        accountQueryRepository = accounts,
        cookieQueryRepository = cookies,
        requestFactory = HofRequestFactory(),
        gateway = gateway,
        loginStateParser = LoginStateParser(),
        formParser = HofFormParser(),
        resultParser = HofResultParser(),
        actionGuard = TownActionGuard(),
        captchaService = captchaService,
    )

    @Test
    fun `reloads current form and submits only fresh server fields`() {
        Mockito.`when`(accounts.findById(7L)).thenReturn(
            HofAccountEntity(7L, "town-user", "encrypted", Instant.EPOCH),
        )
        Mockito.`when`(cookies.findValueMapByAccountId(7L)).thenReturn(mapOf("PHPSESSID" to "session"))
        val currentHtml = """
            <form action="index.php?menu=buy" method="post">
              <input type="hidden" name="csrf" value="fresh-token">
              <table><tr><td><input type="radio" name="item" value="item-1"></td><td>아이템</td></tr></table>
              <button name="Buy" value="buy">Buy</button>
            </form>
        """.trimIndent()
        val parsedActionId = HofFormParser().parse(currentHtml).forms.single().actionId
        Mockito.`when`(gateway.execute(Mockito.eq(7L), anyRequest(), anyCookies()))
            .thenReturn(response(currentHtml), response("<section id='result'>구매했습니다.</section>"))

        val executed = executor.execute(
            accountId = 7L,
            pageUrl = HOF_URL,
            action = TownActionRequest(parsedActionId, listOf(TownActionSelection("item-1"))),
        )

        val requests = ArgumentCaptor.forClass(HofRequest::class.java)
        Mockito.verify(gateway, Mockito.times(2)).execute(
            Mockito.eq(7L),
            capture(requests, HofRequest(HofHttpMethod.GET, HOF_URL)),
            anyCookies(),
        )
        assertEquals(HofHttpMethod.POST, requests.allValues[1].method)
        assertEquals(
            mapOf("csrf" to "fresh-token", "Buy" to "buy", "item" to "item-1"),
            requests.allValues[1].formFields,
        )
        assertEquals(listOf("구매했습니다."), executed.result.messages)
        assertFalse(executed.result.messages.joinToString().contains("<section"))
    }

    @Test
    fun `records captcha and stops before parsing town page`() {
        val account = HofAccountEntity(7L, "town-user", "encrypted", Instant.EPOCH)
        Mockito.`when`(accounts.findById(7L)).thenReturn(account)
        Mockito.`when`(cookies.findValueMapByAccountId(7L)).thenReturn(mapOf("PHPSESSID" to "session"))
        val captchaHtml = "<p>자경단에서 통행증을 발급받아주세요.</p>"
        Mockito.`when`(gateway.execute(Mockito.eq(7L), anyRequest(), anyCookies()))
            .thenReturn(response(captchaHtml))
        Mockito.`when`(captchaService.detectAndRecord(account, captchaHtml, HOF_URL)).thenReturn(
            CaptchaChallengeResponse(1L, 7L, "DETECTED", "통행증", null, HOF_URL, 0, Instant.EPOCH.toString(), null),
        )

        val error = assertFailsWith<ApiException> { executor.load(7L, HOF_URL) }

        assertEquals(ErrorCode.CAPTCHA_REQUIRED, error.errorCode)
        Mockito.verify(captchaService).detectAndRecord(account, captchaHtml, HOF_URL)
    }

    private fun response(body: String) = HofHttpResponse(200, HOF_URL, body, emptyMap())

    private fun anyRequest(): HofRequest = Mockito.any(HofRequest::class.java)
        ?: HofRequest(HofHttpMethod.GET, HOF_URL)

    private fun anyCookies(): Map<String, String> = Mockito.anyMap<String, String>() ?: emptyMap()

    private fun <T : Any> capture(captor: ArgumentCaptor<T>, fallback: T): T = captor.capture() ?: fallback

    private companion object {
        const val HOF_URL = "http://sic.zerosic.com/ZeroHOF/index.php?menu=buy"
    }
}
