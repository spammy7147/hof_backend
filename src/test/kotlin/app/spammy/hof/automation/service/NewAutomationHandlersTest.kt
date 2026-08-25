package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.battle.dto.BattlePatternLoadRequest
import app.spammy.hof.battle.model.BattleMapKeyMode
import app.spammy.hof.town.fishing.dto.FishingBattleTargetResponse
import app.spammy.hof.town.fishing.dto.FishingResponse
import app.spammy.hof.town.fishing.model.FishingAction
import app.spammy.hof.town.fishing.model.FishingOutcome
import app.spammy.hof.town.fishing.model.FishingPrimaryAction
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class NewAutomationHandlersTest {
    private val now = Instant.parse("2026-08-12T01:00:00Z")
    private val party = ResolvedAutomationParty(listOf("c1"), listOf(BattlePatternLoadRequest("c1", 1)))

    @Test
    fun `fishing alternates primary action and waits at the daily limit`() {
        val handler = FishingAutomationHandler()
        val start = handler.evaluate(FishingAutomationSnapshot(1, fishing(FishingPrimaryAction.START), emptyList(), null, now))
        assertEquals(FishingAction.START, assertIs<FishingTownAutomationAction>(assertIs<HandlerEvaluation.Runnable>(start).action).action)
        val catch = handler.evaluate(FishingAutomationSnapshot(1, fishing(FishingPrimaryAction.CATCH), emptyList(), null, now))
        assertEquals(FishingAction.CATCH, assertIs<FishingTownAutomationAction>(assertIs<HandlerEvaluation.Runnable>(catch).action).action)
        val exhausted = handler.evaluate(FishingAutomationSnapshot(1, fishing(FishingPrimaryAction.NONE, remaining = 0), emptyList(), null, now))
        assertEquals("FISHING_DAILY_LIMIT", assertIs<HandlerEvaluation.Unavailable>(exhausted).reasonCode)
    }

    @Test
    fun `fishing catches the final cast even after the remaining count reaches zero`() {
        val finalCatch = FishingAutomationHandler().evaluate(
            FishingAutomationSnapshot(1, fishing(FishingPrimaryAction.CATCH, remaining = 0), emptyList(), null, now),
        )

        assertEquals(
            FishingAction.CATCH,
            assertIs<FishingTownAutomationAction>(assertIs<HandlerEvaluation.Runnable>(finalCatch).action).action,
        )
    }

    @Test
    fun `fishing does not submit start again while the catch transition is pending`() {
        val staleStartedPage = fishing(FishingPrimaryAction.START).copy(lastOutcome = FishingOutcome.STARTED)

        val result = assertIs<HandlerEvaluation.Unavailable>(FishingAutomationHandler().evaluate(
            FishingAutomationSnapshot(1, staleStartedPage, emptyList(), null, now),
        ))

        assertEquals("FISHING_CATCH_TRANSITION_PENDING", result.reasonCode)
        assertEquals(now.plusSeconds(5), result.nextRunAt)
        assertEquals(AutomationWaitScope.HOLD_CURRENT_WORK, result.waitScope)
    }

    @Test
    fun `fishing branches to battle only after catch exposes a blocked battle state`() {
        val state = fishing(FishingPrimaryAction.NONE).copy(
            blockedByBattle = true,
            battleTarget = FishingBattleTargetResponse("battle_map", "fish-1", "낚시 전투"),
        )
        val action = assertIs<HandlerEvaluation.Runnable>(FishingAutomationHandler().evaluate(
            FishingAutomationSnapshot(
                1,
                state,
                listOf(FishingAutomationMapSetting("battle_map", "fish-1", PresetSelectionMode.EXPLICIT, 3, party)),
                null,
                now,
            ),
        )).action
        assertEquals(BattleAutomationActionSource.FISHING_AUTOMATION, assertIs<BattleMapAutomationAction>(action).source)
    }

    @Test
    fun `blocked fishing without a target becomes an authoritative observation gap`() {
        val state = fishing(FishingPrimaryAction.NONE).copy(blockedByBattle = true, battleTarget = null)

        val gap = assertIs<HandlerEvaluation.ObservationGap>(
            FishingAutomationHandler().evaluate(
                FishingAutomationSnapshot(1, state, emptyList(), null, now),
            ),
        )

        assertEquals("FISHING_BATTLE_TARGET_MISSING", gap.reasonCode)
        assertEquals(null, gap.scopeKey)
        assertEquals(true, gap.authoritative)
    }

    @Test
    fun `fishing battle selects the preset configured for its exact map`() {
        val state = fishing(FishingPrimaryAction.NONE).copy(
            blockedByBattle = true,
            battleTarget = FishingBattleTargetResponse("battle_map", "fish-2", "상어 떼"),
        )
        val otherParty = ResolvedAutomationParty(listOf("c2"), listOf(BattlePatternLoadRequest("c2", 2)))
        val action = assertIs<BattleMapAutomationAction>(assertIs<HandlerEvaluation.Runnable>(
            FishingAutomationHandler().evaluate(FishingAutomationSnapshot(
                1,
                state,
                listOf(
                    FishingAutomationMapSetting("battle_map", "fish-1", PresetSelectionMode.EXPLICIT, 3, party),
                    FishingAutomationMapSetting("battle_map", "fish-2", PresetSelectionMode.EXPLICIT, 8, otherParty),
                ),
                null,
                now,
            )),
        ).action)

        assertEquals(8, action.presetId)
        assertEquals(otherParty, action.resolvedParty)
    }

    @Test
    fun `unconfigured fishing battle uses the current primary preset`() {
        val state = fishing(FishingPrimaryAction.NONE).copy(
            blockedByBattle = true,
            battleTarget = FishingBattleTargetResponse("battle_map", "new-fish", "새 낚시 몬스터"),
        )
        val action = assertIs<BattleMapAutomationAction>(assertIs<HandlerEvaluation.Runnable>(
            FishingAutomationHandler().evaluate(FishingAutomationSnapshot(
                1,
                state,
                emptyList(),
                FishingAutomationPreset(PresetSelectionMode.PRIMARY, 11, party),
                now,
            )),
        ).action)

        assertEquals(PresetSelectionMode.PRIMARY, action.presetMode)
        assertEquals(11, action.presetId)
        assertEquals(party, action.resolvedParty)
    }

    @Test
    fun `union resumes persisted rotation and skips shared cooldown without a wait deadline`() {
        val settings = listOf(
            UnionAutomationSetting("union:a", "union", "a", PresetSelectionMode.EXPLICIT, 3, 0, party),
            UnionAutomationSetting("union:b", "union", "b", PresetSelectionMode.EXPLICIT, 3, 1, party),
        )
        val states = listOf(state("a", visible = false), state("b", cooldown = now.plusSeconds(60)))
        val skipped = UnionAutomationHandler().evaluate(UnionAutomationSnapshot(1, settings, states, "union:b", now))
        assertEquals("UNION_SHARED_COOLDOWN", assertIs<HandlerEvaluation.SkippedReason>(skipped).reasonCode)
    }

    @Test
    fun `configured union maps temporarily absent from the live page are skipped without a union wait`() {
        val settings = listOf(UnionAutomationSetting("union:a", "union", "a", PresetSelectionMode.EXPLICIT, 3, 0, party))

        val skipped = assertIs<HandlerEvaluation.SkippedReason>(
            UnionAutomationHandler().evaluate(UnionAutomationSnapshot(1, settings, emptyList(), null, now)),
        )

        assertEquals("UNION_MAP_ABSENT", skipped.reasonCode)
        assertEquals("현재 관측된 유니온 맵이 없어 건너뜁니다.", skipped.message)
    }

    private fun fishing(primary: FishingPrimaryAction, remaining: Int = 3) = FishingResponse(
        null,
        remaining,
        null,
        null,
        null,
        null,
        null,
        "낚시터",
        primary,
        if (primary == FishingPrimaryAction.NONE) emptySet() else setOf(
            if (primary == FishingPrimaryAction.START) FishingAction.START else FishingAction.CATCH,
        ),
        null,
        false,
        null,
        emptyList(),
        null,
    )

    private fun state(code: String, visible: Boolean = true, cooldown: Instant? = null) = BattleMapRunnableState(
        "union",
        code,
        visible,
        true,
        cooldownUntil = cooldown,
        keyMode = BattleMapKeyMode.NOT_REQUIRED,
    )
}
