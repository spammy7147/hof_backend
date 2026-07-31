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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
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

    @Test
    fun `projects raw HTML only inside the backend callback`() {
        stubAccount()
        val html = "<main><p>날짜가 갱신되었습니다.</p><form><button name='do' value='start'>시작</button></form></main>"
        Mockito.`when`(gateway.execute(Mockito.eq(7L), anyRequest(), anyCookies())).thenReturn(response(html))

        val projected = executor.loadProjected(7L, HOF_URL) { raw, finalUrl, page ->
            Triple(raw.contains("날짜가 갱신되었습니다"), finalUrl, page.forms.size)
        }

        assertEquals(Triple(true, HOF_URL, 1), projected)
    }

    @Test
    fun `resolves a semantic action from the same fresh GET that is guarded and submitted`() {
        stubAccount()
        val html = """
            <form method="post"><input type="hidden" name="csrf" value="fresh">
            <button name="do" value="낚는다">낚는다</button></form>
        """.trimIndent()
        Mockito.`when`(gateway.execute(Mockito.eq(7L), anyRequest(), anyCookies()))
            .thenReturn(response(html), response("<div id='result'>물고기가 도망쳤다.</div>"))

        val message = executor.executeProjected(
            accountId = 7L,
            pageUrl = HOF_URL,
            resolveAction = { raw, _, page ->
                assertEquals(true, raw.contains("낚는다"))
                TownActionRequest(page.forms.single().actionId)
            },
        ) { _, _, result, _ -> result.messages.single() }

        assertEquals("물고기가 도망쳤다.", message)
        Mockito.verify(gateway, Mockito.times(2)).execute(Mockito.eq(7L), anyRequest(), anyCookies())
    }

    @Test
    fun `same account costly actions cannot interleave between fresh GET and POST`() {
        stubAccount()
        val formHtml = "<form method='post'><button name='do' value='낚는다'>낚는다</button></form>"
        val calls = AtomicInteger()
        Mockito.`when`(gateway.execute(Mockito.eq(7L), anyRequest(), anyCookies())).thenAnswer {
            if (calls.incrementAndGet() % 2 == 1) response(formHtml) else response("<div id='result'>획득했다.</div>")
        }
        val firstInsideFence = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<String> {
                executor.executeProjected(7L, HOF_URL, resolveAction = { _, _, page ->
                    firstInsideFence.countDown()
                    releaseFirst.await(2, TimeUnit.SECONDS)
                    TownActionRequest(page.forms.single().actionId)
                }) { _, _, result, _ -> result.messages.single() }
            }
            assertTrue(firstInsideFence.await(2, TimeUnit.SECONDS))
            val second = pool.submit<String> {
                executor.executeProjected(7L, HOF_URL, resolveAction = { _, _, page ->
                    TownActionRequest(page.forms.single().actionId)
                }) { _, _, result, _ -> result.messages.single() }
            }
            Thread.sleep(100)
            assertEquals(1, calls.get(), "두 번째 요청은 첫 번째 POST가 끝나기 전에 GET을 수행하면 안 된다")
            releaseFirst.countDown()
            assertEquals("획득했다.", first.get(2, TimeUnit.SECONDS))
            assertEquals("획득했다.", second.get(2, TimeUnit.SECONDS))
            assertEquals(4, calls.get())
        } finally {
            releaseFirst.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `scalar action proves the same semantic form and submits only exact required names`() {
        stubAccount()
        val html = """
            <form action="index.php?menu=auction" method="post">
              <input name="ArticleNo" value=""><input name="BidPrice" value="">
              <input type="submit" name="Bid" value="Bid">
            </form>
        """.trimIndent()
        val actionId = HofFormParser().parse(html, AUCTION_URL).forms.single().actionId
        Mockito.`when`(gateway.execute(Mockito.eq(7L), anyRequest(), anyCookies()))
            .thenReturn(response(html), response("<div id='result'>입찰했습니다.</div>"))

        executor.executeProjectedWithScalars(
            7L, AUCTION_URL, TownActionRequest(actionId),
            mapOf("ArticleNo" to "12", "BidPrice" to "3456"), setOf("ArticleNo", "BidPrice"),
            "Bid",
        ) { _, _, result, _ -> result }

        val requests = ArgumentCaptor.forClass(HofRequest::class.java)
        Mockito.verify(gateway, Mockito.times(2)).execute(Mockito.eq(7L), capture(requests, HofRequest(HofHttpMethod.GET, AUCTION_URL)), anyCookies())
        assertEquals(mapOf("ArticleNo" to "12", "BidPrice" to "3456", "Bid" to "Bid"), requests.allValues[1].formFields)
    }

    @Test
    fun `scalar action rejects fields assembled from another form`() {
        stubAccount()
        val html = """
            <form action="index.php?menu=auction" method="post"><input name="ArticleNo"><input type="submit" name="Bid" value="Bid"></form>
            <form action="index.php?menu=other" method="post"><input name="BidPrice"><input type="submit" name="Other" value="Other"></form>
        """.trimIndent()
        val actionId = HofFormParser().parse(html, AUCTION_URL).forms.first().actionId
        Mockito.`when`(gateway.execute(Mockito.eq(7L), anyRequest(), anyCookies())).thenReturn(response(html))

        assertFailsWith<ApiException> {
            executor.executeProjectedWithScalars(
                7L, AUCTION_URL, TownActionRequest(actionId),
                mapOf("ArticleNo" to "12", "BidPrice" to "3456"), setOf("ArticleNo", "BidPrice"),
                "Bid",
            ) { _, _, result, _ -> result }
        }
        Mockito.verify(gateway, Mockito.times(1)).execute(Mockito.eq(7L), anyRequest(), anyCookies())
    }

    private fun stubAccount() {
        Mockito.`when`(accounts.findById(7L)).thenReturn(
            HofAccountEntity(7L, "town-user", "encrypted", Instant.EPOCH),
        )
        Mockito.`when`(cookies.findValueMapByAccountId(7L)).thenReturn(mapOf("PHPSESSID" to "session"))
    }

    private fun response(body: String) = HofHttpResponse(200, HOF_URL, body, emptyMap())

    private fun anyRequest(): HofRequest = Mockito.any(HofRequest::class.java)
        ?: HofRequest(HofHttpMethod.GET, HOF_URL)

    private fun anyCookies(): Map<String, String> = Mockito.anyMap<String, String>() ?: emptyMap()

    private fun <T : Any> capture(captor: ArgumentCaptor<T>, fallback: T): T = captor.capture() ?: fallback

    private companion object {
        const val HOF_URL = "http://sic.zerosic.com/ZeroHOF/index.php?menu=buy"
        const val AUCTION_URL = "http://sic.zerosic.com/ZeroHOF/index.php?menu=auction"
    }
}
