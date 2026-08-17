package app.spammy.hof.character.command

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.character.dto.CharacterManagementSnapshotResponse
import app.spammy.hof.character.dto.CharacterObservedActionResponse
import app.spammy.hof.character.dto.CharacterActionCandidateResponse
import app.spammy.hof.character.dto.CharacterActionFieldResponse
import app.spammy.hof.character.dto.CharacterIdentityCandidateResponse
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.repository.CharacterQueryRepository
import app.spammy.hof.character.service.CharacterManagementService
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.town.common.model.ParsedTownPage
import app.spammy.hof.town.common.service.TownAuthenticatedExecutor
import app.spammy.hof.town.common.parser.HofFormParser
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import com.fasterxml.jackson.databind.ObjectMapper
import app.spammy.hof.town.common.model.TownSelectionType
import app.spammy.hof.town.common.model.TownActionRequest
import org.mockito.Mockito

class HofCharacterCommandAdapterTest {
    private val revision = Instant.parse("2026-08-17T00:00:00Z")
    private val account = HofAccountEntity(1L, "account", "encrypted", revision)
    private val character = CharacterEntity(7L, account, "hof-10", "소셜", "Social Knight", updatedAt = revision)
    private val context = CharacterCommandContext(1L, 7L, "hof-10")

    @Test
    fun `pray resolves the latest observed semantic form and submits it`() {
        val executor = Mockito.mock(TownAuthenticatedExecutor::class.java)
        val management = Mockito.mock(CharacterManagementService::class.java)
        val query = Mockito.mock(CharacterQueryRepository::class.java)
        arrangeSequence(executor)
        Mockito.`when`(query.findByAccountIdAndId(1L, 7L)).thenReturn(character)
        val page = formPage("<form method='post'><input type='submit' name='Pray' value='기도한다'></form>")
        Mockito.`when`(executor.load(1L, CHARACTER_URL)).thenReturn(page)
        val form = page.forms.single()
        Mockito.`when`(management.execute(1L, "hof-10", app.spammy.hof.town.common.model.TownActionRequest(form.actionId)))
            .thenReturn(CharacterManagementSnapshotResponse(null, emptyList(), listOf("기도 완료")))
        val adapter = HofCharacterCommandAdapter(executor, management, HofRequestFactory(), query)

        val result = adapter.execute(context, CharacterCommand.Pray(7L, revision))

        assertEquals(listOf("기도 완료"), assertIs<CharacterCommandResult.Completed>(result).messages)
    }

    @Test
    fun `unknown form is reported but never submitted`() {
        val executor = Mockito.mock(TownAuthenticatedExecutor::class.java)
        val management = Mockito.mock(CharacterManagementService::class.java)
        val query = Mockito.mock(CharacterQueryRepository::class.java)
        arrangeSequence(executor)
        Mockito.`when`(query.findByAccountIdAndId(1L, 7L)).thenReturn(character)
        Mockito.`when`(executor.load(1L, CHARACTER_URL)).thenReturn(
            formPage("<form method='post'><input type='submit' name='BrandNewFeature' value='새 기능'></form>"),
        )
        val adapter = HofCharacterCommandAdapter(executor, management, HofRequestFactory(), query)

        val result = adapter.execute(context, CharacterCommand.Pray(7L, revision))

        assertEquals("FORM_NOT_OBSERVED", assertIs<CharacterCommandResult.Rejected>(result).code)
        Mockito.verifyNoInteractions(management)
    }

    @Test
    fun `class change uses the job radio observed in the real character form`() {
        val executor = Mockito.mock(TownAuthenticatedExecutor::class.java)
        val management = Mockito.mock(CharacterManagementService::class.java)
        val query = Mockito.mock(CharacterQueryRepository::class.java)
        arrangeSequence(executor)
        Mockito.`when`(query.findByAccountIdAndId(1L, 7L)).thenReturn(character)
        val page = formPage("<form method='post'><input type='radio' name='job' value='523'>Mathematician<input type='submit' name='classchange' value='ClassChange'></form>")
        Mockito.`when`(executor.load(1L, CHARACTER_URL)).thenReturn(page)
        val form = page.forms.single()
        val choice = form.candidates.single { it.inputName == "job" && it.inputValue == "523" }
        Mockito.`when`(
            management.execute(
                1L,
                "hof-10",
                TownActionRequest(form.actionId, selections = listOf(app.spammy.hof.town.common.model.TownActionSelection(choice.id))),
            ),
        )
            .thenReturn(CharacterManagementSnapshotResponse(null, emptyList(), listOf("전직 완료")))
        val adapter = HofCharacterCommandAdapter(executor, management, HofRequestFactory(), query)

        val result = adapter.execute(context, CharacterCommand.ChangeClass(7L, revision, "523"))

        assertEquals(listOf("전직 완료"), assertIs<CharacterCommandResult.Completed>(result).messages)
    }

    @Test
    fun `growth item uses the separate reset selector form`() {
        val executor = Mockito.mock(TownAuthenticatedExecutor::class.java)
        val management = Mockito.mock(CharacterManagementService::class.java)
        val query = Mockito.mock(CharacterQueryRepository::class.java)
        arrangeSequence(executor)
        Mockito.`when`(query.findByAccountIdAndId(1L, 7L)).thenReturn(character)
        val page = formPage(
            "<form method='post'><select name='itemUse'><option value='7510'>Reset Crystal x57</option></select>" +
                "<input type='submit' name='resetVarious' value='Use'></form>",
        )
        Mockito.`when`(executor.load(1L, CHARACTER_URL)).thenReturn(page)
        val form = page.forms.single()
        val choice = form.candidates.single { it.inputName == "itemUse" && it.inputValue == "7510" }
        Mockito.`when`(
            management.execute(
                1L,
                "hof-10",
                TownActionRequest(
                    form.actionId,
                    selections = listOf(app.spammy.hof.town.common.model.TownActionSelection(choice.id)),
                ),
            ),
        ).thenReturn(CharacterManagementSnapshotResponse(null, emptyList(), listOf("성장 아이템 사용 완료")))
        val adapter = HofCharacterCommandAdapter(executor, management, HofRequestFactory(), query)

        val result = adapter.execute(context, CharacterCommand.UseItem(7L, revision, "7510"))

        assertEquals(listOf("성장 아이템 사용 완료"), assertIs<CharacterCommandResult.Completed>(result).messages)
    }

    @Test
    fun `ambiguous knockback returns safe identity candidates to the typed client`() {
        val executor = Mockito.mock(TownAuthenticatedExecutor::class.java)
        val management = Mockito.mock(CharacterManagementService::class.java)
        val query = Mockito.mock(CharacterQueryRepository::class.java)
        arrangeSequence(executor)
        Mockito.`when`(query.findByAccountIdAndId(1L, 7L)).thenReturn(character)
        val page = formPage("<form method='post'><input type='submit' name='knockback' value='Knockback'></form>")
        Mockito.`when`(executor.load(1L, CHARACTER_URL)).thenReturn(page)
        val form = page.forms.single()
        Mockito.`when`(management.execute(1L, "hof-10", TownActionRequest(form.actionId))).thenReturn(
            CharacterManagementSnapshotResponse(
                character = null,
                actions = emptyList(),
                messages = listOf("새 캐릭터 연결을 선택해 주세요."),
                identityResolutionRequired = true,
                identityCandidates = listOf(
                    CharacterIdentityCandidateResponse("hof-11", "소셜", "Social Knight", 60, setOf("name", "job", "level")),
                ),
            ),
        )
        val adapter = HofCharacterCommandAdapter(executor, management, HofRequestFactory(), query)

        val result = assertIs<CharacterCommandResult.IdentityResolutionRequired>(
            adapter.execute(context, CharacterCommand.Knockback(7L, revision, "소셜")),
        )

        assertEquals("hof-11", result.candidates.single().hofCharacterId)
        assertEquals(setOf("name", "job", "level"), result.candidates.single().matchingFields)
    }

    @Test
    fun `controller snapshot json never exposes source form action or field ids`() {
        val snapshot = CharacterManagementSnapshotResponse(
            character = null,
            actions = listOf(
                CharacterObservedActionResponse(
                    actionId = "opaque-action",
                    source = "Pray",
                    label = "기도한다",
                    candidates = listOf(
                        CharacterActionCandidateResponse(
                            id = "opaque-candidate",
                            groupId = "item_no",
                            label = "아이템",
                            selectionType = TownSelectionType.RADIO,
                        ),
                    ),
                    fields = listOf(CharacterActionFieldResponse("opaque-field", "이름")),
                ),
            ),
        )

        val json = ObjectMapper().findAndRegisterModules().writeValueAsString(snapshot)

        listOf("opaque-action", "opaque-candidate", "opaque-field", "item_no", "\"source\"").forEach { forbidden ->
            assertFalse(forbidden in json, json)
        }
    }

    private fun arrangeSequence(executor: TownAuthenticatedExecutor) {
        Mockito.doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            (invocation.arguments[1] as () -> CharacterCommandResult).invoke()
        }.`when`(executor).executeAccountSequence(Mockito.eq(1L), anySequence())
    }

    private fun anySequence(): () -> CharacterCommandResult =
        Mockito.any<() -> CharacterCommandResult>() ?: { error("matcher") }

    private fun formPage(html: String): ParsedTownPage = HofFormParser().parse(html, CHARACTER_URL)

    private companion object {
        const val CHARACTER_URL = "http://sic.zerosic.com/ZeroHOF/index.php?char=hof-10"
    }
}
