package app.spammy.hof.character.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.entity.HofCookieEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.repository.CharacterQueryRepository
import app.spammy.hof.external.client.HofGateway
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.external.parser.LoginStateParser
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CharacterPatternServiceTest {
    private val now = Instant.parse("2026-07-08T00:00:00Z")
    private val account = HofAccountEntity(
        id = 1L,
        loginId = "abcd12",
        encryptedPassword = "qwer12",
        createdAt = now,
    )
    private val character = CharacterEntity(
        id = 10L,
        account = account,
        hofCharacterId = "1683198503393759",
        name = "소셜",
        job = "Social Knight",
        updatedAt = now,
    )
    private val accountQueryRepository = Mockito.mock(AccountQueryRepository::class.java)
    private val cookieQueryRepository = Mockito.mock(CookieQueryRepository::class.java)
    private val characterQueryRepository = Mockito.mock(CharacterQueryRepository::class.java)
    private val gateway = FakeHofGateway()
    private val service = CharacterPatternService(
        accountQueryRepository = accountQueryRepository,
        cookieQueryRepository = cookieQueryRepository,
        characterQueryRepository = characterQueryRepository,
        requestFactory = HofRequestFactory(),
        gateway = gateway,
        loginStateParser = LoginStateParser(),
    )

    @Test
    fun loadPatternPostsSavedPatternSlotWithCookies() {
        Mockito.`when`(accountQueryRepository.findById(1L)).thenReturn(account)
        Mockito.`when`(cookieQueryRepository.findByAccountId(1L)).thenReturn(
            listOf(
                HofCookieEntity(
                    account = account,
                    name = "PHPSESSID",
                    value = "abc",
                    updatedAt = now,
                ),
            ),
        )
        Mockito.`when`(
            characterQueryRepository.findByAccountIdAndHofCharacterId(1L, "1683198503393759"),
        ).thenReturn(character)

        val response = service.loadPattern(
            accountId = 1L,
            hofCharacterId = "1683198503393759",
            slot = 0,
        )

        assertTrue(response.loaded)
        assertEquals(0, response.slot)
        assertEquals("패턴 로드 완료", response.message)
        assertEquals("http://sic.zerosic.com/ZeroHOF/index.php?char=1683198503393759", gateway.requests.single().url)
        assertEquals(mapOf("patternno" to "0", "loadpattern" to "LOAD"), gateway.requests.single().formFields)
        assertEquals(mapOf("PHPSESSID" to "abc"), gateway.cookies.single())
    }

    private class FakeHofGateway : HofGateway {
        val requests = mutableListOf<HofRequest>()
        val cookies = mutableListOf<Map<String, String>>()

        override fun execute(request: HofRequest, cookies: Map<String, String>): HofHttpResponse {
            requests += request
            this.cookies += cookies
            return HofHttpResponse(
                statusCode = 200,
                finalUrl = request.url,
                body = """<div>Funds : $ 1 Time : 10/10</div>""",
                setCookies = emptyMap(),
            )
        }
    }
}
