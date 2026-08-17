package app.spammy.hof.character.transfer

import app.spammy.hof.character.command.CharacterStat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CharacterTransferExecutorTest {
    @Test
    fun `continues unrelated work skips dependency and resumes completed checkpoints without source command`() {
        val calls = mutableListOf<Pair<Long, String>>()
        val checkpoints = mutableListOf<CharacterTransferStepResult>()
        val executor = CharacterTransferExecutor { targetId, step ->
            calls += targetId to step.id
            if (step.id == "skill:x") Result.failure(IllegalStateException("learn failed")) else Result.success(Unit)
        }
        val preview = CharacterTransferPreview(
            sourceCharacterId = 10,
            targetCharacterId = 20,
            steps = listOf(
                CharacterTransferStep.AllocateStats("stats", mapOf(CharacterStat.STR to 1)),
                CharacterTransferStep.LearnSkill("skill:x", "x"),
                CharacterTransferStep.EquipItem("equipment:weapon", "weapon", "item"),
                CharacterTransferStep.SavePatternSlot(
                    "pattern:1", "1", "1", "범용",
                    app.spammy.hof.character.pattern.CharacterPatternSetting(emptyList(), "front", "always"),
                    replacesExisting = false,
                    dependsOn = setOf("skill:x"),
                ),
            ),
            issues = emptyList(),
        )

        val result = executor.execute(
            preview,
            completedStepIds = setOf("stats"),
            onStepResult = checkpoints::add,
        )

        assertEquals(
            listOf(
                CharacterTransferStepStatus.COMPLETED,
                CharacterTransferStepStatus.FAILED,
                CharacterTransferStepStatus.COMPLETED,
                CharacterTransferStepStatus.SKIPPED,
            ),
            result.results.map { it.status },
        )
        assertTrue(calls.all { (characterId, _) -> characterId == 20L })
        assertEquals(listOf("skill:x", "equipment:weapon"), calls.map { it.second })
        assertEquals(result.results, checkpoints)
    }
}
