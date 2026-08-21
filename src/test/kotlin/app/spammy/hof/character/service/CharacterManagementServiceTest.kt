package app.spammy.hof.character.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.character.dto.CharacterDetailResponse
import app.spammy.hof.character.command.CharacterCommand
import app.spammy.hof.character.command.CharacterCommandContext
import app.spammy.hof.character.command.CharacterCommandObservation
import app.spammy.hof.character.command.HofCharacterCommandAdapter
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.repository.CharacterQueryRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.external.client.AccountHofGateway
import app.spammy.hof.external.client.DeferredCharacterRosterHofResponse
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.external.parser.CharacterDetailParser
import app.spammy.hof.external.parser.CharacterRosterParser
import app.spammy.hof.external.parser.LoginStateParser
import app.spammy.hof.town.common.model.TownActionRequest
import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.common.parser.HofResultParser
import app.spammy.hof.town.common.service.TownActionGuard
import app.spammy.hof.town.common.service.TownAuthenticatedExecutor
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.mockito.ArgumentCaptor
import org.mockito.Mockito

class CharacterManagementServiceTest {
    private val now = Instant.parse("2026-08-18T00:00:00Z")
    private val account = HofAccountEntity(1L, "account", "encrypted", now)
    private val character = CharacterEntity(7L, account, "hof-10", "소셜", "Social Knight", updatedAt = now)
    private val accounts = Mockito.mock(AccountQueryRepository::class.java)
    private val cookies = Mockito.mock(CookieQueryRepository::class.java)
    private val characters = Mockito.mock(CharacterQueryRepository::class.java)
    private val gateway = Mockito.mock(AccountHofGateway::class.java)
    private val synchronizer = Mockito.mock(CharacterSnapshotSynchronizer::class.java)
    private val characterService = Mockito.mock(CharacterService::class.java)
    private val detailParser = CharacterDetailParser()
    private val forms = HofFormParser()
    private val executor = TownAuthenticatedExecutor(
        accounts,
        cookies,
        HofRequestFactory(),
        gateway,
        LoginStateParser(),
        forms,
        HofResultParser(),
        TownActionGuard(),
        app.spammy.hof.town.common.service.AccountHofMutationFence(),
    )
    private val service = CharacterManagementService(
        accounts,
        characters,
        HofRequestFactory(),
        executor,
        detailParser,
        synchronizer,
        characterService,
    )
    private val commandAdapter = HofCharacterCommandAdapter(
        executor,
        service,
        HofRequestFactory(),
        CharacterRosterParser(),
        detailParser,
        synchronizer,
    )

    @Test
    fun `item preparation keeps reset candidates from the immediate action response`() {
        Mockito.`when`(accounts.findById(1L)).thenReturn(account)
        Mockito.`when`(cookies.findValueMapByAccountId(1L)).thenReturn(mapOf("PHPSESSID" to "session"))
        Mockito.`when`(characters.findByAccountIdAndHofCharacterId(1L, "hof-10")).thenReturn(character)
        Mockito.`when`(characterService.findAll(1L)).thenReturn(emptyList())
        val parsedSnapshots = mutableListOf<app.spammy.hof.external.parser.CharacterPageParseResult>()
        Mockito.`when`(
            synchronizer.writeParsed(
                Mockito.eq(1L),
                Mockito.anyString(),
                anyPage(),
                Mockito.anySet(),
            ),
        ).thenAnswer { invocation ->
            parsedSnapshots += invocation.getArgument<app.spammy.hof.external.parser.CharacterPageParseResult>(2)
            Mockito.mock(CharacterDetailResponse::class.java)
        }
        val calls = AtomicInteger()
        Mockito.`when`(
            gateway.execute(
                Mockito.eq(1L),
                anyRequest(),
                Mockito.anyMap<String, String>(),
            ),
        ).thenAnswer {
            when (calls.getAndIncrement()) {
                0, 1 -> response(BASE_PAGE)
                2 -> response(RESET_SELECTOR_PAGE)
                else -> response(BASE_PAGE)
            }
        }
        val actionId = forms.parse(BASE_PAGE, CHARACTER_URL).forms.single().actionId

        service.execute(1L, "hof-10", TownActionRequest(actionId))

        assertTrue(
            parsedSnapshots.single().snapshot.equipmentCandidates.any {
                it.typeCode == "resetitem" && it.value == "7510"
            },
        )
    }

    @Test
    fun `reset item use reopens the transient selector and submits it without another page reload`() {
        Mockito.`when`(accounts.findById(1L)).thenReturn(account)
        Mockito.`when`(cookies.findValueMapByAccountId(1L)).thenReturn(mapOf("PHPSESSID" to "session"))
        Mockito.`when`(characters.findByAccountIdAndHofCharacterId(1L, "hof-10")).thenReturn(character)
        Mockito.`when`(characterService.findAll(1L)).thenReturn(emptyList())
        Mockito.`when`(
            synchronizer.writeParsed(
                Mockito.eq(1L),
                Mockito.anyString(),
                anyPage(),
                Mockito.anySet(),
            ),
        ).thenReturn(Mockito.mock(CharacterDetailResponse::class.java))
        Mockito.`when`(
            gateway.execute(
                Mockito.eq(1L),
                anyRequest(),
                Mockito.anyMap<String, String>(),
            ),
        ).thenReturn(response(BASE_PAGE), response(RESET_SELECTOR_PAGE), response(BASE_PAGE))

        val snapshot = requireNotNull(service.executeResetItem(1L, "hof-10", "7510"))

        assertEquals(emptyList(), snapshot.messages)
        val requests = ArgumentCaptor.forClass(HofRequest::class.java)
        Mockito.verify(gateway, Mockito.times(3)).execute(
            Mockito.eq(1L),
            capture(requests, HofRequest(HofHttpMethod.GET, CHARACTER_URL)),
            Mockito.anyMap<String, String>(),
        )
        assertEquals(HofHttpMethod.GET, requests.allValues[0].method)
        assertEquals(mapOf("showreset" to "Use"), requests.allValues[1].formFields)
        assertEquals(
            mapOf("itemUse" to "7510", "resetVarious" to "Use"),
            requests.allValues[2].formFields,
        )
    }

    @Test
    fun `reset item use stops before final submission when the item is no longer offered`() {
        Mockito.`when`(accounts.findById(1L)).thenReturn(account)
        Mockito.`when`(cookies.findValueMapByAccountId(1L)).thenReturn(mapOf("PHPSESSID" to "session"))
        Mockito.`when`(characters.findByAccountIdAndHofCharacterId(1L, "hof-10")).thenReturn(character)
        Mockito.`when`(
            gateway.execute(
                Mockito.eq(1L),
                anyRequest(),
                Mockito.anyMap<String, String>(),
            ),
        ).thenReturn(response(BASE_PAGE), response(RESET_SELECTOR_PAGE))

        val snapshot = service.executeResetItem(1L, "hof-10", "missing-item")

        assertEquals(null, snapshot)
        Mockito.verify(gateway, Mockito.times(2)).execute(
            Mockito.eq(1L),
            anyRequest(),
            Mockito.anyMap<String, String>(),
        )
        Mockito.verifyNoInteractions(synchronizer)
    }

    @Test
    fun `semantic knockback submits both transient confirmations before observing the roster`() {
        Mockito.`when`(accounts.findById(1L)).thenReturn(account)
        Mockito.`when`(cookies.findValueMapByAccountId(1L)).thenReturn(mapOf("PHPSESSID" to "session"))
        val calls = AtomicInteger()
        Mockito.`when`(
            gateway.executeWithoutCharacterRosterObservation(
                Mockito.eq(1L),
                anyRequest(),
                Mockito.anyMap<String, String>(),
            ),
        ).thenAnswer {
            when (calls.getAndIncrement()) {
                0 -> deferredResponse(KNOCKBACK_PAGE)
                1 -> deferredResponse(KNOCKBACK_CONFIRMATION_PAGE)
                2 -> deferredResponse("<div>Knockback complete</div>")
                else -> deferredResponse(REPLACED_ROSTER_PAGE)
            }
        }

        val observed = assertIs<CharacterCommandObservation.KnockbackApplied>(
            execute(CharacterCommand.Knockback(7L, now, "마녀")),
        )

        assertEquals("11", observed.rosterAfter.single().hofCharacterId)
        val requests = ArgumentCaptor.forClass(HofRequest::class.java)
        Mockito.verify(gateway, Mockito.times(4)).executeWithoutCharacterRosterObservation(
            Mockito.eq(1L),
            capture(requests, HofRequest(HofHttpMethod.GET, CHARACTER_URL)),
            Mockito.anyMap<String, String>(),
        )
        assertEquals(mapOf("knockback" to "Knockback"), requests.allValues[1].formFields)
        assertEquals(mapOf("knockback2" to "Yes"), requests.allValues[2].formFields)
    }

    @Test
    fun `semantic kick submits all three transient confirmations before observing removal`() {
        Mockito.`when`(accounts.findById(1L)).thenReturn(account)
        Mockito.`when`(cookies.findValueMapByAccountId(1L)).thenReturn(mapOf("PHPSESSID" to "session"))
        val calls = AtomicInteger()
        Mockito.`when`(
            gateway.executeWithoutCharacterRosterObservation(
                Mockito.eq(1L),
                anyRequest(),
                Mockito.anyMap<String, String>(),
            ),
        ).thenAnswer {
            when (calls.getAndIncrement()) {
                0 -> deferredResponse(KICK_PAGE)
                1 -> deferredResponse(KICK_CONFIRMATION_PAGE)
                2 -> deferredResponse(KICK_FINAL_CONFIRMATION_PAGE)
                3 -> deferredResponse("<div>Kick complete</div>")
                else -> deferredResponse(ROSTER_AFTER_KICK_PAGE)
            }
        }

        val observed = assertIs<CharacterCommandObservation.KickApplied>(
            execute(CharacterCommand.Kick(7L, now, "소셜")),
        )

        assertEquals("22", observed.rosterAfter.single().hofCharacterId)
        val requests = ArgumentCaptor.forClass(HofRequest::class.java)
        Mockito.verify(gateway, Mockito.times(5)).executeWithoutCharacterRosterObservation(
            Mockito.eq(1L),
            capture(requests, HofRequest(HofHttpMethod.GET, CHARACTER_URL)),
            Mockito.anyMap<String, String>(),
        )
        assertEquals(mapOf("byebye" to "Kick"), requests.allValues[1].formFields)
        assertEquals(mapOf("byebye2" to "Dismiss"), requests.allValues[2].formFields)
        assertEquals(mapOf("byebye3" to "Yes"), requests.allValues[3].formFields)
    }

    @Test
    fun `semantic kick stops when the third confirmation form is absent`() {
        Mockito.`when`(accounts.findById(1L)).thenReturn(account)
        Mockito.`when`(cookies.findValueMapByAccountId(1L)).thenReturn(mapOf("PHPSESSID" to "session"))
        val calls = AtomicInteger()
        Mockito.`when`(
            gateway.executeWithoutCharacterRosterObservation(
                Mockito.eq(1L),
                anyRequest(),
                Mockito.anyMap<String, String>(),
            ),
        ).thenAnswer {
            when (calls.getAndIncrement()) {
                0 -> deferredResponse(KICK_PAGE)
                1 -> deferredResponse(KICK_CONFIRMATION_PAGE)
                else -> deferredResponse("<div>Third confirmation unavailable</div>")
            }
        }

        val observed = assertIs<CharacterCommandObservation.Rejected>(
            execute(CharacterCommand.Kick(7L, now, "소셜")),
        )

        assertEquals("FORM_NOT_OBSERVED", observed.code)
        Mockito.verify(gateway, Mockito.times(3)).executeWithoutCharacterRosterObservation(
            Mockito.eq(1L),
            anyRequest(),
            Mockito.anyMap<String, String>(),
        )
    }

    @Test
    fun `generic management execution rejects identity forms before submitting them`() {
        Mockito.`when`(accounts.findById(1L)).thenReturn(account)
        Mockito.`when`(cookies.findValueMapByAccountId(1L)).thenReturn(mapOf("PHPSESSID" to "session"))
        Mockito.`when`(characters.findByAccountIdAndHofCharacterId(1L, "hof-10")).thenReturn(character)
        Mockito.`when`(
            gateway.execute(
                Mockito.eq(1L),
                anyRequest(),
                Mockito.anyMap<String, String>(),
            ),
        ).thenReturn(response(KICK_PAGE))
        val actionId = forms.parse(KICK_PAGE, CHARACTER_URL).forms.single().actionId

        assertFailsWith<ApiException> { service.execute(1L, "hof-10", TownActionRequest(actionId)) }

        Mockito.verify(gateway, Mockito.times(1)).execute(
            Mockito.eq(1L),
            anyRequest(),
            Mockito.anyMap<String, String>(),
        )
    }

    private fun execute(command: CharacterCommand): CharacterCommandObservation =
        commandAdapter.withSession(1L) { session ->
            session.execute(CharacterCommandContext(1L, 7L, "hof-10"), command)
        }

    private fun response(html: String) = HofHttpResponse(200, CHARACTER_URL, html, emptyMap())

    private fun deferredResponse(html: String) = DeferredCharacterRosterHofResponse(response(html), now)

    private fun anyPage(): app.spammy.hof.external.parser.CharacterPageParseResult =
        Mockito.any(app.spammy.hof.external.parser.CharacterPageParseResult::class.java)
            ?: app.spammy.hof.external.parser.CharacterPageParseResult(
                app.spammy.hof.external.model.HofCharacter(id = ""),
                emptyMap(),
            )

    private fun anyRequest(): HofRequest =
        Mockito.any(HofRequest::class.java) ?: HofRequest(HofHttpMethod.GET, CHARACTER_URL)

    private fun <T : Any> capture(captor: ArgumentCaptor<T>, fallback: T): T = captor.capture() ?: fallback

    companion object {
        private const val CHARACTER_URL = "http://sic.zerosic.com/ZeroHOF/index.php?char=hof-10"
        private const val BASE_PAGE = """
            <div class="carpet_frame">소셜<br>Lv.60 Social Knight</div>
            <form action="?char=hof-10" method="post">
              <input type="submit" name="showreset" value="Use">
            </form>
        """
        private const val RESET_SELECTOR_PAGE = """
            <div class="carpet_frame">소셜<br>Lv.60 Social Knight</div>
            <form action="?char=hof-10" method="post">
              <select name="itemUse"><option value="7510">Reset Crystal x 2</option></select>
              <input type="submit" name="resetVarious" value="Use">
            </form>
        """
        private const val KNOCKBACK_PAGE = """
            <div class="carpet_frame">소셜<br>Lv.60 Social Knight</div>
            <form action="?char=hof-10" method="post">
              <input type="submit" name="knockback" value="Knockback">
            </form>
        """
        private const val KNOCKBACK_CONFIRMATION_PAGE = """
            <div class="carpet_frame">소셜<br>Lv.60 Social Knight</div>
            <form action="?char=hof-10" method="post">
              <input type="submit" name="knockback2" value="Yes">
              <input type="submit" value="No">
            </form>
        """
        private const val REPLACED_ROSTER_PAGE = """
            <div class="character-card">
              <a href="?char=11"><img src="witch.gif"></a><br>
              마녀<br>
              Lv.60 Great Witch
            </div>
        """
        private const val KICK_PAGE = """
            <div class="carpet_frame">소셜<br>Lv.60 Social Knight</div>
            <form action="?char=hof-10" method="post">
              <input type="submit" name="byebye" value="Kick">
            </form>
        """
        private const val KICK_CONFIRMATION_PAGE = """
            <div class="carpet_frame">소셜<br>Lv.60 Social Knight</div>
            <form action="?char=hof-10" method="post">
              <input type="submit" name="byebye2" value="Dismiss">
              <input type="submit" value="Cancel">
            </form>
        """
        private const val KICK_FINAL_CONFIRMATION_PAGE = """
            <div class="carpet_frame">소셜<br>Lv.60 Social Knight</div>
            <form action="?char=hof-10" method="post">
              <input type="submit" name="byebye3" value="Yes">
              <input type="submit" value="No">
            </form>
        """
        private const val ROSTER_AFTER_KICK_PAGE = """
            <a href="?char=22">다른 캐릭터</a>
        """
    }
}
