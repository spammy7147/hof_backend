package app.spammy.hof.character.command

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CharacterEquipmentCommandTest {
    @Test
    fun `equipment uses exact source id so duplicate display names stay separate`() {
        val values = listOf("1000", "100003")

        assertEquals("100003", CharacterEquipmentCommandRules.requireExactCandidate("100003", values))
        assertFailsWith<IllegalArgumentException> {
            CharacterEquipmentCommandRules.requireExactCandidate("1000", listOf("1000", "1000"))
        }
    }

    @Test
    fun `equipment presets only allow hof slots one and two`() {
        CharacterEquipmentCommandRules.requirePresetSlot(1)
        CharacterEquipmentCommandRules.requirePresetSlot(2)
        assertFailsWith<IllegalArgumentException> { CharacterEquipmentCommandRules.requirePresetSlot(3) }
    }
}
