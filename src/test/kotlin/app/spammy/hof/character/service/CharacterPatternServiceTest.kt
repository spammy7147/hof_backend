package app.spammy.hof.character.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.battle.dto.BattlePatternLoadRequest
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.repository.CharacterQueryRepository
import app.spammy.hof.external.client.HofGateway
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.client.testAccountHofGateway
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.external.parser.LoginStateParser
import app.spammy.hof.external.parser.CharacterDetailParser
import app.spammy.hof.character.dto.CharacterDetailResponse
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
    private val sessionPatternLoadTracker = SessionPatternLoadTracker()
    private val characterService = Mockito.mock(CharacterService::class.java)
    private val service = CharacterPatternService(
        accountQueryRepository = accountQueryRepository,
        cookieQueryRepository = cookieQueryRepository,
        characterQueryRepository = characterQueryRepository,
        requestFactory = HofRequestFactory(),
        gateway = testAccountHofGateway(gateway, app.spammy.hof.common.time.TimeProvider { now }),
        loginStateParser = LoginStateParser(),
        detailParser = CharacterDetailParser(),
        characterService = characterService,
        sessionPatternLoadTracker = sessionPatternLoadTracker,
    )

    @Test
    fun loadPatternPostsSavedPatternSlotWithCookies() {
        Mockito.`when`(accountQueryRepository.findById(1L)).thenReturn(account)
        Mockito.`when`(cookieQueryRepository.findValueMapByAccountId(1L))
            .thenReturn(mapOf("PHPSESSID" to "abc"))
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
        assertEquals(false, response.characterSynchronized)
        assertEquals(null, response.character)
        assertEquals("http://sic.zerosic.com/ZeroHOF/index.php?char=1683198503393759", gateway.requests.single().url)
        assertEquals(mapOf("patternno" to "0", "loadpattern" to "LOAD"), gateway.requests.single().formFields)
        assertEquals(mapOf("PHPSESSID" to "abc"), gateway.cookies.single())
    }

    @Test
    fun successfulPatternLoadRefreshesAndReturnsTheParsedCharacter() {
        Mockito.`when`(accountQueryRepository.findById(1L)).thenReturn(account)
        Mockito.`when`(cookieQueryRepository.findValueMapByAccountId(1L)).thenReturn(mapOf("PHPSESSID" to "abc"))
        Mockito.`when`(characterQueryRepository.findByAccountIdAndHofCharacterId(1L, character.hofCharacterId))
            .thenReturn(character)
        gateway.responseBody = """
            <div class="carpet_frame"><img src="image/char/sknight02.gif">소셜 Lv.60 Social Knight</div>
            <form><input name="patternno" value="0"><input name="loadpattern" value="LOAD"></form>
        """.trimIndent()
        val refreshed = Mockito.mock(CharacterDetailResponse::class.java)
        Mockito.`when`(characterService.refreshParsedCharacter(anyAccount(), anyHofCharacter()))
            .thenReturn(refreshed)

        val response = service.loadPattern(1L, character.hofCharacterId, 0)

        assertTrue(response.loaded)
        assertTrue(response.characterSynchronized)
        assertEquals(refreshed, response.character)
    }

    @Test
    fun successfulDirectLoadUpdatesTheSharedSessionState() {
        val cookies = mapOf("PHPSESSID" to "abc")
        val pattern = BattlePatternLoadRequest(character.hofCharacterId, 2)
        Mockito.`when`(accountQueryRepository.findById(1L)).thenReturn(account)
        Mockito.`when`(cookieQueryRepository.findValueMapByAccountId(1L)).thenReturn(cookies)
        Mockito.`when`(
            characterQueryRepository.findByAccountIdAndHofCharacterId(1L, character.hofCharacterId),
        ).thenReturn(character)

        service.loadPattern(1L, character.hofCharacterId, 2)

        sessionPatternLoadTracker.withSession(1L, cookies) { session ->
            assertEquals(emptyList(), session.requiredLoads(listOf(pattern)))
        }
    }

    private class FakeHofGateway : HofGateway {
        val requests = mutableListOf<HofRequest>()
        val cookies = mutableListOf<Map<String, String>>()
        var responseBody = """<div>Funds : $ 1 Time : 10/10</div>"""

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

    private fun anyHofCharacter(): app.spammy.hof.external.model.HofCharacter =
        Mockito.any(app.spammy.hof.external.model.HofCharacter::class.java)
            ?: app.spammy.hof.external.model.HofCharacter(id = "")

    private fun anyAccount(): HofAccountEntity =
        Mockito.any(HofAccountEntity::class.java) ?: account
}
