package app.spammy.hof.town.agency

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.external.client.AccountHofGateway
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.model.*
import app.spammy.hof.external.parser.LoginStateParser
import app.spammy.hof.town.agency.dto.RecruitCharacterRequest
import app.spammy.hof.town.agency.parser.RecruitmentPageParser
import app.spammy.hof.town.agency.service.RecruitmentService
import app.spammy.hof.town.common.model.TownFeatureId
import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.common.parser.HofResultParser
import app.spammy.hof.town.common.service.*
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.mockito.Mockito

class RecruitmentTest {
    private val forms = HofFormParser()
    private val parser = RecruitmentPageParser()

    @Test fun `현재 정원과 실제 직업 성별 opaque 후보를 파싱한다`() {
        val html = fixture()
        val snapshot = parser.parse(html, URL, forms.parse(html, URL))

        assertEquals(29, snapshot.currentCharacters)
        assertEquals(45, snapshot.capacity)
        assertTrue(snapshot.jobs.any { it.name == "Monk" && it.price == 10_000L })
        assertEquals(listOf("남성", "여성"), snapshot.genders.map { it.label })
        assertEquals(16, snapshot.nameMaxLength)
        assertTrue(snapshot.recruitmentAvailable)
        assertTrue(snapshot.jobs.all { it.id != it.name })
    }

    @Test fun `실제 HOF의 분리된 직업 행과 인접 성별 문구를 파싱한다`() {
        val html = liveFixture()
        val snapshot = parser.parse(html, LIVE_URL, forms.parse(html, LIVE_URL))

        assertEquals(45, snapshot.currentCharacters)
        assertEquals(129, snapshot.capacity)
        assertEquals(listOf("Warrior", "Sorcerer", "Monk"), snapshot.jobs.map { it.name })
        assertEquals(listOf(2_000L, 2_000L, 10_000L), snapshot.jobs.map { it.price })
        assertEquals(listOf("남성", "여성"), snapshot.genders.map { it.label })
        assertTrue(snapshot.recruitmentAvailable)
    }

    @Test fun `외부 이미지는 노출하지 않고 카드와 radio가 유일하게 연결되지 않으면 모집을 닫는다`() {
        val foreign = fixture().replace("/image/job/monk.gif", "https://evil.example/monk.gif")
        assertNull(parser.parse(foreign, URL, forms.parse(foreign, URL)).jobs.single { it.name == "Monk" }.imageUrl)

        val duplicate = fixture().replace("<input type=\"submit\" name=\"Recruit\" value=\"Recruit\">", """
            <input type="text" maxlength="16" name="OtherName" title="name">
            <input type="submit" name="Recruit" value="Recruit">
        """.trimIndent())
        assertFalse(parser.parse(duplicate, URL, forms.parse(duplicate, URL)).recruitmentAvailable)

        val readOnly = fixture().replace("name=\"NewName\"", "name=\"NewName\" readonly")
        assertFalse(parser.parse(readOnly, URL, forms.parse(readOnly, URL)).recruitmentAvailable)
    }

    @Test fun `모집은 최신 GET의 실제 값과 이름 필드만 한 번 제출한다`() {
        val harness = Harness()
        harness.stub(fixture(), fixture().replace("</body>", "<p class=success>새 캐릭터를 모집했습니다.</p></body>"))
        val initial = parser.parse(fixture(), URL, forms.parse(fixture(), URL))

        val response = harness.service.recruit(7L, RecruitCharacterRequest(initial.jobs.last().id, "새동료", initial.genders.last().id))

        val requests = harness.requests()
        assertEquals(listOf(HofHttpMethod.GET, HofHttpMethod.POST), requests.map(HofRequest::method))
        assertEquals(listOf("nonce", "Job", "Gender", "NewName", "Recruit"), requests.last().formEntries.map { it.name })
        assertEquals(listOf("fresh-recruitment", "job-opaque-9", "gender-server-f", "새동료", "Recruit"), requests.last().formEntries.map { it.value })
        assertTrue(response.result?.messages?.any { it.contains("모집") } == true)
    }

    @Test fun `실제 HOF 모집 필드와 중복 recruit control을 관측 순서대로 제출한다`() {
        val harness = Harness()
        harness.stub(liveFixture(), liveFixture())
        val initial = parser.parse(liveFixture(), LIVE_URL, forms.parse(liveFixture(), LIVE_URL))

        harness.service.recruit(
            7L,
            RecruitCharacterRequest(initial.jobs.last().id, "새동료", initial.genders.last().id),
        )

        val submitted = harness.requests().last().formEntries
        assertEquals(listOf("recruit_no", "recruit_gend", "recruit_name", "recruit", "recruit"), submitted.map { it.name })
        assertEquals(listOf("9", "1", "새동료", "Recruit", ""), submitted.map { it.value })
    }

    @Test fun `빈 이름과 관측되지 않은 선택은 POST 전에 거부한다`() {
        val harness = Harness()
        harness.stub(fixture())
        assertFailsWith<ApiException> { harness.service.recruit(7L, RecruitCharacterRequest("forged", "", "forged")) }
        assertEquals(emptyList(), harness.requests())

        assertFailsWith<ApiException> { harness.service.recruit(7L, RecruitCharacterRequest("forged", "이름", "forged")) }
        assertEquals(listOf(HofHttpMethod.GET), harness.requests().map(HofRequest::method))
    }

    @Test fun `이름의 제어문자와 보이지 않는 문자를 HOF 요청 전에 거부한다`() {
        listOf("새\n동료", "새\u200B동료", "\uE000").forEach { invalidName ->
            val harness = Harness()
            assertFailsWith<ApiException> {
                harness.service.recruit(7L, RecruitCharacterRequest("job", invalidName, "gender"))
            }
            assertEquals(emptyList(), harness.requests())
        }
    }

    @Test fun `HOF 규칙대로 ASCII는 1칸 비 ASCII는 2칸으로 이름 길이를 검증한다`() {
        val tooLong = Harness()
        assertFailsWith<ApiException> {
            tooLong.service.recruit(7L, RecruitCharacterRequest("job", "가".repeat(9), "gender"))
        }
        assertEquals(emptyList(), tooLong.requests())

        val accepted = Harness()
        accepted.stub(fixture(), fixture())
        val initial = parser.parse(fixture(), URL, forms.parse(fixture(), URL))
        accepted.service.recruit(
            7L,
            RecruitCharacterRequest(initial.jobs.first().id, "abc123가나다라마", initial.genders.first().id),
        )
        assertEquals(listOf(HofHttpMethod.GET, HofHttpMethod.POST), accepted.requests().map(HofRequest::method))
    }

    @Test fun `이름과 동일한 필드명의 성공 control이 둘 이상이면 제출하지 않는다`() {
        val polluted = fixture().replace(
            "<input type=\"text\" maxlength=\"16\" name=\"NewName\">",
            "<input type=\"hidden\" name=\"NewName\" value=\"server\"><input type=\"text\" maxlength=\"16\" name=\"NewName\">",
        )
        val harness = Harness()
        harness.stub(polluted)
        val initial = parser.parse(polluted, URL, forms.parse(polluted, URL))

        assertFailsWith<ApiException> {
            harness.service.recruit(
                7L,
                RecruitCharacterRequest(initial.jobs.first().id, "새동료", initial.genders.first().id),
            )
        }
        assertEquals(listOf(HofHttpMethod.GET), harness.requests().map(HofRequest::method))
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = ["hidden", "disabled", "readonly", "display:none", "maxlength", "submit", "capacity", "job", "gender"])
    fun `최신 모집 양식과 선택지가 바뀌면 기존 화면의 입력을 제출하지 않는다`(change: String) {
        val original = fixture()
        val initial = parser.parse(original, URL, forms.parse(original, URL))
        val latest = when (change) {
            "hidden" -> original.replace("type=\"text\"", "type=\"hidden\"")
            "disabled" -> original.replace("name=\"NewName\"", "name=\"NewName\" disabled")
            "readonly" -> original.replace("name=\"NewName\"", "name=\"NewName\" readonly")
            "display:none" -> original.replace("name=\"NewName\"", "name=\"NewName\" style=\"display:none\"")
            "maxlength" -> original.replace("maxlength=\"16\"", "maxlength=\"2\"")
            "submit" -> original.replace("value=\"Recruit\"", "value=\"Delete\"")
            "capacity" -> original.replace("현재 캐릭터: 29", "현재 캐릭터: 45")
            "job" -> original.replace("job-opaque-9", "job-new-9")
            "gender" -> original.replace("gender-server-f", "gender-new-f")
            else -> error(change)
        }
        val harness = Harness()
        harness.stub(latest)

        assertFailsWith<ApiException> {
            harness.service.recruit(7L, RecruitCharacterRequest(initial.jobs.last().id, "새동료", initial.genders.last().id))
        }

        assertEquals(listOf(HofHttpMethod.GET), harness.requests().map(HofRequest::method))
    }

    @Test fun `모집은 최신 응답의 쿠키와 nonce 및 바뀐 이름 필드를 사용한다`() {
        val initial = parser.parse(fixture(), URL, forms.parse(fixture(), URL))
        val latest = fixture().replace("NewName", "CharacterName").replace("fresh-recruitment", "rotated-nonce")
        val harness = Harness()
        harness.stub(latest, latest, responseCookies = mapOf("PHPSESSID" to "rotated-session"))

        harness.service.recruit(7L, RecruitCharacterRequest(initial.jobs.last().id, "동료", initial.genders.last().id))

        assertEquals(listOf("nonce", "Job", "Gender", "CharacterName", "Recruit"), harness.requests().last().formEntries.map { it.name })
        assertEquals("rotated-nonce", harness.requests().last().formEntries.first().value)
        assertEquals(mapOf("PHPSESSID" to "rotated-session"), harness.requestCookies().last())
    }

    @Test fun `모집의 최신 GET과 POST 사이에 다른 계정 행동이 끼어들지 않는다`() {
        val harness = Harness()
        harness.stub(fixture(), fixture())
        val initial = parser.parse(fixture(), URL, forms.parse(fixture(), URL))
        val getObserved = java.util.concurrent.CountDownLatch(1)
        val releaseGet = java.util.concurrent.CountDownLatch(1)
        val otherEntered = java.util.concurrent.CountDownLatch(1)
        val otherStarted = java.util.concurrent.CountDownLatch(1)
        harness.beforeResponse = { request ->
            if (request.method == HofHttpMethod.GET) {
                getObserved.countDown()
                check(releaseGet.await(2, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val recruitment = pool.submit {
                harness.service.recruit(7L, RecruitCharacterRequest(initial.jobs.last().id, "동료", initial.genders.last().id))
            }
            assertTrue(getObserved.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val other = pool.submit {
                otherStarted.countDown()
                harness.executor.executeAccountSequence(7L) {
                    otherEntered.countDown()
                    assertEquals(listOf(HofHttpMethod.GET, HofHttpMethod.POST), harness.requests().map(HofRequest::method))
                }
            }
            assertTrue(otherStarted.await(2, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(otherEntered.await(100, java.util.concurrent.TimeUnit.MILLISECONDS))
            releaseGet.countDown()
            recruitment.get(2, java.util.concurrent.TimeUnit.SECONDS)
            other.get(2, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(0, otherEntered.count)
        } finally {
            releaseGet.countDown()
            pool.shutdownNow()
        }
    }

    private inner class Harness {
        private val accounts = Mockito.mock(AccountQueryRepository::class.java)
        private val cookies = Mockito.mock(CookieQueryRepository::class.java)
        private val gateway = Mockito.mock(AccountHofGateway::class.java)
        private val locations = Mockito.mock(TownLocationResolver::class.java)
        val executor = TownAuthenticatedExecutor(accounts, cookies, HofRequestFactory(), gateway, LoginStateParser(), forms, HofResultParser(), TownActionGuard(), AccountHofMutationFence())
        val service = RecruitmentService(executor, locations, parser)
        var beforeResponse: (HofRequest) -> Unit = {}

        fun stub(vararg responses: String, responseCookies: Map<String, String> = emptyMap()) {
            Mockito.`when`(accounts.findById(7L)).thenReturn(HofAccountEntity(7L, "recruiter", "encrypted", Instant.EPOCH))
            Mockito.`when`(cookies.findValueMapByAccountId(7L)).thenReturn(mapOf("PHPSESSID" to "session"))
            Mockito.`when`(locations.resolve(TownFeatureId.TALENT_AGENCY, null)).thenReturn(ResolvedTownLocation(TownFeatureId.TALENT_AGENCY, URL))
            var index = 0
            Mockito.`when`(gateway.execute(Mockito.eq(7L), anyRequest(), anyCookies())).thenAnswer { invocation ->
                beforeResponse(invocation.getArgument(1))
                HofHttpResponse(200, URL, responses[(index++).coerceAtMost(responses.lastIndex)], responseCookies)
            }
        }

        fun requests(): List<HofRequest> = Mockito.mockingDetails(gateway).invocations.mapNotNull { it.arguments.getOrNull(1) as? HofRequest }
        fun requestCookies(): List<Map<String, String>> = Mockito.mockingDetails(gateway).invocations.map { it.getArgument(2) }
        private fun anyRequest(): HofRequest = Mockito.any(HofRequest::class.java) ?: HofRequest(HofHttpMethod.GET, URL)
        private fun anyCookies(): Map<String, String> = Mockito.anyMap<String, String>() ?: emptyMap()
    }

    private fun fixture() = requireNotNull(javaClass.getResource("/fixtures/town/agency/recruitment.html")).readText()
    private fun liveFixture() = requireNotNull(javaClass.getResource("/fixtures/town/agency/recruitment-live.html")).readText()
    private companion object {
        const val URL = "https://hof.zerosic.com/index.php?menu=recruit"
        const val LIVE_URL = "https://hof.zerosic.com/index.php?recruit"
    }
}
