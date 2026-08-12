package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.automation.entity.RaidAutomationCycleStatus
import app.spammy.hof.battle.model.BattleMapKeyMode
import app.spammy.hof.town.fishing.dto.FishingBattleTargetResponse
import app.spammy.hof.town.fishing.dto.FishingResponse
import app.spammy.hof.town.fishing.model.*
import app.spammy.hof.town.raid.dto.*
import app.spammy.hof.town.raid.model.*
import java.time.Instant
import kotlin.test.*

class NewAutomationHandlersTest {
    private val now = Instant.parse("2026-08-12T01:00:00Z")
    private val party = ResolvedAutomationParty(listOf("c1"), listOf(app.spammy.hof.battle.dto.BattlePatternLoadRequest("c1", 1)))

    @Test
    fun `fishing alternates primary action and waits at the daily limit`() {
        val handler = FishingAutomationHandler()
        val start = handler.evaluate(FishingAutomationSnapshot(1, fishing(FishingPrimaryAction.START), emptyList(), null, now))
        assertEquals(FishingAction.START, assertIs<FishingTownAutomationAction>(assertIs<HandlerEvaluation.Runnable>(start).action).action)
        val exhausted = handler.evaluate(FishingAutomationSnapshot(1, fishing(FishingPrimaryAction.NONE, remaining = 0), emptyList(), null, now))
        assertEquals("FISHING_DAILY_LIMIT", assertIs<HandlerEvaluation.Unavailable>(exhausted).reasonCode)
    }

    @Test
    fun `fishing battle uses configured preset then returns one battle action`() {
        val state = fishing(FishingPrimaryAction.NONE).copy(
            blockedByBattle = true, battleTarget = FishingBattleTargetResponse("battle_map", "fish-1", "낚시 전투"),
        )
        val action = assertIs<HandlerEvaluation.Runnable>(FishingAutomationHandler().evaluate(
            FishingAutomationSnapshot(1, state, listOf(FishingAutomationMapSetting("battle_map", "fish-1", PresetSelectionMode.EXPLICIT, 3, party)), null, now),
        )).action
        assertEquals(BattleAutomationActionSource.FISHING_AUTOMATION, assertIs<BattleMapAutomationAction>(action).source)
    }

    @Test
    fun `fishing battle selects the preset configured for its exact map`() {
        val state = fishing(FishingPrimaryAction.NONE).copy(
            blockedByBattle = true, battleTarget = FishingBattleTargetResponse("battle_map", "fish-2", "상어 떼"),
        )
        val otherParty = ResolvedAutomationParty(listOf("c2"), listOf(app.spammy.hof.battle.dto.BattlePatternLoadRequest("c2", 2)))
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
            blockedByBattle = true, battleTarget = FishingBattleTargetResponse("battle_map", "new-fish", "새 낚시 몬스터"),
        )
        val action = assertIs<BattleMapAutomationAction>(assertIs<HandlerEvaluation.Runnable>(
            FishingAutomationHandler().evaluate(FishingAutomationSnapshot(
                1, state, emptyList(),
                FishingAutomationPreset(PresetSelectionMode.PRIMARY, 11, party), now,
            )),
        ).action)

        assertEquals(PresetSelectionMode.PRIMARY, action.presetMode)
        assertEquals(11, action.presetId)
        assertEquals(party, action.resolvedParty)
    }

    @Test
    fun `union resumes persisted rotation and waits on shared cooldown`() {
        val settings = listOf(
            UnionAutomationSetting("union:a", "union", "a", PresetSelectionMode.EXPLICIT, 3, 0, party),
            UnionAutomationSetting("union:b", "union", "b", PresetSelectionMode.EXPLICIT, 3, 1, party),
        )
        val states = listOf(state("a", visible = false), state("b", cooldown = now.plusSeconds(60)))
        val wait = UnionAutomationHandler().evaluate(UnionAutomationSnapshot(1, settings, states, "union:b", now))
        assertEquals("UNION_SHARED_COOLDOWN", assertIs<HandlerEvaluation.Unavailable>(wait).reasonCode)
    }

    @Test
    fun `raid rewards before registration and treats closed as a local abort`() {
        val target = RaidAutomationTarget("r1", "레이드", PresetSelectionMode.EXPLICIT, 3, 0, party)
        val reward = RaidPubResponse(emptyList(), false, false, null, null, setOf(RaidAction.REWARD), null)
        assertEquals(RaidAction.REWARD, assertIs<RaidTownAutomationAction>(
            assertIs<HandlerEvaluation.Runnable>(RaidAutomationHandler().evaluate(RaidAutomationSnapshot(1, reward, listOf(target), null, null, now))).action,
        ).action)
        val closedRaid = RaidPubRaidResponse("r1", "레이드", true, null, null, null, RaidStatus.CLOSED, null, null, emptyList(), true, emptySet(), null)
        val closed = RaidAutomationHandler().evaluate(RaidAutomationSnapshot(
            1, RaidPubResponse(listOf(closedRaid), true, false, null, null, emptySet(), null), listOf(target), null,
            OpenRaidCycleSnapshot(9, "r1", RaidAutomationCycleStatus.IN_BATTLE, null), now,
        ))
        assertIs<RaidCycleAbortAutomationAction>(assertIs<HandlerEvaluation.Runnable>(closed).action)
    }

    private fun fishing(primary: FishingPrimaryAction, remaining: Int = 3) = FishingResponse(
        null, remaining, null, null, null, null, null, "낚시터", primary,
        if (primary == FishingPrimaryAction.NONE) emptySet() else setOf(if (primary == FishingPrimaryAction.START) FishingAction.START else FishingAction.CATCH),
        null, false, null, emptyList(), null,
    )
    private fun preset() = FishingAutomationPreset(PresetSelectionMode.EXPLICIT, 3, party)
    private fun state(code: String, visible: Boolean = true, cooldown: Instant? = null) = BattleMapRunnableState(
        "union", code, visible, true, cooldownUntil = cooldown, keyMode = BattleMapKeyMode.NOT_REQUIRED,
    )
}
