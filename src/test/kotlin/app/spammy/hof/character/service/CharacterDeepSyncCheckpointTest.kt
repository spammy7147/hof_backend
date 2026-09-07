package app.spammy.hof.character.service

import app.spammy.hof.external.model.HofActionPatternRow
import app.spammy.hof.external.model.HofEquipment
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CharacterDeepSyncCheckpointTest {
    private val original = CharacterRestoreState(
        "fixture", (0..2).map { HofActionPatternRow(it, judge = "$it", quantity = "$it", skill = "0") },
        listOf(HofEquipment("shield", "Shield", "Pattern Shield", "/shield.gif", "패턴 추가 +2")), "front", "always",
    )
    private val unequipped = original.copy(patterns = original.patterns.take(1), equipment = emptyList())

    @Test
    fun `equipment changes allow trailing rows to disappear but preserve every remaining condition`() {
        val checkpoint = CharacterDeepSyncCheckpoint(original, pendingChange = CharacterSyncChange.LOAD_EQUIPMENT)
        checkpoint.requireExpected(unequipped)
        listOf(
            unequipped.copy(patterns = listOf(unequipped.patterns.single().copy(judge = "external"))),
            unequipped.copy(patterns = listOf(unequipped.patterns.single().copy(quantity = "external"))),
            unequipped.copy(position = "back"),
            unequipped.copy(guard = "never"),
            unequipped.copy(equipment = original.equipment),
        ).forEach { changed -> assertFailsWith<IllegalStateException> { checkpoint.requireExpected(changed) } }
    }

    @Test
    fun `restoring equipment can reveal trailing rows but cannot replace existing conditions or introduce other equipment`() {
        val checkpoint = CharacterDeepSyncCheckpoint(original,
            observed = CharacterSyncObservation.from(unequipped), pendingChange = CharacterSyncChange.RESTORE_EQUIPMENT)
        val expanded = original.copy(patterns = original.patterns.map { it.copy(skill = "fallback") })
        checkpoint.requireExpected(expanded)
        assertFailsWith<IllegalStateException> {
            checkpoint.requireExpected(expanded.copy(patterns = expanded.patterns.map { it.copy(judge = "external") }))
        }
        assertFailsWith<IllegalStateException> {
            checkpoint.requireExpected(expanded.copy(equipment = listOf(original.equipment.single().copy(name = "Other shield"))))
        }
    }

    @Test
    fun `missing or corrupt prefix evidence does not allow a reduced pattern to bypass recovery checks`() {
        val observed = CharacterSyncObservation.from(original)
        listOf(emptyList(), listOf("invalid", observed.conditions)).forEach { prefixes ->
            val checkpoint = CharacterDeepSyncCheckpoint(original,
                observed = observed.copy(conditionPrefixes = prefixes), pendingChange = CharacterSyncChange.LOAD_EQUIPMENT)
            assertFailsWith<IllegalStateException> { checkpoint.requireExpected(unequipped) }
        }
    }
}
