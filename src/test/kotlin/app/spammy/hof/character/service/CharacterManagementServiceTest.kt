package app.spammy.hof.character.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.character.dto.CharacterDetailResponse
import app.spammy.hof.character.dto.CharacterResponse
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.entity.CharacterHofIdLinkReason
import app.spammy.hof.character.identity.CharacterIdentityEvidence
import app.spammy.hof.character.identity.CharacterIdentityResolution
import app.spammy.hof.character.identity.CharacterIdentityResolver
import app.spammy.hof.character.identity.CharacterLifecycleService
import app.spammy.hof.character.repository.CharacterQueryRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.external.client.AccountHofGateway
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
    private val identityResolver = Mockito.mock(CharacterIdentityResolver::class.java)
    private val lifecycleService = Mockito.mock(CharacterLifecycleService::class.java)
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
    )
    private val service = CharacterManagementService(
        accounts,
        characters,
        HofRequestFactory(),
        executor,
        detailParser,
        synchronizer,
        CharacterRosterParser(),
        characterService,
        identityResolver,
        lifecycleService,
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
    fun `knockback submits the transient confirmation before reloading the character page`() {
        Mockito.`when`(accounts.findById(1L)).thenReturn(account)
        Mockito.`when`(cookies.findValueMapByAccountId(1L)).thenReturn(mapOf("PHPSESSID" to "session"))
        Mockito.`when`(characters.findByAccountIdAndHofCharacterId(1L, "hof-10")).thenReturn(character)
        val before = CharacterResponse(7L, "hof-10", "마녀", "Great Witch", 60, 0, null, revision = now)
        Mockito.`when`(characterService.findAll(1L)).thenReturn(listOf(before))
        Mockito.`when`(
            synchronizer.writeParsed(
                Mockito.eq(1L),
                Mockito.anyString(),
                anyPage(),
                Mockito.anySet(),
            ),
        ).thenReturn(Mockito.mock(CharacterDetailResponse::class.java))
        val replacement = CharacterIdentityEvidence("11", "마녀", "Great Witch", 60)
        Mockito.`when`(
            identityResolver.resolveKnockback(anyIdentityEvidence(), anyIdentityEvidenceList(), anyIdentityEvidenceList()),
        ).thenReturn(CharacterIdentityResolution.Confirmed(replacement))
        val calls = AtomicInteger()
        Mockito.`when`(
            gateway.execute(
                Mockito.eq(1L),
                anyRequest(),
                Mockito.anyMap<String, String>(),
            ),
        ).thenAnswer {
            when (calls.getAndIncrement()) {
                0, 1 -> response(KNOCKBACK_PAGE)
                2 -> response(KNOCKBACK_CONFIRMATION_PAGE)
                3 -> response("<div>Knockback complete</div>")
                4 -> response(REPLACED_ROSTER_PAGE)
                else -> response(REPLACEMENT_DETAIL_PAGE)
            }
        }
        val actionId = forms.parse(KNOCKBACK_PAGE, CHARACTER_URL).forms.single().actionId

        service.execute(1L, "hof-10", TownActionRequest(actionId))

        val requests = ArgumentCaptor.forClass(HofRequest::class.java)
        Mockito.verify(gateway, Mockito.times(6)).execute(
            Mockito.eq(1L),
            capture(requests, HofRequest(HofHttpMethod.GET, CHARACTER_URL)),
            Mockito.anyMap<String, String>(),
        )
        assertEquals(mapOf("knockback" to "Knockback"), requests.allValues[2].formFields)
        assertEquals(mapOf("knockback2" to "Yes"), requests.allValues[3].formFields)
        Mockito.verify(lifecycleService).link(1L, 7L, "11", CharacterHofIdLinkReason.KNOCKBACK, false)
    }

    @Test
    fun `kick submits all three transient forms before archiving the character`() {
        Mockito.`when`(accounts.findById(1L)).thenReturn(account)
        Mockito.`when`(cookies.findValueMapByAccountId(1L)).thenReturn(mapOf("PHPSESSID" to "session"))
        Mockito.`when`(characters.findByAccountIdAndHofCharacterId(1L, "hof-10")).thenReturn(character)
        val before = CharacterResponse(7L, "hof-10", "소셜", "Social Knight", 60, 0, null, revision = now)
        Mockito.`when`(characterService.findAll(1L)).thenReturn(listOf(before))
        val calls = AtomicInteger()
        Mockito.`when`(
            gateway.execute(
                Mockito.eq(1L),
                anyRequest(),
                Mockito.anyMap<String, String>(),
            ),
        ).thenAnswer {
            when (calls.getAndIncrement()) {
                0, 1 -> response(KICK_PAGE)
                2 -> response(KICK_CONFIRMATION_PAGE)
                3 -> response(KICK_FINAL_CONFIRMATION_PAGE)
                4 -> response("<div>Kick complete</div>")
                else -> response(ROSTER_AFTER_KICK_PAGE)
            }
        }
        val actionId = forms.parse(KICK_PAGE, CHARACTER_URL).forms.single().actionId

        val snapshot = service.execute(1L, "hof-10", TownActionRequest(actionId))

        assertTrue(snapshot.targetRemoved)
        val requests = ArgumentCaptor.forClass(HofRequest::class.java)
        Mockito.verify(gateway, Mockito.times(6)).execute(
            Mockito.eq(1L),
            capture(requests, HofRequest(HofHttpMethod.GET, CHARACTER_URL)),
            Mockito.anyMap<String, String>(),
        )
        assertEquals(mapOf("byebye" to "Kick"), requests.allValues[2].formFields)
        assertEquals(mapOf("byebye2" to "Dismiss"), requests.allValues[3].formFields)
        assertEquals(mapOf("byebye3" to "Yes"), requests.allValues[4].formFields)
        Mockito.verify(lifecycleService).archive(1L, 7L)
    }

    @Test
    fun `kick stops without archiving when the third confirmation form is absent`() {
        Mockito.`when`(accounts.findById(1L)).thenReturn(account)
        Mockito.`when`(cookies.findValueMapByAccountId(1L)).thenReturn(mapOf("PHPSESSID" to "session"))
        Mockito.`when`(characters.findByAccountIdAndHofCharacterId(1L, "hof-10")).thenReturn(character)
        Mockito.`when`(characterService.findAll(1L)).thenReturn(
            listOf(CharacterResponse(7L, "hof-10", "소셜", "Social Knight", 60, 0, null, revision = now)),
        )
        val calls = AtomicInteger()
        Mockito.`when`(
            gateway.execute(
                Mockito.eq(1L),
                anyRequest(),
                Mockito.anyMap<String, String>(),
            ),
        ).thenAnswer {
            when (calls.getAndIncrement()) {
                0, 1 -> response(KICK_PAGE)
                2 -> response(KICK_CONFIRMATION_PAGE)
                else -> response("<div>Third confirmation unavailable</div>")
            }
        }
        val actionId = forms.parse(KICK_PAGE, CHARACTER_URL).forms.single().actionId

        assertFailsWith<ApiException> {
            service.execute(1L, "hof-10", TownActionRequest(actionId))
        }

        Mockito.verify(gateway, Mockito.times(4)).execute(
            Mockito.eq(1L),
            anyRequest(),
            Mockito.anyMap<String, String>(),
        )
        Mockito.verifyNoInteractions(identityResolver, lifecycleService)
    }

    @Test
    fun `confirmed knockback reconciles the roster only after the second form is submitted`() {
        Mockito.`when`(accounts.findById(1L)).thenReturn(account)
        Mockito.`when`(cookies.findValueMapByAccountId(1L)).thenReturn(mapOf("PHPSESSID" to "session"))
        Mockito.`when`(characters.findByAccountIdAndHofCharacterId(1L, "hof-10")).thenReturn(character)
        val before = CharacterResponse(7L, "hof-10", "마녀", "Great Witch", 60, 0, null, revision = now)
        Mockito.`when`(characterService.findAll(1L)).thenReturn(listOf(before))
        Mockito.`when`(
            synchronizer.writeParsed(
                Mockito.eq(1L),
                Mockito.anyString(),
                anyPage(),
                Mockito.anySet(),
            ),
        ).thenReturn(Mockito.mock(CharacterDetailResponse::class.java))
        val replacement = CharacterIdentityEvidence("11", "마녀", "Great Witch", 60)
        Mockito.`when`(
            identityResolver.resolveKnockback(anyIdentityEvidence(), anyIdentityEvidenceList(), anyIdentityEvidenceList()),
        ).thenReturn(CharacterIdentityResolution.Confirmed(replacement))
        val calls = AtomicInteger()
        Mockito.`when`(
            gateway.execute(
                Mockito.eq(1L),
                anyRequest(),
                Mockito.anyMap<String, String>(),
            ),
        ).thenAnswer {
            when (calls.getAndIncrement()) {
                0, 1 -> response(KNOCKBACK_CONFIRMATION_PAGE)
                2 -> response("<div>Knockback complete</div>")
                3 -> response(REPLACED_ROSTER_PAGE)
                else -> response(REPLACEMENT_DETAIL_PAGE)
            }
        }
        val actionId = forms.parse(KNOCKBACK_CONFIRMATION_PAGE, CHARACTER_URL).forms
            .single { it.submitSource == "knockback2" }
            .actionId

        service.execute(1L, "hof-10", TownActionRequest(actionId))

        Mockito.verify(identityResolver).resolveKnockback(
            anyIdentityEvidence(),
            anyIdentityEvidenceList(),
            anyIdentityEvidenceList(),
        )
        Mockito.verify(lifecycleService).link(1L, 7L, "11", CharacterHofIdLinkReason.KNOCKBACK, false)
    }

    @Test
    fun `confirmed kick archives the target only after the third form is submitted`() {
        Mockito.`when`(accounts.findById(1L)).thenReturn(account)
        Mockito.`when`(cookies.findValueMapByAccountId(1L)).thenReturn(mapOf("PHPSESSID" to "session"))
        Mockito.`when`(characters.findByAccountIdAndHofCharacterId(1L, "hof-10")).thenReturn(character)
        val before = CharacterResponse(7L, "hof-10", "소셜", "Social Knight", 60, 0, null, revision = now)
        Mockito.`when`(characterService.findAll(1L)).thenReturn(listOf(before))
        Mockito.`when`(
            synchronizer.writeParsed(
                Mockito.eq(1L),
                Mockito.anyString(),
                anyPage(),
                Mockito.anySet(),
            ),
        ).thenReturn(Mockito.mock(CharacterDetailResponse::class.java))
        val calls = AtomicInteger()
        Mockito.`when`(
            gateway.execute(
                Mockito.eq(1L),
                anyRequest(),
                Mockito.anyMap<String, String>(),
            ),
        ).thenAnswer {
            when (calls.getAndIncrement()) {
                0, 1 -> response(KICK_FINAL_CONFIRMATION_PAGE)
                2 -> response("<div>Kick complete</div>")
                else -> response(ROSTER_AFTER_KICK_PAGE)
            }
        }
        val actionId = forms.parse(KICK_FINAL_CONFIRMATION_PAGE, CHARACTER_URL).forms
            .single { it.submitSource == "byebye3" }
            .actionId

        val snapshot = service.execute(1L, "hof-10", TownActionRequest(actionId))

        assertTrue(snapshot.targetRemoved)
        Mockito.verify(lifecycleService).archive(1L, 7L)
        Mockito.verifyNoInteractions(identityResolver)
    }

    private fun response(html: String) = HofHttpResponse(200, CHARACTER_URL, html, emptyMap())

    private fun anyPage(): app.spammy.hof.external.parser.CharacterPageParseResult =
        Mockito.any(app.spammy.hof.external.parser.CharacterPageParseResult::class.java)
            ?: app.spammy.hof.external.parser.CharacterPageParseResult(
                app.spammy.hof.external.model.HofCharacter(id = ""),
                emptyMap(),
            )

    private fun anyRequest(): HofRequest =
        Mockito.any(HofRequest::class.java) ?: HofRequest(HofHttpMethod.GET, CHARACTER_URL)

    private fun anyIdentityEvidence(): CharacterIdentityEvidence =
        Mockito.any(CharacterIdentityEvidence::class.java) ?: CharacterIdentityEvidence("", "")

    private fun anyIdentityEvidenceList(): List<CharacterIdentityEvidence> =
        Mockito.anyList<CharacterIdentityEvidence>() ?: emptyList()

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
        private const val REPLACEMENT_DETAIL_PAGE = """
            <div class="carpet_frame">마녀<br>Lv.60 Great Witch</div>
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
