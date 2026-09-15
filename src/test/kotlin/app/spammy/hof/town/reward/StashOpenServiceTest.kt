package app.spammy.hof.town.reward

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.external.client.AccountHofGateway
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.external.parser.LoginStateParser
import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.common.parser.HofResultParser
import app.spammy.hof.town.common.parser.TownEntryPageParser
import app.spammy.hof.town.common.repository.TownFeatureLocationQueryRepository
import app.spammy.hof.town.common.repository.TownFeatureLocationRepository
import app.spammy.hof.town.common.service.AccountHofMutationFence
import app.spammy.hof.town.common.service.TownActionGuard
import app.spammy.hof.town.common.service.TownAuthenticatedExecutor
import app.spammy.hof.town.common.service.TownLocationResolver
import app.spammy.hof.town.reward.dto.StashOpenRequest
import app.spammy.hof.town.reward.dto.StashResponse
import app.spammy.hof.town.reward.model.StashOpenAction
import app.spammy.hof.town.reward.parser.OrbExchangeParser
import app.spammy.hof.town.reward.parser.StashPageParser
import app.spammy.hof.town.reward.service.RewardService
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.mockito.Mockito

class StashOpenServiceTest {
    @Test fun `실제 개봉 응답의 보상과 최신 보유량을 한 번의 제출 뒤 반환한다`() {
        val response = open(fixture("stash-live-post.html"))
        val result = assertNotNull(response.result)

        assertEquals("SUCCESS", result.status)
        assertEquals("Weapon Box (Dagger&MainGauche) (Stash)", result.items.single().name)
        assertEquals(1, result.items.single().quantity)
        assertEquals(52, response.boxes.single { it.label == "Plumpy Fish (Stash)" }.owned)
        assertEquals(listOf("Plumpy Fish (Stash)을 개봉합니다."), result.messages)
        assertTrue(result.messages.none { it.contains("개봉 가능한") })
    }

    @Test fun `실패와 발견이 함께 오면 아이템 설명과 실패 안내를 구분해 보존한다`() {
        val failure = "개봉할 수 있는 아이템이 없습니다."
        assertEquals("FAILURE", open(resultPage("<p>$failure</p>")).result?.status)
        val response = open(resultPage("""
            <img src="box.gif">Weapon Box (Spear&amp;Pike)<span> (Stash)</span> / <span>추가 조건은 없습니다.</span> 발견!!<br>
            <p>$failure</p>
        """.trimIndent()))
        val result = assertNotNull(response.result)
        assertEquals("INFORMATIONAL", result.status)
        assertEquals(listOf(failure), result.messages)
        assertEquals("Weapon Box (Spear&Pike) (Stash)", result.items.single().name)
        assertEquals("추가 조건은 없습니다.", result.items.single().detail)
        assertEquals("SUCCESS", open(resultPage("<img src='box.gif'>Box (Stash) / 추가 조건은 없습니다. 발견!!<br>")).result?.status)
    }

    private fun resultPage(markup: String) = fixture("stash-live-get.html")
        .replace("개봉 가능한 물건들의 목록", "$markup<hr>개봉 가능한 물건들의 목록")

    @Test fun `묶음 개봉에서 실제 발견 수량을 합산하고 표시 제한을 안내한다`() {
        for (action in listOf(StashOpenAction.TWENTY, StashOpenAction.HUNDRED, StashOpenAction.THOUSAND)) {
            // 요청 개수보다 적은 발견 응답: 결과를 요청 개수로 채우면 안 된다.
            val markup = "<div><span>Material Sack (Stash) 발견!!</span></div>".repeat(action.drawCount!! - 2)
            val result = assertNotNull(open(resultPage(markup), action).result)
            assertEquals(action.drawCount - 2, result.items.single().quantity)
        }
        val explicit = assertNotNull(open(resultPage("<p>Material Sack (Stash) x3 / 소재 발견!!</p><p>Material Sack (Stash) ×2 / 소재 발견!!</p>")).result)
        assertEquals("Material Sack (Stash)", explicit.items.single().name)
        assertEquals(5, explicit.items.single().quantity)

        val distinct = (1..105).joinToString("") { "<p>Reward $it (Stash) 발견!!</p>" }
        val limited = assertNotNull(open(resultPage(distinct), StashOpenAction.THOUSAND).result)
        assertEquals(100, limited.items.size)
        assertTrue(limited.messages.any { "105종" in it && "100종" in it })
    }

    @Test fun `목록과 개봉 시도와 다른 구역의 문구를 성공으로 오인하지 않는다`() {
        val pages = listOf(
            fixture("stash-live-get.html"),
            resultPage("<div class='success'>새로운 안내입니다.</div>"),
            resultPage("<h4>다른 기능</h4><p>Other Box (Stash) 발견!!</p>"),
            resultPage("<form><p>Inventory Box (Stash) 발견!!</p></form>"),
            resultPage("<script>Box (Stash) 발견!!</script>"),
        )
        pages.forEach { html ->
            val result = assertNotNull(open(html).result)
            assertEquals("UNKNOWN", result.status)
            assertTrue(result.items.isEmpty())
            assertTrue(result.messages.isEmpty())
        }
    }

    @Test fun `개봉 안내만 있으면 문구와 새로고침 안내를 보존하고 성공으로 확정하지 않는다`() {
        val result = assertNotNull(open(resultPage("<img src='fish.gif'>Plumpy Fish (Stash) / 설명을 개봉합니다.<hr>")).result)
        assertEquals("UNKNOWN", result.status)
        assertTrue(result.items.isEmpty())
        assertTrue(result.messages.contains("Plumpy Fish (Stash)을 개봉합니다."))
        assertTrue(result.messages.any { "새로고침" in it })
        assertTrue(result.refreshRequired)
    }

    @Test fun `개봉한 아이템 종류와 관계없이 중첩된 발견 행만 읽는다`() {
        for (source in listOf("Plumpy Fish", "Big Plumpy Fish", "Shiny Plumpy Fish", "Uncommon Cloth Box", "Lucky Pipe")) {
            // 이름 변형과 사진 3의 보상명은 합성 표본이며 추가 실서버 개봉은 하지 않는다.
            val result = assertNotNull(open(resultPage("""
                <img src='source.gif'><span>$source</span> / 설명을 개봉합니다.<hr>
                <div><span><img src='reward.gif'><b>Weapon Box (Spear&amp;Pike)</b><span> (Stash)</span> / 설명</span> 발견!!</div>
            """.trimIndent())).result)
            assertEquals("SUCCESS", result.status)
            assertEquals("Weapon Box (Spear&Pike) (Stash)", result.items.single().name)
        }
    }

    @Test fun `결과 문구에 인증 정보나 HTML 문서가 섞여도 앱에 전달하지 않는다`() {
        for (secret in listOf("PHPSESSID=fixture-secret", "Authorization: Bearer fixture-secret", "password=fixture-secret", "&lt;html&gt;fixture-secret&lt;/html&gt;")) {
            val result = assertNotNull(open(resultPage("<p>Box (Stash) / $secret 발견!!</p>")).result)
            assertEquals("UNKNOWN", result.status)
            assertTrue(result.items.isEmpty())
            assertTrue(result.messages.isEmpty())
        }
    }

    private fun open(after: String, action: StashOpenAction = StashOpenAction.ONE): StashResponse {
        val before = fixture("stash-live-get.html")
        val accounts = Mockito.mock(AccountQueryRepository::class.java)
        val cookies = Mockito.mock(CookieQueryRepository::class.java)
        val gateway = Mockito.mock(AccountHofGateway::class.java)
        Mockito.`when`(accounts.findById(7L)).thenReturn(HofAccountEntity(7L, "stash-fixture", "encrypted", Instant.EPOCH))
        Mockito.`when`(cookies.findValueMapByAccountId(7L)).thenReturn(mapOf("PHPSESSID" to "fixture-session"))
        val requests = mutableListOf<HofRequest>()
        Mockito.`when`(gateway.execute(Mockito.eq(7L), anyRequest(), anyCookies())).thenAnswer { call ->
            val request = call.getArgument<HofRequest>(1)
            requests += request
            HofHttpResponse(200, URL, if (request.method == HofHttpMethod.POST) after else before, emptyMap())
        }
        val service = RewardService(
            TownAuthenticatedExecutor(accounts, cookies, HofRequestFactory(), gateway, LoginStateParser(), HofFormParser(),
                HofResultParser(), TownActionGuard(), AccountHofMutationFence()),
            TownLocationResolver(Mockito.mock(TownFeatureLocationRepository::class.java),
                Mockito.mock(TownFeatureLocationQueryRepository::class.java), TownEntryPageParser()),
            StashPageParser(), OrbExchangeParser(),
        )
        val loaded = service.loadStash(7L)
        assertNull(loaded.result)
        val target = loaded.boxes.single { it.label == "Plumpy Fish (Stash)" }
        val response = service.openStash(7L, StashOpenRequest(target.id, action))
        assertEquals(listOf(HofHttpMethod.GET, HofHttpMethod.GET, HofHttpMethod.POST), requests.map { it.method })
        val submitName = when (action) {
            StashOpenAction.ONE -> "Open"
            StashOpenAction.TWENTY -> "Open20"
            StashOpenAction.HUNDRED -> "Open100"
            StashOpenAction.THOUSAND -> "AllOpen"
            StashOpenAction.ALL -> error("실제 표본에 전부 버튼은 없다")
        }
        assertEquals(mapOf("ItemNo" to "4579", submitName to "${action.drawCount}개 열기"), requests.last().formFields)
        return response
    }

    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/fixtures/town/reward/$name")).readText()
    private fun anyRequest(): HofRequest = Mockito.any(HofRequest::class.java) ?: HofRequest(HofHttpMethod.GET, URL)
    private fun anyCookies(): Map<String, String> = Mockito.anyMap<String, String>() ?: emptyMap()
    private companion object { const val URL = "https://hof.zerosic.com/index.php?menu=stash" }
}
