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

class CharacterStatCommandRulesTest {
    @Test
    fun `multiple stat allocations must fit the latest observed status point balance`() {
        CharacterStatCommandRules.requireAllocation(
            mapOf(CharacterStat.STR to 3, CharacterStat.INT to 2),
            observedStatusPoints = 5,
        )

        assertFailsWith<IllegalArgumentException> {
            CharacterStatCommandRules.requireAllocation(
                mapOf(CharacterStat.STR to 3, CharacterStat.INT to 3),
                observedStatusPoints = 5,
            )
        }
    }

    @Test
    fun `stat allocation rejects empty zero and negative requests`() {
        assertFailsWith<IllegalArgumentException> {
            CharacterStatCommandRules.requireAllocation(emptyMap(), observedStatusPoints = 5)
        }
        assertFailsWith<IllegalArgumentException> {
            CharacterStatCommandRules.requireAllocation(mapOf(CharacterStat.STR to 0), observedStatusPoints = 5)
        }
        assertFailsWith<IllegalArgumentException> {
            CharacterStatCommandRules.requireAllocation(mapOf(CharacterStat.STR to -1), observedStatusPoints = 5)
        }
    }
}
