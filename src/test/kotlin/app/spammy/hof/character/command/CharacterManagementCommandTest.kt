package app.spammy.hof.character.command

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class CharacterManagementCommandTest {
    private val revision = Instant.parse("2026-08-17T00:00:00Z")

    @Test
    fun `rename kick knockback and class change require dangerous preview`() {
        val commands = listOf(
            CharacterCommand.Rename(1L, revision, "새이름"),
            CharacterCommand.Kick(1L, revision, "소셜"),
            CharacterCommand.Knockback(1L, revision, "소셜"),
            CharacterCommand.ChangeClass(1L, revision, "RoyalGuard"),
        )

        assertEquals(List(4) { true }, commands.map { CharacterCommandPreviewFactory.preview(it, "소셜").dangerous })
        assertEquals(false, CharacterCommandPreviewFactory.preview(CharacterCommand.Pray(1L, revision), "소셜").dangerous)
    }
}
