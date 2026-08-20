package app.spammy.hof.automation.raid

import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.automation.entity.RaidAutomationCycleStatus
import app.spammy.hof.automation.service.ResolvedAutomationParty
import app.spammy.hof.battle.dto.BattlePatternLoadRequest
import app.spammy.hof.common.time.TimeProvider
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RaidCycleModuleTest {
    private val now = Instant.parse("2026-08-20T01:00:00Z")

    @Test
    fun `새 사이클은 현재 순환 대상만 준비하고 그 대상에 필요한 RESET 한 행동만 반환한다`() {
        val current = target("raid-b", 1)
        val other = target("raid-a", 0)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(other, current), current.raidId),
                null,
            ),
        )
        val observation = RaidObservation(
            raids = listOf(
                observed(other, resetRequired = true),
                observed(current, resetRequired = true),
            ),
            applied = false,
            registrationWait = false,
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { observation }, TimeProvider { now })

        val directive = assertIs<RaidDirective.Execute>(module.decideNext(1))
        val intent = assertIs<RaidIntent.Town>(directive.intent)

        assertEquals(RaidIntentKind.RESET, intent.kind)
        assertEquals("raid-b", intent.raidId)
        assertEquals(RaidAutomationCycleStatus.PREPARING, store.state.openCycle?.status)
        assertEquals("raid-b", store.state.openCycle?.raidId)
    }

    @Test
    fun `준비 대상이 등록 가능하면 RESET 없이 REGISTER 한 행동을 반환한다`() {
        val target = target("raid-a", 0)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                null,
            ),
        )
        val observation = RaidObservation(
            raids = listOf(
                RaidObservedTarget(
                    id = target.raidId,
                    name = target.name,
                    playable = true,
                    status = RaidObservedStatus.RECRUITING,
                    statusText = "파티 모집 중 (신청 안됨)",
                    joined = false,
                    actions = setOf(RaidIntentKind.REGISTER),
                ),
            ),
            applied = false,
            registrationWait = false,
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { observation }, TimeProvider { now })

        val intent = assertIs<RaidIntent.Town>(assertIs<RaidDirective.Execute>(module.decideNext(1)).intent)

        assertEquals(RaidIntentKind.REGISTER, intent.kind)
        assertEquals("raid-a", intent.requestRaidId)
        assertEquals(RaidAutomationCycleStatus.PREPARING, store.state.openCycle?.status)
    }

    @Test
    fun `RESET 결과가 등록 가능 상태를 증명해도 사이클은 PREPARING으로 유지한다`() {
        val target = target("raid-a", 0)
        val cycle = RaidCycleSnapshot(1, 7, target.raidId, target.name, RaidAutomationCycleStatus.PREPARING, null)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                cycle,
            ),
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { error("GET should not be used") }, TimeProvider { now })
        val afterReset = RaidObservation(
            raids = listOf(
                RaidObservedTarget(
                    id = target.raidId,
                    name = target.name,
                    playable = true,
                    status = RaidObservedStatus.RECRUITING,
                    statusText = "파티 모집 중 (신청 안됨)",
                    joined = false,
                    actions = setOf(RaidIntentKind.REGISTER),
                ),
            ),
            applied = false,
            registrationWait = false,
        )

        val result = assertIs<RaidRecordResult.Recorded>(module.recordObservedResult(
            1,
            RaidAttempt(7, RaidIntentKind.RESET, target.raidId),
            RaidResultObservation.Page(afterReset),
        ))

        assertEquals(null, result.completion)
        assertEquals(RaidAutomationCycleStatus.PREPARING, store.state.openCycle?.status)
    }

    @Test
    fun `REGISTER 응답에서 대상 참가가 관측되어야 등록 대기로 전이한다`() {
        val target = target("raid-a", 0)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                RaidCycleSnapshot(1, 7, target.raidId, target.name, RaidAutomationCycleStatus.PREPARING, null),
            ),
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { error("GET should not be used") }, TimeProvider { now })
        val registered = RaidObservation(
            raids = listOf(
                RaidObservedTarget(
                    id = target.raidId,
                    name = target.name,
                    playable = true,
                    status = RaidObservedStatus.WAITING,
                    statusText = "파티 모집 중 (120초 후 출발 가능)",
                    waitSeconds = 120,
                    joined = true,
                    actions = emptySet(),
                ),
            ),
            applied = true,
            registrationWait = false,
        )

        val result = module.recordObservedResult(
            1,
            RaidAttempt(7, RaidIntentKind.REGISTER, target.raidId),
            RaidResultObservation.Page(registered),
        )

        assertIs<RaidRecordResult.Recorded>(result)
        assertEquals(RaidAutomationCycleStatus.REGISTERED_WAITING, store.state.openCycle?.status)
        assertEquals(now.plusSeconds(120), store.state.openCycle?.nextCheckAt)
    }

    @Test
    fun `REGISTER 응답이 이미 전투 상태면 추가 GET 없이 IN_BATTLE로 fast forward한다`() {
        val target = target("raid-a", 0)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                RaidCycleSnapshot(1, 7, target.raidId, target.name, RaidAutomationCycleStatus.PREPARING, null),
            ),
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { error("GET should not be used") }, TimeProvider { now })
        val registeredAndStarted = RaidObservation(
            raids = listOf(
                RaidObservedTarget(
                    id = target.raidId,
                    name = target.name,
                    playable = true,
                    status = RaidObservedStatus.IN_BATTLE,
                    statusText = "전투 중",
                    joined = true,
                    actions = emptySet(),
                    battle = RaidObservedBattle("raid", "raid001"),
                ),
            ),
            applied = true,
            registrationWait = false,
        )

        val result = module.recordObservedResult(
            1,
            RaidAttempt(7, RaidIntentKind.REGISTER, target.raidId),
            RaidResultObservation.Page(registeredAndStarted),
        )

        assertIs<RaidRecordResult.Recorded>(result)
        assertEquals(RaidAutomationCycleStatus.IN_BATTLE, store.state.openCycle?.status)
        assertEquals(null, store.state.openCycle?.nextCheckAt)
    }

    @Test
    fun `등록된 레이드는 START 버튼이 보여도 서버가 준 출발 시각까지 기다린다`() {
        val target = target("raid-a", 0)
        val cycle = RaidCycleSnapshot(1, 7, target.raidId, target.name, RaidAutomationCycleStatus.REGISTERED_WAITING, null)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(RaidCycleConfiguration(7, true, listOf(target), target.raidId), cycle),
        )
        val observation = RaidObservation(
            raids = listOf(
                RaidObservedTarget(
                    id = target.raidId,
                    name = target.name,
                    playable = true,
                    status = RaidObservedStatus.READY,
                    statusText = "파티 모집 중 (90초 후 출발 가능)",
                    waitSeconds = 90,
                    joined = true,
                    actions = setOf(RaidIntentKind.START),
                ),
            ),
            applied = true,
            registrationWait = false,
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { observation }, TimeProvider { now })

        val wait = assertIs<RaidDirective.WaitUntil>(module.decideNext(1))

        assertEquals(RaidWaitReason.WAITING_TO_START, wait.reason)
        assertEquals(now.plusSeconds(90), wait.at)
    }

    @Test
    fun `START 응답에서 전투 단계가 관측되어야 IN_BATTLE로 전이한다`() {
        val target = target("raid-a", 0)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                RaidCycleSnapshot(1, 7, target.raidId, target.name, RaidAutomationCycleStatus.REGISTERED_WAITING, null),
            ),
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { error("GET should not be used") }, TimeProvider { now })
        val started = RaidObservation(
            raids = listOf(
                RaidObservedTarget(
                    id = target.raidId,
                    name = target.name,
                    playable = true,
                    status = RaidObservedStatus.IN_BATTLE,
                    statusText = "전투 중",
                    joined = true,
                    actions = emptySet(),
                    battle = RaidObservedBattle("raid", "raid001"),
                ),
            ),
            applied = true,
            registrationWait = false,
        )

        val result = module.recordObservedResult(
            1,
            RaidAttempt(7, RaidIntentKind.START, target.raidId),
            RaidResultObservation.Page(started),
        )

        assertIs<RaidRecordResult.Recorded>(result)
        assertEquals(RaidAutomationCycleStatus.IN_BATTLE, store.state.openCycle?.status)
    }

    @Test
    fun `전투 단계는 최신 유효 프리셋으로 BATTLE 한 행동을 반환한다`() {
        val party = ResolvedAutomationParty(listOf("character-1"), listOf(BattlePatternLoadRequest("character-1", 1)))
        val target = target("raid-a", 0, party)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                RaidCycleSnapshot(1, 7, target.raidId, target.name, RaidAutomationCycleStatus.IN_BATTLE, null),
            ),
        )
        val observation = RaidObservation(
            raids = listOf(
                RaidObservedTarget(
                    id = target.raidId,
                    name = target.name,
                    playable = true,
                    status = RaidObservedStatus.IN_BATTLE,
                    statusText = "전투 중",
                    joined = true,
                    actions = emptySet(),
                    battle = RaidObservedBattle("raid", "raid001"),
                ),
            ),
            applied = true,
            registrationWait = false,
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { observation }, TimeProvider { now })

        val battle = assertIs<RaidIntent.Battle>(assertIs<RaidDirective.Execute>(module.decideNext(1)).intent)

        assertEquals("raid001", battle.mapCode)
        assertEquals(3, battle.presetId)
        assertEquals(party, battle.party)
    }

    @Test
    fun `전투 뒤 완료 상태를 다시 관측하면 REWARD_PENDING에서 보상 한 행동을 반환한다`() {
        val target = target("raid-a", 0)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                RaidCycleSnapshot(1, 7, target.raidId, target.name, RaidAutomationCycleStatus.IN_BATTLE, null),
            ),
        )
        val observation = RaidObservation(
            raids = listOf(
                RaidObservedTarget(
                    id = target.raidId,
                    name = target.name,
                    playable = true,
                    status = RaidObservedStatus.COMPLETED,
                    statusText = "보상 확인 시간",
                    joined = true,
                    actions = emptySet(),
                ),
            ),
            applied = true,
            registrationWait = false,
            globalActions = setOf(RaidIntentKind.REWARD),
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { observation }, TimeProvider { now })

        val reward = assertIs<RaidIntent.Town>(assertIs<RaidDirective.Execute>(module.decideNext(1)).intent)

        assertEquals(RaidIntentKind.REWARD, reward.kind)
        assertEquals(null, reward.requestRaidId)
        assertEquals(RaidAutomationCycleStatus.REWARD_PENDING, store.state.openCycle?.status)
    }

    @Test
    fun `REWARD POST 응답이 보상 소멸과 재등록 대기를 함께 보여주면 추가 GET 없이 완료한다`() {
        val target = target("raid-a", 0)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                RaidCycleSnapshot(1, 7, target.raidId, target.name, RaidAutomationCycleStatus.REWARD_PENDING, null),
            ),
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { error("GET should not be used") }, TimeProvider { now })
        val afterReward = RaidObservation(
            raids = listOf(
                RaidObservedTarget(
                    id = target.raidId,
                    name = target.name,
                    playable = true,
                    status = RaidObservedStatus.COMPLETED,
                    statusText = "보상 확인 시간",
                    joined = true,
                    actions = emptySet(),
                ),
            ),
            applied = true,
            registrationWait = true,
            registrationWaitSeconds = 10_000,
            globalActions = emptySet(),
        )

        val recorded = assertIs<RaidRecordResult.Recorded>(module.recordObservedResult(
            1,
            RaidAttempt(7, RaidIntentKind.REWARD, target.raidId, requestRaidId = null),
            RaidResultObservation.Page(afterReward),
        ))

        assertEquals(RaidCycleOutcomeKind.COMPLETED, recorded.completion?.kind)
        assertEquals(null, store.state.openCycle)
        assertEquals(true, store.lastAdvanceRotation)
    }

    @Test
    fun `보상 응답에 정보가 부족하면 다음 최신 GET에서 재등록 대기를 확인하고 완료한다`() {
        val target = target("raid-a", 0)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                RaidCycleSnapshot(1, 7, target.raidId, target.name, RaidAutomationCycleStatus.POST_REWARD_CHECK, null),
            ),
        )
        val observation = RaidObservation(
            raids = listOf(
                RaidObservedTarget(
                    id = target.raidId,
                    name = target.name,
                    playable = true,
                    status = RaidObservedStatus.COMPLETED,
                    statusText = "보상 확인 시간",
                    joined = true,
                    actions = emptySet(),
                ),
            ),
            applied = true,
            registrationWait = true,
            registrationWaitSeconds = 9_000,
            globalActions = emptySet(),
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { observation }, TimeProvider { now })

        val complete = assertIs<RaidDirective.Complete>(module.decideNext(1))

        assertEquals(RaidCycleOutcomeKind.COMPLETED, complete.outcome.kind)
        assertEquals(null, store.state.openCycle)
        assertEquals(true, store.lastAdvanceRotation)
    }

    @Test
    fun `설정 밖 수동 레이드가 진행 중이면 레이드만 30초 보류하고 새 사이클을 열지 않는다`() {
        val target = target("raid-auto", 0)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                null,
            ),
        )
        val observation = RaidObservation(
            raids = listOf(
                RaidObservedTarget(
                    id = "raid-manual",
                    name = "수동 레이드",
                    playable = true,
                    status = RaidObservedStatus.IN_BATTLE,
                    statusText = "전투 중",
                    joined = true,
                    actions = emptySet(),
                    battle = RaidObservedBattle("raid", "manual001"),
                ),
                RaidObservedTarget(
                    id = target.raidId,
                    name = target.name,
                    playable = true,
                    status = RaidObservedStatus.RECRUITING,
                    joined = false,
                    actions = setOf(RaidIntentKind.REGISTER),
                ),
            ),
            applied = true,
            registrationWait = false,
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { observation }, TimeProvider { now })

        val hold = assertIs<RaidDirective.Hold>(module.decideNext(1))

        assertEquals(RaidHoldReason.MANUAL_RAID_ACTIVE, hold.reason)
        assertEquals(now.plusSeconds(30), hold.recheckAt)
        assertEquals(null, store.state.openCycle)
    }

    @Test
    fun `설정된 레이드를 사용자가 먼저 시작했으면 현재 전투 단계에서 편입한다`() {
        val party = ResolvedAutomationParty(listOf("character-1"), listOf(BattlePatternLoadRequest("character-1", 1)))
        val target = target("raid-auto", 0, party)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                null,
            ),
        )
        val observation = RaidObservation(
            raids = listOf(
                RaidObservedTarget(
                    id = target.raidId,
                    name = target.name,
                    playable = true,
                    status = RaidObservedStatus.IN_BATTLE,
                    statusText = "전투 중",
                    joined = true,
                    actions = emptySet(),
                    battle = RaidObservedBattle("raid", "raid001"),
                ),
            ),
            applied = true,
            registrationWait = false,
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { observation }, TimeProvider { now })

        val battle = assertIs<RaidIntent.Battle>(assertIs<RaidDirective.Execute>(module.decideNext(1)).intent)

        assertEquals("raid-auto", battle.raidId)
        assertEquals(RaidAutomationCycleStatus.IN_BATTLE, store.state.openCycle?.status)
    }

    @Test
    fun `등록 대기 사이클에서 참가가 사라지고 다시 등록 가능하면 등록 유실로 닫는다`() {
        val target = target("raid-auto", 0)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                RaidCycleSnapshot(1, 7, target.raidId, target.name, RaidAutomationCycleStatus.REGISTERED_WAITING, null),
            ),
        )
        val observation = RaidObservation(
            raids = listOf(
                RaidObservedTarget(
                    id = target.raidId,
                    name = target.name,
                    playable = true,
                    status = RaidObservedStatus.RECRUITING,
                    statusText = "파티 모집 중 (신청 안됨)",
                    joined = false,
                    actions = setOf(RaidIntentKind.REGISTER),
                ),
            ),
            applied = false,
            registrationWait = false,
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { observation }, TimeProvider { now })

        val complete = assertIs<RaidDirective.Complete>(module.decideNext(1))

        assertEquals(RaidCycleOutcomeKind.ABORTED_REGISTRATION_LOST, complete.outcome.kind)
        assertEquals(null, store.state.openCycle)
    }

    @Test
    fun `열린 사이클의 대상이 CLOSED면 로컬 행동을 만들지 않고 즉시 닫는다`() {
        val target = target("raid-auto", 0)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                RaidCycleSnapshot(1, 7, target.raidId, target.name, RaidAutomationCycleStatus.IN_BATTLE, null),
            ),
        )
        val observation = RaidObservation(
            raids = listOf(
                RaidObservedTarget(
                    id = target.raidId,
                    name = target.name,
                    playable = true,
                    status = RaidObservedStatus.CLOSED,
                    joined = false,
                    actions = emptySet(),
                ),
            ),
            applied = false,
            registrationWait = false,
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { observation }, TimeProvider { now })

        val complete = assertIs<RaidDirective.Complete>(module.decideNext(1))

        assertEquals(RaidCycleOutcomeKind.ABORTED_CLOSED, complete.outcome.kind)
    }

    @Test
    fun `진행 중 대상을 설정에서 해제하면 자동 사이클을 수동으로 인계한다`() {
        val cycle = RaidCycleSnapshot(1, 7, "raid-auto", "레이드", RaidAutomationCycleStatus.IN_BATTLE, null)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, emptyList(), null),
                cycle,
            ),
        )
        val module = DefaultRaidCycleModule(
            store,
            RaidObservationReader { error("hand-off should not require a HOF read") },
            TimeProvider { now },
        )

        val complete = assertIs<RaidDirective.Complete>(module.decideNext(1))

        assertEquals(RaidCycleOutcomeKind.HANDED_OFF_MANUAL, complete.outcome.kind)
        assertEquals(null, store.state.openCycle)
    }

    @Test
    fun `설정 저장에서 전달된 수동 인계도 module seam 안에서 사이클을 닫는다`() {
        val target = target("raid-auto", 0)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                RaidCycleSnapshot(1, 7, target.raidId, target.name, RaidAutomationCycleStatus.IN_BATTLE, null),
            ),
        )
        val module: RaidCycleModule = DefaultRaidCycleModule(
            store,
            RaidObservationReader { error("manual hand-off should not require a HOF read") },
            TimeProvider { now },
        )

        val result = assertIs<RaidRecordResult.Recorded>(module.recordObservedResult(
            1,
            RaidAttempt(7, RaidIntentKind.REFRESH, target.raidId, null),
            RaidResultObservation.ManualHandoff,
        ))

        assertEquals(RaidCycleOutcomeKind.HANDED_OFF_MANUAL, result.completion?.kind)
        assertEquals(null, store.state.openCycle)
    }

    @Test
    fun `다른 설정 대상이 실제 진행 중이면 이전 사이클을 교체하고 그 상태로 편입한다`() {
        val party = ResolvedAutomationParty(listOf("character-1"), listOf(BattlePatternLoadRequest("character-1", 1)))
        val first = target("raid-a", 0, party)
        val observedActive = target("raid-b", 1, party)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(first, observedActive), first.raidId),
                RaidCycleSnapshot(1, 7, first.raidId, first.name, RaidAutomationCycleStatus.IN_BATTLE, null),
            ),
        )
        val observation = RaidObservation(
            raids = listOf(
                RaidObservedTarget(
                    id = first.raidId,
                    name = first.name,
                    playable = true,
                    status = RaidObservedStatus.RECRUITING,
                    joined = false,
                    actions = setOf(RaidIntentKind.REGISTER),
                ),
                RaidObservedTarget(
                    id = observedActive.raidId,
                    name = observedActive.name,
                    playable = true,
                    status = RaidObservedStatus.IN_BATTLE,
                    statusText = "전투 중",
                    joined = true,
                    actions = emptySet(),
                    battle = RaidObservedBattle("raid", "raid002"),
                ),
            ),
            applied = true,
            registrationWait = false,
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { observation }, TimeProvider { now })

        val battle = assertIs<RaidIntent.Battle>(assertIs<RaidDirective.Execute>(module.decideNext(1)).intent)

        assertEquals("raid-b", battle.raidId)
        assertEquals(listOf(RaidCycleOutcomeKind.SUPERSEDED_BY_OBSERVED_RAID), store.finishedOutcomes)
        assertEquals("raid-b", store.state.openCycle?.raidId)
        assertEquals(RaidAutomationCycleStatus.IN_BATTLE, store.state.openCycle?.status)
    }

    @Test
    fun `저장 단계보다 수동 진행이 앞서면 최신 전투 단계로 fast forward한다`() {
        val party = ResolvedAutomationParty(listOf("character-1"), listOf(BattlePatternLoadRequest("character-1", 1)))
        val target = target("raid-auto", 0, party)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                RaidCycleSnapshot(1, 7, target.raidId, target.name, RaidAutomationCycleStatus.REGISTERED_WAITING, null),
            ),
        )
        val observation = RaidObservation(
            raids = listOf(
                RaidObservedTarget(
                    id = target.raidId,
                    name = target.name,
                    playable = true,
                    status = RaidObservedStatus.IN_BATTLE,
                    statusText = "전투 중",
                    joined = true,
                    actions = emptySet(),
                    battle = RaidObservedBattle("raid", "raid001"),
                ),
            ),
            applied = false,
            registrationWait = false,
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { observation }, TimeProvider { now })

        val battle = assertIs<RaidIntent.Battle>(assertIs<RaidDirective.Execute>(module.decideNext(1)).intent)

        assertEquals("raid001", battle.mapCode)
        assertEquals(RaidAutomationCycleStatus.IN_BATTLE, store.state.openCycle?.status)
    }

    @Test
    fun `진행 중 사이클이 수동으로 보상 확인 종료까지 진행됐으면 RESET 없이 완료한다`() {
        val target = target("raid-auto", 0)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                RaidCycleSnapshot(1, 7, target.raidId, target.name, RaidAutomationCycleStatus.IN_BATTLE, null),
            ),
        )
        val observation = RaidObservation(
            raids = listOf(observed(target, resetRequired = true)),
            applied = false,
            registrationWait = false,
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { observation }, TimeProvider { now })

        val completed = assertIs<RaidDirective.Complete>(module.decideNext(1))

        assertEquals(RaidCycleOutcomeKind.COMPLETED, completed.outcome.kind)
        assertEquals(true, store.lastAdvanceRotation)
    }

    @Test
    fun `REWARD_PENDING 복구 단계는 보상이 가능하면 보상 행동을 다시 판단한다`() {
        val target = target("raid-auto", 0)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                RaidCycleSnapshot(1, 7, target.raidId, target.name, RaidAutomationCycleStatus.REWARD_PENDING, null),
            ),
        )
        val observation = RaidObservation(
            raids = listOf(
                RaidObservedTarget(
                    id = target.raidId,
                    name = target.name,
                    playable = true,
                    status = RaidObservedStatus.COMPLETED,
                    statusText = "보상 확인 시간",
                    joined = true,
                    actions = emptySet(),
                ),
            ),
            applied = false,
            registrationWait = false,
            globalActions = setOf(RaidIntentKind.REWARD),
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { observation }, TimeProvider { now })

        val reward = assertIs<RaidIntent.Town>(assertIs<RaidDirective.Execute>(module.decideNext(1)).intent)

        assertEquals(RaidIntentKind.REWARD, reward.kind)
    }

    @Test
    fun `보상 후 GET에 쿨타임 정보가 없으면 REFRESH 한 행동을 반환한다`() {
        val target = target("raid-auto", 0)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                RaidCycleSnapshot(1, 7, target.raidId, target.name, RaidAutomationCycleStatus.POST_REWARD_CHECK, null),
            ),
        )
        val observation = RaidObservation(
            raids = listOf(
                RaidObservedTarget(
                    id = target.raidId,
                    name = target.name,
                    playable = true,
                    status = RaidObservedStatus.COMPLETED,
                    statusText = "보상 확인 시간",
                    joined = true,
                    actions = emptySet(),
                ),
            ),
            applied = false,
            registrationWait = false,
            globalActions = setOf(RaidIntentKind.REFRESH),
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { observation }, TimeProvider { now })

        val refresh = assertIs<RaidIntent.Town>(assertIs<RaidDirective.Execute>(module.decideNext(1)).intent)

        assertEquals(RaidIntentKind.REFRESH, refresh.kind)
        assertEquals(null, refresh.requestRaidId)
    }

    @Test
    fun `REFRESH POST가 최신 상태를 증명하면 같은 응답으로 사이클을 완료한다`() {
        val target = target("raid-auto", 0)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                RaidCycleSnapshot(1, 7, target.raidId, target.name, RaidAutomationCycleStatus.POST_REWARD_CHECK, null),
            ),
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { error("GET should not be used") }, TimeProvider { now })
        val refreshed = RaidObservation(
            raids = listOf(
                RaidObservedTarget(
                    id = target.raidId,
                    name = target.name,
                    playable = true,
                    status = RaidObservedStatus.COMPLETED,
                    statusText = "보상 확인 시간",
                    joined = true,
                    actions = emptySet(),
                ),
            ),
            applied = true,
            registrationWait = false,
            globalActions = emptySet(),
        )

        val recorded = assertIs<RaidRecordResult.Recorded>(module.recordObservedResult(
            1,
            RaidAttempt(7, RaidIntentKind.REFRESH, target.raidId, null),
            RaidResultObservation.Page(refreshed),
        ))

        assertEquals(RaidCycleOutcomeKind.COMPLETED, recorded.completion?.kind)
        assertEquals(true, store.lastAdvanceRotation)
    }

    private fun target(id: String, order: Int, party: ResolvedAutomationParty? = null) = RaidCycleTarget(
        raidId = id,
        name = id,
        presetMode = PresetSelectionMode.EXPLICIT,
        presetId = 3,
        executionOrder = order,
        party = party,
    )

    private fun observed(target: RaidCycleTarget, resetRequired: Boolean) = RaidObservedTarget(
        id = target.raidId,
        name = target.name,
        playable = true,
        status = RaidObservedStatus.COMPLETED,
        statusText = if (resetRequired) "보상 확인 종료(리셋 가능)" else null,
        joined = false,
        actions = if (resetRequired) setOf(RaidIntentKind.RESET) else emptySet(),
    )

    private class InMemoryRaidCycleStore(initial: RaidCycleAccountState) : RaidCycleStore {
        var state: RaidCycleAccountState = initial
        var lastAdvanceRotation: Boolean? = null
        val finishedOutcomes = mutableListOf<RaidCycleOutcomeKind>()

        override fun load(accountId: Long): RaidCycleAccountState = state

        override fun open(
            accountId: Long,
            entryId: Long,
            target: RaidCycleTarget,
            now: Instant,
            status: RaidAutomationCycleStatus,
            observedStatus: String?,
            nextCheckAt: Instant?,
        ): RaidCycleSnapshot {
            val cycle = RaidCycleSnapshot(1, entryId, target.raidId, target.name, status, nextCheckAt)
            state = state.copy(openCycle = cycle)
            return cycle
        }

        override fun transition(
            accountId: Long,
            raidId: String,
            status: RaidAutomationCycleStatus,
            observedStatus: String?,
            nextCheckAt: Instant?,
            now: Instant,
        ): RaidCycleSnapshot {
            val cycle = requireNotNull(state.openCycle).copy(status = status, nextCheckAt = nextCheckAt)
            state = state.copy(openCycle = cycle)
            return cycle
        }

        override fun finish(
            accountId: Long,
            raidId: String,
            outcome: RaidCycleOutcomeKind,
            now: Instant,
            advanceRotation: Boolean,
        ): RaidCycleOutcome {
            val cycle = requireNotNull(state.openCycle)
            state = state.copy(openCycle = null)
            lastAdvanceRotation = advanceRotation
            finishedOutcomes += outcome
            return RaidCycleOutcome(cycle.entryId, cycle.raidId, outcome)
        }
    }
}
