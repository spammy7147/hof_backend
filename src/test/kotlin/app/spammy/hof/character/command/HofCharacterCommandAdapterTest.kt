package app.spammy.hof.character.command

import app.spammy.hof.character.dto.CharacterManagementSnapshotResponse
import app.spammy.hof.character.dto.CharacterObservedActionResponse
import app.spammy.hof.character.dto.CharacterActionCandidateResponse
import app.spammy.hof.character.dto.CharacterActionFieldResponse
import app.spammy.hof.character.service.CharacterManagementService
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.model.HofRequestOrigin
import app.spammy.hof.external.parser.CharacterRosterParser
import app.spammy.hof.external.parser.CharacterDetailParser
import app.spammy.hof.town.common.model.ParsedTownPage
import app.spammy.hof.town.common.model.ParsedTownResult
import app.spammy.hof.town.common.service.TownAuthenticatedExecutor
import app.spammy.hof.town.common.service.TownRequestContinuation
import app.spammy.hof.character.service.CharacterSnapshotSynchronizer
import app.spammy.hof.account.entity.HofAccountEntity
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
        val adapter = adapter(executor, management)

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
        val adapter = adapter(executor, management)

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
        val adapter = adapter(executor, management)

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
        val adapter = adapter(executor, management)

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
        val adapter = adapter(executor, management)

        val result = execute(adapter, CharacterCommand.PrepareItems(7L, revision))

        assertIs<CharacterCommandObservation.Applied>(result)
        Mockito.verify(management).execute(1L, "hof-10", TownActionRequest(form.actionId))
    }

    @Test
    fun `knockback requests the complete confirmation sequence and returns the authoritative roster`() {
        val executor = Mockito.mock(TownAuthenticatedExecutor::class.java)
        val management = Mockito.mock(CharacterManagementService::class.java)
        arrangeIdentitySequence(
            executor,
            requiredFields = listOf("knockback", "knockback2"),
            rosterHtml = ROSTER_HTML,
            messages = listOf("이동 완료"),
        )
        val adapter = adapter(executor, management)

        val result = assertIs<CharacterCommandObservation.KnockbackApplied>(
            execute(adapter, CharacterCommand.Knockback(7L, revision, "소셜")),
        )

        assertEquals(listOf("이동 완료"), result.messages)
        assertEquals(listOf("20", "11"), result.rosterAfter.map { it.hofCharacterId })
        Mockito.verifyNoInteractions(management)
    }

    @Test
    fun `missing knockback confirmation is rejected without reading a roster`() {
        val executor = Mockito.mock(TownAuthenticatedExecutor::class.java)
        val management = Mockito.mock(CharacterManagementService::class.java)
        arrangeSequence(executor)
        Mockito.`when`(
            executor.executeResolvedFormSequenceWithFollowupProjected(
                Mockito.eq(1L),
                eqString(CHARACTER_URL),
                eqList(listOf("knockback", "knockback2")),
                eqString(HOME_URL),
                anyUnconfirmedIdentityProjector(),
                anyIdentityFollowupProjector(),
            ),
        ).thenReturn(null)
        val adapter = adapter(executor, management)

        val result = assertIs<CharacterCommandObservation.Rejected>(
            execute(adapter, CharacterCommand.Knockback(7L, revision, "소셜")),
        )

        assertEquals("FORM_NOT_OBSERVED", result.code)
        Mockito.verifyNoInteractions(management)
    }

    @Test
    fun `kick requests all three confirmation steps and returns removal evidence`() {
        val executor = Mockito.mock(TownAuthenticatedExecutor::class.java)
        val management = Mockito.mock(CharacterManagementService::class.java)
        arrangeIdentitySequence(
            executor,
            requiredFields = listOf("byebye", "byebye2", "byebye3"),
            rosterHtml = ROSTER_HTML,
            messages = listOf("삭제 완료"),
        )
        val adapter = adapter(executor, management)

        val result = assertIs<CharacterCommandObservation.KickApplied>(
            execute(adapter, CharacterCommand.Kick(7L, revision, "소셜")),
        )

        assertEquals(listOf("삭제 완료"), result.messages)
        assertEquals(listOf("20", "11"), result.rosterAfter.map { it.hofCharacterId })
        Mockito.verifyNoInteractions(management)
    }

    @Test
    fun `remote session observes the authoritative home roster before a destructive command`() {
        val executor = Mockito.mock(TownAuthenticatedExecutor::class.java)
        val management = Mockito.mock(CharacterManagementService::class.java)
        arrangeSequence(executor)
        Mockito.doAnswer { invocation ->
            invocation.getArgument<(String, String, ParsedTownPage) -> List<CharacterCommandObservedIdentity>>(3)
                .invoke(ROSTER_HTML, HOME_URL, formPage(ROSTER_HTML))
        }.`when`(executor).loadProjectedWithoutCharacterRosterObservation(
            Mockito.eq(1L),
            eqString(HOME_URL),
            anyOrigin(),
            anyRosterProjector(),
        )
        val adapter = adapter(executor, management)

        val roster = adapter.withSession(1L) { it.observeRoster() }

        assertEquals(listOf("20", "11"), roster.map { it.hofCharacterId })
        Mockito.verify(executor).loadProjectedWithoutCharacterRosterObservation(
            Mockito.eq(1L),
            eqString(HOME_URL),
            anyOrigin(),
            anyRosterProjector(),
        )
        Mockito.verify(executor, Mockito.never()).loadProjected(
            Mockito.eq(1L),
            eqString(HOME_URL),
            anyOrigin(),
            anyRosterProjector(),
        )
    }

    @Test
    fun `lost final identity response becomes a typed unconfirmed observation`() {
        val executor = Mockito.mock(TownAuthenticatedExecutor::class.java)
        val management = Mockito.mock(CharacterManagementService::class.java)
        arrangeSequence(executor)
        Mockito.doAnswer { invocation ->
            invocation.getArgument<(Instant) -> CharacterCommandObservation>(4).invoke(revision)
        }.`when`(executor).executeResolvedFormSequenceWithFollowupProjected(
            Mockito.eq(1L),
            eqString(CHARACTER_URL),
            eqList(listOf("knockback", "knockback2")),
            eqString(HOME_URL),
            anyUnconfirmedIdentityProjector(),
            anyIdentityFollowupProjector(),
        )
        val adapter = adapter(executor, management)

        val result = assertIs<CharacterCommandObservation.IdentityAppliedRosterUnconfirmed>(
            execute(adapter, CharacterCommand.Knockback(7L, revision, "소셜")),
        )

        assertEquals(revision, result.rosterObservedAt)
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

    private fun arrangeIdentitySequence(
        executor: TownAuthenticatedExecutor,
        requiredFields: List<String>,
        rosterHtml: String,
        messages: List<String>,
    ) {
        arrangeSequence(executor)
        Mockito.doAnswer { invocation ->
            assertEquals(requiredFields, invocation.getArgument<List<String>>(2))
            invocation.getArgument<(
                ParsedTownResult,
                String?,
                String?,
                ParsedTownPage?,
                Instant,
                TownRequestContinuation,
            ) -> CharacterCommandObservation>(5)
                .invoke(
                    ParsedTownResult(messages, emptyList()),
                    rosterHtml,
                    HOME_URL,
                    formPage(rosterHtml),
                    revision,
                    TownRequestContinuation(HofAccountEntity(1L, "account", "encrypted", revision), emptyMap()),
                )
        }.`when`(executor).executeResolvedFormSequenceWithFollowupProjected(
            Mockito.eq(1L),
            eqString(CHARACTER_URL),
            eqList(requiredFields),
            eqString(HOME_URL),
            anyUnconfirmedIdentityProjector(),
            anyIdentityFollowupProjector(),
        )
    }

    private fun anyUnconfirmedIdentityProjector(): (Instant) -> CharacterCommandObservation =
        Mockito.any<(Instant) -> CharacterCommandObservation>() ?: { error("matcher") }

    private fun anyIdentityFollowupProjector(): (
        ParsedTownResult,
        String?,
        String?,
        ParsedTownPage?,
        Instant,
        TownRequestContinuation,
    ) -> CharacterCommandObservation = Mockito.any<(
        ParsedTownResult,
        String?,
        String?,
        ParsedTownPage?,
        Instant,
        TownRequestContinuation,
    ) -> CharacterCommandObservation>() ?: { _, _, _, _, _, _ -> error("matcher") }

    private fun anyRosterProjector(): (String, String, ParsedTownPage) -> List<CharacterCommandObservedIdentity> =
        Mockito.any<(String, String, ParsedTownPage) -> List<CharacterCommandObservedIdentity>>()
            ?: { _, _, _ -> error("matcher") }

    private fun anyOrigin(): HofRequestOrigin =
        Mockito.any(HofRequestOrigin::class.java) ?: HofRequestOrigin.INTERACTIVE

    private fun eqString(value: String): String = Mockito.eq(value) ?: value

    private fun eqList(value: List<String>): List<String> = Mockito.eq(value) ?: value

    private fun execute(
        adapter: HofCharacterCommandAdapter,
        command: CharacterCommand,
    ): CharacterCommandObservation = adapter.withSession(context.accountId) { it.execute(context, command) }

    private fun adapter(executor: TownAuthenticatedExecutor, management: CharacterManagementService) =
        HofCharacterCommandAdapter(
            executor,
            management,
            HofRequestFactory(),
            CharacterRosterParser(),
            CharacterDetailParser(),
            Mockito.mock(CharacterSnapshotSynchronizer::class.java),
        )

    private fun formPage(html: String): ParsedTownPage = HofFormParser().parse(html, CHARACTER_URL)

    private companion object {
        const val CHARACTER_URL = "http://sic.zerosic.com/ZeroHOF/index.php?char=hof-10"
        const val HOME_URL = "http://sic.zerosic.com/ZeroHOF/index.php"
        const val ROSTER_HTML = """
            <div class="character-card">
              <a href="?char=20"><img src="other.gif"></a><br>
              다른 캐릭터<br>
              Lv.40 Knight
            </div>
            <div class="character-card">
              <a href="?char=11"><img src="social.gif"></a><br>
              소셜<br>
              Lv.60 Social Knight
            </div>
        """
    }
}
