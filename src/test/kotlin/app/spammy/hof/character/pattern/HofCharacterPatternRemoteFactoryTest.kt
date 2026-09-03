package app.spammy.hof.character.pattern

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.command.CharacterInternalFormExecutor
import app.spammy.hof.character.repository.CharacterQueryRepository
import app.spammy.hof.character.service.CharacterSnapshotSynchronizer
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.model.HofRequestOrigin
import app.spammy.hof.external.parser.CharacterDetailParser
import app.spammy.hof.town.common.model.ParsedTownPage
import app.spammy.hof.town.common.model.ParsedTownResult
import app.spammy.hof.town.common.model.TownActionRequest
import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.common.service.TownAuthenticatedExecutor
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import org.mockito.Mockito

class HofCharacterPatternRemoteFactoryTest {
    @Test
    fun `pattern mutation resolves selections from the submit-time page`() {
        val executor = Mockito.mock(TownAuthenticatedExecutor::class.java)
        val characters = Mockito.mock(CharacterQueryRepository::class.java)
        val now = Instant.parse("2026-08-21T00:00:00Z")
        val account = HofAccountEntity(1L, "account", "encrypted", now)
        Mockito.`when`(characters.findByAccountIdAndId(1L, 7L)).thenReturn(
            CharacterEntity(7L, account, "hof-10", "소셜", "Social Knight", updatedAt = now),
        )
        Mockito.doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            (invocation.arguments[1] as () -> CharacterPatternMutationReceipt).invoke()
        }.`when`(executor).executeAccountSequence(Mockito.eq(1L), anySequence())

        val latestHtml = """
            <form action="?char=hof-10" method="post">
              <input type="radio" name="position" value="front">Front
              <input type="radio" name="position" value="back">Back
              <select name="guard"><option value="0">None</option><option value="2">Two</option></select>
              <input type="submit" name="ChangePosition" value="Save">
            </form>
        """.trimIndent()
        val latestPage = HofFormParser().parse(latestHtml, CHARACTER_URL)
        var resolved: TownActionRequest? = null
        Mockito.doAnswer { invocation ->
            val resolver = invocation.getArgument<(String, String, ParsedTownPage) -> TownActionRequest>(3)
            val projector = invocation.getArgument<(String, String, ParsedTownResult, ParsedTownPage) -> Unit>(4)
            resolved = resolver(latestHtml, CHARACTER_URL, latestPage)
            projector(latestHtml, CHARACTER_URL, ParsedTownResult(emptyList(), emptyList()), latestPage)
        }.`when`(executor).executeProjected<Unit>(
            Mockito.eq(1L),
            eqString(CHARACTER_URL),
            anyOrigin(),
            anyResolver(),
            anyProjector(),
        )
        val factory = HofCharacterPatternRemoteFactory(
            characters,
            executor,
            CharacterInternalFormExecutor(executor, HofRequestFactory()),
            HofRequestFactory(),
            CharacterDetailParser(),
            Mockito.mock(CharacterSnapshotSynchronizer::class.java),
        )

        val receipt = factory.withRemote(1L, 7L) { it.changePositionGuard("back", "2") }

        assertEquals(CharacterPatternMutationReceipt.RESPONSE_RECEIVED, receipt)
        val form = latestPage.forms.single()
        assertEquals(form.actionId, resolved?.actionId)
        assertEquals(
            setOf("back", "2"),
            resolved?.selections.orEmpty().map { selected ->
                form.candidates.single { it.id == selected.candidateId }.inputValue
            }.toSet(),
        )
    }

    private fun anySequence(): () -> CharacterPatternMutationReceipt =
        Mockito.any<() -> CharacterPatternMutationReceipt>() ?: { error("matcher") }

    private fun anyResolver(): (String, String, ParsedTownPage) -> TownActionRequest =
        Mockito.any<(String, String, ParsedTownPage) -> TownActionRequest>() ?: { _, _, _ -> error("matcher") }

    private fun anyProjector(): (String, String, ParsedTownResult, ParsedTownPage) -> Unit =
        Mockito.any<(String, String, ParsedTownResult, ParsedTownPage) -> Unit>() ?: { _, _, _, _ -> }

    private fun anyOrigin(): HofRequestOrigin =
        Mockito.any(HofRequestOrigin::class.java) ?: HofRequestOrigin.INTERACTIVE

    private fun eqString(value: String): String = Mockito.eq(value) ?: value

    private companion object {
        const val CHARACTER_URL = "https://hof.zerosic.com/index.php?char=hof-10"
    }
}
