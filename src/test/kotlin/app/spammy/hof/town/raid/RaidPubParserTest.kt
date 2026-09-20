package app.spammy.hof.town.raid

import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.common.model.ParsedTownResult
import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.battle.dto.BattleMapResponse
import app.spammy.hof.battle.model.BattleMapKeyMode
import app.spammy.hof.battle.service.BattleMapService
import app.spammy.hof.battle.service.CurrentBattleMapObservation
import app.spammy.hof.battle.service.CurrentBattleMapObservationStatus
import app.spammy.hof.external.client.*
import app.spammy.hof.external.model.*
import app.spammy.hof.external.parser.LoginStateParser
import app.spammy.hof.external.parser.RaidCooldownAssociationStatus
import app.spammy.hof.external.parser.RaidCooldownPageObservation
import app.spammy.hof.town.common.model.TownFeatureId
import app.spammy.hof.town.common.parser.HofResultParser
import app.spammy.hof.town.common.service.*
import app.spammy.hof.town.raid.dto.RaidPubActionRequest
import app.spammy.hof.town.raid.model.RaidAction
import app.spammy.hof.town.raid.model.RaidBattleObservationStatus
import app.spammy.hof.town.raid.model.RaidStatus
import app.spammy.hof.town.raid.model.RaidRewardWindowStatus
import app.spammy.hof.town.raid.parser.RaidPubParser
import app.spammy.hof.town.raid.service.RaidActionPreconditionChangedException
import app.spammy.hof.town.raid.service.RaidPubService
import app.spammy.hof.town.raid.service.NonPersistentRaidCooldownEvidenceRecorder
import app.spammy.hof.town.raid.service.RaidCooldownEvidenceRecorder
import java.time.Instant
import kotlin.test.*
import org.mockito.Mockito

class RaidPubParserTest {
    private val forms = HofFormParser()
    private val parser = RaidPubParser()

    @Test fun `일반 조회에 없는 실제 신청 완료 문구를 갱신 직접 응답에서 인식한다`() {
        val before = fixture().replace("현재 상태는 신청 대기 (신청 가능까지 6분 58초)",
            "<input type=\"submit\" name=\"refresh_nonce\" value=\"상태 갱신\">")
        val after = before.replaceFirst("  <h4>",
            "  <div class=\"result\">현재 전투에 신청한 상태입니다.(신청 불가능)</div>\n  <h4>")
        assertFalse(parser.parse(before, URL, forms.parse(before, URL)).registrationStateObserved)
        val context = service(before, after)

        val response = context.service.actionForAutomation(7L, RaidPubActionRequest(RaidAction.REFRESH), "RaidGoblin")

        assertTrue(response.registrationStateObserved)
        assertTrue(response.applied)
        assertTrue(response.raids.single { it.id == "RaidGoblin" }.joined)
        assertEquals(listOf(HofHttpMethod.GET, HofHttpMethod.POST), context.requests().map { it.method })
    }

    @Test fun `실제 신청 가능 상태입니다 응답으로 자동화 갱신을 완료한다`() {
        val before = registerableFixture()
            .replace("현재 상태는 신청 가능", "<input type=\"submit\" name=\"refresh_nonce\" value=\"상태 갱신\">")
        val after = before.replaceFirst("  <h4>", "  <div class=\"result\">현재 상태는 신청 가능 상태입니다.</div>\n  <h4>")
        val parsed = parser.parse(after, URL, forms.parse(after, URL))
        assertTrue(parsed.pageComplete)
        assertTrue(parsed.raids.all { it.status != RaidStatus.UNKNOWN })
        assertFalse(parsed.raids.any { it.joined })
        val shortResponse = after.replace("신청 가능 상태입니다.", "신청 가능")
        assertTrue(parser.parse(shortResponse, URL, forms.parse(shortResponse, URL)).registrationStateObserved)
        val context = service(before, after)

        val response = context.service.actionForAutomation(7L, RaidPubActionRequest(RaidAction.REFRESH), "RaidGoblin")

        assertTrue(response.registrationStateObserved)
        assertFalse(response.applied)
        assertFalse(response.applyWait)
        assertEquals(listOf(HofHttpMethod.GET, HofHttpMethod.POST), context.requests().map { it.method })
    }

    @Test fun `신청 가능 문구의 부정 조건 설명과 인용은 완전한 신청 상태가 아니다`() {
        for (message in listOf(
            "현재 상태는 신청 가능하지 않은 상태입니다.",
            "현재 상태는 신청 가능 상태입니다라고 표시되면 신청하세요.",
            "<blockquote>현재 상태는 신청 가능 상태입니다.</blockquote>",
            "현재 전투에 신청한 상태가 아닙니다.(신청 가능)",
            "현재 전투에 신청한 상태입니다라고 표시되면 기다리세요.",
            "<blockquote>현재 전투에 신청한 상태입니다.(신청 불가능)</blockquote>",
            "<span hidden>현재 전투에 신청한 상태입니다.(신청 불가능)</span>",
        )) {
            val html = registerableFixture().replace("현재 상태는 신청 가능", "<div class=\"result\">$message</div>")
            val parsed = parser.parse(html, URL, forms.parse(html, URL))
            assertTrue(parsed.pageComplete)
            assertFalse(parsed.registrationStateObserved, message)
        }
    }

    @Test fun `폼 내부 보상 없음 직접 응답은 수동과 자동화 결과에 보존한다`() {
        val before = fixture().replace("현재 상태 : 모집 중", "현재 상태 : 보상 확인 시간 (남은 시간 앞으로 0시간 29분 56초)")
        val after = before.replaceFirst("  <h4>", "  <font color=\"#88ee88\">수령 가능한 보상이 없습니다.</font><br>\n  <h4>")
        for (automation in listOf(false, true)) {
            val context = service(before, after)
            val request = RaidPubActionRequest(RaidAction.REWARD)
            val response = if (automation) context.service.actionForAutomation(7L, request, "RaidGoblin")
                else context.service.action(7L, request)

            assertTrue(response.pageComplete)
            assertEquals(listOf("수령 가능한 보상이 없습니다."), response.result?.messages)
            assertEquals(emptyList(), response.result?.items)
            assertEquals(listOf(HofHttpMethod.GET, HofHttpMethod.POST), context.requests().map { it.method })
        }
    }

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

    @Test fun `보상 없음 문장은 결과 영역에서 공백과 줄바꿈을 정규화한다`() {
        val before = fixture().replace("현재 상태 : 모집 중", "현재 상태 : 보상 확인 시간 (남은 시간 앞으로 0시간 29분 56초)")
        for (markup in listOf(
            "<font>수령&nbsp; 가능한<br>보상이 없습니다</font>",
            "<div class=\"notice\"> 수령 가능한 보상이 없습니다. </div>",
            "수령 가능한 보상이 없습니다.<br>",
            "수령 가능한\n보상이 없습니다.<br>",
            "수령\r\n가능한&nbsp;\n보상이 없습니다<br>",
        )) {
            val after = before.replaceFirst("  <h4>", "  <br>$markup\n  <h4>")
            val response = service(before, after).service.actionForAutomation(7L, RaidPubActionRequest(RaidAction.REWARD), "RaidGoblin")
            assertEquals(listOf("수령 가능한 보상이 없습니다."), response.result?.messages, markup)
        }
        val outside = before.replace("</form>", "</form><div class=\"notice\">수령 가능한 보상이 없습니다.</div>")
        assertEquals(listOf("수령 가능한 보상이 없습니다."),
            service(before, outside).service.action(7L, RaidPubActionRequest(RaidAction.REWARD)).result?.messages)
    }

    @Test fun `필드 버튼 인용 설명과 다른 레이드의 보상 없음 문구는 결과 증거가 아니다`() {
        val before = fixture().replace("현재 상태 : 모집 중", "현재 상태 : 보상 확인 시간 (남은 시간 앞으로 0시간 29분 56초)")
        val message = "수령 가능한 보상이 없습니다."
        for (markup in listOf(
            "<input type=\"hidden\" name=\"help\" value=\"$message\">",
            "<button type=\"button\">$message</button>",
            "<script>document.write('$message')</script>",
            "<style>/* $message */</style>",
            "<blockquote><div class=\"notice\">$message</div></blockquote>",
            "<p>안내: <q>$message</q></p>",
            "<p>보상이 없으면 $message 라고 나옵니다.</p>",
            "<br>보상이 없으면\n$message\n라고 나옵니다.<br>",
            "<span hidden>$message</span>",
        )) {
            val after = before.replaceFirst("  <h4>", "  $markup\n  <h4>")
            val response = service(before, after).service.action(7L, RaidPubActionRequest(RaidAction.REWARD))
            assertTrue(response.result?.messages.orEmpty().none { it == message }, markup)
        }
        val otherRaid = before.replace("현재 상태 : 418초 후 출발", "<div class=\"notice\">$message</div>현재 상태 : 418초 후 출발")
        assertTrue(service(before, otherRaid).service.action(7L, RaidPubActionRequest(RaidAction.REWARD)).result?.messages.orEmpty().isEmpty())
    }

    @Test fun `불완전 직접 응답과 보충 GET의 보상 없음 문구를 직접 결과로 승격하지 않는다`() {
        val before = fixture().replace("현재 상태 : 모집 중", "현재 상태 : 보상 확인 시간 (남은 시간 앞으로 0시간 29분 56초)")
        val after = before.replace("</form>", "</form><div class=\"notice\">수령 가능한 보상이 없습니다.</div>")
        for (direct in listOf(before.substringBefore("<div id=\"foot\""), after.substringBefore("<div id=\"foot\""))) {
            val context = service(before, direct, after)
            val response = context.service.action(7L, RaidPubActionRequest(RaidAction.REWARD))
            assertTrue(response.pageComplete)
            assertTrue(response.result?.messages.orEmpty().isEmpty())
            assertEquals(listOf(HofHttpMethod.GET, HofHttpMethod.POST, HofHttpMethod.GET), context.requests().map { it.method })
            val automation = service(before, direct, after)
            val failure = assertFailsWith<app.spammy.hof.common.error.ApiException> {
                automation.service.actionForAutomation(7L, RaidPubActionRequest(RaidAction.REWARD), "RaidGoblin")
            }
            assertEquals(app.spammy.hof.common.error.ErrorCode.HOF_REQUEST_FAILED, failure.errorCode)
            assertEquals(listOf(HofHttpMethod.GET, HofHttpMethod.POST), automation.requests().map { it.method })
        }
        assertTrue(service(after).service.load(7L).result?.messages.orEmpty().isEmpty())
        val wrongPage = parser.parse(after, "https://hof.zerosic.com/index.php?menu=housing", forms.parse(after, URL),
            HofResultParser().parse(after), rewardResponse = true)
        assertFalse(wrongPage.pageComplete)
        assertTrue(wrongPage.result?.messages.orEmpty().isEmpty())
    }

    @Test fun `보상 없음 문구 보강은 기존 직접 응답의 수령 아이템을 지우지 않는다`() {
        val before = fixture().replace("현재 상태 : 모집 중", "현재 상태 : 보상 확인 시간 (남은 시간 앞으로 0시간 29분 56초)")
        val after = before.replace("</form>", "</form><div id=\"result\"><span class=\"result-item\">레이드 보상 아이템</span></div>")
        val response = service(before, after).service.actionForAutomation(7L, RaidPubActionRequest(RaidAction.REWARD), "RaidGoblin")
        assertEquals(listOf("레이드 보상 아이템"), response.result?.items?.map { it.name })
        assertEquals(app.spammy.hof.automation.raid.RaidRewardResultKind.RECEIVED,
            app.spammy.hof.automation.raid.RaidRewardResultEvidence.from(response.result))
    }

    @Test fun `출발 가능 문구가 있어도 남은 모집 시간이 있으면 대기 상태로 파싱한다`() {
        val html = fixture().replace(
            "현재 상태 : 418초 후 출발",
            "현재 상태 : 파티 모집 중 (1643초 후 출발 가능)",
        )

        val page = parser.parse(html, URL, forms.parse(html, URL))

        assertEquals(RaidStatus.WAITING, page.raids[1].status)
        assertEquals(1643, page.raids[1].waitSeconds)
        assertTrue(RaidAction.START in page.raids[1].actions)
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
        assertFalse(page.pageComplete)
    }

    @Test fun `비정상적으로 큰 신청 대기시간은 overflow 없이 누락한다`() {
        val html = fixture().replace("6분 58초", "999999999999999999999999시간 58초")
        val page = parser.parse(html, URL, forms.parse(html, URL))
        assertTrue(page.applyWait)
        assertNull(page.applyWaitSeconds)
    }

    @Test fun `보상 확인 상태와 보상 시점부터 시작된 세 시간 공유 쿨다운을 파싱한다`() {
        val html = fixture()
            .replace("현재 상태는 신청 대기 (신청 가능까지 6분 58초)", "현재 상태는 신청 대기입니다.(신청 가능 까지 2시간 59분 46초)")
            .replace("현재 상태 : 모집 중", "현재 상태 : 보상 확인 시간 (남은 시간 앞으로 0시간 26분 43초)")
        val page = parser.parse(html, URL, forms.parse(html, URL))

        assertEquals(RaidStatus.COMPLETED, page.raids.first().status)
        assertEquals(1_603, page.raids.first().waitSeconds)
        assertEquals(RaidRewardWindowStatus.CLAIM_WINDOW, page.raids.first().rewardWindowStatus)
        assertEquals(1_603, page.raids.first().rewardWaitSeconds)
        assertTrue(page.applyWait)
        assertEquals(10_786, page.applyWaitSeconds)
    }

    @Test fun `보상 확인 종료 상태와 실제 전투 리셋 버튼을 리셋 가능한 완료 레이드로 파싱한다`() {
        val html = fixture()
            .replace("현재 상태는 신청 대기 (신청 가능까지 6분 58초)", "현재 상태는 신청 가능")
            .replace("현재 상태 : 모집 중", "현재 상태 : 보상 확인 종료(리셋 가능)")
            .replace(
                "<input type=\"submit\" name=\"leave_goblin\" value=\"파티에서 나온다\">",
                "<input type=\"submit\" name=\"leave_goblin\" value=\"파티에서 나온다\">" +
                    "<input type=\"submit\" name=\"reset_goblin\" value=\"전투를 리셋한다\">",
            )

        val page = parser.parse(html, URL, forms.parse(html, URL))

        assertEquals(RaidStatus.COMPLETED, page.raids.first().status)
        assertTrue(RaidAction.RESET in page.raids.first().actions)
    }

    @Test fun `레이드 리셋 성공의 초록색 문구를 결과 메시지로 보존한다`() {
        val html = fixture().replace(
            "<input type=\"submit\" name=\"leave_goblin\" value=\"파티에서 나온다\">",
            "<input type=\"submit\" name=\"leave_goblin\" value=\"파티에서 나온다\">" +
                "<font color=\"green\">전투가 신청 가능 상태로 바뀌었습니다.</font>",
        )

        val page = parser.parse(html, URL, forms.parse(html, URL), ParsedTownResult(emptyList(), emptyList()))

        assertTrue(page.result?.messages.orEmpty().contains("전투가 신청 가능 상태로 바뀌었습니다."))
    }

    @Test fun `등록 성공 문구의 완료는 모집 중 레이드를 보상 단계로 오인하지 않는다`() {
        val html = registerableFixture()
            .replace("[다른 신청자]", "[《테스트 길드》현재사용자]")
            .replace(
                "현재 상태 : 파티 모집 중 (신청 안됨)",
                "현재 상태 : 파티 모집 중 (신청 안됨) 전투 신청이 완료되었습니다.",
            )

        val page = parser.parse(html, URL, forms.parse(html, URL))

        assertEquals(RaidStatus.RECRUITING, page.raids.first().status)
        assertTrue(page.raids.first().joined)
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
        assertTrue(page.globalActions.isEmpty())
    }

    @Test fun `POST HOF raidpub action이 아닌 form은 화면으로 관측하지 않는다`() {
        val external = fixture().replace("method=\"post\" action=\"index.php\"", "method=\"post\" action=\"https://evil.example/ZeroHOF/index.php?menu=raidpub\"")
        val wrongMenu = fixture().replace("method=\"post\" action=\"index.php\"", "method=\"post\" action=\"index.php?menu=store\"")
        val getForm = fixture().replace("method=\"post\"", "method=\"get\"")
        assertTrue(parser.parse(external, URL, forms.parse(external, URL)).raids.isEmpty())
        assertTrue(parser.parse(wrongMenu, URL, forms.parse(wrongMenu, URL)).raids.isEmpty())
        assertTrue(parser.parse(getForm, URL, forms.parse(getForm, URL)).raids.isEmpty())
    }

    @Test fun `현재 페이지가 raidpub가 아니면 같은 HOF index POST도 화면으로 관측하지 않는다`() {
        val nonRaidUrl = "https://hof.zerosic.com/index.php?menu=store"

        val page = parser.parse(fixture(), nonRaidUrl, forms.parse(fixture(), nonRaidUrl))

        assertFalse(page.pageComplete)
        assertTrue(page.raids.isEmpty())
    }

    @Test fun `raidlog 종료 표식이 잘린 form-only 응답은 완전한 raidpub 페이지가 아니다`() {
        val truncated = fixture().replace("<a href=\"?menu=raidlog\">Battle Log 전부표시</a>", "")

        val page = parser.parse(truncated, URL, forms.parse(truncated, URL))

        assertFalse(page.pageComplete)
        assertTrue(page.raids.isEmpty())
    }

    @Test fun `완전한 raidpub 페이지에 battle log 링크가 여러 개 있어도 상태를 관측한다`() {
        val repeatedLogs = fixture().replace(
            "<a href=\"?menu=raidlog\">Battle Log 전부표시</a>",
            List(16) { index ->
                "<a href=\"?menu=raidlog&page=$index\">Battle Log</a>"
            }.joinToString(prefix = "<div>", postfix = "</div>"),
        )

        val page = parser.parse(repeatedLogs, URL, forms.parse(repeatedLogs, URL))

        assertTrue(page.pageComplete)
        assertEquals(3, page.raids.size)
    }

    @Test fun `페이지 관측 상한 뒤의 신청 완료 문구는 applied 증거로 사용하지 않는다`() {
        val oversized = fixture().replace(
            "</form>\n</div>",
            "</form><div>${"x".repeat(200_500)} 신청 완료</div></div>",
        )

        val page = parser.parse(oversized, URL, forms.parse(oversized, URL))

        assertFalse(page.applied)
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

    @Test fun `등록 POST의 기존 전투 충돌을 UNKNOWN이 아닌 명시적 실패로 반환한다`() {
        val html = registerableFixture()
        val rejected = html.replace(
            "</body>",
            "<p>이미 전투 중입니다. 퇴치/보상 확인/상태 갱신을 해주세요.</p></body>",
        )
        val context = service(html, rejected)

        val response = context.service.action(7L, RaidPubActionRequest(RaidAction.REGISTER, "RaidGoblin"))

        assertEquals("FAILURE", response.result?.status)
        assertEquals(
            listOf("이미 전투 중입니다. 퇴치/보상 확인/상태 갱신을 해주세요."),
            response.result?.messages,
        )
        assertEquals(2, context.requests().size)
    }

    @Test fun `상태 갱신은 실제 submit을 실행하고 응답의 신청 쿨타임을 반환한다`() {
        val withRefresh = fixture().replace(
            "<input type=\"submit\" name=\"reward_nonce\" value=\"보상 확인\">",
            "<input type=\"submit\" name=\"refresh_nonce\" value=\"상태 갱신\">" +
                "<input type=\"submit\" name=\"reward_nonce\" value=\"보상 확인\">",
        )
        val refreshed = withRefresh.replace(
            "현재 상태는 신청 대기 (신청 가능까지 6분 58초)",
            "현재 상태는 신청 대기입니다.(신청 가능 까지 1시간 37분 58초)",
        )
        val context = service(withRefresh, refreshed)

        val response = context.service.action(7L, RaidPubActionRequest(RaidAction.REFRESH, null))

        assertEquals(5_878, response.applyWaitSeconds)
        assertEquals(2, context.requests().size)
        assertEquals(
            "상태 갱신",
            context.requests().last().formEntries.single { it.name == "refresh_nonce" }.value,
        )
    }

    @Test fun `신청 대기 중에는 submit이 보여도 등록하지 않는다`() {
        val context = service(fixture())
        assertFailsWith<app.spammy.hof.common.error.ApiException> {
            context.service.action(7L, RaidPubActionRequest(RaidAction.REGISTER, "RaidGoblin"))
        }
        assertEquals(1, context.requests().size)
    }

    @Test fun `현재 사용자가 신청자에 없으면 신청 안됨 문구 없이도 등록한다`() {
        val recruitingForCurrentUser = fixture()
            .replace("현재 상태는 신청 대기 (신청 가능까지 6분 58초)", "현재 상태는 신청 가능")
            .replace("[《테스트 길드》현재사용자]", "[다른 신청자]")
        val context = service(recruitingForCurrentUser, recruitingForCurrentUser)

        context.service.action(7L, RaidPubActionRequest(RaidAction.REGISTER, "RaidGoblin"))

        assertEquals(2, context.requests().size)
        assertEquals(
            "등록한다",
            context.requests().last().formEntries.single { it.name == "register_goblin" }.value,
        )
    }

    @Test fun `상시 보상 버튼이 보여도 완료 레이드가 없으면 제출하지 않는다`() {
        val context = service(fixture())
        assertFailsWith<app.spammy.hof.common.error.ApiException> {
            context.service.action(7L, RaidPubActionRequest(RaidAction.REWARD, null))
        }
        assertEquals(1, context.requests().size)
    }

    @Test fun `자동화 보상은 다른 레이드의 전역 보상 버튼을 대상 성공으로 쓰지 않는다`() {
        val anotherRaidCompleted = fixture().replace(
            "현재 상태 : 418초 후 출발",
            "현재 상태 : 보상 확인 시간 (남은 시간 앞으로 0시간 26분 43초)",
        )
        val context = service(anotherRaidCompleted)

        assertFailsWith<RaidActionPreconditionChangedException> {
            context.service.actionForAutomation(
                7L,
                RaidPubActionRequest(RaidAction.REWARD, null),
                "RaidGoblin",
            )
        }

        assertEquals(1, context.requests().size)
    }

    @Test fun `자동화 보상은 등록 쿨타임 중에도 가입한 완료 대상이면 제출한다`() {
        val completed = fixture()
            .replace(
                "현재 상태 : 418초 후 출발",
                "현재 상태 : 보상 확인 시간 (남은 시간 앞으로 0시간 26분 43초)",
            )
            .replace("- [다른 사람]", "- [《테스트 길드》현재사용자]")
        val context = service(completed, completed)

        context.service.actionForAutomation(
            7L,
            RaidPubActionRequest(RaidAction.REWARD, null),
            "RaidSiren",
        )

        assertEquals(2, context.requests().size)
        assertEquals(
            "보상 확인",
            context.requests().last().formEntries.single { it.name == "reward_nonce" }.value,
        )
    }

    @Test fun `보상 공유 대기만으로는 레이드 리셋을 제출하지 않는다`() {
        val completed = fixture()
            .replace("현재 상태 : 418초 후 출발", "현재 상태 : 보상 확인 시간 (남은 시간 앞으로 0시간 26분 43초)")
            .replace("- [다른 사람]", "- [《테스트 길드》현재사용자]")
        val beforeReward = completed.replace(
            "현재 상태는 신청 대기 (신청 가능까지 6분 58초)",
            "현재 상태는 신청 가능",
        )
        val blocked = service(beforeReward)
        assertFailsWith<app.spammy.hof.common.error.ApiException> {
            blocked.service.action(7L, RaidPubActionRequest(RaidAction.RESET, "RaidSiren"))
        }
        assertEquals(1, blocked.requests().size)

        val sharedCooldown = service(completed)
        assertFailsWith<app.spammy.hof.common.error.ApiException> {
            sharedCooldown.service.action(7L, RaidPubActionRequest(RaidAction.RESET, "RaidSiren"))
        }
        assertEquals(1, sharedCooldown.requests().size)
    }

    @Test fun `보상 확인이 종료되어 리셋 가능하면 공유 대기 표기가 없어도 실제 레이드 리셋을 제출한다`() {
        val resettable = fixture()
            .replace("현재 상태는 신청 대기 (신청 가능까지 6분 58초)", "현재 상태는 신청 가능")
            .replace("현재 상태 : 모집 중", "현재 상태 : 보상 확인 종료(리셋 가능)")
            .replace("[《테스트 길드》현재사용자]", "[다른 신청자]")
            .replace(
                "<input type=\"submit\" name=\"leave_goblin\" value=\"파티에서 나온다\">",
                "<input type=\"submit\" name=\"leave_goblin\" value=\"파티에서 나온다\">" +
                    "<input type=\"submit\" name=\"reset_goblin\" value=\"전투를 리셋한다\">",
            )
        val context = service(resettable, resettable)

        context.service.action(7L, RaidPubActionRequest(RaidAction.RESET, "RaidGoblin"))

        assertEquals(2, context.requests().size)
        assertEquals(
            "전투를 리셋한다",
            context.requests().last().formEntries.single { it.name == "reset_goblin" }.value,
        )

        val rewardContext = service(resettable)
        assertFailsWith<app.spammy.hof.common.error.ApiException> {
            rewardContext.service.action(7L, RaidPubActionRequest(RaidAction.REWARD, null))
        }
        assertEquals(1, rewardContext.requests().size)
    }

    @Test fun `참가하지 않은 raid의 시작과 나오기는 제출하지 않는다`() {
        val context = service(fixture())
        assertFailsWith<app.spammy.hof.common.error.ApiException> {
            context.service.action(7L, RaidPubActionRequest(RaidAction.START, "RaidSiren"))
        }
        assertEquals(1, context.requests().size)
    }

    @Test fun `시작 버튼이 보여도 출발 대기 중에는 제출하지 않는다`() {
        val context = service(fixture().replace("- [다른 사람]", "- [《테스트 길드》현재사용자]"))
        assertFailsWith<app.spammy.hof.common.error.ApiException> {
            context.service.action(7L, RaidPubActionRequest(RaidAction.START, "RaidSiren"))
        }
        assertEquals(1, context.requests().size)
    }

    @Test fun `raid_hunt에서 실제 관측되고 내가 참가한 raid만 기존 전투 CTA를 노출한다`() {
        val context = service(fixture())
        val response = context.service.load(7L)
        assertEquals("RaidGoblin", response.raids.first().battleTarget?.mapCode)
        assertNull(response.raids[1].battleTarget)
        Mockito.verify(context.maps).rememberRaidTargets(
            7L,
            linkedMapOf("RaidGoblin" to "고블린 전투 마차", "RaidSiren" to "음란 해역의 괴물 (Seiren Nom)"),
        )
    }

    @Test fun `전투 정보실 id와 실제 raid_common 코드가 달라도 열린 단일 레이드 맵을 연결한다`() {
        val context = service(fixture())
        Mockito.`when`(context.maps.observeCurrentlyAvailableMaps(7L, "raid"))
            .thenReturn(CurrentBattleMapObservation(
                CurrentBattleMapObservationStatus.OBSERVED,
                listOf(observedMap("raid001", "고블린 전투 마차")),
            ))

        val response = context.service.load(7L)

        assertEquals("raid001", response.raids.first().battleTarget?.mapCode)
        assertNull(response.raids[1].battleTarget)
    }

    @Test fun `raid_hunt의 명시적 맵 부재를 불완전 응답과 구분해 전달한다`() {
        val context = service(fixture())
        Mockito.`when`(context.maps.observeCurrentlyAvailableMaps(7L, "raid"))
            .thenReturn(CurrentBattleMapObservation(CurrentBattleMapObservationStatus.ABSENT, emptyList()))

        val response = context.service.load(7L)

        assertEquals(RaidBattleObservationStatus.ABSENT, response.battleObservationStatus)
        assertNull(response.raids.first().battleTarget)
    }

    @Test fun `raid_hunt에서 새로 시작된 쿨타임 맵도 참가한 raid의 적용 증거로 전달한다`() {
        val context = service(fixture())
        Mockito.`when`(context.maps.observeCurrentlyAvailableMaps(7L, "raid"))
            .thenReturn(CurrentBattleMapObservation(
                CurrentBattleMapObservationStatus.OBSERVED,
                listOf(observedMap("RaidGoblin").copy(enabled = false, cooldownRemainingSeconds = 90)),
            ))

        val response = context.service.load(7L)

        assertEquals("RaidGoblin", response.raids.first().battleTarget?.mapCode)
        assertEquals(90, response.raids.first().battleTarget?.cooldownRemainingSeconds)
    }

    @Test fun `모호한 raid 쿨타임 evidence case 식별자를 응답까지 보존한다`() {
        var activeJoinedRaidCount = -1
        var raidScope = ""
        val recorder = RaidCooldownEvidenceRecorder { evidenceContext, _ ->
            activeJoinedRaidCount = evidenceContext.activeJoinedRaidCount
            raidScope = evidenceContext.raidScope
            "case-raid-cooldown"
        }
        val context = service(fixture(), cooldownEvidence = recorder)
        Mockito.`when`(context.maps.observeCurrentlyAvailableMaps(7L, "raid"))
            .thenReturn(CurrentBattleMapObservation(
                CurrentBattleMapObservationStatus.INCOMPLETE,
                listOf(observedMap("RaidGoblin")),
                RaidCooldownPageObservation(
                    status = RaidCooldownAssociationStatus.AMBIGUOUS,
                    candidateSeconds = listOf(120),
                    mapCount = 1,
                    candidateCount = 1,
                    domFingerprint = "d".repeat(64),
                    responseShapeFingerprint = "r".repeat(64),
                    reasonCode = "RAID_COOLDOWN_ASSOCIATION_AMBIGUOUS",
                ),
            ))

        val response = context.service.load(7L)

        assertEquals("case-raid-cooldown", response.battleObservationEvidence?.caseId)
        assertEquals(1, activeJoinedRaidCount)
        assertEquals("RaidGoblin", raidScope)
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

    @Test fun `안전한 form만 남은 action 응답도 raidpub 최신 GET으로 보충한다`() {
        val partial = fixture().replace("<a href=\"?menu=raidlog\">Battle Log 전부표시</a>", "")
        val html = startableFixture()
        val context = service(html, partial, html)

        val response = context.service.action(7L, RaidPubActionRequest(RaidAction.START, "RaidSiren"))

        assertTrue(response.pageComplete)
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
        .replace("현재 상태 : 모집 중", "현재 상태 : 파티 모집 중 (신청 안됨)")
        .replace("[《테스트 길드》현재사용자]", "[다른 신청자]")
    private fun startableFixture() = fixture()
        .replace("현재 상태 : 418초 후 출발", "현재 상태 : 출발 가능")
        .replace("- [다른 사람]", "- [《테스트 길드》현재사용자]")
    private fun service(
        vararg responses: String,
        cooldownEvidence: RaidCooldownEvidenceRecorder = NonPersistentRaidCooldownEvidenceRecorder,
    ): Context {
        val accounts = Mockito.mock(AccountQueryRepository::class.java)
        val cookies = Mockito.mock(CookieQueryRepository::class.java)
        val gateway = Mockito.mock(AccountHofGateway::class.java)
        val locations = Mockito.mock(TownLocationResolver::class.java)
        val maps = Mockito.mock(BattleMapService::class.java)
        Mockito.`when`(accounts.findById(7L)).thenReturn(HofAccountEntity(7L, "raid", "encrypted", Instant.EPOCH))
        Mockito.`when`(cookies.findValueMapByAccountId(7L)).thenReturn(mapOf("PHPSESSID" to "session"))
        Mockito.`when`(locations.resolve(TownFeatureId.RAID_INFO, null)).thenReturn(ResolvedTownLocation(TownFeatureId.RAID_INFO, URL))
        val mapObservation = CurrentBattleMapObservation(
            CurrentBattleMapObservationStatus.OBSERVED,
            listOf(observedMap("RaidGoblin"), observedMap("RaidSiren")),
        )
        Mockito.`when`(maps.observeCurrentlyAvailableMaps(7L, "raid")).thenReturn(mapObservation)
        Mockito.`when`(
            maps.observeCurrentlyAvailableMaps(7L, "raid", HofRequestOrigin.AUTOMATION),
        ).thenReturn(mapObservation)
        Mockito.`when`(gateway.execute(Mockito.eq(7L), anyRequest(), anyCookies())).thenReturn(
            HofHttpResponse(200, URL, responses.first(), emptyMap()),
            *responses.drop(1).map { HofHttpResponse(200, URL, it, emptyMap()) }.toTypedArray(),
        )
        val executor = TownAuthenticatedExecutor(accounts, cookies, HofRequestFactory(), gateway, LoginStateParser(), forms, HofResultParser(), TownActionGuard(), app.spammy.hof.town.common.service.AccountHofMutationFence())
        return Context(RaidPubService(executor, locations, parser, maps, cooldownEvidence), gateway, maps)
    }
    private fun observedMap(code: String, name: String = code) = BattleMapResponse("raid", code, name, null, 0, 0, null, null, null, null, null, null, BattleMapKeyMode.UNKNOWN, null, null, false, true, true, null, "?raid_common=$code")
    private data class Context(val service: RaidPubService, val gateway: AccountHofGateway, val maps: BattleMapService) {
        fun requests() = Mockito.mockingDetails(gateway).invocations.mapNotNull { it.arguments.getOrNull(1) as? HofRequest }
    }
    private fun anyRequest(): HofRequest = Mockito.any(HofRequest::class.java) ?: HofRequest(HofHttpMethod.GET, URL)
    private fun anyCookies(): Map<String, String> = Mockito.anyMap<String, String>() ?: emptyMap()
    private companion object { const val URL = "https://hof.zerosic.com/index.php?menu=raidpub" }
}
