package app.spammy.hof.character.command

import app.spammy.hof.external.model.HofEquipmentCandidate
import app.spammy.hof.external.parser.CharacterDetailParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CharacterEquipmentCommandTest {
    @Test
    fun `deep sync restores the same card variant from equipped and stock HTML`() {
        val snapshot = CharacterDetailParser().parsePage("10", """
            <form><table><tr><td class="align-right">Armor :</td><td>
              <input name="spot" value="armor"><img src="/SinkArmor.gif">
              +9 Studded Sink Armor (Crocodile)<span class="light"> (Armor)</span>
              / <span style="font-size:80%">MAXHP+250, STR+30</span>
            </td></tr></table></form>
            <script>
              function Listtype_equip(mode) {
                switch(mode) {
                case "armor":
                html = '<input type="radio" name="item_no" value="crocodile"><img src="/SinkArmor.gif">+9 Studded Sink Armor (Crocodile)<span class="light"> (Armor)</span> x2 / MAXHP+250, STR+30<br />' +
                '<input type="radio" name="item_no" value="eel"><img src="/SinkArmor.gif">+9 Studded Sink Armor (Eel)<span class="light"> (Armor)</span> x1 / MAXSP+250, INT+30<br />';
                }
              }
            </script>
        """.trimIndent()).snapshot
        val original = snapshot.equipment.single()

        assertEquals(
            "crocodile",
            CharacterEquipmentCommandRules.requireRestoreCandidate(
                original.name, original.iconUrl, original.description, snapshot.equipmentCandidates,
            ).value,
        )
    }

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
