package app.spammy.hof.character.command

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CharacterSkillCommandTest {
    @Test
    fun `learn skill requires one exact currently learnable source value`() {
        assertEquals("1044", CharacterSkillCommandRules.requireLearnable("1044", listOf("1000", "1044")))
        assertFailsWith<IllegalArgumentException> {
            CharacterSkillCommandRules.requireLearnable("missing", listOf("1000", "1044"))
        }
    }
}
