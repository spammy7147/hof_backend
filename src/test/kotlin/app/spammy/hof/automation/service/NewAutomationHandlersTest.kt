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
    }

    @Test
    fun `fishing branches to battle only after catch exposes a blocked battle state`() {
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
    fun `configured union maps temporarily absent from the live page wait for respawn without a configuration warning`() {
        val settings = listOf(
            UnionAutomationSetting("union:a", "union", "a", PresetSelectionMode.EXPLICIT, 3, 0, party),
        )

        val wait = assertIs<HandlerEvaluation.Unavailable>(
            UnionAutomationHandler().evaluate(UnionAutomationSnapshot(1, settings, emptyList(), null, now)),
        )

        assertEquals("UNION_MAP_RESPAWN_WAIT", wait.reasonCode)
        assertEquals("유니온 맵 재생성을 기다립니다.", wait.message)
        assertEquals(now.plusSeconds(300), wait.nextRunAt)
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
            listOf(target), null, null, now, registrationCooldownChecked = true,
        ))

        val action = assertIs<RaidTownAutomationAction>(assertIs<HandlerEvaluation.Runnable>(evaluation).action)
        assertEquals(RaidAction.REGISTER, action.action)
    }

    @Test
    fun `raid refreshes status after reward instead of trusting a visible reset action`() {
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
        assertEquals(RaidAction.REFRESH, action.action)
        assertEquals(null, action.raidId)
        assertEquals("r1", action.targetRaidId)
        assertEquals("레이드", action.raidName)
        assertEquals("보상 확인 시간", action.observedStatus)
    }

    @Test
    fun `raid waits until the reported reward confirmation time instead of polling every thirty seconds`() {
        val target = RaidAutomationTarget("r1", "레이드", PresetSelectionMode.EXPLICIT, 3, 0, party)
        val rewarded = RaidPubRaidResponse(
            "r1", "레이드", true, null, null, null, RaidStatus.COMPLETED,
            "보상 확인 시간 (남은 시간 앞으로 0시간 23분 10초)", 1_390,
            listOf("현재사용자"), true, setOf(RaidAction.RESET), null,
        )
        val evaluation = RaidAutomationHandler().evaluate(RaidAutomationSnapshot(
            1,
            RaidPubResponse(listOf(rewarded), true, true, 10_786, null, setOf(RaidAction.REWARD), null),
            listOf(target), null,
            OpenRaidCycleSnapshot(9, "r1", RaidAutomationCycleStatus.REWARD_PENDING, null), now,
        ))

        val unavailable = assertIs<HandlerEvaluation.Unavailable>(evaluation)
        assertEquals("RAID_REWARD_CONFIRMATION_WAIT", unavailable.reasonCode)
        assertEquals(now.plusSeconds(1_390), unavailable.nextRunAt)
    }

    @Test
    fun `raid resets after reward only when the active raid reports reward confirmation ended`() {
        val target = RaidAutomationTarget("r1", "레이드", PresetSelectionMode.EXPLICIT, 3, 0, party)
        val resettable = RaidPubRaidResponse(
            "r1", "레이드", true, null, null, null, RaidStatus.COMPLETED,
            "현재 상태 : 보상 확인 종료(리셋 가능)", null, listOf("현재사용자"), true,
            setOf(RaidAction.RESET), null,
        )
        val evaluation = RaidAutomationHandler().evaluate(RaidAutomationSnapshot(
            1,
            RaidPubResponse(listOf(resettable), true, true, 10_786, null, setOf(RaidAction.REWARD), null),
            listOf(target), null,
            OpenRaidCycleSnapshot(9, "r1", RaidAutomationCycleStatus.REWARD_PENDING, null), now,
        ))

        val action = assertIs<RaidTownAutomationAction>(assertIs<HandlerEvaluation.Runnable>(evaluation).action)
        assertEquals(RaidAction.RESET, action.action)
        assertEquals("r1", action.raidId)
    }

    @Test
    fun `raid resets an expired reward confirmation instead of aborting or requesting reward again`() {
        val target = RaidAutomationTarget("r1", "레이드", PresetSelectionMode.EXPLICIT, 3, 0, party)
        val resettable = RaidPubRaidResponse(
            "r1", "레이드", true, null, null, null, RaidStatus.COMPLETED,
            "보상 확인 종료(리셋 가능)", null, listOf("현재사용자"), true,
            setOf(RaidAction.RESET), null,
        )
        val evaluation = RaidAutomationHandler().evaluate(RaidAutomationSnapshot(
            1,
            RaidPubResponse(listOf(resettable), true, false, null, null, setOf(RaidAction.REWARD), null),
            listOf(target), null,
            OpenRaidCycleSnapshot(9, "r1", RaidAutomationCycleStatus.IN_BATTLE, null), now,
        ))

        val action = assertIs<RaidTownAutomationAction>(assertIs<HandlerEvaluation.Runnable>(evaluation).action)
        assertEquals(RaidAction.RESET, action.action)
        assertEquals("r1", action.raidId)
    }

    @Test
    fun `raid resets an expired reward confirmation before registration even without an open cycle`() {
        val target = RaidAutomationTarget("r1", "레이드", PresetSelectionMode.EXPLICIT, 3, 0, party)
        val resettable = RaidPubRaidResponse(
            "r1", "레이드", true, null, null, null, RaidStatus.COMPLETED,
            "보상 확인 종료(리셋 가능)", null, emptyList(), false,
            setOf(RaidAction.REGISTER, RaidAction.RESET), null,
        )
        val evaluation = RaidAutomationHandler().evaluate(RaidAutomationSnapshot(
            1,
            RaidPubResponse(listOf(resettable), false, true, 10_786, null, emptySet(), null),
            listOf(target), null, null, now,
        ))

        val action = assertIs<RaidTownAutomationAction>(assertIs<HandlerEvaluation.Runnable>(evaluation).action)
        assertEquals(RaidAction.RESET, action.action)
        assertEquals("r1", action.raidId)
    }

    @Test
    fun `raid registers while departure is pending when the current user is not an applicant`() {
        val target = RaidAutomationTarget("r1", "레이드", PresetSelectionMode.EXPLICIT, 3, 0, party)
        val waiting = RaidPubRaidResponse(
            "r1", "레이드", true, null, null, null, RaidStatus.WAITING,
            "파티 모집 중 (30초 후 출발 가능)", 30, emptyList(), false,
            setOf(RaidAction.REGISTER), null,
        )
        val evaluation = RaidAutomationHandler().evaluate(RaidAutomationSnapshot(
            1,
            RaidPubResponse(listOf(waiting), false, false, null, null, emptySet(), null),
            listOf(target), null, null, now, registrationCooldownChecked = true,
        ))

        val action = assertIs<RaidTownAutomationAction>(assertIs<HandlerEvaluation.Runnable>(evaluation).action)
        assertEquals(RaidAction.REGISTER, action.action)
        assertEquals("r1", action.raidId)
    }

    @Test
    fun `raid refreshes status before checking registration cooldown`() {
        val target = RaidAutomationTarget("r1", "레이드", PresetSelectionMode.EXPLICIT, 3, 0, party)
        val apparentlyRegisterable = RaidPubRaidResponse(
            "r1", "레이드", true, null, null, null, RaidStatus.RECRUITING,
            "파티 모집 중 (신청 안됨)", null, emptyList(), false,
            setOf(RaidAction.REGISTER), null,
        )
        val evaluation = RaidAutomationHandler().evaluate(RaidAutomationSnapshot(
            1,
            RaidPubResponse(listOf(apparentlyRegisterable), false, false, null, null, setOf(RaidAction.REFRESH), null),
            listOf(target), null, null, now,
        ))

        val action = assertIs<RaidTownAutomationAction>(assertIs<HandlerEvaluation.Runnable>(evaluation).action)
        assertEquals(RaidAction.REFRESH, action.action)
        assertEquals("r1", action.targetRaidId)
    }

    @Test
    fun `raid waits for refreshed registration cooldown`() {
        val target = RaidAutomationTarget("r1", "레이드", PresetSelectionMode.EXPLICIT, 3, 0, party)
        val raid = RaidPubRaidResponse(
            "r1", "레이드", true, null, null, null, RaidStatus.RECRUITING,
            "파티 모집 중 (신청 안됨)", null, emptyList(), false,
            setOf(RaidAction.REGISTER), null,
        )
        val evaluation = RaidAutomationHandler().evaluate(RaidAutomationSnapshot(
            1,
            RaidPubResponse(listOf(raid), false, true, 5_878, null, setOf(RaidAction.REFRESH), null),
            listOf(target), null, null, now, registrationCooldownChecked = true,
        ))

        val unavailable = assertIs<HandlerEvaluation.Unavailable>(evaluation)
        assertEquals("RAID_SHARED_COOLDOWN", unavailable.reasonCode)
        assertEquals(now.plusSeconds(5_878), unavailable.nextRunAt)
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
    fun `raid abandons a stale registered cycle before registering again`() {
        val target = RaidAutomationTarget("RaidGoblin", "고블린 전투 마차", PresetSelectionMode.EXPLICIT, 3, 0, party)
        val registerable = RaidPubRaidResponse(
            "RaidGoblin", "고블린 전투 마차", true, null, 6, "100000+",
            RaidStatus.RECRUITING, "파티 모집 중 (신청 안됨)", null,
            emptyList(), false, setOf(RaidAction.REGISTER), null,
        )
        val snapshot = RaidAutomationSnapshot(
            1,
            RaidPubResponse(listOf(registerable), false, false, null, null, emptySet(), null),
            listOf(target),
            null,
            OpenRaidCycleSnapshot(9, "RaidGoblin", RaidAutomationCycleStatus.REGISTERED_WAITING, null),
            now,
        )

        val recovery = RaidAutomationHandler().evaluate(snapshot)
        val abort = assertIs<RaidCycleAbortAutomationAction>(assertIs<HandlerEvaluation.Runnable>(recovery).action)
        assertEquals(RaidCycleAbortReason.REGISTRATION_LOST, abort.reason)

        val registration = RaidAutomationHandler().evaluate(snapshot.copy(openCycle = null, registrationCooldownChecked = true))
        val action = assertIs<RaidTownAutomationAction>(assertIs<HandlerEvaluation.Runnable>(registration).action)
        assertEquals(RaidAction.REGISTER, action.action)
        assertEquals("RaidGoblin", action.raidId)
    }

    @Test
    fun `raid abandons a stale registered cycle when departure is possible but the current user is not an applicant`() {
        val target = RaidAutomationTarget("RaidGoblin", "고블린 전투 마차", PresetSelectionMode.EXPLICIT, 3, 0, party)
        val registerable = RaidPubRaidResponse(
            "RaidGoblin", "고블린 전투 마차", true, null, 6, "100000+",
            RaidStatus.READY, "파티 모집 중 (출발 가능)", null,
            listOf("다른 사용자"), false, setOf(RaidAction.REGISTER, RaidAction.START), null,
        )

        val evaluation = RaidAutomationHandler().evaluate(RaidAutomationSnapshot(
            1,
            RaidPubResponse(listOf(registerable), false, false, null, null, emptySet(), null),
            listOf(target),
            null,
            OpenRaidCycleSnapshot(9, "RaidGoblin", RaidAutomationCycleStatus.REGISTERED_WAITING, null),
            now,
        ))

        val abort = assertIs<RaidCycleAbortAutomationAction>(assertIs<HandlerEvaluation.Runnable>(evaluation).action)
        assertEquals(RaidCycleAbortReason.REGISTRATION_LOST, abort.reason)
    }

    @Test
    fun `raid treats missing current user applicant membership as a lost registration`() {
        val target = RaidAutomationTarget("RaidGoblin", "고블린 전투 마차", PresetSelectionMode.EXPLICIT, 3, 0, party)
        val uncertain = RaidPubRaidResponse(
            "RaidGoblin", "고블린 전투 마차", true, null, 6, "100000+",
            RaidStatus.RECRUITING, "파티 모집 중", null,
            emptyList(), false, setOf(RaidAction.REGISTER), null,
        )

        val evaluation = RaidAutomationHandler().evaluate(RaidAutomationSnapshot(
            1,
            RaidPubResponse(listOf(uncertain), false, false, null, null, emptySet(), null),
            listOf(target),
            null,
            OpenRaidCycleSnapshot(9, "RaidGoblin", RaidAutomationCycleStatus.REGISTERED_WAITING, null),
            now,
        ))

        val abort = assertIs<RaidCycleAbortAutomationAction>(assertIs<HandlerEvaluation.Runnable>(evaluation).action)
        assertEquals(RaidCycleAbortReason.REGISTRATION_LOST, abort.reason)
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
