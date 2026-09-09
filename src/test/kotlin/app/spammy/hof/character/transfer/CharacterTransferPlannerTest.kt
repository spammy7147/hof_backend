package app.spammy.hof.character.transfer

import app.spammy.hof.character.command.CharacterStat
import app.spammy.hof.character.pattern.CharacterPatternRowValue
import app.spammy.hof.character.pattern.CharacterPatternSetting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CharacterTransferPlannerTest {
    private val planner = CharacterTransferPlanner()
    private val defaultRow = CharacterPatternRowValue("first-judge", "0", "first-skill")

    @Test
    fun `rejects another HOF account`() {
        assertFailsWith<IllegalArgumentException> {
            planner.preview(source(accountId = 1), target(accountId = 2), CharacterTransferRequest(includeCurrentPattern = true))
        }
    }

    @Test
    fun `fills target tail with default rows when source has eight and target has ten`() {
        val sourceRows = (0 until 8).map { row("judge", it.toString(), "skill") }
        val preview = planner.preview(
            source(current = setting(sourceRows)),
            target(capacity = 10),
            CharacterTransferRequest(includeCurrentPattern = true),
        )

        val step = preview.steps.single() as CharacterTransferStep.ApplyCurrentPattern
        assertEquals(10, step.setting.rows.size)
        assertEquals(sourceRows, step.setting.rows.take(8))
        assertEquals(listOf(defaultRow, defaultRow), step.setting.rows.takeLast(2))
    }

    @Test
    fun `returns blocking overflow draft and does not execute it`() {
        val preview = planner.preview(
            source(current = setting(List(11) { row("judge", "0", "skill") })),
            target(capacity = 10),
            CharacterTransferRequest(includeCurrentPattern = true),
        )

        assertFalse(preview.executable)
        assertTrue(preview.steps.isEmpty())
        assertEquals("PATTERN_OVERFLOW", preview.issues.single().code)
    }

    @Test
    fun `rejects duplicate targets and marks occupied slot as replacement`() {
        val saved = mapOf("5" to setting(listOf(row("judge", "0", "skill"))))
        val duplicate = planner.preview(
            source(saved = saved), target(),
            CharacterTransferRequest(savedPatternMappings = listOf(
                CharacterSavedPatternMapping("5", "1"), CharacterSavedPatternMapping("5", "1"),
            )),
        )
        assertFalse(duplicate.executable)

        val replacement = planner.preview(
            source(saved = saved), target(occupied = setOf("1")),
            CharacterTransferRequest(savedPatternMappings = listOf(CharacterSavedPatternMapping("5", "1"))),
        ).steps.filterIsInstance<CharacterTransferStep.SavePatternSlot>().single()
        assertTrue(replacement.replacesExisting)
    }

    @Test
    fun `plans only affordable positive stats learnable skills and exact equipment values`() {
        val preview = planner.preview(
            source(
                stats = mapOf(CharacterStat.STR to 20, CharacterStat.INT to 5),
                skills = setOf("learnable", "missing"),
                equipment = listOf(
                    CharacterTransferEquipment("weapon", "item-7"),
                    CharacterTransferEquipment("shield", "same-name-but-other-id"),
                ),
            ),
            target(
                points = 20,
                stats = mapOf(CharacterStat.STR to 10, CharacterStat.INT to 5),
                learnable = setOf("learnable"),
                equipment = setOf("item-7"),
            ),
            CharacterTransferRequest(includeStats = true, includeSkills = true, includeEquipment = true),
        )

        assertEquals(mapOf(CharacterStat.STR to 10), (preview.steps.first { it.id == "stats" } as CharacterTransferStep.AllocateStats).amounts)
        assertTrue(preview.steps.any { it.id == "skill:learnable" })
        assertTrue(preview.steps.none { it.id.startsWith("equipment-current:") })
        assertEquals(setOf("SKILL_NOT_LEARNABLE", "EQUIPMENT_NOT_AVAILABLE"), preview.issues.map { it.code }.toSet())
    }

    @Test
    fun `rebuilds equipment presets one and two then restores current equipment`() {
        val preview = planner.preview(
            source(
                equipment = listOf(CharacterTransferEquipment("weapon", "current-weapon")),
                equipmentPresets = mapOf(
                    1 to listOf(CharacterTransferEquipment("weapon", "preset-one")),
                    2 to listOf(CharacterTransferEquipment("shield", "preset-two")),
                ),
            ),
            target(equipment = setOf("current-weapon", "preset-one", "preset-two")),
            CharacterTransferRequest(includeEquipment = true),
        )

        assertTrue(preview.executable)
        assertEquals(
            listOf(
                "equipment-preset:1:clear", "equipment-preset:1:item:0", "equipment-preset:1:save",
                "equipment-preset:2:clear", "equipment-preset:2:item:0", "equipment-preset:2:save",
                "equipment-current:clear", "equipment-current:item:0", "preserve-current-pattern",
            ),
            preview.steps.map { it.id },
        )
        assertEquals(
            setOf("equipment-preset:1:clear", "equipment-preset:1:item:0"),
            preview.steps.filterIsInstance<CharacterTransferStep.SaveEquipmentPreset>().first().dependsOn,
        )
    }

    private fun source(
        accountId: Long = 1,
        current: CharacterPatternSetting = setting(listOf(row("judge", "0", "skill"))),
        saved: Map<String, CharacterPatternSetting> = emptyMap(),
        stats: Map<CharacterStat, Int> = emptyMap(),
        skills: Set<String> = emptySet(),
        equipment: List<CharacterTransferEquipment> = emptyList(),
        equipmentPresets: Map<Int, List<CharacterTransferEquipment>> = emptyMap(),
    ) = CharacterTransferSource(
        accountId = accountId,
        characterId = 10,
        currentPattern = current,
        savedPatterns = saved,
        realStats = stats,
        learnedSkills = skills,
        equipment = equipment,
        equipmentPresets = equipmentPresets,
    )

    private fun target(
        accountId: Long = 1,
        capacity: Int = 10,
        occupied: Set<String> = emptySet(),
        points: Int = 0,
        stats: Map<CharacterStat, Int> = emptyMap(),
        learnable: Set<String> = emptySet(),
        equipment: Set<String> = emptySet(),
    ) = CharacterTransferTarget(
        accountId, 20, capacity, defaultRow,
        allowedJudges = setOf("judge", "first-judge"),
        allowedSkills = setOf("skill", "first-skill"),
        allowedPositions = setOf("front", "back"), allowedGuards = setOf("always", "never"),
        occupiedPatternSlots = occupied, statusPoints = points, realStats = stats,
        learnableSkills = learnable, equipmentCandidateValues = equipment,
        currentPattern = setting(List(capacity) { defaultRow }),
    )

    private fun setting(rows: List<CharacterPatternRowValue>) = CharacterPatternSetting(rows, "front", "always")
    private fun row(judge: String, quantity: String, skill: String) = CharacterPatternRowValue(judge, quantity, skill)
}
