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
    fun `비활성화된 레이드는 복구를 보존하고 GET과 POST를 모두 중단한다`() {
        val target = target("raid-a", 0)
        val recovery = recovery(target.raidId, nextCheckAt = now)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, false, listOf(target), target.raidId),
                RaidCycleSnapshot(1, 7, target.raidId, target.name, RaidAutomationCycleStatus.IN_BATTLE, null, recovery),
            ),
        )
        val module = DefaultRaidCycleModule(
            store,
            RaidObservationReader { error("disabled raid recovery must not GET") },
            TimeProvider { now },
        )

        assertIs<RaidDirective.Hold>(module.decideNext(1))
        assertEquals(recovery.chainId, store.state.openCycle?.battleRecovery?.chainId)
    }

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
    fun `권위 조회에서 REGISTER가 적용되지 않았음이 명확하면 안전한 재제출을 허용한다`() {
        val target = target("raid-a", 0)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                RaidCycleSnapshot(1, 7, target.raidId, target.name, RaidAutomationCycleStatus.PREPARING, null),
            ),
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { error("GET should not be used") }, TimeProvider { now })
        val notRegistered = RaidObservation(
            raids = listOf(
                RaidObservedTarget(
                    id = target.raidId,
                    name = target.name,
                    playable = true,
                    status = RaidObservedStatus.RECRUITING,
                    joined = false,
                    actions = setOf(RaidIntentKind.REGISTER),
                ),
            ),
            applied = false,
            registrationWait = false,
        )

        val result = module.recordObservedResult(
            1,
            RaidAttempt(7, RaidIntentKind.REGISTER, target.raidId),
            RaidResultObservation.Page(notRegistered),
        )

        assertIs<RaidRecordResult.NotApplied>(result)
        assertEquals(RaidAutomationCycleStatus.PREPARING, store.state.openCycle?.status)
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
    fun `전투 프리셋 구성이 사라지면 사이클을 유지하고 설정 복구를 기다린다`() {
        val target = target("raid-a", 0, party = null)
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
                    joined = true,
                    actions = emptySet(),
                    battle = RaidObservedBattle("raid", "raid001"),
                ),
            ),
            applied = false,
            registrationWait = false,
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { observation }, TimeProvider { now })

        val hold = assertIs<RaidDirective.Hold>(module.decideNext(1))

        assertEquals(RaidHoldReason.INVALID_PRESET, hold.reason)
        assertEquals(null, hold.recheckAt)
        assertEquals(RaidAutomationCycleStatus.IN_BATTLE, store.state.openCycle?.status)
    }

    @Test
    fun `전투 뒤 완료 상태를 다시 관측하면 REWARD_PENDING에서 보상 한 행동을 반환한다`() {
        val target = target("raid-a", 0)
        val activeRecovery = recovery(target.raidId, nextCheckAt = now)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                RaidCycleSnapshot(
                    1,
                    7,
                    target.raidId,
                    target.name,
                    RaidAutomationCycleStatus.IN_BATTLE,
                    null,
                    activeRecovery,
                ),
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

        val directive = assertIs<RaidDirective.Execute>(module.decideNext(1))
        val reward = assertIs<RaidIntent.Town>(directive.intent)

        assertEquals(RaidIntentKind.REWARD, reward.kind)
        assertEquals("RAID_BATTLE_APPLIED_COMPLETED", directive.reasonCode)
        assertEquals(null, reward.requestRaidId)
        assertEquals(RaidAutomationCycleStatus.REWARD_PENDING, store.state.openCycle?.status)
        assertEquals(null, store.state.openCycle?.battleRecovery)
    }

    @Test
    fun `완료 상태가 복구를 끝냈지만 보상 동작이 아직 없으면 적용 확인 사유로 기다린다`() {
        val target = target("raid-a", 0)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                RaidCycleSnapshot(
                    1,
                    7,
                    target.raidId,
                    target.name,
                    RaidAutomationCycleStatus.IN_BATTLE,
                    null,
                    recovery(target.raidId, nextCheckAt = now),
                ),
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
            globalActions = emptySet(),
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { observation }, TimeProvider { now })

        val hold = assertIs<RaidDirective.Hold>(module.decideNext(1))

        assertEquals("RAID_BATTLE_APPLIED_COMPLETED", hold.reasonCode)
        assertEquals(true, hold.message.contains("적용을 확인"))
        assertEquals(null, store.state.openCycle?.battleRecovery)
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
    fun `다른 설정 대상이 실제 진행 중이면 이전 사이클 교체를 먼저 보고한 뒤 새 상태로 편입한다`() {
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

        val superseded = assertIs<RaidDirective.Complete>(module.decideNext(1))
        val battle = assertIs<RaidIntent.Battle>(assertIs<RaidDirective.Execute>(module.decideNext(1)).intent)

        assertEquals(RaidCycleOutcomeKind.SUPERSEDED_BY_OBSERVED_RAID, superseded.outcome.kind)
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
    fun `REWARD_PENDING 대상이 새 모집 단계로 바뀌면 다른 레이드의 전역 보상 버튼을 실행하지 않는다`() {
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
                    status = RaidObservedStatus.RECRUITING,
                    statusText = "파티 모집 중 (1799초 후 출발 가능)",
                    waitSeconds = 1799,
                    joined = true,
                    actions = emptySet(),
                ),
            ),
            applied = false,
            registrationWait = false,
            globalActions = setOf(RaidIntentKind.REWARD),
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { observation }, TimeProvider { now })

        val complete = assertIs<RaidDirective.Complete>(module.decideNext(1))

        assertEquals(RaidCycleOutcomeKind.COMPLETED, complete.outcome.kind)
        assertEquals(null, store.state.openCycle)
        assertEquals(true, store.lastAdvanceRotation)
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
    fun `REFRESH POST의 성공 표시만 있고 재등록 대기가 없으면 사이클을 닫지 않는다`() {
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

        assertIs<RaidRecordResult.NeedsRecheck>(module.recordObservedResult(
            1,
            RaidAttempt(7, RaidIntentKind.REFRESH, target.raidId, null),
            RaidResultObservation.Page(refreshed),
        ))

        assertEquals(RaidAutomationCycleStatus.POST_REWARD_CHECK, store.state.openCycle?.status)
        assertEquals(null, store.lastAdvanceRotation)
    }

    @Test
    fun `REFRESH POST가 재등록 대기를 증명하면 같은 응답으로 사이클을 완료한다`() {
        val target = target("raid-auto", 0)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                RaidCycleSnapshot(1, 7, target.raidId, target.name, RaidAutomationCycleStatus.POST_REWARD_CHECK, null),
            ),
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { error("GET should not be used") }, TimeProvider { now })
        val refreshed = RaidObservation(
            raids = listOf(observed(target, resetRequired = false)),
            applied = true,
            registrationWait = true,
            registrationWaitSeconds = 10_000,
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

    @Test
    fun `불명확한 레이드 전투는 IN_BATTLE을 유지하며 5분 복구로 기록한다`() {
        val target = target("raid-a", 0)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                RaidCycleSnapshot(1, 7, target.raidId, target.name, RaidAutomationCycleStatus.IN_BATTLE, null),
            ),
        )
        val module = DefaultRaidCycleModule(
            store,
            RaidObservationReader { error("ambiguous handoff must not GET") },
            TimeProvider { now },
        )

        val result = assertIs<RaidRecordResult.BattleRecoveryStarted>(module.recordObservedResult(
            1,
            ambiguousBattleAttempt(target.raidId, "execution-1", submittedAt = now),
            RaidResultObservation.BattleAmbiguous("전투 단말 결과를 관측하지 못했습니다."),
        ))

        assertEquals(now.plusSeconds(300), result.at)
        assertEquals(RaidAutomationCycleStatus.IN_BATTLE, store.state.openCycle?.status)
        val recovery = requireNotNull(store.state.openCycle?.battleRecovery)
        assertEquals("execution-1", recovery.originalExecutionIdentity)
        assertEquals(0, recovery.retransmissionCount)
        assertEquals(now.plusSeconds(300), recovery.nextCheckAt)
        assertEquals(RaidBattleRecoveryObservation.RESULT_UNOBSERVED, recovery.lastObservation)
    }

    @Test
    fun `복구 확인 전에는 GET하지 않고 확인 시각까지 레이드만 기다린다`() {
        val target = target("raid-a", 0)
        val recovery = recovery(target.raidId, nextCheckAt = now.plusSeconds(300))
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                RaidCycleSnapshot(
                    1,
                    7,
                    target.raidId,
                    target.name,
                    RaidAutomationCycleStatus.IN_BATTLE,
                    null,
                    recovery,
                ),
            ),
        )
        val module = DefaultRaidCycleModule(
            store,
            RaidObservationReader { error("GET must wait for the recovery deadline") },
            TimeProvider { now },
        )

        val wait = assertIs<RaidDirective.WaitUntil>(module.decideNext(1))

        assertEquals(RaidWaitReason.BATTLE_RECOVERY_RECHECK, wait.reason)
        assertEquals(now.plusSeconds(300), wait.at)
    }

    @Test
    fun `복구 시 과거 캐시 관측은 실행 허가가 아니며 5분 뒤 최신 GET만 예약한다`() {
        val target = target("raid-a", 0)
        val recovery = recovery(target.raidId, nextCheckAt = now)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                RaidCycleSnapshot(1, 7, target.raidId, target.name, RaidAutomationCycleStatus.IN_BATTLE, null, recovery),
            ),
        )
        val stale = inBattleObservation(
            target,
            RaidBattleAvailability.RUNNABLE,
            RaidObservedBattle("raid", recovery.mapCode),
        ).copy(fresh = false)
        val module = DefaultRaidCycleModule(store, RaidObservationReader { stale }, TimeProvider { now })

        val hold = assertIs<RaidDirective.Hold>(module.decideNext(1))

        assertEquals(RaidHoldReason.BATTLE_OBSERVATION_INCOMPLETE, hold.reason)
        assertEquals(now.plusSeconds(300), hold.recheckAt)
        assertEquals(RaidBattleRecoveryObservation.INCOMPLETE, store.state.openCycle?.battleRecovery?.lastObservation)
    }

    @Test
    fun `복구 시각의 불완전 관측은 POST 없이 5분 뒤 최신 GET만 다시 예약한다`() {
        val target = target("raid-a", 0)
        val recovery = recovery(target.raidId, nextCheckAt = now)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                RaidCycleSnapshot(1, 7, target.raidId, target.name, RaidAutomationCycleStatus.IN_BATTLE, null, recovery),
            ),
        )
        var reads = 0
        val observation = inBattleObservation(target, RaidBattleAvailability.INCOMPLETE, battle = null)
        val module = DefaultRaidCycleModule(store, RaidObservationReader { reads += 1; observation }, TimeProvider { now })

        val hold = assertIs<RaidDirective.Hold>(module.decideNext(1))

        assertEquals(1, reads)
        assertEquals(RaidHoldReason.BATTLE_OBSERVATION_INCOMPLETE, hold.reason)
        assertEquals(now.plusSeconds(300), hold.recheckAt)
        assertEquals(RaidBattleRecoveryObservation.INCOMPLETE, store.state.openCycle?.battleRecovery?.lastObservation)
    }

    @Test
    fun `5분 뒤 같은 맵이 실행 가능하면 최신 프리셋으로 연결된 새 전투를 만든다`() {
        val latestParty = ResolvedAutomationParty(listOf("latest-character"), listOf(BattlePatternLoadRequest("latest-character", 2)))
        val target = target("raid-a", 0, latestParty).copy(presetId = 44)
        val recovery = recovery(target.raidId, nextCheckAt = now)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                RaidCycleSnapshot(1, 7, target.raidId, target.name, RaidAutomationCycleStatus.IN_BATTLE, null, recovery),
            ),
        )
        val observation = inBattleObservation(
            target,
            RaidBattleAvailability.RUNNABLE,
            RaidObservedBattle("raid", recovery.mapCode),
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { observation }, TimeProvider { now })

        val directive = assertIs<RaidDirective.Execute>(module.decideNext(1))
        val battle = assertIs<RaidIntent.Battle>(directive.intent)

        assertEquals(44, battle.presetId)
        assertEquals(latestParty, battle.party)
        assertEquals(recovery.chainId, battle.recoveryChainId)
        assertEquals(1, battle.retransmissionCount)
        assertEquals("RAID_BATTLE_RETRANSMIT", directive.reasonCode)
        assertEquals(true, directive.warning?.contains("최초 미확정 ${recovery.firstAmbiguousAt}"))
        assertEquals(true, directive.warning?.contains("재전송 1회"))
        assertEquals(true, directive.warning?.contains("다음 확인 ${now.plusSeconds(300)}"))
    }

    @Test
    fun `복구 중 다른 실행 가능 맵이 관측되면 상태 대체 사유와 함께 새 맵을 선택한다`() {
        val party = ResolvedAutomationParty(
            listOf("character-1"),
            listOf(BattlePatternLoadRequest("character-1", 1)),
        )
        val target = target("raid-a", 0, party)
        val recovery = recovery(target.raidId, nextCheckAt = now)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                RaidCycleSnapshot(1, 7, target.raidId, target.name, RaidAutomationCycleStatus.IN_BATTLE, null, recovery),
            ),
        )
        val observation = inBattleObservation(
            target,
            RaidBattleAvailability.RUNNABLE,
            RaidObservedBattle("raid", "raid002"),
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { observation }, TimeProvider { now })

        val directive = assertIs<RaidDirective.Execute>(module.decideNext(1))

        assertEquals("raid002", assertIs<RaidIntent.Battle>(directive.intent).mapCode)
        assertEquals("RAID_BATTLE_RECOVERY_SUPERSEDED_BY_MAP", directive.reasonCode)
        assertEquals(true, directive.message?.contains("다른 전투 맵"))
        assertEquals(null, store.state.openCycle?.battleRecovery)
    }

    @Test
    fun `복구 중 다른 맵으로 바뀐 뒤 프리셋이 유효하지 않아도 상태 대체 사유를 보존한다`() {
        val target = target("raid-a", 0)
        val recovery = recovery(target.raidId, nextCheckAt = now)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                RaidCycleSnapshot(1, 7, target.raidId, target.name, RaidAutomationCycleStatus.IN_BATTLE, null, recovery),
            ),
        )
        val observation = inBattleObservation(
            target,
            RaidBattleAvailability.RUNNABLE,
            RaidObservedBattle("raid", "raid002"),
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { observation }, TimeProvider { now })

        val hold = assertIs<RaidDirective.Hold>(module.decideNext(1))

        assertEquals(RaidHoldReason.INVALID_PRESET, hold.reason)
        assertEquals("RAID_BATTLE_RECOVERY_SUPERSEDED_BY_MAP", hold.reasonCode)
        assertEquals(true, hold.message.contains("이전 복구를 종료"))
        assertEquals(null, store.state.openCycle?.battleRecovery)
    }

    @Test
    fun `재전송이 다시 불명확해도 횟수 제한 없이 매번 5분 뒤 새 실행을 허용한다`() {
        val party = ResolvedAutomationParty(listOf("character-1"), listOf(BattlePatternLoadRequest("character-1", 1)))
        val target = target("raid-a", 0, party)
        var current = now
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                RaidCycleSnapshot(1, 7, target.raidId, target.name, RaidAutomationCycleStatus.IN_BATTLE, null),
            ),
        )
        val module = DefaultRaidCycleModule(
            store,
            RaidObservationReader {
                inBattleObservation(target, RaidBattleAvailability.RUNNABLE, RaidObservedBattle("raid", "raid001"))
            },
            TimeProvider { current },
        )

        var attempt = ambiguousBattleAttempt(target.raidId, "execution-0", submittedAt = current)
        repeat(4) { retryIndex ->
            assertIs<RaidRecordResult.BattleRecoveryStarted>(module.recordObservedResult(
                1,
                attempt,
                RaidResultObservation.BattleAmbiguous("결과 미관측"),
            ))
            current = current.plusSeconds(300)
            val battle = assertIs<RaidIntent.Battle>(assertIs<RaidDirective.Execute>(module.decideNext(1)).intent)
            assertEquals(retryIndex + 1, battle.retransmissionCount)
            attempt = ambiguousBattleAttempt(
                target.raidId,
                "execution-${retryIndex + 1}",
                submittedAt = current,
                recoveryChainId = battle.recoveryChainId,
                retransmissionCount = battle.retransmissionCount,
            )
        }
    }

    @Test
    fun `실행 가능 상태 뒤 새 쿨타임은 적용 증거가 되어 복구를 해제한다`() {
        val target = target("raid-a", 0)
        val recovery = recovery(target.raidId, nextCheckAt = now, submittedFromRunnable = true)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                RaidCycleSnapshot(1, 7, target.raidId, target.name, RaidAutomationCycleStatus.IN_BATTLE, null, recovery),
            ),
        )
        val observation = inBattleObservation(
            target,
            RaidBattleAvailability.COOLDOWN,
            RaidObservedBattle("raid", recovery.mapCode, 90),
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { observation }, TimeProvider { now })

        val wait = assertIs<RaidDirective.WaitUntil>(module.decideNext(1))

        assertEquals(RaidWaitReason.BATTLE_APPLIED_COOLDOWN, wait.reason)
        assertEquals(null, store.state.openCycle?.battleRecovery)
    }

    @Test
    fun `제출 전 관측이 없는 legacy 복구는 현재 쿨타임만으로 적용을 추정하지 않는다`() {
        val target = target("raid-a", 0)
        val recovery = recovery(target.raidId, nextCheckAt = now, submittedFromRunnable = false)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                RaidCycleSnapshot(1, 7, target.raidId, target.name, RaidAutomationCycleStatus.IN_BATTLE, null, recovery),
            ),
        )
        val observation = inBattleObservation(
            target,
            RaidBattleAvailability.COOLDOWN,
            RaidObservedBattle("raid", recovery.mapCode, 90),
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { observation }, TimeProvider { now })

        val wait = assertIs<RaidDirective.WaitUntil>(module.decideNext(1))

        assertEquals(RaidWaitReason.BATTLE_COOLDOWN, wait.reason)
        assertEquals(recovery.chainId, store.state.openCycle?.battleRecovery?.chainId)
    }

    @Test
    fun `legacy 복구도 실행 가능 상태에서 재전송한 뒤에는 새 쿨타임을 적용 증거로 인정한다`() {
        val party = ResolvedAutomationParty(
            listOf("character-1"),
            listOf(BattlePatternLoadRequest("character-1", 1)),
        )
        val target = target("raid-a", 0, party)
        var current = now
        var observation = inBattleObservation(
            target,
            RaidBattleAvailability.RUNNABLE,
            RaidObservedBattle("raid", "raid001"),
        )
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                RaidCycleSnapshot(
                    1,
                    7,
                    target.raidId,
                    target.name,
                    RaidAutomationCycleStatus.IN_BATTLE,
                    null,
                    recovery(target.raidId, nextCheckAt = current, submittedFromRunnable = false),
                ),
            ),
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { observation }, TimeProvider { current })

        val retransmission = assertIs<RaidIntent.Battle>(assertIs<RaidDirective.Execute>(module.decideNext(1)).intent)
        assertEquals(true, retransmission.submittedFromRunnable)
        assertIs<RaidRecordResult.BattleRecoveryStarted>(module.recordObservedResult(
            1,
            RaidAttempt(
                entryId = retransmission.entryId,
                kind = RaidIntentKind.BATTLE,
                raidId = retransmission.raidId,
                executionIdentity = "execution-1",
                categoryId = retransmission.categoryId,
                mapCode = retransmission.mapCode,
                recoveryChainId = retransmission.recoveryChainId,
                retransmissionCount = retransmission.retransmissionCount,
                submittedAt = current,
                submittedFromRunnable = retransmission.submittedFromRunnable,
            ),
            RaidResultObservation.BattleAmbiguous("재전송 결과 미관측"),
        ))
        current = current.plusSeconds(300)
        observation = inBattleObservation(
            target,
            RaidBattleAvailability.COOLDOWN,
            RaidObservedBattle("raid", "raid001", 90),
        )

        val wait = assertIs<RaidDirective.WaitUntil>(module.decideNext(1))

        assertEquals(RaidWaitReason.BATTLE_APPLIED_COOLDOWN, wait.reason)
        assertEquals(null, store.state.openCycle?.battleRecovery)
    }

    @Test
    fun `전투 중 참가 해제가 관측되면 활성 복구보다 우선해 사이클을 종료한다`() {
        val target = target("raid-a", 0)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                RaidCycleSnapshot(
                    1,
                    7,
                    target.raidId,
                    target.name,
                    RaidAutomationCycleStatus.IN_BATTLE,
                    null,
                    recovery(target.raidId, nextCheckAt = now),
                ),
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
                    joined = false,
                    actions = emptySet(),
                    battleAvailability = RaidBattleAvailability.INCOMPLETE,
                ),
            ),
            applied = false,
            registrationWait = false,
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { observation }, TimeProvider { now })

        val complete = assertIs<RaidDirective.Complete>(module.decideNext(1))

        assertEquals(RaidCycleOutcomeKind.ABORTED_REGISTRATION_LOST, complete.outcome.kind)
        assertEquals("RAID_BATTLE_RECOVERY_SUPERSEDED", complete.reasonCode)
        assertEquals(null, store.state.openCycle)
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

    private fun ambiguousBattleAttempt(
        raidId: String,
        executionIdentity: String,
        submittedAt: Instant,
        recoveryChainId: String? = null,
        retransmissionCount: Int = 0,
    ) = RaidAttempt(
        entryId = 7,
        kind = RaidIntentKind.BATTLE,
        raidId = raidId,
        requestRaidId = null,
        executionIdentity = executionIdentity,
        categoryId = "raid",
        mapCode = "raid001",
        recoveryChainId = recoveryChainId,
        retransmissionCount = retransmissionCount,
        submittedAt = submittedAt,
        submittedFromRunnable = true,
    )

    private fun recovery(
        raidId: String,
        nextCheckAt: Instant,
        submittedFromRunnable: Boolean = true,
    ) = RaidBattleRecovery(
        chainId = "recovery-1",
        raidId = raidId,
        categoryId = "raid",
        mapCode = "raid001",
        originalExecutionIdentity = "execution-0",
        latestExecutionIdentity = "execution-0",
        firstAmbiguousAt = now,
        lastSubmittedAt = now,
        retransmissionCount = 0,
        nextCheckAt = nextCheckAt,
        submittedFromRunnable = submittedFromRunnable,
        lastObservation = RaidBattleRecoveryObservation.RESULT_UNOBSERVED,
    )

    private fun inBattleObservation(
        target: RaidCycleTarget,
        availability: RaidBattleAvailability,
        battle: RaidObservedBattle?,
    ) = RaidObservation(
        raids = listOf(
            RaidObservedTarget(
                id = target.raidId,
                name = target.name,
                playable = true,
                status = RaidObservedStatus.IN_BATTLE,
                statusText = "전투 중",
                joined = true,
                actions = emptySet(),
                battle = battle,
                battleAvailability = availability,
            ),
        ),
        applied = false,
        registrationWait = false,
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

        override fun saveBattleRecovery(
            accountId: Long,
            raidId: String,
            recovery: RaidBattleRecovery,
            now: Instant,
        ): RaidCycleSnapshot {
            val cycle = requireNotNull(state.openCycle).copy(battleRecovery = recovery)
            state = state.copy(openCycle = cycle)
            return cycle
        }

        override fun clearBattleRecovery(accountId: Long, raidId: String, now: Instant): RaidCycleSnapshot {
            val cycle = requireNotNull(state.openCycle).copy(battleRecovery = null)
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
