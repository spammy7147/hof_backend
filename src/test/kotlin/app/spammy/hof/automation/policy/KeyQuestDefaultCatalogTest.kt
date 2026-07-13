package app.spammy.hof.automation.policy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyQuestDefaultCatalogTest {
    @Test
    fun preservesEverySuppliedMapAndPartyBlueprint() {
        val east = KeyQuestDefaultCatalog.defaults.getValue("0563")
        assertEquals(listOf("Noble1021", "Noble1022", "Noble1023", "Noble102"), east.candidates)
        assertEquals(
            listOf("소셜2" to 5, "사제" to 7, "바드2" to 8, "솬서" to 0, "에인" to 3),
            east.partyBlueprintByMap.getValue("Noble1021").slots.map { it.characterName to it.patternSlot },
        )
        assertEquals(
            listOf("소셜" to 0, "사제" to 0, "바드" to 0, "에인" to 0, "동방" to 0),
            east.partyBlueprintByMap.getValue("Noble102").slots.map { it.characterName to it.patternSlot },
        )
        assertEquals(3, east.partyBlueprintByMap.getValue("Noble1021").battleCount)

        val west = KeyQuestDefaultCatalog.defaults.getValue("0571")
        assertEquals(listOf("Noble201"), west.candidates)
        assertEquals(
            listOf("카발" to 2, "사제2" to 3, "낫망네크" to 3),
            west.partyBlueprint!!.slots.map { it.characterName to it.patternSlot },
        )
        assertEquals(1, west.partyBlueprint.battleCount)

        val culvert = KeyQuestDefaultCatalog.defaults.getValue("0351")
        assertEquals(listOf("tnfh1"), culvert.candidates)
        assertEquals("Culvert- 마을 지하 수로(입구)", culvert.mapNames.getValue("tnfh1"))
        assertNull(culvert.partyBlueprint)
    }
}
