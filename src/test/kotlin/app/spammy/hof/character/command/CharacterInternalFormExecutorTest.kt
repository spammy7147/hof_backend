package app.spammy.hof.character.command

import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.model.HofRequestOrigin
import app.spammy.hof.town.common.model.ParsedTownPage
import app.spammy.hof.town.common.model.ParsedTownResult
import app.spammy.hof.town.common.model.TownActionRequest
import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.common.service.TownAuthenticatedExecutor
import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.mockito.Mockito

class CharacterInternalFormExecutorTest {
    @Test
    fun `internal pattern and deep-sync boundary never submits an identity form`() {
        val town = Mockito.mock(TownAuthenticatedExecutor::class.java)
        val html = """
            <form action="?char=hof-10" method="post">
              <input type="radio" name="position" value="front">Front
              <select name="guard"><option value="0">None</option></select>
              <input type="submit" name="kick" value="정말 해고">
            </form>
        """.trimIndent()
        val page = HofFormParser().parse(html, CHARACTER_URL)
        Mockito.doAnswer { invocation ->
            invocation.getArgument<(String, String, ParsedTownPage) -> TownActionRequest>(3)
                .invoke(html, CHARACTER_URL, page)
        }.`when`(town).executeProjected<Unit>(
            Mockito.eq(1L),
            eqString(CHARACTER_URL),
            anyOrigin(),
            anyResolver(),
            anyProjector(),
        )
        val forms = CharacterInternalFormExecutor(town, HofRequestFactory())

        assertFailsWith<IllegalStateException> {
            forms.execute(1L, "hof-10") { observed -> TownActionRequest(observed.forms.single().actionId) }
        }
    }

    private fun anyResolver(): (String, String, ParsedTownPage) -> TownActionRequest =
        Mockito.any<(String, String, ParsedTownPage) -> TownActionRequest>() ?: { _, _, _ -> error("matcher") }

    private fun anyProjector(): (String, String, ParsedTownResult, ParsedTownPage) -> Unit =
        Mockito.any<(String, String, ParsedTownResult, ParsedTownPage) -> Unit>() ?: { _, _, _, _ -> }

    private fun anyOrigin(): HofRequestOrigin =
        Mockito.any(HofRequestOrigin::class.java) ?: HofRequestOrigin.INTERACTIVE

    private fun eqString(value: String): String = Mockito.eq(value) ?: value

    private companion object {
        const val CHARACTER_URL = "http://sic.zerosic.com/ZeroHOF/index.php?char=hof-10"
    }
}
