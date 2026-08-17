package app.spammy.hof.character.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.character.dto.CharacterDetailResponse
import app.spammy.hof.character.entity.CharacterEntity
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

    private fun response(html: String) = HofHttpResponse(200, CHARACTER_URL, html, emptyMap())

    private fun anyPage(): app.spammy.hof.external.parser.CharacterPageParseResult =
        Mockito.any(app.spammy.hof.external.parser.CharacterPageParseResult::class.java)
            ?: app.spammy.hof.external.parser.CharacterPageParseResult(
                app.spammy.hof.external.model.HofCharacter(id = ""),
                emptyMap(),
            )

    private fun anyRequest(): HofRequest =
        Mockito.any(HofRequest::class.java) ?: HofRequest(HofHttpMethod.GET, CHARACTER_URL)

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
    }
}
