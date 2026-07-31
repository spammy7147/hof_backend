package app.spammy.hof.town.raid

import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.battle.dto.BattleMapResponse
import app.spammy.hof.battle.model.BattleMapKeyMode
import app.spammy.hof.battle.service.BattleMapService
import app.spammy.hof.captcha.service.CaptchaService
import app.spammy.hof.external.client.*
import app.spammy.hof.external.model.*
import app.spammy.hof.external.parser.LoginStateParser
import app.spammy.hof.town.common.model.TownFeatureId
import app.spammy.hof.town.common.parser.HofResultParser
import app.spammy.hof.town.common.service.*
import app.spammy.hof.town.raid.dto.RaidPubActionRequest
import app.spammy.hof.town.raid.model.RaidAction
import app.spammy.hof.town.raid.model.RaidStatus
import app.spammy.hof.town.raid.parser.RaidPubParser
import app.spammy.hof.town.raid.service.RaidPubService
import java.time.Instant
import kotlin.test.*
import org.mockito.Mockito

class RaidPubParserTest {
    private val forms = HofFormParser()
    private val parser = RaidPubParser()

    @Test fun `APK raidpub 경계로 상태 신청자 버튼과 대기시간을 파싱한다`() {
        val html = fixture()
        val page = parser.parse(html, URL, forms.parse(html, URL))
        assertEquals(3, page.raids.size)
        assertEquals(RaidStatus.RECRUITING, page.raids[0].status)
        assertTrue(RaidAction.REGISTER in page.raids[0].actions)
        assertTrue(RaidAction.LEAVE in page.raids[0].actions)
        assertTrue(page.raids[0].joined)
        assertEquals(2, page.raids[0].applicants.size)
        assertEquals(418, page.raids[1].waitSeconds)
        assertEquals(5, page.raids[1].maxPartySize)
        assertFalse(page.raids[2].playable)
        assertTrue(RaidAction.REWARD in page.globalActions)
        assertTrue(RaidAction.WAIT_RESET in page.globalActions)
        assertEquals(418, page.applyWaitSeconds)
        assertTrue(page.applyWait)
    }

    @Test fun `의미가 비슷해도 허용하지 않은 submit value는 action으로 노출하지 않는다`() {
        val html = fixture().replace("value=\"등록한다\"", "value=\"등록 상태를 본다\"")
        val page = parser.parse(html, URL, forms.parse(html, URL))
        assertFalse(RaidAction.REGISTER in page.raids.first().actions)
    }

    @Test fun `실제 파티에 등록한다 문구를 등록 action으로 해석한다`() {
        val html = fixture().replace("value=\"등록한다\"", "value=\"파티에 등록한다\"")
        val page = parser.parse(html, URL, forms.parse(html, URL))
        assertTrue(RaidAction.REGISTER in page.raids.first().actions)
    }

    @Test fun `중복 raid code는 안정 식별자로 노출하지 않는다`() {
        val duplicate = fixture().replace("RaidSiren", "RaidGoblin")
        val page = parser.parse(duplicate, URL, forms.parse(duplicate, URL))
        assertTrue(page.raids.none { it.id == "RaidGoblin" })
    }

    @Test fun `비정상적으로 큰 신청 대기시간은 overflow 없이 누락한다`() {
        val html = fixture().replace("6분 58초", "999999999999999999999999시간 58초")
        val page = parser.parse(html, URL, forms.parse(html, URL))
        assertTrue(page.applyWait)
        assertNull(page.applyWaitSeconds)
    }

    @Test fun `같은 submit control이 중복 관측되면 action을 노출하지 않는다`() {
        val duplicate = "<input type=\"submit\" name=\"register_goblin\" value=\"등록한다\">"
        val html = fixture().replace(duplicate, duplicate + duplicate)
        val page = parser.parse(html, URL, forms.parse(html, URL))
        assertFalse(RaidAction.REGISTER in page.raids.first().actions)
    }

    @Test fun `중복 hidden field가 있으면 action을 노출하지 않는다`() {
        val html = fixture().replace(
            "<input type=\"hidden\" name=\"nonce\" value=\"raid-fresh\">",
            "<input type=\"hidden\" name=\"nonce\" value=\"raid-fresh\"><input type=\"hidden\" name=\"nonce\" value=\"polluted\">",
        )
        val page = parser.parse(html, URL, forms.parse(html, URL))
        assertTrue(page.raids.all { it.actions.isEmpty() })
        assertEquals(setOf(RaidAction.REFRESH), page.globalActions)
    }

    @Test fun `POST HOF raidpub action이 아닌 form은 화면으로 관측하지 않는다`() {
        val external = fixture().replace("method=\"post\" action=\"?menu=raidpub\"", "method=\"post\" action=\"https://evil.example/ZeroHOF/index.php?menu=raidpub\"")
        val getForm = fixture().replace("method=\"post\"", "method=\"get\"")
        assertTrue(parser.parse(external, URL, forms.parse(external, URL)).raids.isEmpty())
        assertTrue(parser.parse(getForm, URL, forms.parse(getForm, URL)).raids.isEmpty())
    }

    @Test fun `콜론형 header에서도 현재 사용자 이름을 찾아 참가 상태를 판별한다`() {
        val html = fixture().replace("《테스트 길드》현재사용자 Funds :", "현재사용자 Funds:")
        val page = parser.parse(html, URL, forms.parse(html, URL))
        assertTrue(page.raids.first().joined)
    }

    @Test fun `raid section 밖 submit과 section 안 submit 소유권을 섞지 않는다`() {
        val page = parser.parse(fixture(), URL, forms.parse(fixture(), URL))
        assertFalse(RaidAction.REWARD in page.raids.first().actions)
        assertFalse(RaidAction.START in page.raids.first().actions)
        assertFalse(RaidAction.REGISTER in page.globalActions)
    }

    @Test fun `action은 최신 GET의 section 소유 submit 하나만 제출한다`() {
        val html = registerableFixture()
        val context = service(html, html)
        val response = context.service.action(7L, RaidPubActionRequest(RaidAction.REGISTER, "RaidGoblin"))
        assertEquals(3, response.raids.size)
        assertFalse(response.applyWait)
        assertEquals(2, context.requests().size)
        assertEquals(
            mapOf("nonce" to "raid-fresh", "register_goblin" to "등록한다"),
            context.requests().last().formEntries.associate { it.name to it.value },
        )
    }

    @Test fun `신청 대기 중에는 submit이 보여도 등록하지 않는다`() {
        val context = service(fixture())
        assertFailsWith<app.spammy.hof.common.error.ApiException> {
            context.service.action(7L, RaidPubActionRequest(RaidAction.REGISTER, "RaidGoblin"))
        }
        assertEquals(1, context.requests().size)
    }

    @Test fun `참가하지 않은 raid의 시작과 나오기는 제출하지 않는다`() {
        val context = service(fixture())
        assertFailsWith<app.spammy.hof.common.error.ApiException> {
            context.service.action(7L, RaidPubActionRequest(RaidAction.START, "RaidSiren"))
        }
        assertEquals(1, context.requests().size)
    }

    @Test fun `raid_hunt에서 실제 관측되고 내가 참가한 raid만 기존 전투 CTA를 노출한다`() {
        val response = service(fixture()).service.load(7L)
        assertEquals("RaidGoblin", response.raids.first().battleTarget?.mapCode)
        assertNull(response.raids[1].battleTarget)
    }

    @Test fun `다른 raid의 action을 요청하면 POST 없이 fail closed한다`() {
        val context = service(fixture())
        assertFailsWith<app.spammy.hof.common.error.ApiException> {
            context.service.action(7L, RaidPubActionRequest(RaidAction.START, "RaidGoblin"))
        }
        assertEquals(1, context.requests().size)
    }

    @Test fun `START 응답이 전투 페이지면 raidpub 최신 GET을 한 번만 다시 읽는다`() {
        val battle = "<html><body><h1>전투 준비</h1></body></html>"
        val html = startableFixture()
        val context = service(html, battle, html)
        val response = context.service.action(7L, RaidPubActionRequest(RaidAction.START, "RaidSiren"))
        assertEquals(3, response.raids.size)
        assertEquals(3, context.requests().size)
        assertEquals(HofHttpMethod.GET, context.requests().last().method)
    }

    @Test fun `보충 GET에도 raidpub form이 없으면 세 번째 요청 뒤 fail closed한다`() {
        val battle = "<html><body><h1>전투 준비</h1></body></html>"
        val context = service(startableFixture(), battle, battle)
        assertFailsWith<app.spammy.hof.common.error.ApiException> {
            context.service.action(7L, RaidPubActionRequest(RaidAction.START, "RaidSiren"))
        }
        assertEquals(3, context.requests().size)
    }

    private fun fixture() = requireNotNull(javaClass.getResource("/fixtures/town/raid/raidpub.html")).readText()
    private fun registerableFixture() = fixture()
        .replace("현재 상태는 신청 대기 (신청 가능까지 6분 58초)", "현재 상태는 신청 가능")
        .replace("[《테스트 길드》현재사용자]", "[다른 신청자]")
    private fun startableFixture() = fixture().replace("- [다른 사람]", "- [《테스트 길드》현재사용자]")
    private fun service(vararg responses: String): Context {
        val accounts = Mockito.mock(AccountQueryRepository::class.java)
        val cookies = Mockito.mock(CookieQueryRepository::class.java)
        val gateway = Mockito.mock(AccountHofGateway::class.java)
        val captcha = Mockito.mock(CaptchaService::class.java)
        val locations = Mockito.mock(TownLocationResolver::class.java)
        val maps = Mockito.mock(BattleMapService::class.java)
        Mockito.`when`(accounts.findById(7L)).thenReturn(HofAccountEntity(7L, "raid", "encrypted", Instant.EPOCH))
        Mockito.`when`(cookies.findValueMapByAccountId(7L)).thenReturn(mapOf("PHPSESSID" to "session"))
        Mockito.`when`(locations.resolve(TownFeatureId.RAID_INFO, null)).thenReturn(ResolvedTownLocation(TownFeatureId.RAID_INFO, URL))
        Mockito.`when`(maps.findCurrentlyObservedMaps(7L, "raid")).thenReturn(listOf(observedMap("RaidGoblin"), observedMap("RaidSiren")))
        Mockito.`when`(gateway.execute(Mockito.eq(7L), anyRequest(), anyCookies())).thenReturn(
            HofHttpResponse(200, URL, responses.first(), emptyMap()),
            *responses.drop(1).map { HofHttpResponse(200, URL, it, emptyMap()) }.toTypedArray(),
        )
        val executor = TownAuthenticatedExecutor(accounts, cookies, HofRequestFactory(), gateway, LoginStateParser(), forms, HofResultParser(), TownActionGuard(), captcha)
        return Context(RaidPubService(executor, locations, parser, maps), gateway)
    }
    private fun observedMap(code: String) = BattleMapResponse("raid", code, code, null, 0, 0, null, null, null, null, null, null, BattleMapKeyMode.UNKNOWN, null, null, false, true, true, null, "?raid_common=$code")
    private data class Context(val service: RaidPubService, val gateway: AccountHofGateway) {
        fun requests() = Mockito.mockingDetails(gateway).invocations.mapNotNull { it.arguments.getOrNull(1) as? HofRequest }
    }
    private fun anyRequest(): HofRequest = Mockito.any(HofRequest::class.java) ?: HofRequest(HofHttpMethod.GET, URL)
    private fun anyCookies(): Map<String, String> = Mockito.anyMap<String, String>() ?: emptyMap()
    private companion object { const val URL = "http://sic.zerosic.com/ZeroHOF/index.php?menu=raidpub" }
}
