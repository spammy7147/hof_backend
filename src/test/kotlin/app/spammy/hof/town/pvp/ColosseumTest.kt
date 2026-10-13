package app.spammy.hof.town.pvp

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.external.client.*
import app.spammy.hof.external.model.*
import app.spammy.hof.external.parser.LoginStateParser
import app.spammy.hof.town.common.model.TownFeatureId
import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.common.parser.HofResultParser
import app.spammy.hof.town.common.service.*
import app.spammy.hof.town.pvp.dto.SaveColosseumTeamRequest
import app.spammy.hof.town.pvp.dto.ColosseumTradeRequest
import app.spammy.hof.town.pvp.parser.ColosseumParser
import app.spammy.hof.town.pvp.service.ColosseumService
import java.time.Instant
import kotlin.test.*
import org.mockito.Mockito

class ColosseumTest {
    private val forms = HofFormParser()
    private val parser = ColosseumParser()
    @Test fun `원본 랭킹의 이름 없는 SetTeam과 폼 밖 카드에서 정확히 5명을 읽는다`() {
        val html = fixture("rank.html")
        val value = parser.parseBattle(html, "${BASE}?menu=rank", forms.parse(html, "${BASE}?menu=rank"))
        assertEquals(6, value.fighters.size)
        assertEquals(5, value.selectedTeam.size)
        assertEquals(5, value.minTeamSize)
        assertNotNull(value.teamActionId)
    }
    @Test fun `랭킹은 표시 정보이며 이름 없는 팀 버튼과 별도 ChallengeRank 계약을 읽는다`() {
        val html = fixture("rank.html")
        val page = forms.parse(html, BATTLE_URL)
        val value = parser.parseBattle(html, BATTLE_URL, page)
        assertTrue(value.challengeAvailable)
        assertEquals("1위", value.topRankings.single().rank)
        assertEquals(2, value.topRankings.single().teams.size)
        assertEquals(2, value.nearRankings.size)
        assertEquals("101", value.fighters.first().characterId)
        assertEquals("전사A Lv.60 Social Knight", value.fighters.first().label)
        assertEquals("https://hof.zerosic.com/image/char/a.gif", value.fighters.first().imageUrl)
        assertEquals(emptyList(), page.forms.single { it.actionId == value.teamActionId }.submitFields)
    }
    @Test fun `challenge 결과의 양쪽 HP 생존자 데미지 보상과 세부 로그를 구조화한다`() {
        val html = fixture("rank-result.html")
        val result = assertNotNull(parser.parseBattle(html, BATTLE_URL, forms.parse(html, BATTLE_URL)).battleResult)
        assertEquals(16, result.turns)
        assertEquals("내팀", result.winner)
        assertEquals(listOf("0/28480", "59606/59606"), result.teams.map { it.hp })
        assertEquals(listOf("0/5", "5/5"), result.teams.map { it.survivors })
        assertEquals(listOf(0L, 268716L), result.teams.map { it.totalDamage })
        assertNotNull(result.reward)
        assertEquals("$BASE?rlog=1234567890123456", result.detailUrl)
    }
    @Test fun `공개 rlog의 초기 캐릭터 HP 대신 최종 팀 통계와 이름을 읽는다`() {
        val html = fixture("rank-result.html").replace("<h2>Show Detail( 16 turns. )</h2>",
            "<h2>battle log*</h2><table><tr><td>상대팀[170위]<br>레벨 합계 : 276<br>평균 레벨 : 55.2<br>총 HP : 28480/28480</td><td>내팀[171위]<br>레벨 합계 : 300<br>평균 레벨 : 60<br>총 HP : 59606/59606</td></tr></table>")
        val result = assertNotNull(parser.parseBattle(html, BATTLE_URL, forms.parse(html, BATTLE_URL)).battleResult)
        assertEquals(16, result.turns)
        assertEquals("상대팀[170위]", result.teams.first().label)
        assertEquals("0/28480", result.teams.first().hp)
    }
    @Test fun `외부 또는 임의 query 로그 주소는 응답에 노출하지 않는다`() {
        for (link in listOf("https://evil.example/?rlog=123", "?rlog=123&amp;delete=1", "?rlog=abc")) {
            val html = fixture("rank-result.html").replace("?rlog=1234567890123456", link)
            assertNull(parser.parseBattle(html, BATTLE_URL, forms.parse(html, BATTLE_URL)).battleResult?.detailUrl)
        }
    }
    @Test fun `무승부 결과도 승자 없이 표시한다`() {
        val html = fixture("rank-result.html").replace("《테스트 칭호》내팀[171위]은(는) 승리했다!", "무승부!")
        val result = assertNotNull(parser.parseBattle(html, BATTLE_URL, forms.parse(html, BATTLE_URL)).battleResult)
        assertNull(result.winner)
        assertEquals(2, result.teams.size)
    }
    @Test fun `교환소 radio 없는 항목은 선택 불가다`() {
        val html = fixture("shop.html"); val shop = parser.parseShop(html, BASE, forms.parse(html, BASE))
        assertTrue(shop.items.single { it.label.contains("Sword") }.selectable); assertFalse(shop.items.single { it.label.contains("Emblem") }.selectable); assertEquals(250, shop.currencies.single().quantity)
    }
    @Test fun `challenge는 최신 버튼을 확인하고 팀 또는 상대 필드 없이 한 번 제출한다`() {
        val context = service(TownFeatureId.COLOSSEUM_BATTLE, BATTLE_URL, fixture("rank.html"), fixture("rank-result.html"))
        assertEquals(16, context.service.challenge(7L).battleResult?.turns)
        assertEquals(listOf(HofHttpMethod.GET, HofHttpMethod.POST), context.requests().map { it.method })
        assertEquals(listOf(HofFormField("ChallengeRank", "challenge!")), context.requests().last().formEntries)
    }
    @Test fun `팀 저장은 관측된 char 필드 5개와 hidden trigger만 제출하고 저장을 확인한다`() {
        val html = fixture("rank.html")
        val selected = parser.parseBattle(html, BATTLE_URL, forms.parse(html, BATTLE_URL)).selectedTeam
        val context = service(TownFeatureId.COLOSSEUM_BATTLE, BATTLE_URL, html, html)
        assertEquals(selected, context.service.saveTeam(7L, SaveColosseumTeamRequest(selected)).selectedTeam)
        assertEquals((101..105).map { HofFormField("char_$it", "1") } + HofFormField("SetRankTeam", "fixture-trigger"), context.requests().last().formEntries)
    }
    @Test fun `부족 중복 미관측 팀원은 POST 이전에 거부한다`() {
        val html = fixture("rank.html")
        val selected = parser.parseBattle(html, BATTLE_URL, forms.parse(html, BATTLE_URL)).selectedTeam
        for (ids in listOf(selected.take(4), selected.take(4) + selected.first(), selected.take(4) + "missing")) {
            val context = service(TownFeatureId.COLOSSEUM_BATTLE, BATTLE_URL, html)
            assertFailsWith<app.spammy.hof.common.error.ApiException> { context.service.saveTeam(7L, SaveColosseumTeamRequest(ids)) }
            assertTrue(context.requests().none { it.method == HofHttpMethod.POST })
        }
    }
    @Test fun `팀 저장 응답에 설정이 없으면 GET 하나로 저장을 확인하며 POST를 반복하지 않는다`() {
        val html = fixture("rank.html")
        val selected = parser.parseBattle(html, BATTLE_URL, forms.parse(html, BATTLE_URL)).selectedTeam
        val context = service(TownFeatureId.COLOSSEUM_BATTLE, BATTLE_URL, html,
            "<div id='menu2'>팀을 저장했습니다.</div>", html)
        assertEquals(selected, context.service.saveTeam(7L, SaveColosseumTeamRequest(selected)).selectedTeam)
        assertEquals(listOf(HofHttpMethod.GET, HofHttpMethod.POST, HofHttpMethod.GET), context.requests().map { it.method })
    }
    @Test fun `캐릭터 순서가 바뀌어도 이전 선택은 같은 캐릭터 5명을 저장한다`() {
        val initial = fixture("rank.html")
        val selected = parser.parseBattle(initial, BATTLE_URL, forms.parse(initial, BATTLE_URL)).selectedTeam
        val latest = initial.replace("char_101", "char_TEMP").replace("char_106", "char_101").replace("char_TEMP", "char_106")
        val context = service(TownFeatureId.COLOSSEUM_BATTLE, BATTLE_URL, latest, initial)
        context.service.saveTeam(7L, SaveColosseumTeamRequest(selected))
        assertEquals((101..105).map { "char_$it" }.toSet(), context.requests().last().formEntries.filter { it.name.startsWith("char_") }.map { it.name }.toSet())
    }
    @Test fun `저장한 팀이 다르면 성공으로 보고하지 않는다`() {
        val html = fixture("rank.html")
        val selected = parser.parseBattle(html, BATTLE_URL, forms.parse(html, BATTLE_URL)).selectedTeam
        val context = service(TownFeatureId.COLOSSEUM_BATTLE, BATTLE_URL, html, html.replace("id=\"box5\"", "id=\"box5\" disabled"))
        assertFailsWith<app.spammy.hof.common.error.ApiException> { context.service.saveTeam(7L, SaveColosseumTeamRequest(selected)) }
        assertEquals(1, context.requests().count { it.method == HofHttpMethod.POST })
    }
    @Test fun `쿨다운의 disabled challenge와 사라진 팀 저장 버튼은 전송하지 않는다`() {
        val html = fixture("rank.html").replace("value=\"challenge!\"", "disabled value=\"challenge!\"")
            .replace("<input type=\"submit\" class=\"btn\" value=\"SetTeam\">", "")
            .replace("<h4>랭킹", "<div>Time left to Next : 0:00:30</div><h4>랭킹")
        val value = parser.parseBattle(html, BATTLE_URL, forms.parse(html, BATTLE_URL))
        assertFalse(value.challengeAvailable)
        assertEquals("Time left to Next : 0:00:30", value.challengeNotice)
        assertNull(value.teamActionId)
        val context = service(TownFeatureId.COLOSSEUM_BATTLE, BATTLE_URL, html)
        assertFailsWith<app.spammy.hof.common.error.ApiException> { context.service.challenge(7L) }
        assertTrue(context.requests().none { it.method == HofHttpMethod.POST })
    }
    @Test fun `콜로세움 교환은 최신 GET의 strict ItemT와 scalar를 제출한다`() {
        val html = fixture("shop.html")
        val shop = parser.parseShop(html, SHOP_URL, forms.parse(html, SHOP_URL))
        val row = shop.items.single { it.label.contains("Sword") }
        val context = service(TownFeatureId.COLOSSEUM_EXCHANGE, SHOP_URL, html)
        context.service.trade(7L, ColosseumTradeRequest(row.id, shop.currentCategoryId, 9))
        assertEquals(
            mapOf("nonce" to "shop", "ItemT" to "37", "list_type" to "all", "amount" to "9", "ItemNo" to "sword", "Create" to "Create"),
            context.requests().last().formEntries.associate { it.name to it.value },
        )
    }
    @Test fun `교환 scalar가 중복되거나 ItemT assignment가 임의 함수면 fail closed한다`() {
        val arbitrary = fixture("shop.html").replace("document.getElementById('ItemT').value='37'", "window.pickRecipe('37')")
        assertFalse(parser.parseShop(arbitrary, SHOP_URL, forms.parse(arbitrary, SHOP_URL)).items.single { it.label.contains("Sword") }.selectable)
        val ambiguous = fixture("shop.html").replace("<input name=\"amount\"", "<input name=\"amount\" value=\"2\"><input name=\"amount\"")
        assertTrue(parser.parseShop(ambiguous, SHOP_URL, forms.parse(ambiguous, SHOP_URL)).items.isEmpty())
    }
    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/fixtures/town/pvp/$name")).readText()
    private fun service(feature: TownFeatureId, url: String, vararg html: String): Context {
        val accounts = Mockito.mock(AccountQueryRepository::class.java)
        val cookies = Mockito.mock(CookieQueryRepository::class.java)
        val gateway = Mockito.mock(AccountHofGateway::class.java)
        val locations = Mockito.mock(TownLocationResolver::class.java)
        Mockito.`when`(accounts.findById(7L)).thenReturn(HofAccountEntity(7L, "pvp", "encrypted", Instant.EPOCH))
        Mockito.`when`(cookies.findValueMapByAccountId(7L)).thenReturn(mapOf("PHPSESSID" to "session"))
        Mockito.`when`(locations.resolve(feature, null)).thenReturn(ResolvedTownLocation(feature, url))
        Mockito.`when`(gateway.execute(Mockito.eq(7L), anyRequest(), anyCookies())).thenReturn(
            HofHttpResponse(200, url, html.first(), emptyMap()),
            *html.drop(1).map { HofHttpResponse(200, url, it, emptyMap()) }.toTypedArray(),
        )
        val executor = TownAuthenticatedExecutor(accounts, cookies, HofRequestFactory(), gateway, LoginStateParser(), forms, HofResultParser(), TownActionGuard(), app.spammy.hof.town.common.service.AccountHofMutationFence())
        return Context(ColosseumService(executor, locations, parser), gateway)
    }
    private data class Context(val service: ColosseumService, val gateway: AccountHofGateway) {
        fun requests() = Mockito.mockingDetails(gateway).invocations.mapNotNull { it.arguments.getOrNull(1) as? HofRequest }
    }
    private fun anyRequest(): HofRequest = Mockito.any(HofRequest::class.java) ?: HofRequest(HofHttpMethod.GET, BASE)
    private fun anyCookies(): Map<String, String> = Mockito.anyMap<String, String>() ?: emptyMap()
    private companion object {
        const val BASE = "https://hof.zerosic.com/index.php"
        const val BATTLE_URL = "$BASE?menu=rank"
        const val SHOP_URL = "$BASE?menu=colosseumshop"
    }
}
