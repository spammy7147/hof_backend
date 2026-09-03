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
import app.spammy.hof.town.pvp.dto.ChallengeColosseumRequest
import app.spammy.hof.town.pvp.dto.ColosseumTradeRequest
import app.spammy.hof.town.pvp.parser.ColosseumParser
import app.spammy.hof.town.pvp.service.ColosseumService
import java.time.Instant
import kotlin.test.*
import org.mockito.Mockito

class ColosseumTest {
    private val forms = HofFormParser()
    private val parser = ColosseumParser()
    @Test fun `팀 checkbox와 challenge form을 서로 다른 계약으로 파싱한다`() {
        val html = fixture("battle.html"); val value = parser.parseBattle(html, BASE, forms.parse(html, BASE))
        assertEquals(3, value.selectedTeam.size); assertEquals(3, value.fighters.size); assertEquals(5, value.maxTeamSize); assertEquals(1, value.opponents.size)
    }
    @Test fun `팀 submit 문구가 달라도 관측한 Team 배열에서 5인 후보를 파싱한다`() {
        val html = fixture("battle.html").replace("name=\"TeamSetting\" value=\"Set Team\"", "name=\"Save\" value=\"Apply\"")
        val value = parser.parseBattle(html, BATTLE_URL, forms.parse(html, BATTLE_URL))
        assertEquals(3, value.fighters.size); assertEquals(5, value.maxTeamSize); assertNotNull(value.teamActionId)
    }
    @Test fun `challenge 결과를 현재 응답에서 구조화한다`() {
        val html = fixture("result.html"); val result = assertNotNull(parser.parseBattle(html, BASE, forms.parse(html, BASE)).battleResult)
        assertEquals(12, result.turns); assertEquals("테스트전사A", result.winner); assertEquals("0/27306", result.playerHp); assertEquals(1936, result.totalDamage); assertNotNull(result.reward); assertEquals(1, result.detail.first().turn)
    }
    @Test fun `교환소 radio 없는 항목은 선택 불가다`() {
        val html = fixture("shop.html"); val shop = parser.parseShop(html, BASE, forms.parse(html, BASE))
        assertTrue(shop.items.single { it.label.contains("Sword") }.selectable); assertFalse(shop.items.single { it.label.contains("Emblem") }.selectable); assertEquals(250, shop.currencies.single().quantity)
    }
    @Test fun `challenge opaque id는 nonce가 바뀐 최신 GET에서도 같은 상대를 가리킨다`() {
        val initial = fixture("battle.html").replace("opponent-fresh", "opponent-old")
        val latest = fixture("battle.html")
        val opponentId = parser.parseBattle(initial, BATTLE_URL, forms.parse(initial, BATTLE_URL)).opponents.single().id
        val context = service(TownFeatureId.COLOSSEUM_BATTLE, BATTLE_URL, latest, fixture("result.html"))
        val response = context.service.challenge(7L, ChallengeColosseumRequest(opponentId))
        assertEquals(12, response.battleResult?.turns)
        assertEquals(
            mapOf("nonce" to "opponent-fresh", "enemy" to "77", "Fight" to "Challenge"),
            context.requests().last().formEntries.associate { it.name to it.value },
        )
        assertFalse(context.requests().last().formEntries.any { it.name == "Team[]" })
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
    @Test fun `콜로세움 팀 폼에 별도 상한이 없으면 고정 5인 상한을 사용한다`() {
        val html = fixture("battle.html").replace(" checked", "")
        val battle = parser.parseBattle(html, BATTLE_URL, forms.parse(html, BATTLE_URL))
        assertEquals(5, battle.maxTeamSize)
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
        const val BATTLE_URL = "$BASE?menu=colosseum"
        const val SHOP_URL = "$BASE?menu=colosseumshop"
    }
}
