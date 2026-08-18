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
import kotlin.test.assertTrue
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
    fun `initial knockback returns the original confirmation form without reconciling the roster`() {
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
                else -> response(KNOCKBACK_CONFIRMATION_PAGE)
            }
        }
        val actionId = forms.parse(KNOCKBACK_PAGE, CHARACTER_URL).forms.single().actionId

        val snapshot = service.execute(1L, "hof-10", TownActionRequest(actionId))

        assertEquals("knockback2", snapshot.actions.single { it.source == "knockback2" }.source)
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
    fun `initial kick returns the original confirmation form without archiving the target`() {
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
                else -> response(KICK_CONFIRMATION_PAGE)
            }
        }
        val actionId = forms.parse(KICK_PAGE, CHARACTER_URL).forms.single().actionId

        val snapshot = service.execute(1L, "hof-10", TownActionRequest(actionId))

        assertEquals("byebye2", snapshot.actions.single { it.source == "byebye2" }.source)
        Mockito.verifyNoInteractions(identityResolver, lifecycleService)
    }

    @Test
    fun `confirmed kick archives the target only after the second form is submitted`() {
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
                0, 1 -> response(KICK_CONFIRMATION_PAGE)
                2 -> response("<div>Kick complete</div>")
                else -> response(ROSTER_AFTER_KICK_PAGE)
            }
        }
        val actionId = forms.parse(KICK_CONFIRMATION_PAGE, CHARACTER_URL).forms
            .single { it.submitSource == "byebye2" }
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
        private const val ROSTER_AFTER_KICK_PAGE = """
            <a href="?char=22">다른 캐릭터</a>
        """
    }
}
