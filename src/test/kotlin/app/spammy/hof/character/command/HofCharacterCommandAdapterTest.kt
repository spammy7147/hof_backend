package app.spammy.hof.character.command

import app.spammy.hof.character.dto.CharacterManagementSnapshotResponse
import app.spammy.hof.character.dto.CharacterObservedActionResponse
import app.spammy.hof.character.dto.CharacterActionCandidateResponse
import app.spammy.hof.character.dto.CharacterActionFieldResponse
import app.spammy.hof.character.dto.CharacterIdentityCandidateResponse
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
    private val context = CharacterCommandContext(1L, 7L, "hof-10")

    @Test
    fun `pray resolves the latest observed semantic form and submits it`() {
        val executor = Mockito.mock(TownAuthenticatedExecutor::class.java)
        val management = Mockito.mock(CharacterManagementService::class.java)
        arrangeSequence(executor)
        val page = formPage("<form method='post'><input type='submit' name='Pray' value='기도한다'></form>")
        Mockito.`when`(executor.load(1L, CHARACTER_URL)).thenReturn(page)
        val form = page.forms.single()
        Mockito.`when`(management.execute(1L, "hof-10", app.spammy.hof.town.common.model.TownActionRequest(form.actionId)))
            .thenReturn(CharacterManagementSnapshotResponse(null, emptyList(), listOf("기도 완료")))
        val adapter = HofCharacterCommandAdapter(executor, management, HofRequestFactory())

        val result = execute(adapter, CharacterCommand.Pray(7L, revision))

        assertEquals(listOf("기도 완료"), assertIs<CharacterCommandObservation.Applied>(result).messages)
    }

    @Test
    fun `unknown form is reported but never submitted`() {
        val executor = Mockito.mock(TownAuthenticatedExecutor::class.java)
        val management = Mockito.mock(CharacterManagementService::class.java)
        arrangeSequence(executor)
        Mockito.`when`(executor.load(1L, CHARACTER_URL)).thenReturn(
            formPage("<form method='post'><input type='submit' name='BrandNewFeature' value='새 기능'></form>"),
        )
        val adapter = HofCharacterCommandAdapter(executor, management, HofRequestFactory())

        val result = execute(adapter, CharacterCommand.Pray(7L, revision))

        assertEquals("FORM_NOT_OBSERVED", assertIs<CharacterCommandObservation.Rejected>(result).code)
        Mockito.verifyNoInteractions(management)
    }

    @Test
    fun `class change uses the job radio observed in the real character form`() {
        val executor = Mockito.mock(TownAuthenticatedExecutor::class.java)
        val management = Mockito.mock(CharacterManagementService::class.java)
        arrangeSequence(executor)
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
        val adapter = HofCharacterCommandAdapter(executor, management, HofRequestFactory())

        val result = execute(adapter, CharacterCommand.ChangeClass(7L, revision, "523"))

        assertEquals(listOf("전직 완료"), assertIs<CharacterCommandObservation.Applied>(result).messages)
    }

    @Test
    fun `growth item reopens the transient reset selector through management service`() {
        val executor = Mockito.mock(TownAuthenticatedExecutor::class.java)
        val management = Mockito.mock(CharacterManagementService::class.java)
        arrangeSequence(executor)
        val page = formPage("<form method='post'><input type='submit' name='showreset' value='Use'></form>")
        Mockito.`when`(executor.load(1L, CHARACTER_URL)).thenReturn(page)
        Mockito.`when`(management.executeResetItem(1L, "hof-10", "7510"))
            .thenReturn(CharacterManagementSnapshotResponse(null, emptyList(), listOf("성장 아이템 사용 완료")))
        val adapter = HofCharacterCommandAdapter(executor, management, HofRequestFactory())

        val result = execute(adapter, CharacterCommand.UseItem(7L, revision, "7510"))

        assertEquals(listOf("성장 아이템 사용 완료"), assertIs<CharacterCommandObservation.Applied>(result).messages)
        Mockito.verify(management).executeResetItem(1L, "hof-10", "7510")
    }

    @Test
    fun `preparing item use opens the reset selector before the item snapshot is shown`() {
        val executor = Mockito.mock(TownAuthenticatedExecutor::class.java)
        val management = Mockito.mock(CharacterManagementService::class.java)
        arrangeSequence(executor)
        val page = formPage("<form method='post'><input type='submit' name='showreset' value='Use'></form>")
        Mockito.`when`(executor.load(1L, CHARACTER_URL)).thenReturn(page)
        val form = page.forms.single()
        Mockito.`when`(management.execute(1L, "hof-10", TownActionRequest(form.actionId)))
            .thenReturn(CharacterManagementSnapshotResponse(null, emptyList(), emptyList()))
        val adapter = HofCharacterCommandAdapter(executor, management, HofRequestFactory())

        val result = execute(adapter, CharacterCommand.PrepareItems(7L, revision))

        assertIs<CharacterCommandObservation.Applied>(result)
        Mockito.verify(management).execute(1L, "hof-10", TownActionRequest(form.actionId))
    }

    @Test
    fun `ambiguous knockback returns safe identity candidates to the typed client`() {
        val executor = Mockito.mock(TownAuthenticatedExecutor::class.java)
        val management = Mockito.mock(CharacterManagementService::class.java)
        arrangeSequence(executor)
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
        val adapter = HofCharacterCommandAdapter(executor, management, HofRequestFactory())

        val result = assertIs<CharacterCommandObservation.IdentityResolutionRequired>(
            execute(adapter, CharacterCommand.Knockback(7L, revision, "소셜")),
        )

        assertEquals("hof-11", result.candidates.single().hofCharacterId)
        assertEquals(setOf("name", "job", "level"), result.candidates.single().matchingFields)
    }

    @Test
    fun `knockback delegates its complete confirmation sequence to management`() {
        val executor = Mockito.mock(TownAuthenticatedExecutor::class.java)
        val management = Mockito.mock(CharacterManagementService::class.java)
        arrangeSequence(executor)
        val initialPage = formPage("<form method='post'><input type='submit' name='knockback' value='Knockback'></form>")
        Mockito.`when`(executor.load(1L, CHARACTER_URL)).thenReturn(initialPage)
        val initialForm = initialPage.forms.single()
        Mockito.`when`(management.execute(1L, "hof-10", TownActionRequest(initialForm.actionId))).thenReturn(
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
        val adapter = HofCharacterCommandAdapter(executor, management, HofRequestFactory())

        val result = assertIs<CharacterCommandObservation.IdentityResolutionRequired>(
            execute(adapter, CharacterCommand.Knockback(7L, revision, "소셜")),
        )

        assertEquals("hof-11", result.candidates.single().hofCharacterId)
        assertEquals("소셜", result.candidates.single().name)
        assertEquals(60, result.candidates.single().level)
        assertEquals("Social Knight", result.candidates.single().job)
        Mockito.verify(management).execute(1L, "hof-10", TownActionRequest(initialForm.actionId))
    }

    @Test
    fun `kick delegates all three confirmation forms to management`() {
        val executor = Mockito.mock(TownAuthenticatedExecutor::class.java)
        val management = Mockito.mock(CharacterManagementService::class.java)
        arrangeSequence(executor)
        val initialPage = formPage("<form method='post'><input type='submit' name='byebye' value='Kick'></form>")
        Mockito.`when`(executor.load(1L, CHARACTER_URL)).thenReturn(initialPage)
        val initialForm = initialPage.forms.single()
        Mockito.`when`(management.execute(1L, "hof-10", TownActionRequest(initialForm.actionId))).thenReturn(
            CharacterManagementSnapshotResponse(
                character = null,
                actions = emptyList(),
                messages = listOf("캐릭터를 삭제했습니다."),
                targetRemoved = true,
            ),
        )
        val adapter = HofCharacterCommandAdapter(executor, management, HofRequestFactory())

        val result = assertIs<CharacterCommandObservation.Applied>(
            execute(adapter, CharacterCommand.Kick(7L, revision, "소셜")),
        )

        assertEquals(listOf("캐릭터를 삭제했습니다."), result.messages)
        Mockito.verify(management).execute(1L, "hof-10", TownActionRequest(initialForm.actionId))
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
            (invocation.arguments[1] as () -> CharacterCommandObservation).invoke()
        }.`when`(executor).executeAccountSequence(Mockito.eq(1L), anySequence())
    }

    private fun anySequence(): () -> CharacterCommandObservation =
        Mockito.any<() -> CharacterCommandObservation>() ?: { error("matcher") }

    private fun execute(
        adapter: HofCharacterCommandAdapter,
        command: CharacterCommand,
    ): CharacterCommandObservation = adapter.withSession(context.accountId) { it.execute(context, command) }

    private fun formPage(html: String): ParsedTownPage = HofFormParser().parse(html, CHARACTER_URL)

    private companion object {
        const val CHARACTER_URL = "http://sic.zerosic.com/ZeroHOF/index.php?char=hof-10"
    }
}
