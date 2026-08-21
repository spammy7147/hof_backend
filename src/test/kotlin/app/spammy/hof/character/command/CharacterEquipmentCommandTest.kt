package app.spammy.hof.character.command

import app.spammy.hof.external.model.HofEquipmentCandidate
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

    @Test
    fun `deep sync restores shield and secondary accessory from catalog identity instead of slot name`() {
        val candidates = listOf(
            HofEquipmentCandidate("shield-id", "armor", "Aegis", "aegis.gif", "Aegis DEF +10"),
            HofEquipmentCandidate("ring-id", "accessory", "Ruby Ring", "ring.gif", "Ruby Ring LUK +2"),
        )

        assertEquals(
            "shield-id",
            CharacterEquipmentCommandRules.requireRestoreCandidate(
                name = "Aegis",
                iconUrl = "aegis.gif",
                description = "DEF +10",
                observed = candidates,
            ).value,
        )
        assertEquals(
            "ring-id",
            CharacterEquipmentCommandRules.requireRestoreCandidate(
                name = "Ruby Ring",
                iconUrl = "ring.gif",
                description = "LUK +2",
                observed = candidates,
            ).value,
        )
    }

    @Test
    fun `deep sync refuses to guess between duplicate equipment identities`() {
        assertFailsWith<IllegalArgumentException> {
            CharacterEquipmentCommandRules.requireRestoreCandidate(
                name = "Twin Ring",
                iconUrl = "",
                description = "",
                observed = listOf(
                    HofEquipmentCandidate("ring-a", "accessory", "Twin Ring"),
                    HofEquipmentCandidate("ring-b", "accessory", "Twin Ring"),
                ),
            )
        }
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
