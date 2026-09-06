package app.spammy.hof.town.common.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.external.client.AccountHofGateway
import app.spammy.hof.external.client.DeferredCharacterRosterHofResponse
import app.spammy.hof.town.common.service.TownRequestContinuation
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.external.model.HofFormField
import app.spammy.hof.external.model.HofRequestOrigin
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
    private val executor = TownAuthenticatedExecutor(
        accountQueryRepository = accounts,
        cookieQueryRepository = cookies,
        requestFactory = HofRequestFactory(),
        gateway = gateway,
        loginStateParser = LoginStateParser(),
        formParser = HofFormParser(),
        resultParser = HofResultParser(),
        actionGuard = TownActionGuard(),
        mutationFence = AccountHofMutationFence(),
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
    fun `does not treat captcha-like item text as a town authentication gate`() {
        val account = HofAccountEntity(7L, "town-user", "encrypted", Instant.EPOCH)
        Mockito.`when`(accounts.findById(7L)).thenReturn(account)
        Mockito.`when`(cookies.findValueMapByAccountId(7L)).thenReturn(mapOf("PHPSESSID" to "session"))
        val captchaHtml = "<p>자경단에서 통행증을 발급받아주세요.</p><form><button name='Sell'>판매</button></form>"
        Mockito.`when`(gateway.execute(Mockito.eq(7L), anyRequest(), anyCookies()))
            .thenReturn(response(captchaHtml))

        val page = executor.load(7L, HOF_URL)

        assertEquals(1, page.forms.size)
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
    fun `non-success HOF response never reaches a feature projector`() {
        stubAccount()
        Mockito.`when`(gateway.execute(Mockito.eq(7L), anyRequest(), anyCookies())).thenReturn(
            HofHttpResponse(500, HOF_URL, "<div id='contents'>stale skeleton</div>", emptyMap()),
        )
        var projected = false

        val error = assertFailsWith<ApiException> {
            executor.loadProjected(7L, HOF_URL) { _, _, _ ->
                projected = true
            }
        }

        assertEquals(ErrorCode.HOF_REQUEST_FAILED, error.errorCode)
        assertFalse(projected)
    }

    @Test
    fun `observed GET keeps exact query order and fresh response cookies inside account fence`() {
        stubAccount()
        val current = "<a href='?menu=quest&amp;action=get&amp;no=R%2B10'>수락</a>"
        val seenRequests = mutableListOf<HofRequest>()
        val seenCookies = mutableListOf<Map<String, String>>()
        Mockito.`when`(gateway.execute(Mockito.eq(7L), anyRequest(), anyCookies()))
            .thenAnswer { invocation ->
                seenRequests += invocation.getArgument<HofRequest>(1)
                seenCookies += invocation.getArgument<Map<String, String>>(2)
                if (seenRequests.size == 1) HofHttpResponse(200, QUEST_URL, current, mapOf("rotated" to "fresh"))
                else HofHttpResponse(200, QUEST_URL, "<div id='result'>수락했습니다.</div>", emptyMap())
            }

        val message = executor.executeObservedGetProjected(
            7L,
            QUEST_URL,
            setOf("action", "no"),
            resolveQuery = { _, _, _ -> listOf(HofFormField("action", "get"), HofFormField("no", "R+10")) },
        ) { _, _, result, _ -> result.messages.single() }

        assertEquals("수락했습니다.", message)
        assertEquals(2, seenRequests.size)
        assertEquals(listOf(HofFormField("action", "get"), HofFormField("no", "R+10")), seenRequests[1].formEntries)
        assertEquals("fresh", seenCookies[1]["rotated"])
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
        val secondInsideFence = CountDownLatch(1)
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
                    secondInsideFence.countDown()
                    TownActionRequest(page.forms.single().actionId)
                }) { _, _, result, _ -> result.messages.single() }
            }
            assertFalse(
                secondInsideFence.await(100, TimeUnit.MILLISECONDS),
                "두 번째 요청은 첫 번째 POST가 끝나기 전에 action fence 안으로 진입하면 안 된다",
            )
            assertEquals(1, calls.get())
            releaseFirst.countDown()
            assertEquals("획득했다.", first.get(2, TimeUnit.SECONDS))
            assertEquals("획득했다.", second.get(2, TimeUnit.SECONDS))
            assertEquals(4, calls.get())
        } finally {
            releaseFirst.countDown()
            pool.shutdownNow()
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = ["missing", "hidden", "readonly", "duplicate", "other_form", "unknown_action", "maxlength"])
    fun `문자열 제출 경계는 최신 guard form 밖의 필드와 변경된 control을 거부한다`(change: String) {
        stubAccount()
        val input = "<input type='text' name='memo' maxlength='32'>"
        val changed = when (change) {
            "hidden" -> input.replace("type='text'", "type='hidden'")
            "readonly" -> input.replace("name='memo'", "name='memo' readonly")
            "duplicate" -> input + input
            else -> input
        }
        val html = "<form method='post'>" + changed + "<button name='Save' value='save'>저장</button></form>" +
            "<form method='post'><input type='text' name='other' maxlength='32'><button name='Other' value='save'>별도</button></form>"
        Mockito.`when`(gateway.execute(Mockito.eq(7L), anyRequest(), anyCookies())).thenReturn(response(html))

        assertFailsWith<ApiException> {
            executor.executeResolvedTextProjected(7L, HOF_URL, resolve = { _, _, page ->
                val id = if (change == "unknown_action") "unknown" else page.forms.first().actionId
                val name = when (change) { "missing" -> "missing"; "other_form" -> "other"; else -> "memo" }
                val value = if (change == "maxlength") "x".repeat(33) else "문자열"
                TownActionRequest(id) to HofFormField(name, value)
            }) { _, _, _, _ -> Unit }
        }

        Mockito.verify(gateway, Mockito.times(1)).execute(Mockito.eq(7L), anyRequest(), anyCookies())
    }

    @Test
    fun `continuation is discarded without POST when another account mutation follows its GET`() {
        stubAccount()
        val formHtml = "<form method='post'><button name='do' value='낚는다'>낚는다</button></form>"
        Mockito.`when`(gateway.execute(Mockito.eq(7L), anyRequest(), anyCookies()))
            .thenReturn(response(formHtml))
        val observed = executor.loadContinuableProjected(
            7L,
            HOF_URL,
            HofRequestOrigin.AUTOMATION,
        ) { _, _, page -> page.forms.single().actionId }
        executor.executeAccountSequence(7L) { Unit }

        assertFailsWith<AccountHofObservationInvalidatedException> {
            executor.executeObservedProjected(
                accountId = 7L,
                pageUrl = HOF_URL,
                origin = HofRequestOrigin.AUTOMATION,
                observation = observed.continuation,
                resolveAction = { _, _, _ -> TownActionRequest(observed.value) },
                expectedForm = { true },
            ) { _, _, _, _ -> Unit }
        }

        Mockito.verify(gateway, Mockito.times(1)).execute(Mockito.eq(7L), anyRequest(), anyCookies())
    }

    @Test
    fun `observed semantic form mismatch is a precondition change before POST`() {
        stubAccount()
        val formHtml = "<form method='post'><button name='FStart' value='낚시를 시작한다'>낚시를 시작한다</button></form>"
        Mockito.`when`(gateway.execute(Mockito.eq(7L), anyRequest(), anyCookies()))
            .thenReturn(response(formHtml))
        val observed = executor.loadContinuableProjected(
            7L,
            HOF_URL,
            HofRequestOrigin.AUTOMATION,
        ) { _, _, page -> page.forms.single().actionId }

        assertFailsWith<ObservedTownActionPreconditionChangedException> {
            executor.executeObservedProjected(
                accountId = 7L,
                pageUrl = HOF_URL,
                origin = HofRequestOrigin.AUTOMATION,
                observation = observed.continuation,
                resolveAction = { _, _, _ -> TownActionRequest(observed.value) },
                expectedForm = { false },
            ) { _, _, _, _ -> Unit }
        }

        Mockito.verify(gateway, Mockito.times(1)).execute(Mockito.eq(7L), anyRequest(), anyCookies())
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
    fun `scalar action accepts one unnamed submit without inventing a submit field`() {
        stubAccount()
        val html = """
            <form action="index.php?menu=auction" method="post">
              <input type="text" name="BidPrice" value="0">
              <input type="submit" value="입찰">
              <input type="hidden" name="ArticleNo" value="0">
            </form>
        """.trimIndent()
        val actionId = HofFormParser().parse(html, AUCTION_URL).forms.single().actionId
        Mockito.`when`(gateway.execute(Mockito.eq(7L), anyRequest(), anyCookies()))
            .thenReturn(response(html), response("<div id='result'>입찰했습니다.</div>"))

        executor.executeProjectedWithScalars(
            7L, AUCTION_URL, TownActionRequest(actionId),
            mapOf("ArticleNo" to "12", "BidPrice" to "3456"), setOf("ArticleNo", "BidPrice"),
            null,
        ) { _, _, result, _ -> result }

        val requests = ArgumentCaptor.forClass(HofRequest::class.java)
        Mockito.verify(gateway, Mockito.times(2)).execute(
            Mockito.eq(7L), capture(requests, HofRequest(HofHttpMethod.GET, AUCTION_URL)), anyCookies(),
        )
        assertEquals(mapOf("BidPrice" to "3456", "ArticleNo" to "12"), requests.allValues[1].formFields)
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

    @Test
    fun `two step scalar action guards final form from entry response inside one fence`() {
        stubAccount()
        val main = "<form action='index.php?menu=auction' method='post'><input type='submit' name='ExhibitItemForm' value='Put Auction'></form>"
        val exhibit = """
          <form action="index.php?menu=auction" method="post">
            <input type="radio" name="item_no" value="77"><input name="Amount"><select name="ExhibitTime"><option value="24">24</option></select>
            <input name="StartPrice"><input name="Comment"><input type="submit" name="PutAuction" value="1">
          </form>
        """.trimIndent()
        val entryId = HofFormParser().parse(main, AUCTION_URL).forms.single().actionId
        val exhibitPage = HofFormParser().parse(exhibit, AUCTION_URL)
        val finalForm = exhibitPage.forms.first { it.submitFields.any { field -> field.name == "PutAuction" } }
        val candidate = finalForm.candidates.first { it.inputName == "item_no" }
        Mockito.`when`(gateway.execute(Mockito.eq(7L), anyRequest(), anyCookies()))
            .thenReturn(response(main), response(exhibit), response("<div id='result'>출품했습니다.</div>"))

        executor.executeTwoStepProjectedWithScalars(
            7L, AUCTION_URL,
            entryAction = { TownActionRequest(entryId) },
            finalAction = { TownActionRequest(finalForm.actionId, listOf(TownActionSelection(candidate.id))) },
            scalarValues = mapOf("Amount" to "2", "ExhibitTime" to "24", "StartPrice" to "9000", "Comment" to "memo"),
            requiredScalarFields = setOf("Amount", "ExhibitTime", "StartPrice", "Comment"),
            requiredFinalSubmitField = "PutAuction",
        ) { _, _, result, _ -> result }

        val requests = ArgumentCaptor.forClass(HofRequest::class.java)
        Mockito.verify(gateway, Mockito.times(3)).execute(Mockito.eq(7L), capture(requests, HofRequest(HofHttpMethod.GET, AUCTION_URL)), anyCookies())
        assertEquals("ExhibitItemForm", requests.allValues[1].formFields.keys.single())
        assertEquals(mapOf("item_no" to "77", "Amount" to "2", "ExhibitTime" to "24", "StartPrice" to "9000", "Comment" to "memo", "PutAuction" to "1"), requests.allValues[2].formFields)
    }

    @Test
    fun `two step scalar action rejects a value outside server select options`() {
        stubAccount()
        val main = "<form action='index.php?menu=auction' method='post'><input type='submit' name='ExhibitItemForm' value='Put Auction'></form>"
        val exhibit = """
          <form action="index.php?menu=auction" method="post">
            <input type="radio" name="item_no" value="77"><input name="Amount"><select name="ExhibitTime"><option value="24">24</option></select>
            <input name="StartPrice"><input name="Comment"><input type="submit" name="PutAuction" value="1">
          </form>
        """.trimIndent()
        val entryId = HofFormParser().parse(main, AUCTION_URL).forms.single().actionId
        val finalForm = HofFormParser().parse(exhibit, AUCTION_URL).forms.single()
        val candidate = finalForm.candidates.first { it.inputName == "item_no" }
        Mockito.`when`(gateway.execute(Mockito.eq(7L), anyRequest(), anyCookies())).thenReturn(response(main), response(exhibit))

        assertFailsWith<ApiException> {
            executor.executeTwoStepProjectedWithScalars(
                7L, AUCTION_URL,
                entryAction = { TownActionRequest(entryId) },
                finalAction = { TownActionRequest(finalForm.actionId, listOf(TownActionSelection(candidate.id))) },
                scalarValues = mapOf("Amount" to "2", "ExhibitTime" to "forged", "StartPrice" to "9000", "Comment" to "memo"),
                requiredScalarFields = setOf("Amount", "ExhibitTime", "StartPrice", "Comment"),
                requiredFinalSubmitField = "PutAuction",
            ) { _, _, result, _ -> result }
        }
        Mockito.verify(gateway, Mockito.times(2)).execute(Mockito.eq(7L), anyRequest(), anyCookies())
    }

    @Test
    fun `card second stage options uses fresh entry form and forwards response cookies`() {
        stubAccount()
        val main = """
          <form action="index.php?menu=cardmix" method="post"><input type="hidden" name="nonce" value="fresh">
            <input type="radio" name="ItemNo" value="201"><input type="submit" name="Create" value="Create">
          </form>
        """.trimIndent()
        val second = """
          <form action="index.php?menu=cardmix" method="post"><input type="hidden" name="ItemNo" value="201">
            <input type="radio" name="AddMaterial" value="202"><input name="amount"><input type="submit" name="Create" value="Create">
          </form>
        """.trimIndent()
        Mockito.`when`(gateway.execute(Mockito.eq(7L), anyRequest(), anyCookies()))
            .thenReturn(response(main), HofHttpResponse(200, HOF_URL, second, mapOf("stage" to "two")))

        val materialCount = executor.loadSecondStageProjected(
            7L, HOF_URL, "Create",
            entryAction = { _, _, page ->
                val form = page.forms.single()
                TownActionRequest(form.actionId, listOf(TownActionSelection(form.candidates.single().id)))
            },
        ) { _, _, page -> page.forms.single().candidates.size }

        assertEquals(1, materialCount)
        val requests = ArgumentCaptor.forClass(HofRequest::class.java)
        Mockito.verify(gateway, Mockito.times(2)).execute(Mockito.eq(7L), capture(requests, HofRequest(HofHttpMethod.GET, HOF_URL)), anyCookies())
        assertEquals(mapOf("nonce" to "fresh", "ItemNo" to "201", "Create" to "Create"), requests.allValues[1].formFields)
    }

    @Test
    fun `resolved card two step revalidates both forms and submits amount only on final stage`() {
        stubAccount()
        val main = """
          <form action="index.php?menu=cardmix" method="post"><input type="hidden" name="nonce" value="fresh">
            <input type="radio" name="ItemNo" value="201"><input type="submit" name="Create" value="Create">
          </form>
        """.trimIndent()
        val second = """
          <form action="index.php?menu=cardmix" method="post"><input type="hidden" name="ItemNo" value="201"><input type="hidden" name="nonce" value="second">
            <input type="radio" name="AddMaterial" value="202"><input name="amount"><input type="submit" name="Create" value="Create">
          </form>
        """.trimIndent()
        Mockito.`when`(gateway.execute(Mockito.eq(7L), anyRequest(), anyCookies()))
            .thenReturn(response(main), HofHttpResponse(200, HOF_URL, second, mapOf("stage" to "two")), response("<div id='result'>합성 성공</div>"))

        executor.executeResolvedTwoStepProjectedWithScalars(
            7L, HOF_URL, "Create",
            entryAction = { _, _, page ->
                val form = page.forms.single()
                TownActionRequest(form.actionId, listOf(TownActionSelection(form.candidates.single().id)))
            },
            finalAction = { _, _, page ->
                val form = page.forms.single()
                TownActionRequest(form.actionId, listOf(TownActionSelection(form.candidates.single().id)))
            },
            scalarValues = mapOf("amount" to "2"), requiredScalarFields = setOf("amount"), requiredFinalSubmitField = "Create",
        ) { _, _, result, _ -> result }

        val requests = ArgumentCaptor.forClass(HofRequest::class.java)
        Mockito.verify(gateway, Mockito.times(3)).execute(Mockito.eq(7L), capture(requests, HofRequest(HofHttpMethod.GET, HOF_URL)), anyCookies())
        assertEquals(mapOf("nonce" to "fresh", "ItemNo" to "201", "Create" to "Create"), requests.allValues[1].formFields)
        assertEquals(mapOf("ItemNo" to "201", "nonce" to "second", "AddMaterial" to "202", "amount" to "2", "Create" to "Create"), requests.allValues[2].formFields)
    }

    @Test
    fun `resolved confirmation sequence forwards the final rotated cookie to its authoritative followup`() {
        stubAccount()
        val first = "<form method='post'><button name='knockback' value='1'>Knockback</button></form>"
        val second = "<form method='post'><button name='knockback2' value='1'>Confirm</button></form>"
        val seenRequests = mutableListOf<HofRequest>()
        val seenCookies = mutableListOf<Map<String, String>>()
        var continuation: TownRequestContinuation? = null
        Mockito.`when`(
            gateway.executeWithoutCharacterRosterObservation(Mockito.eq(7L), anyRequest(), anyCookies()),
        ).thenAnswer { invocation ->
            seenRequests += invocation.getArgument<HofRequest>(1)
            seenCookies += invocation.getArgument<Map<String, String>>(2)
            when (seenRequests.size) {
                1 -> HofHttpResponse(200, HOF_URL, first, mapOf("stage" to "first"))
                2 -> HofHttpResponse(200, HOF_URL, second, mapOf("stage" to "second"))
                3 -> HofHttpResponse(200, HOF_URL, "<div id='result'>이동 완료</div>", mapOf("PHPSESSID" to "rotated"))
                else -> HofHttpResponse(200, HOME_URL, "<div>권위 명단</div>", emptyMap())
            }.let { response ->
                DeferredCharacterRosterHofResponse(response, Instant.EPOCH.plusSeconds(seenRequests.size.toLong()))
            }
        }

        val result = executor.executeResolvedFormSequenceWithFollowupProjected(
            7L,
            HOF_URL,
            listOf("knockback", "knockback2"),
            HOME_URL,
            onFinalSubmissionUnconfirmed = { error("final submission must be observed") },
        ) { actionResult, followupHtml, _, _, _, active ->
            continuation = active
            actionResult.messages.single() to followupHtml
        }

        var continuedCookies: Map<String, String>? = null
        Mockito.`when`(gateway.execute(Mockito.eq(7L), anyRequest(), anyCookies())).thenAnswer { invocation ->
            continuedCookies = invocation.getArgument(2)
            HofHttpResponse(200, DETAIL_URL, "<div>새 상세</div>", emptyMap())
        }
        val detail = executor.continueLoadProjected(requireNotNull(continuation), DETAIL_URL) { html, _, _ -> html }

        assertEquals("이동 완료" to "<div>권위 명단</div>", result)
        assertEquals("<div>새 상세</div>", detail)
        assertEquals(HOME_URL, seenRequests[3].url)
        assertEquals("rotated", seenCookies[3]["PHPSESSID"])
        assertEquals("second", seenCookies[3]["stage"])
        Mockito.verify(gateway, Mockito.times(4))
            .executeWithoutCharacterRosterObservation(Mockito.eq(7L), anyRequest(), anyCookies())
        assertEquals("rotated", continuedCookies?.get("PHPSESSID"))
    }

    @Test
    fun `applied confirmation is preserved when authoritative roster followup fails`() {
        stubAccount()
        val first = "<form method='post'><button name='knockback' value='1'>Knockback</button></form>"
        val second = "<form method='post'><button name='knockback2' value='1'>Confirm</button></form>"
        val calls = AtomicInteger()
        Mockito.`when`(
            gateway.executeWithoutCharacterRosterObservation(Mockito.eq(7L), anyRequest(), anyCookies()),
        ).thenAnswer {
            when (val call = calls.incrementAndGet()) {
                1 -> DeferredCharacterRosterHofResponse(response(first), Instant.EPOCH.plusSeconds(1))
                2 -> DeferredCharacterRosterHofResponse(response(second), Instant.EPOCH.plusSeconds(2))
                3 -> DeferredCharacterRosterHofResponse(
                    response("<div id='result'>이동 완료</div>"),
                    Instant.EPOCH.plusSeconds(3),
                )
                else -> throw app.spammy.hof.external.client.DeferredCharacterRosterRequestException(
                    Instant.EPOCH.plusSeconds(4),
                    Instant.EPOCH.plusSeconds(5),
                    IllegalStateException("followup unavailable: $call"),
                )
            }
        }

        val observed = executor.executeResolvedFormSequenceWithFollowupProjected(
            7L,
            HOF_URL,
            listOf("knockback", "knockback2"),
            HOME_URL,
            onFinalSubmissionUnconfirmed = { error("final submission must be observed") },
        ) { result, followupHtml, _, _, observedAt, _ -> Triple(result.messages.single(), followupHtml, observedAt) }

        assertEquals(Triple("이동 완료", null, Instant.EPOCH.plusSeconds(5)), observed)
    }

    @Test
    fun `lost final confirmation response is projected as unconfirmed instead of being replayed`() {
        stubAccount()
        val first = "<form method='post'><button name='knockback' value='1'>Knockback</button></form>"
        val second = "<form method='post'><button name='knockback2' value='1'>Confirm</button></form>"
        val calls = AtomicInteger()
        val finalFailureObservedAt = Instant.EPOCH.plusSeconds(3)
        Mockito.`when`(
            gateway.executeWithoutCharacterRosterObservation(Mockito.eq(7L), anyRequest(), anyCookies()),
        ).thenAnswer {
            when (calls.incrementAndGet()) {
                1 -> DeferredCharacterRosterHofResponse(response(first), Instant.EPOCH.plusSeconds(1))
                2 -> DeferredCharacterRosterHofResponse(response(second), Instant.EPOCH.plusSeconds(2))
                else -> throw app.spammy.hof.external.client.DeferredCharacterRosterRequestException(
                    Instant.EPOCH.plusSeconds(2),
                    finalFailureObservedAt,
                    IllegalStateException("response lost"),
                )
            }
        }

        val observed = executor.executeResolvedFormSequenceWithFollowupProjected(
            7L,
            HOF_URL,
            listOf("knockback", "knockback2"),
            HOME_URL,
            onFinalSubmissionUnconfirmed = { "unconfirmed" to it },
        ) { _, _, _, _, _, _ -> error("must not project a confirmed action") }

        assertEquals("unconfirmed" to finalFailureObservedAt, observed)
        Mockito.verify(gateway, Mockito.times(3))
            .executeWithoutCharacterRosterObservation(Mockito.eq(7L), anyRequest(), anyCookies())
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
        const val HOF_URL = "https://hof.zerosic.com/index.php?menu=buy"
        const val AUCTION_URL = "https://hof.zerosic.com/index.php?menu=auction"
        const val QUEST_URL = "https://hof.zerosic.com/index.php?menu=quest"
        const val HOME_URL = "https://hof.zerosic.com/index.php"
        const val DETAIL_URL = "https://hof.zerosic.com/index.php?char=11"
    }
}
