package app.spammy.hof.status.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.repository.CharacterQueryRepository
import app.spammy.hof.external.client.HofGateway
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.client.testAccountHofGateway
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.external.parser.HofMainStatusParser
import app.spammy.hof.external.parser.CharacterRosterParser
import app.spammy.hof.external.parser.LoginStateParser
import java.time.Instant
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HofStatusServiceTest {
    private val now = Instant.parse("2026-07-12T00:00:00Z")
    private val account = HofAccountEntity(
        id = 1L,
        loginId = "abcd12",
        encryptedPassword = "encrypted-password",
        createdAt = now,
    )
    private val accountQueryRepository = Mockito.mock(AccountQueryRepository::class.java)
    private val cookieQueryRepository = Mockito.mock(CookieQueryRepository::class.java)
    private val gateway = FakeHofGateway()
    private val characterQueryRepository = Mockito.mock(CharacterQueryRepository::class.java)
    private val service = HofStatusService(
        accountQueryRepository = accountQueryRepository,
        cookieQueryRepository = cookieQueryRepository,
        requestFactory = HofRequestFactory(),
        gateway = testAccountHofGateway(gateway, TimeProvider { now }),
        loginStateParser = LoginStateParser(),
        statusParser = HofMainStatusParser(),
        rosterParser = CharacterRosterParser(),
        characterQueryRepository = characterQueryRepository,
        timeProvider = TimeProvider { now },
    )

    @Test
    fun fetchUsesStoredCookiesAndReturnsParsedStatus() {
        Mockito.`when`(accountQueryRepository.findById(1L)).thenReturn(account)
        Mockito.`when`(cookieQueryRepository.findValueMapByAccountId(1L))
            .thenReturn(mapOf("PHPSESSID" to "session-value"))
        Mockito.`when`(characterQueryRepository.findAllByAccountId(1L)).thenReturn(
            listOf(character("111", now), character("222", null)),
        )
        gateway.responseBody += """
            <a href="?char=111">첫째</a><a href="?char=222">둘째</a>
        """.trimIndent()

        val response = service.fetch(1L)

        assertEquals(1L, response.accountId)
        assertEquals("《얼어붙은 손길》공민이", response.playerName)
        assertEquals(309385362L, response.funds)
        assertEquals(6000, response.timeCurrent)
        assertEquals(6000, response.timeMax)
        assertEquals("Nothing", response.work)
        assertEquals("item/funds", response.auction)
        assertEquals(now, response.observedAt)
        assertEquals(2, response.totalCharacterCount)
        assertEquals(1, response.synchronizedCharacterCount)
        assertTrue(response.characterSyncRequired)
        assertEquals("http://sic.zerosic.com/ZeroHOF/index.php", gateway.requests.single().url)
        assertEquals(mapOf("PHPSESSID" to "session-value"), gateway.cookies.single())
    }

    @Test
    fun fetchDoesNotRequireSyncWhenRemoteAndDetailedLocalRostersMatch() {
        Mockito.`when`(accountQueryRepository.findById(1L)).thenReturn(account)
        Mockito.`when`(cookieQueryRepository.findValueMapByAccountId(1L))
            .thenReturn(mapOf("PHPSESSID" to "session-value"))
        Mockito.`when`(characterQueryRepository.findAllByAccountId(1L)).thenReturn(
            listOf(character("111", now)),
        )
        gateway.responseBody += """<a href="?char=111">첫째</a>"""

        val response = service.fetch(1L)

        assertEquals(1, response.totalCharacterCount)
        assertEquals(1, response.synchronizedCharacterCount)
        assertEquals(false, response.characterSyncRequired)
        assertEquals(1, gateway.requests.size)
    }

    private fun character(hofId: String, syncedAt: Instant?): CharacterEntity = CharacterEntity(
        account = account,
        hofCharacterId = hofId,
        name = hofId,
        job = "job",
        updatedAt = now,
        detailSyncedAt = syncedAt,
    )

    @Test
    fun fetchRejectsMissingAccountWithExistingErrorContract() {
        Mockito.`when`(accountQueryRepository.findById(99L)).thenReturn(null)

        val error = assertFailsWith<ApiException> { service.fetch(99L) }

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, error.errorCode)
        assertEquals("HOF 계정을 찾지 못했습니다.", error.message)
        Mockito.verifyNoInteractions(cookieQueryRepository)
    }

    @Test
    fun fetchRejectsAccountWithoutStoredCookies() {
        Mockito.`when`(accountQueryRepository.findById(1L)).thenReturn(account)
        Mockito.`when`(cookieQueryRepository.findValueMapByAccountId(1L)).thenReturn(emptyMap())

        val error = assertFailsWith<ApiException> { service.fetch(1L) }

        assertEquals(ErrorCode.HOF_SESSION_EXPIRED, error.errorCode)
        assertEquals("저장된 HOF 로그인 쿠키가 없습니다.", error.message)
        assertEquals(emptyList(), gateway.requests)
    }

    @Test
    fun fetchRejectsStoredCookiesWhenHomeIsLoggedOut() {
        Mockito.`when`(accountQueryRepository.findById(1L)).thenReturn(account)
        Mockito.`when`(cookieQueryRepository.findValueMapByAccountId(1L))
            .thenReturn(mapOf("PHPSESSID" to "expired-session"))
        gateway.responseBody = """
            <div>《순금 120%》켄류</div>
            <form method="post">
              <input name="id">
              <input name="pass" type="password">
              <input name="Login" value="login">
            </form>
        """.trimIndent()

        val error = assertFailsWith<ApiException> { service.fetch(1L) }

        assertEquals(ErrorCode.HOF_SESSION_EXPIRED, error.errorCode)
        assertEquals("HOF 로그인 세션이 만료되었습니다.", error.message)
    }

    private class FakeHofGateway : HofGateway {
        val requests = mutableListOf<HofRequest>()
        val cookies = mutableListOf<Map<String, String>>()
        var responseBody = """
            <table>
              <tr>
                <td>《얼어붙은 손길》공민이</td>
                <td>Funds : ${'$'} 309,385,362<br>Work : Nothing</td>
                <td>Time : 6000/6000<br>Auction : item/funds</td>
              </tr>
            </table>
        """.trimIndent()

        override fun execute(request: HofRequest, cookies: Map<String, String>): HofHttpResponse {
            requests += request
            this.cookies += cookies
            return HofHttpResponse(
                statusCode = 200,
                finalUrl = request.url,
                body = responseBody,
                setCookies = emptyMap(),
            )
        }
    }
}
