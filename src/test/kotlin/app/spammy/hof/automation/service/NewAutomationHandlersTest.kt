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
    fun `raid rewards only a completed active cycle and treats closed as a local abort`() {
        val target = RaidAutomationTarget("r1", "레이드", PresetSelectionMode.EXPLICIT, 3, 0, party)
        val completedRaid = RaidPubRaidResponse(
            "r1", "레이드", true, null, null, null, RaidStatus.COMPLETED,
            "보상 확인 시간 (남은 시간 앞으로 0시간 26분 43초)", null,
            listOf("현재사용자"), true, setOf(RaidAction.RESET), null,
        )
        val reward = RaidPubResponse(listOf(completedRaid), true, false, null, null, setOf(RaidAction.REWARD), null)
        assertEquals(RaidAction.REWARD, assertIs<RaidTownAutomationAction>(
            assertIs<HandlerEvaluation.Runnable>(RaidAutomationHandler().evaluate(RaidAutomationSnapshot(
                1, reward, listOf(target), null,
                OpenRaidCycleSnapshot(9, "r1", RaidAutomationCycleStatus.IN_BATTLE, null), now,
            ))).action,
        ).action)
        val closedRaid = RaidPubRaidResponse("r1", "레이드", true, null, null, null, RaidStatus.CLOSED, null, null, emptyList(), true, emptySet(), null)
        val closed = RaidAutomationHandler().evaluate(RaidAutomationSnapshot(
            1, RaidPubResponse(listOf(closedRaid), true, false, null, null, emptySet(), null), listOf(target), null,
            OpenRaidCycleSnapshot(9, "r1", RaidAutomationCycleStatus.IN_BATTLE, null), now,
        ))
        assertIs<RaidCycleAbortAutomationAction>(assertIs<HandlerEvaluation.Runnable>(closed).action)
    }

    @Test
    fun `raid does not claim the always visible reward button before completion`() {
        val target = RaidAutomationTarget("r1", "레이드", PresetSelectionMode.EXPLICIT, 3, 0, party)
        val recruiting = RaidPubRaidResponse(
            "r1", "레이드", true, null, null, null, RaidStatus.RECRUITING,
            "파티 모집 중 (신청 안됨)", null, emptyList(), false, setOf(RaidAction.REGISTER), null,
        )
        val evaluation = RaidAutomationHandler().evaluate(RaidAutomationSnapshot(
            1,
            RaidPubResponse(listOf(recruiting), false, false, null, null, setOf(RaidAction.REWARD), null),
            listOf(target), null, null, now,
        ))

        val action = assertIs<RaidTownAutomationAction>(assertIs<HandlerEvaluation.Runnable>(evaluation).action)
        assertEquals(RaidAction.REGISTER, action.action)
    }

    @Test
    fun `raid resets once after reward collection before completing the cycle`() {
        val target = RaidAutomationTarget("r1", "레이드", PresetSelectionMode.EXPLICIT, 3, 0, party)
        val rewarded = RaidPubRaidResponse(
            "r1", "레이드", true, null, null, null, RaidStatus.COMPLETED,
            "보상 확인 시간", null, listOf("현재사용자"), true, setOf(RaidAction.RESET), null,
        )
        val evaluation = RaidAutomationHandler().evaluate(RaidAutomationSnapshot(
            1,
            RaidPubResponse(listOf(rewarded), true, true, 10_786, null, setOf(RaidAction.REWARD), null),
            listOf(target), null,
            OpenRaidCycleSnapshot(9, "r1", RaidAutomationCycleStatus.REWARD_PENDING, null), now,
        ))

        val action = assertIs<RaidTownAutomationAction>(assertIs<HandlerEvaluation.Runnable>(evaluation).action)
        assertEquals(RaidAction.RESET, action.action)
        assertEquals("r1", action.raidId)
    }

    @Test
    fun `raid waits for departure time even while the start button is visible`() {
        val target = RaidAutomationTarget("RaidGoblin", "고블린 전투 마차", PresetSelectionMode.EXPLICIT, 3, 0, party)
        val waiting = RaidPubRaidResponse(
            "RaidGoblin", "고블린 전투 마차", true, null, 6, "100000+",
            RaidStatus.WAITING, "파티 모집 중 (1294초 후 출발 가능)", 1294,
            listOf("현재사용자"), true, setOf(RaidAction.START), null,
        )
        val evaluation = RaidAutomationHandler().evaluate(RaidAutomationSnapshot(
            1,
            RaidPubResponse(listOf(waiting), true, false, null, null, emptySet(), null),
            listOf(target),
            null,
            OpenRaidCycleSnapshot(9, "RaidGoblin", RaidAutomationCycleStatus.REGISTERED_WAITING, null),
            now,
        ))

        val unavailable = assertIs<HandlerEvaluation.Unavailable>(evaluation)
        assertEquals("RAID_WAITING_TO_START", unavailable.reasonCode)
        assertEquals(now.plusSeconds(1294), unavailable.nextRunAt)
    }

    @Test
    fun `raid waits for the observed next battle cooldown`() {
        val target = RaidAutomationTarget("RaidGoblin", "고블린 전투 마차", PresetSelectionMode.EXPLICIT, 3, 0, party)
        val fighting = RaidPubRaidResponse(
            "RaidGoblin", "고블린 전투 마차", true, null, 6, "100000+",
            RaidStatus.IN_BATTLE, "전투 중", null, listOf("현재사용자"), true, emptySet(),
            RaidBattleTargetResponse("raid", "raid001", 99),
        )

        val evaluation = RaidAutomationHandler().evaluate(RaidAutomationSnapshot(
            1,
            RaidPubResponse(listOf(fighting), true, false, null, null, emptySet(), null),
            listOf(target),
            null,
            OpenRaidCycleSnapshot(9, "RaidGoblin", RaidAutomationCycleStatus.IN_BATTLE, null),
            now,
        ))

        val unavailable = assertIs<HandlerEvaluation.Unavailable>(evaluation)
        assertEquals("RAID_BATTLE_COOLDOWN", unavailable.reasonCode)
        assertEquals(now.plusSeconds(99), unavailable.nextRunAt)
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
