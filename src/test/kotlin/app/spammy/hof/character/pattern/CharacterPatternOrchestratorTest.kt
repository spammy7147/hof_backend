package app.spammy.hof.character.pattern

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CharacterPatternOrchestratorTest {
    private val revision = Instant.parse("2026-08-17T00:00:00Z")
    private val base = CharacterPatternSetting(
        rows = listOf(row("j1", "s1"), row("j2", "s2")),
        position = "front",
        guard = "always",
    )

    @Test
    fun `apply submits every row once then position and guard without original add delete`() {
        val remote = FakeRemote(state())
        val draft = CharacterPatternDraft(revision, base.rows.reversed(), "back", "never")

        val result = CharacterPatternOrchestrator().apply(remote, base, revision, draft)

        assertIs<CharacterPatternOperationResult.Completed>(result)
        assertEquals(listOf("observe", "changeRows:2", "observe", "positionGuard:back:never", "observe"), remote.operations)
    }

    @Test
    fun `lost row response is observed and never blindly retransmitted`() {
        val remote = FakeRemote(state(), loseRowsResponse = true)
        val draft = CharacterPatternDraft(revision, base.rows.reversed(), "front", "always")

        val result = CharacterPatternOrchestrator().apply(remote, base, revision, draft)

        assertIs<CharacterPatternOperationResult.Completed>(result)
        assertEquals(1, remote.operations.count { it.startsWith("changeRows") })
    }

    @Test
    fun `server change returns row diff before any mutation`() {
        val changed = base.copy(rows = listOf(base.rows[0], row("changed", "s2")))
        val remote = FakeRemote(state(setting = changed, revision = revision.plusSeconds(1)))
        val draft = CharacterPatternDraft(revision, base.rows, "front", "always")

        val result = CharacterPatternOrchestrator().apply(remote, base, revision, draft)

        assertEquals(listOf(2), assertIs<CharacterPatternOperationResult.Conflict>(result).rowDiffs.map { it.rowNumber })
        assertEquals(listOf("observe"), remote.operations)
    }

    @Test
    fun `apply and save empty slot happens only after both current setting forms`() {
        val slots = listOf(CharacterPatternRemoteSlot("0", "빈슬롯", false))
        val remote = FakeRemote(state(slots = slots))
        val draft = CharacterPatternDraft(revision, base.rows, "back", "never")

        val result = CharacterPatternOrchestrator().apply(
            remote,
            base,
            revision,
            draft,
            PatternSlotAfterApply.SaveEmpty("0", "대회랑"),
        )

        assertIs<CharacterPatternOperationResult.Completed>(result)
        assertEquals(
            listOf("observe", "changeRows:2", "observe", "positionGuard:back:never", "observe", "save:0:대회랑", "observe"),
            remote.operations,
        )
    }

    @Test
    fun `replace deletes observed slot then saves same target and reports partial save failure`() {
        val slots = listOf(CharacterPatternRemoteSlot("5", "대회랑", true))
        val remote = FakeRemote(state(slots = slots), failSave = true)
        val draft = CharacterPatternDraft(revision, base.rows, "front", "always")

        val result = CharacterPatternOrchestrator().apply(
            remote,
            base,
            revision,
            draft,
            PatternSlotAfterApply.Replace("5", "대회랑"),
        )

        assertIs<CharacterPatternOperationResult.PartiallyApplied>(result)
        assertEquals(listOf("delete:5", "save:5:대회랑"), remote.operations.filter { it.startsWith("delete") || it.startsWith("save") })
    }

    @Test
    fun `missing skill is rejected before sending anything`() {
        val remote = FakeRemote(state())
        val draft = CharacterPatternDraft(revision, listOf(row("j1", "missing"), row("j2", "s2")), "front", "always")

        val result = CharacterPatternOrchestrator().apply(remote, base, revision, draft)

        assertEquals("SKILL_NOT_ALLOWED", assertIs<CharacterPatternOperationResult.Rejected>(result).code)
        assertEquals(listOf("observe"), remote.operations)
    }

    private class FakeRemote(
        initial: CharacterPatternRemoteState,
        private val loseRowsResponse: Boolean = false,
        private val failSave: Boolean = false,
    ) : CharacterPatternRemote {
        var state = initial
        val operations = mutableListOf<String>()

        override fun observe(): CharacterPatternRemoteState = state.also { operations += "observe" }

        override fun changeAllRows(rows: List<CharacterPatternRowValue>): CharacterPatternMutationReceipt {
            operations += "changeRows:${rows.size}"
            state = state.copy(revision = state.revision.plusSeconds(1), setting = state.setting.copy(rows = rows))
            return if (loseRowsResponse) CharacterPatternMutationReceipt.RESPONSE_LOST else CharacterPatternMutationReceipt.RESPONSE_RECEIVED
        }

        override fun changePositionGuard(position: String, guard: String): CharacterPatternMutationReceipt {
            operations += "positionGuard:$position:$guard"
            state = state.copy(
                revision = state.revision.plusSeconds(1),
                setting = state.setting.copy(position = position, guard = guard),
            )
            return CharacterPatternMutationReceipt.RESPONSE_RECEIVED
        }

        override fun saveSlot(slotCode: String, name: String): CharacterPatternMutationReceipt {
            operations += "save:$slotCode:$name"
            if (!failSave) replaceSlot(slotCode, CharacterPatternRemoteSlot(slotCode, name, true))
            return CharacterPatternMutationReceipt.RESPONSE_RECEIVED
        }

        override fun deleteSlot(slotCode: String): CharacterPatternMutationReceipt {
            operations += "delete:$slotCode"
            replaceSlot(slotCode, CharacterPatternRemoteSlot(slotCode, "빈슬롯", false))
            return CharacterPatternMutationReceipt.RESPONSE_RECEIVED
        }

        override fun loadSlot(slotCode: String): CharacterPatternMutationReceipt {
            operations += "load:$slotCode"
            state = state.copy(revision = state.revision.plusSeconds(1))
            return CharacterPatternMutationReceipt.RESPONSE_RECEIVED
        }

        private fun replaceSlot(slotCode: String, replacement: CharacterPatternRemoteSlot) {
            state = state.copy(
                revision = state.revision.plusSeconds(1),
                savedSlots = state.savedSlots.map { if (it.slotCode == slotCode) replacement else it },
            )
        }
    }

    private fun state(
        setting: CharacterPatternSetting = base,
        revision: Instant = this.revision,
        slots: List<CharacterPatternRemoteSlot> = emptyList(),
    ) = CharacterPatternRemoteState(
        revision,
        setting,
        capacity = 2,
        judgeValues = setOf("j1", "j2", "changed"),
        skillValues = setOf("s1", "s2"),
        positionValues = setOf("front", "back"),
        guardValues = setOf("always", "never"),
        savedSlots = slots,
    )

    private fun row(judge: String, skill: String) = CharacterPatternRowValue(judge, "0", skill)
}
