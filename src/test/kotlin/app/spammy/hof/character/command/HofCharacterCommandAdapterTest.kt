package app.spammy.hof.character.command

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
import app.spammy.hof.town.common.model.TownActionRequest
import org.mockito.Mockito

class HofCharacterCommandAdapterTest {
    private val revision = Instant.parse("2026-08-17T00:00:00Z")
    private val context = CharacterCommandContext(1L, 7L, "hof-10")

    @Test
    fun `pray resolves the latest observed semantic form and submits it`() {
        val executor = Mockito.mock(TownAuthenticatedExecutor::class.java)
        arrangeSequence(executor)
        val page = formPage("<form method='post'><input type='submit' name='Pray' value='기도한다'></form>")
        val form = page.forms.single()
        arrangeProjectedAction(executor, page, listOf("기도 완료")) { request ->
            assertEquals(TownActionRequest(form.actionId), request)
        }
        val adapter = adapter(executor)

        val result = execute(adapter, CharacterCommand.Pray(7L, revision))

        assertEquals(listOf("기도 완료"), assertIs<CharacterCommandObservation.Applied>(result).messages)
    }

    @Test
    fun `unknown form is reported but never submitted`() {
        val executor = Mockito.mock(TownAuthenticatedExecutor::class.java)
        arrangeSequence(executor)
        arrangeProjectedAction(
            executor,
            formPage("<form method='post'><input type='submit' name='BrandNewFeature' value='새 기능'></form>"),
            emptyList(),
        )
        val adapter = adapter(executor)

        val result = execute(adapter, CharacterCommand.Pray(7L, revision))

        assertEquals("FORM_NOT_OBSERVED", assertIs<CharacterCommandObservation.Rejected>(result).code)
    }

    @Test
    fun `class change uses the job radio observed in the real character form`() {
        val executor = Mockito.mock(TownAuthenticatedExecutor::class.java)
        arrangeSequence(executor)
        val page = formPage("<form method='post'><input type='radio' name='job' value='523'>Mathematician<input type='submit' name='classchange' value='ClassChange'></form>")
        val form = page.forms.single()
        val choice = form.candidates.single { it.inputName == "job" && it.inputValue == "523" }
        arrangeProjectedAction(executor, page, listOf("전직 완료")) { request ->
            assertEquals(
                TownActionRequest(form.actionId, selections = listOf(app.spammy.hof.town.common.model.TownActionSelection(choice.id))),
                request,
            )
        }
        val adapter = adapter(executor)

        val result = execute(adapter, CharacterCommand.ChangeClass(7L, revision, "523"))

        assertEquals(listOf("전직 완료"), assertIs<CharacterCommandObservation.Applied>(result).messages)
    }

    @Test
    fun `growth item reopens the transient reset selector through the command adapter`() {
        val executor = Mockito.mock(TownAuthenticatedExecutor::class.java)
        arrangeSequence(executor)
        arrangeTwoStepAction(executor, listOf("성장 아이템 사용 완료"))
        val adapter = adapter(executor)

        val result = execute(adapter, CharacterCommand.UseItem(7L, revision, "7510"))

        assertEquals(listOf("성장 아이템 사용 완료"), assertIs<CharacterCommandObservation.Applied>(result).messages)
    }

    @Test
    fun `preparing item use opens the reset selector before the item snapshot is shown`() {
        val executor = Mockito.mock(TownAuthenticatedExecutor::class.java)
        arrangeSequence(executor)
        val page = formPage("<form method='post'><input type='submit' name='showreset' value='Use'></form>")
        val form = page.forms.single()
        val selectorHtml = "<form method='post'><select name='itemUse'><option value='7510'>Reset</option></select>" +
            "<input type='submit' name='resetVarious' value='Use'></form>"
        arrangeProjectedAction(
            executor,
            page,
            emptyList(),
            responseHtml = selectorHtml,
            responsePage = formPage(selectorHtml),
        ) { request ->
            assertEquals(TownActionRequest(form.actionId), request)
        }
        val adapter = adapter(executor)

        val result = execute(adapter, CharacterCommand.PrepareItems(7L, revision))

        assertIs<CharacterCommandObservation.Applied>(result)
    }

    @Test
    fun `knockback requests the complete confirmation sequence and returns the authoritative roster`() {
        val executor = Mockito.mock(TownAuthenticatedExecutor::class.java)
        arrangeIdentitySequence(
            executor,
            requiredFields = listOf("knockback", "knockback2"),
            rosterHtml = ROSTER_HTML,
            messages = listOf("이동 완료"),
        )
        val adapter = adapter(executor)

        val result = assertIs<CharacterCommandObservation.KnockbackApplied>(
            execute(adapter, CharacterCommand.Knockback(7L, revision, "소셜")),
        )

        assertEquals(listOf("이동 완료"), result.messages)
        assertEquals(listOf("20", "11"), result.rosterAfter.map { it.hofCharacterId })
    }

    @Test
    fun `missing knockback confirmation is rejected without reading a roster`() {
        val executor = Mockito.mock(TownAuthenticatedExecutor::class.java)
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
        val adapter = adapter(executor)

        val result = assertIs<CharacterCommandObservation.Rejected>(
            execute(adapter, CharacterCommand.Knockback(7L, revision, "소셜")),
        )

        assertEquals("FORM_NOT_OBSERVED", result.code)
    }

    @Test
    fun `kick requests the real three confirmation steps and returns removal evidence`() {
        val executor = Mockito.mock(TownAuthenticatedExecutor::class.java)
        arrangeIdentitySequence(
            executor,
            requiredFields = listOf("byebye", "byebye2", "kick"),
            rosterHtml = ROSTER_HTML,
            messages = listOf("삭제 완료"),
        )
        val adapter = adapter(executor)

        val result = assertIs<CharacterCommandObservation.KickApplied>(
            execute(adapter, CharacterCommand.Kick(7L, revision, "소셜")),
        )

        assertEquals(listOf("삭제 완료"), result.messages)
        assertEquals(listOf("20", "11"), result.rosterAfter.map { it.hofCharacterId })
    }

    @Test
    fun `remote session observes the authoritative home roster before a destructive command`() {
        val executor = Mockito.mock(TownAuthenticatedExecutor::class.java)
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
        val adapter = adapter(executor)

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
        val adapter = adapter(executor)

        val result = assertIs<CharacterCommandObservation.IdentityAppliedRosterUnconfirmed>(
            execute(adapter, CharacterCommand.Knockback(7L, revision, "소셜")),
        )

        assertEquals(revision, result.rosterObservedAt)
    }

    private fun arrangeSequence(executor: TownAuthenticatedExecutor) {
        Mockito.doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            (invocation.arguments[1] as () -> CharacterCommandObservation).invoke()
        }.`when`(executor).executeAccountSequence(Mockito.eq(1L), anySequence())
    }

    private fun anySequence(): () -> CharacterCommandObservation =
        Mockito.any<() -> CharacterCommandObservation>() ?: { error("matcher") }

    private fun arrangeProjectedAction(
        executor: TownAuthenticatedExecutor,
        page: ParsedTownPage,
        messages: List<String>,
        responseHtml: String = "<div class='carpet_frame'>소셜<br>Lv.60 Social Knight</div>",
        responsePage: ParsedTownPage = page,
        verifyAction: (TownActionRequest) -> Unit = {},
    ) {
        Mockito.doAnswer { invocation ->
            val resolver = invocation.getArgument<(String, String, ParsedTownPage) -> TownActionRequest>(3)
            val projector = invocation.getArgument<(String, String, ParsedTownResult, ParsedTownPage) -> List<String>>(4)
            verifyAction(resolver("<div class='carpet_frame'>소셜<br>Lv.60 Social Knight</div>", CHARACTER_URL, page))
            projector(
                responseHtml,
                CHARACTER_URL,
                ParsedTownResult(messages, emptyList()),
                responsePage,
            )
        }.`when`(executor).executeProjected<List<String>>(
            Mockito.eq(1L),
            eqString(CHARACTER_URL),
            anyOrigin(),
            anyActionResolver(),
            anyActionProjector(),
        )
    }

    private fun arrangeTwoStepAction(executor: TownAuthenticatedExecutor, messages: List<String>) {
        val entryPage = formPage("<form method='post'><input type='submit' name='showreset' value='Use'></form>")
        val finalPage = formPage(
            "<form method='post'><select name='itemUse'><option value='7510'>Reset Crystal</option></select>" +
                "<input type='submit' name='resetVarious' value='Use'></form>",
        )
        Mockito.doAnswer { invocation ->
            val direct = invocation.getArgument<(ParsedTownPage) -> TownActionRequest?>(3)(entryPage)
            val entry = requireNotNull(invocation.getArgument<(ParsedTownPage) -> TownActionRequest?>(5)(entryPage))
            val final = requireNotNull(invocation.getArgument<(ParsedTownPage) -> TownActionRequest?>(7)(finalPage))
            assertEquals(null, direct)
            assertEquals("showreset", entryPage.forms.single { it.actionId == entry.actionId }.submitSource)
            assertEquals("7510", finalPage.forms.single { it.actionId == final.actionId }
                .candidates.single { it.id == final.selections.single().candidateId }.inputValue)
            invocation.getArgument<(String, String, ParsedTownResult, ParsedTownPage) -> List<String>>(8)
                .invoke(
                    "<div class='carpet_frame'>소셜<br>Lv.60 Social Knight</div>",
                    CHARACTER_URL,
                    ParsedTownResult(messages, emptyList()),
                    finalPage,
                )
        }.`when`(executor).executeResolvedDirectOrTwoStepProjected<List<String>>(
            Mockito.eq(1L),
            eqString(CHARACTER_URL),
            eqString("use_char_item"),
            anyPageActionResolver(),
            eqString("showreset"),
            anyPageActionResolver(),
            eqString("resetVarious"),
            anyPageActionResolver(),
            anyActionProjector(),
        )
    }

    private fun anyActionResolver(): (String, String, ParsedTownPage) -> TownActionRequest =
        Mockito.any<(String, String, ParsedTownPage) -> TownActionRequest>() ?: { _, _, _ -> error("matcher") }

    private fun anyPageActionResolver(): (ParsedTownPage) -> TownActionRequest? =
        Mockito.any<(ParsedTownPage) -> TownActionRequest?>() ?: { null }

    private fun anyActionProjector(): (String, String, ParsedTownResult, ParsedTownPage) -> List<String> =
        Mockito.any<(String, String, ParsedTownResult, ParsedTownPage) -> List<String>>()
            ?: { _, _, _, _ -> error("matcher") }

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

    private fun adapter(executor: TownAuthenticatedExecutor) =
        HofCharacterCommandAdapter(
            executor,
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
