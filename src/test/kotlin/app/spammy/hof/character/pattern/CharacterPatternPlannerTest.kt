package app.spammy.hof.character.pattern

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CharacterPatternPlannerTest {
    private val planner = CharacterPatternPlanner()
    private val revision = Instant.parse("2026-08-17T00:00:00Z")
    private val default = row("first-judge", "first-skill", "0")

    @Test
    fun `same row count reorder produces one complete replacement plan`() {
        val base = setting(listOf(row("j1", "s1"), row("j2", "s2"), row("j3", "s3")))
        val draft = CharacterPatternDraft(revision, base.rows.reversed(), "front", "always")

        val result = planner.plan(
            base, revision, base, revision, draft, 3,
            allowedJudges = setOf("j1", "j2", "j3"),
            allowedSkills = setOf("s1", "s2", "s3"),
            allowedPositions = setOf("front", "back"),
            allowedGuards = setOf("always"),
        )

        assertEquals(base.rows.reversed(), assertIs<CharacterPatternPlanResult.Ready>(result).plan.rows)
    }

    @Test
    fun `insert above allows exactly one temporary overflow and locks apply`() {
        val draft = CharacterPatternDraft(revision, List(10) { row("j$it", "s$it") }, "front", "always")

        val inserted = planner.insertAbove(draft, selectedIndex = 4, capacity = 10, defaultRow = default)

        assertEquals(11, inserted.rows.size)
        assertEquals(default, inserted.rows[4])
        val result = planner.plan(
            setting(draft.rows), revision, setting(draft.rows), revision, inserted, 10,
            inserted.rows.map { it.judge }.toSet(), inserted.rows.map { it.skill }.toSet(), setOf("front"), setOf("always"),
        )
        assertEquals("DELETE_REQUIRED", assertIs<CharacterPatternPlanResult.Rejected>(result).code)
    }

    @Test
    fun `delete compacts rows and fills the final row with first options and zero`() {
        val rows = listOf(row("j1", "s1"), row("j2", "s2"), row("j3", "s3"))
        val draft = CharacterPatternDraft(revision, rows, "front", "always")

        val deleted = planner.delete(draft, selectedIndex = 1, capacity = 3, defaultRow = default)

        assertEquals(listOf(rows[0], rows[2], default), deleted.rows)
    }

    @Test
    fun `server revision conflict returns only changed row numbers and values`() {
        val base = setting(listOf(row("j1", "s1"), row("j2", "s2")))
        val current = setting(listOf(row("j1", "s1"), row("changed", "s2")))
        val draft = CharacterPatternDraft(revision, base.rows, "front", "always")

        val result = planner.plan(
            base, revision, current, revision.plusSeconds(1), draft, 2,
            setOf("j1", "j2"), setOf("s1", "s2"), setOf("front"), setOf("always"),
        )

        val conflict = assertIs<CharacterPatternPlanResult.Conflict>(result)
        assertEquals(listOf(2), conflict.rowDiffs.map { it.rowNumber })
        assertEquals("changed", conflict.rowDiffs.single().current?.judge)
    }

    @Test
    fun `user confirmed overwrite validates the draft but bypasses only revision conflict`() {
        val base = setting(listOf(row("j1", "s1")))
        val current = setting(listOf(row("changed", "s1")))
        val draft = CharacterPatternDraft(revision, listOf(row("j1", "s1")), "front", "always")

        val result = planner.plan(
            base, revision, current, revision.plusSeconds(1), draft, 1,
            setOf("j1", "changed"), setOf("s1"), setOf("front"), setOf("always"),
            force = true,
        )

        assertIs<CharacterPatternPlanResult.Ready>(result)
    }

    private fun row(judge: String, skill: String, quantity: String = "0") = CharacterPatternRowValue(judge, quantity, skill)
    private fun setting(rows: List<CharacterPatternRowValue>) = CharacterPatternSetting(rows, "front", "always")
}
