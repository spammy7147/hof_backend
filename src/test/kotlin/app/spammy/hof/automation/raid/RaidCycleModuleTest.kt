package app.spammy.hof.automation.raid

import app.spammy.hof.automation.config.RaidAutomationProperties
import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.automation.entity.RaidAutomationCycleStatus
import app.spammy.hof.automation.service.AutomationDiagnosticKind
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

        assertIs<RaidDirective.Hold>(module.decide(1).directive)
        assertEquals(recovery.chainId, store.state.openCycle?.battleRecovery?.chainId)
    }

    @Test
    fun `새 레이드 판단은 현재 순환 대상의 상태 갱신만 먼저 반환하고 사이클을 열지 않는다`() {
        val current = target("raid-b", 1)
        val other = target("raid-a", 0)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(other, current), current.raidId),
                null,
            ),
        )
        val module = DefaultRaidCycleModule(
            store,
            RaidObservationReader { error("상태 갱신 전 GET으로 gameplay를 추정하면 안 됩니다") },
            TimeProvider { now },
        )

        val directive = assertIs<RaidDirective.Execute>(module.decide(1).directive)
        val intent = assertIs<RaidIntent.Town>(directive.intent)

        assertEquals(RaidIntentKind.REFRESH, intent.kind)
        assertEquals("raid-b", intent.raidId)
        assertEquals(null, intent.requestRaidId)
        assertEquals(null, store.state.openCycle)
    }

    @Test
    fun `상태 갱신 직접 응답이 등록 가능하면 사이클을 연 뒤 다음 판단에서 REGISTER한다`() {
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
            fresh = true,
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { observation }, TimeProvider { now })

        val refresh = assertIs<RaidIntent.Town>(assertIs<RaidDirective.Execute>(module.decide(1).directive).intent)
        assertEquals(RaidIntentKind.REFRESH, refresh.kind)
        assertIs<RaidRecordResult.Recorded>(module.recordObservedResult(
            1,
            RaidAttempt(7, RaidIntentKind.REFRESH, target.raidId, requestRaidId = null),
            RaidResultObservation.Page(observation),
        ))
        val intent = assertIs<RaidIntent.Town>(assertIs<RaidDirective.Execute>(module.decide(1).directive).intent)

        assertEquals(RaidIntentKind.REGISTER, intent.kind)
        assertEquals("raid-a", intent.requestRaidId)
        assertEquals(RaidAutomationCycleStatus.PREPARING, store.state.openCycle?.status)
    }

    @Test
    fun `미참가 상태 갱신 응답이 전역 등록 쿨다운이면 성공 표식 없이 종료 시각을 기록한다`() {
        val target = target("raid-a", 0)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                null,
            ),
        )
        val cooldownSeconds = 6_027
        val observation = RaidObservation(
            raids = listOf(
                RaidObservedTarget(
                    id = target.raidId,
                    name = target.name,
                    playable = true,
                    status = RaidObservedStatus.COMPLETED,
                    statusText = "보상 확인 시간",
                    joined = false,
                    actions = emptySet(),
                ),
            ),
            applied = false,
            registrationWait = true,
            registrationWaitSeconds = cooldownSeconds,
            fresh = true,
            actionSuccessMarker = false,
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { observation }, TimeProvider { now })

        val refresh = assertIs<RaidIntent.Town>(assertIs<RaidDirective.Execute>(module.decide(1).directive).intent)
        val wait = assertIs<RaidRecordResult.EntryWait>(module.recordObservedResult(
            1,
            RaidAttempt(7, RaidIntentKind.REFRESH, refresh.raidId, requestRaidId = null),
            RaidResultObservation.Page(observation),
        ))

        assertEquals(now.plusSeconds(cooldownSeconds.toLong()), wait.at)
        assertEquals("RAID_GLOBAL_REGISTRATION_COOLDOWN", wait.reasonCode)
        assertEquals(null, store.state.openCycle)
    }

    @Test
    fun `가입한 완료 레이드는 전역 등록 쿨타임 중에도 보상 단계로 연다`() {
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
                    status = RaidObservedStatus.COMPLETED,
                    statusText = "보상 확인 시간",
                    joined = true,
                    actions = emptySet(),
                    rewardWindow = RaidRewardWindowObservation.ClaimWindow(1_800),
                ),
            ),
            applied = false,
            registrationWait = true,
            registrationWaitSeconds = 10_000,
            globalActions = setOf(RaidIntentKind.REWARD),
            fresh = true,
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { observation }, TimeProvider { now })

        val result = module.recordObservedResult(
            1,
            RaidAttempt(7, RaidIntentKind.REFRESH, target.raidId, requestRaidId = null),
            RaidResultObservation.Page(observation),
        )

        assertIs<RaidRecordResult.Recorded>(result)
        assertEquals(RaidAutomationCycleStatus.REWARD_PENDING, store.state.openCycle?.status)
        val reward = assertIs<RaidIntent.Town>(assertIs<RaidDirective.Execute>(module.decide(1).directive).intent)
        assertEquals(RaidIntentKind.REWARD, reward.kind)
    }

    @Test
    fun `열린 사이클의 가입 완료 상태는 전역 등록 쿨타임보다 보상을 우선한다`() {
        val target = target("raid-a", 0)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                RaidCycleSnapshot(1, 7, target.raidId, target.name, RaidAutomationCycleStatus.PREPARING, null),
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
                    rewardWindow = RaidRewardWindowObservation.Available,
                ),
            ),
            applied = false,
            registrationWait = true,
            registrationWaitSeconds = 10_000,
            globalActions = setOf(RaidIntentKind.REWARD),
            fresh = true,
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { observation }, TimeProvider { now })

        val result = module.recordObservedResult(
            1,
            RaidAttempt(7, RaidIntentKind.REFRESH, target.raidId, requestRaidId = null),
            RaidResultObservation.Page(observation),
        )

        assertIs<RaidRecordResult.Recorded>(result)
        assertEquals(RaidAutomationCycleStatus.REWARD_PENDING, store.state.openCycle?.status)
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

        assertIs<RaidRecordResult.EntryWait>(result)
        assertEquals(RaidAutomationCycleStatus.REGISTERED_WAITING, store.state.openCycle?.status)
        assertEquals(now.plusSeconds(120), store.state.openCycle?.nextCheckAt)
    }

    @Test
    fun `완전한 신청 가능 상태는 이전 결과 귀속 없이 새 신청 판단을 허용한다`() {
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

        assertIs<RaidRecordResult.FreshDecision>(result)
        assertEquals(RaidAutomationCycleStatus.PREPARING, store.state.openCycle?.status)
    }

    @Test
    fun `REGISTER가 기존 전투 상태로 거절되면 저장 요청을 반복하지 않고 REFRESH를 선택한다`() {
        val target = target("raid-a", 0)
        val cleanPage = RaidObservation(
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
            globalActions = setOf(RaidIntentKind.REFRESH),
        )
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                RaidCycleSnapshot(1, 7, target.raidId, target.name, RaidAutomationCycleStatus.PREPARING, null),
            ),
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { cleanPage }, TimeProvider { now })
        val rejected = cleanPage.copy(
            resultMessages = listOf("이미 전투 중입니다. 퇴치/보상 확인/상태 갱신을 해주세요."),
        )

        assertIs<RaidRecordResult.Recorded>(module.recordObservedResult(
            1,
            RaidAttempt(7, RaidIntentKind.REGISTER, target.raidId),
            RaidResultObservation.Page(rejected),
        ))
        assertEquals(RaidAutomationCycleStatus.REGISTRATION_REFRESH_REQUIRED, store.state.openCycle?.status)

        val refresh = assertIs<RaidIntent.Town>(
            assertIs<RaidDirective.Execute>(module.decide(1).directive).intent,
        )
        assertEquals(RaidIntentKind.REFRESH, refresh.kind)

        assertIs<RaidRecordResult.Recorded>(module.recordObservedResult(
            1,
            RaidAttempt(7, RaidIntentKind.REFRESH, target.raidId),
            RaidResultObservation.Page(cleanPage),
        ))
        assertEquals(RaidAutomationCycleStatus.PREPARING, store.state.openCycle?.status)
        val register = assertIs<RaidIntent.Town>(
            assertIs<RaidDirective.Execute>(module.decide(1).directive).intent,
        )
        assertEquals(RaidIntentKind.REGISTER, register.kind)
    }

    @Test
    fun `REGISTER 응답이 신청 대기를 밝히면 정확한 만료 시각까지 레이드 범위만 기다린다`() {
        val target = target("raid-a", 0)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                RaidCycleSnapshot(1, 7, target.raidId, target.name, RaidAutomationCycleStatus.PREPARING, null),
            ),
        )
        val module = DefaultRaidCycleModule(
            store,
            RaidObservationReader { error("cooldown deadline must not GET") },
            TimeProvider { now },
        )
        val cooldownSeconds = 10_765
        val cooldown = RaidObservation(
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
            registrationWait = true,
            registrationWaitSeconds = cooldownSeconds,
            resultMessages = listOf("현재 상태는 신청 대기입니다.(신청 가능 까지 2시간 59분 25초)"),
        )

        assertIs<RaidRecordResult.EntryWait>(module.recordObservedResult(
            1,
            RaidAttempt(7, RaidIntentKind.REGISTER, target.raidId),
            RaidResultObservation.Page(cooldown),
        ))
        assertEquals(RaidAutomationCycleStatus.REGISTRATION_COOLDOWN, store.state.openCycle?.status)
        assertEquals(now.plusSeconds(cooldownSeconds.toLong()), store.state.openCycle?.nextCheckAt)

        val wait = assertIs<RaidDirective.WaitUntil>(module.decide(1).directive)
        assertEquals(RaidWaitReason.REGISTRATION_COOLDOWN, wait.reason)
        assertEquals(now.plusSeconds(cooldownSeconds.toLong()), wait.at)
    }

    @Test
    fun `등록 쿨타임이 만료된 parked 레이드는 GET 판단 전에 상태 갱신부터 실행한다`() {
        val target = target("raid-a", 0)
        val deadline = now.plusSeconds(20)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                RaidCycleSnapshot(
                    1,
                    7,
                    target.raidId,
                    target.name,
                    RaidAutomationCycleStatus.REGISTRATION_COOLDOWN,
                    deadline,
                ),
            ),
        )
        val module = DefaultRaidCycleModule(
            store,
            RaidObservationReader { error("parked due 상태는 REFRESH 직접 응답 전에 GET하지 않습니다") },
            TimeProvider { deadline },
        )

        val refresh = assertIs<RaidIntent.Town>(assertIs<RaidDirective.Execute>(module.decide(1).directive).intent)

        assertEquals(RaidIntentKind.REFRESH, refresh.kind)
        assertEquals(null, refresh.requestRaidId)
    }

    @Test
    fun `개인 전투 쿨타임은 작업권을 놓고 만료 시 다시 상태 갱신한 뒤 전투한다`() {
        val party = ResolvedAutomationParty(listOf("character-1"), listOf(BattlePatternLoadRequest("character-1", 1)))
        val target = target("raid-a", 0, party)
        var current = now
        var latest = inBattleObservation(
            target,
            RaidBattleAvailability.COOLDOWN,
            RaidObservedBattle("raid", "raid001", 20, RaidCooldownSource.HOF_DIRECT),
        ).copy(applied = true)
        var reads = 0
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                null,
            ),
        )
        val module = DefaultRaidCycleModule(
            store,
            RaidObservationReader { reads += 1; latest },
            TimeProvider { current },
        )

        val initialRefresh = assertIs<RaidIntent.Town>(assertIs<RaidDirective.Execute>(module.decide(1).directive).intent)
        val initialWait = assertIs<RaidRecordResult.EntryWait>(module.recordObservedResult(
            1,
            RaidAttempt(7, RaidIntentKind.REFRESH, initialRefresh.raidId, requestRaidId = null),
            RaidResultObservation.Page(latest),
        ))
        assertEquals(now.plusSeconds(20), initialWait.at)
        assertEquals(initialWait.at, store.state.openCycle?.nextCheckAt)

        current = initialWait.at
        val dueRefresh = assertIs<RaidIntent.Town>(assertIs<RaidDirective.Execute>(module.decide(1).directive).intent)
        assertEquals(RaidIntentKind.REFRESH, dueRefresh.kind)
        assertEquals(0, reads)

        latest = inBattleObservation(
            target,
            RaidBattleAvailability.RUNNABLE,
            RaidObservedBattle("raid", "raid001"),
        ).copy(applied = true)
        assertIs<RaidRecordResult.Recorded>(module.recordObservedResult(
            1,
            RaidAttempt(7, RaidIntentKind.REFRESH, dueRefresh.raidId, requestRaidId = null),
            RaidResultObservation.Page(latest),
        ))
        assertEquals(null, store.state.openCycle?.nextCheckAt)

        val battle = assertIs<RaidIntent.Battle>(assertIs<RaidDirective.Execute>(module.decide(1).directive).intent)
        assertEquals("raid001", battle.mapCode)
        assertEquals(1, reads)
    }

    @Test
    fun `비정상 등록 대기는 30초 fallback을 쓰고 짧은 정상 대기는 5초 안전 하한을 지킨다`() {
        listOf<Pair<Int?, Long>>(null to 30L, 0 to 30L, 1 to 5L).forEach { (observedSeconds, expectedSeconds) ->
            listOf(
                RaidIntentKind.REGISTER to RaidAutomationCycleStatus.PREPARING,
                RaidIntentKind.REFRESH to RaidAutomationCycleStatus.REGISTRATION_REFRESH_REQUIRED,
            ).forEach { (intentKind, initialStatus) ->
            val target = target("raid-a", 0)
            val store = InMemoryRaidCycleStore(
                RaidCycleAccountState(
                    RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                    RaidCycleSnapshot(1, 7, target.raidId, target.name, initialStatus, null),
                ),
            )
            val module = DefaultRaidCycleModule(
                store,
                RaidObservationReader { error("registration fallback must not GET") },
                TimeProvider { now },
            )
            val cooldown = RaidObservation(
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
                registrationWait = true,
                registrationWaitSeconds = observedSeconds,
            )

            assertIs<RaidRecordResult.EntryWait>(module.recordObservedResult(
                1,
                RaidAttempt(7, intentKind, target.raidId),
                RaidResultObservation.Page(cooldown),
            ))
            assertEquals(
                now.plusSeconds(expectedSeconds),
                store.state.openCycle?.nextCheckAt,
                "$intentKind/$observedSeconds",
            )
            }
        }
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

        val decision = module.decide(1)
        val wait = assertIs<RaidDirective.WaitUntil>(decision.directive)

        assertEquals(RaidWaitReason.WAITING_TO_START, wait.reason)
        assertEquals(now.plusSeconds(90), wait.at)
        assertEquals(target.raidId, decision.authoritativeState?.raidId)
        assertEquals(RaidObservedStatus.READY, decision.authoritativeState?.target?.status)
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
            actionSuccessMarker = true,
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
    fun `성공 표식 없는 START 최신 상태는 다른 참가자의 시작일 수 있어 기록하지 않는다`() {
        val target = target("raid-a", 0)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                RaidCycleSnapshot(1, 7, target.raidId, target.name, RaidAutomationCycleStatus.REGISTERED_WAITING, null),
            ),
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { error("GET should not be used") }, TimeProvider { now })
        val externallyStarted = RaidObservation(
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
            actionSuccessMarker = false,
            registrationWait = false,
        )

        val result = module.recordObservedResult(
            1,
            RaidAttempt(7, RaidIntentKind.START, target.raidId),
            RaidResultObservation.Page(externallyStarted),
        )

        assertIs<RaidRecordResult.NeedsRecheck>(result)
        assertEquals(RaidAutomationCycleStatus.REGISTERED_WAITING, store.state.openCycle?.status)
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

        val battle = assertIs<RaidIntent.Battle>(assertIs<RaidDirective.Execute>(module.decide(1).directive).intent)

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

        val hold = assertIs<RaidDirective.Hold>(module.decide(1).directive)

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
                    rewardWindow = RaidRewardWindowObservation.Available,
                ),
            ),
            applied = true,
            registrationWait = false,
            globalActions = setOf(RaidIntentKind.REWARD),
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { observation }, TimeProvider { now })

        val directive = assertIs<RaidDirective.Execute>(module.decide(1).directive)
        val reward = assertIs<RaidIntent.Town>(directive.intent)

        assertEquals(RaidIntentKind.REWARD, reward.kind)
        assertEquals("RAID_BATTLE_APPLIED_COMPLETED", directive.reasonCode)
        assertEquals(null, reward.requestRaidId)
        assertEquals(RaidAutomationCycleStatus.REWARD_PENDING, store.state.openCycle?.status)
        assertEquals(null, store.state.openCycle?.battleRecovery)
    }

    @Test
    fun `완료 상태가 전투 복구를 끝내면 전역 버튼과 무관하게 즉시 보상을 선택한다`() {
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
                    rewardWindow = RaidRewardWindowObservation.Available,
                ),
            ),
            applied = true,
            registrationWait = false,
            globalActions = emptySet(),
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { observation }, TimeProvider { now })

        val reward = assertIs<RaidDirective.Execute>(module.decide(1).directive)

        assertEquals(RaidIntentKind.REWARD, reward.intent.kind)
        assertEquals("RAID_BATTLE_APPLIED_COMPLETED", reward.reasonCode)
        assertEquals(null, store.state.openCycle?.battleRecovery)
    }

    @Test
    fun `REWARD POST가 보상을 증명해도 상태 갱신을 한 번 거쳐 쿨타임을 기록한다`() {
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

        assertEquals(null, recorded.completion)
        assertEquals(RaidAutomationCycleStatus.POST_REWARD_CHECK, store.state.openCycle?.status)
        val refresh = assertIs<RaidIntent.Town>(assertIs<RaidDirective.Execute>(module.decide(1).directive).intent)
        assertEquals(RaidIntentKind.REFRESH, refresh.kind)
        val wait = assertIs<RaidRecordResult.EntryWait>(module.recordObservedResult(
            1,
            RaidAttempt(7, RaidIntentKind.REFRESH, target.raidId, requestRaidId = null),
            RaidResultObservation.Page(afterReward),
        ))
        assertEquals(RaidCycleOutcomeKind.COMPLETED, wait.completion?.kind)
        assertEquals(now.plusSeconds(10_000), wait.at)
        assertEquals(null, store.state.openCycle)
        assertEquals(true, store.lastAdvanceRotation)
    }

    @Test
    fun `보상 없음 직접 응답은 상시 보상 버튼이 남아 있어도 보상 처리를 끝낸다`() {
        val target = target("raid-a", 0)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                RaidCycleSnapshot(1, 7, target.raidId, target.name, RaidAutomationCycleStatus.REWARD_PENDING, null),
            ),
        )
        val module = DefaultRaidCycleModule(
            store,
            RaidObservationReader { error("GET should not be used") },
            TimeProvider { now },
        )
        val directResponse = RaidObservation(
            raids = listOf(
                RaidObservedTarget(
                    id = target.raidId,
                    name = target.name,
                    playable = true,
                    status = RaidObservedStatus.COMPLETED,
                    statusText = "보상 확인 시간",
                    joined = true,
                    actions = emptySet(),
                    rewardWindow = RaidRewardWindowObservation.Available,
                ),
            ),
            applied = false,
            registrationWait = false,
            globalActions = setOf(RaidIntentKind.REWARD),
            rewardResult = RaidRewardResultKind.NOTHING_AVAILABLE,
        )

        val result = module.recordObservedResult(
            1,
            RaidAttempt(7, RaidIntentKind.REWARD, target.raidId, requestRaidId = null),
            RaidResultObservation.Page(directResponse),
        )

        assertIs<RaidRecordResult.Recorded>(result)
        assertEquals(RaidAutomationCycleStatus.POST_REWARD_CHECK, store.state.openCycle?.status)
    }

    @Test
    fun `보상 확인 단계는 GET 추정 없이 상태 갱신 직접 응답으로 쿨타임을 기록한다`() {
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
        val module = DefaultRaidCycleModule(store, RaidObservationReader { error("보상 뒤 REFRESH 전에 GET하지 않습니다") }, TimeProvider { now })

        val refresh = assertIs<RaidIntent.Town>(assertIs<RaidDirective.Execute>(module.decide(1).directive).intent)
        val wait = assertIs<RaidRecordResult.EntryWait>(module.recordObservedResult(
            1,
            RaidAttempt(7, RaidIntentKind.REFRESH, refresh.raidId, requestRaidId = null),
            RaidResultObservation.Page(observation),
        ))

        assertEquals(RaidCycleOutcomeKind.COMPLETED, wait.completion?.kind)
        assertEquals(now.plusSeconds(9_000), wait.at)
        assertEquals(null, store.state.openCycle)
        assertEquals(true, store.lastAdvanceRotation)
    }

    @Test
    fun `설정 밖 수동 레이드가 진행 중이면 레이드만 10분 보류하고 새 사이클을 열지 않는다`() {
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

        val refresh = assertIs<RaidIntent.Town>(assertIs<RaidDirective.Execute>(module.decide(1).directive).intent)
        val wait = assertIs<RaidRecordResult.EntryWait>(module.recordObservedResult(
            1,
            RaidAttempt(7, RaidIntentKind.REFRESH, refresh.raidId, requestRaidId = null),
            RaidResultObservation.Page(observation.copy(applied = true)),
        ))

        assertEquals(now.plusSeconds(600), wait.at)
        assertEquals("raid-manual", wait.raidId)
        assertEquals(null, store.state.openCycle)
    }

    @Test
    fun `설정된 외부 레이드가 전투 중이면 gameplay 없이 10분 뒤 재확인한다`() {
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
                    id = target.raidId,
                    name = target.name,
                    playable = false,
                    status = RaidObservedStatus.IN_BATTLE,
                    statusText = "전투 중",
                    joined = false,
                    actions = emptySet(),
                    battle = RaidObservedBattle("raid", "raid001"),
                ),
            ),
            applied = true,
            registrationWait = false,
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { observation }, TimeProvider { now })

        val refresh = assertIs<RaidIntent.Town>(assertIs<RaidDirective.Execute>(module.decide(1).directive).intent)
        val wait = assertIs<RaidRecordResult.EntryWait>(module.recordObservedResult(
            1,
            RaidAttempt(7, RaidIntentKind.REFRESH, refresh.raidId, requestRaidId = null),
            RaidResultObservation.Page(observation),
        ))

        assertEquals(now.plusSeconds(600), wait.at)
        assertEquals(null, store.state.openCycle)
        assertEquals("RAID_EXTERNAL_CONFIGURED_ACTIVE", wait.reasonCode)
        assertEquals(null, wait.warning)
        assertEquals("외부 전투 또는 보상 단계 종료 뒤 상태 갱신", wait.releaseCondition)
    }

    @Test
    fun `설정된 외부 레이드의 보상 창은 보상하지 않고 표시된 종료 시각에 재확인한다`() {
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
                    id = target.raidId,
                    name = target.name,
                    playable = false,
                    status = RaidObservedStatus.COMPLETED,
                    statusText = "보상 확인 시간",
                    waitSeconds = 1_800,
                    joined = false,
                    actions = emptySet(),
                    rewardWindow = RaidRewardWindowObservation.ClaimWindow(1_800),
                ),
            ),
            applied = true,
            registrationWait = false,
            globalActions = setOf(RaidIntentKind.REWARD),
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { observation }, TimeProvider { now })

        val refresh = assertIs<RaidIntent.Town>(assertIs<RaidDirective.Execute>(module.decide(1).directive).intent)
        val wait = assertIs<RaidRecordResult.EntryWait>(module.recordObservedResult(
            1,
            RaidAttempt(7, RaidIntentKind.REFRESH, refresh.raidId, requestRaidId = null),
            RaidResultObservation.Page(observation),
        ))

        assertEquals(now.plusSeconds(1_800), wait.at)
        assertEquals(null, store.state.openCycle)
        assertEquals("RAID_EXTERNAL_CONFIGURED_ACTIVE", wait.reasonCode)
        assertEquals(null, wait.warning)
    }

    @Test
    fun `상태 갱신 뒤 참가권을 잃은 열린 사이클은 외부 보상 버튼을 누르지 않고 종료한다`() {
        val target = target("raid-auto", 0)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                RaidCycleSnapshot(1, 7, target.raidId, target.name, RaidAutomationCycleStatus.PREPARING, null),
            ),
        )
        val observation = RaidObservation(
            raids = listOf(
                RaidObservedTarget(
                    id = target.raidId,
                    name = target.name,
                    playable = false,
                    status = RaidObservedStatus.COMPLETED,
                    statusText = "보상 확인 시간",
                    waitSeconds = 1_800,
                    joined = false,
                    actions = emptySet(),
                    rewardWindow = RaidRewardWindowObservation.ClaimWindow(1_800),
                ),
            ),
            applied = true,
            registrationWait = false,
            globalActions = setOf(RaidIntentKind.REWARD),
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { observation }, TimeProvider { now })

        val complete = assertIs<RaidDirective.Complete>(module.decide(1).directive)

        assertEquals(RaidCycleOutcomeKind.ABORTED_REGISTRATION_LOST, complete.outcome.kind)
        assertEquals(false, store.lastAdvanceRotation)
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

        val refresh = assertIs<RaidIntent.Town>(assertIs<RaidDirective.Execute>(module.decide(1).directive).intent)
        assertEquals(RaidIntentKind.REFRESH, refresh.kind)
        assertIs<RaidRecordResult.Recorded>(module.recordObservedResult(
            1,
            RaidAttempt(7, RaidIntentKind.REFRESH, target.raidId, requestRaidId = null),
            RaidResultObservation.Page(observation),
        ))
        val battle = assertIs<RaidIntent.Battle>(assertIs<RaidDirective.Execute>(module.decide(1).directive).intent)

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

        val complete = assertIs<RaidDirective.Complete>(module.decide(1).directive)

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

        val complete = assertIs<RaidDirective.Complete>(module.decide(1).directive)

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

        val complete = assertIs<RaidDirective.Complete>(module.decide(1).directive)

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

        val superseded = assertIs<RaidDirective.Complete>(module.decide(1).directive)
        val refresh = assertIs<RaidIntent.Town>(assertIs<RaidDirective.Execute>(module.decide(1).directive).intent)
        assertEquals(RaidIntentKind.REFRESH, refresh.kind)
        assertIs<RaidRecordResult.Recorded>(module.recordObservedResult(
            1,
            RaidAttempt(7, RaidIntentKind.REFRESH, observedActive.raidId, requestRaidId = null),
            RaidResultObservation.Page(observation),
        ))
        val battle = assertIs<RaidIntent.Battle>(assertIs<RaidDirective.Execute>(module.decide(1).directive).intent)

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

        val battle = assertIs<RaidIntent.Battle>(assertIs<RaidDirective.Execute>(module.decide(1).directive).intent)

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

        val completed = assertIs<RaidDirective.Complete>(module.decide(1).directive)

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
                    rewardWindow = RaidRewardWindowObservation.Available,
                ),
            ),
            applied = false,
            registrationWait = false,
            globalActions = setOf(RaidIntentKind.REWARD),
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { observation }, TimeProvider { now })

        val reward = assertIs<RaidIntent.Town>(assertIs<RaidDirective.Execute>(module.decide(1).directive).intent)

        assertEquals(RaidIntentKind.REWARD, reward.kind)
    }

    @Test
    fun `자동화 소유 레이드의 보상 수령 가능 창은 남은 시간이 양수여도 즉시 보상을 선택한다`() {
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
                    statusText = "보상 확인 시간 (남은 시간 앞으로 0시간 30분 0초)",
                    waitSeconds = 1_800,
                    joined = true,
                    actions = emptySet(),
                    rewardWindow = RaidRewardWindowObservation.ClaimWindow(1_800),
                ),
            ),
            applied = false,
            registrationWait = false,
            globalActions = emptySet(),
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { observation }, TimeProvider { now })

        val reward = assertIs<RaidIntent.Town>(assertIs<RaidDirective.Execute>(module.decide(1).directive).intent)

        assertEquals(RaidIntentKind.REWARD, reward.kind)
        assertEquals(target.raidId, reward.raidId)
        assertEquals(null, store.state.openCycle?.nextCheckAt)
    }

    @Test
    fun `불완전한 보상 창은 5회만 읽고 레이드 보상 관측을 held한다`() {
        val target = target("raid-auto", 0)
        var current = now
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                RaidCycleSnapshot(1, 7, target.raidId, target.name, RaidAutomationCycleStatus.REWARD_PENDING, null),
            ),
        )
        val incomplete = RaidObservation(
            raids = listOf(
                RaidObservedTarget(
                    id = target.raidId,
                    name = target.name,
                    playable = true,
                    status = RaidObservedStatus.COMPLETED,
                    statusText = "보상 상태 확인 필요",
                    joined = true,
                    actions = emptySet(),
                    rewardWindow = RaidRewardWindowObservation.Incomplete("reward-window-case"),
                ),
            ),
            applied = false,
            registrationWait = false,
            globalActions = setOf(RaidIntentKind.REWARD),
        )
        val module = DefaultRaidCycleModule(
            store,
            RaidObservationReader { incomplete.copy(observedAt = current) },
            TimeProvider { current },
        )

        repeat(4) {
            val decision = module.decide(1)
            val recheck = assertIs<RaidDirective.Hold>(decision.directive)
            assertEquals(current.plusSeconds(10), recheck.recheckAt)
            assertEquals(null, decision.authoritativeState)
            current = current.plusSeconds(10)
        }
        val heldDecision = module.decide(1)
        val held = assertIs<RaidDirective.Hold>(heldDecision.directive)

        assertEquals(null, held.recheckAt)
        assertEquals(null, heldDecision.authoritativeState)
        assertEquals("RAID_REWARD_OBSERVATION_HELD", held.reasonCode)
        assertEquals(AutomationDiagnosticKind.RAID_REWARD_OBSERVATION_HELD, held.diagnosticKind)
        assertEquals(RaidRewardRecoveryKind.WINDOW_OBSERVATION, store.state.openCycle?.rewardRecovery?.kind)
        assertEquals(true, store.state.openCycle?.rewardRecovery?.held)
    }

    @Test
    fun `불완전했던 보상 창이 available이면 관측 예산을 지우고 새 보상만 선택한다`() {
        val target = target("raid-auto", 0)
        var rewardWindow: RaidRewardWindowObservation = RaidRewardWindowObservation.Incomplete()
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                RaidCycleSnapshot(1, 7, target.raidId, target.name, RaidAutomationCycleStatus.REWARD_PENDING, null),
            ),
        )
        val module = DefaultRaidCycleModule(
            store,
            RaidObservationReader {
                RaidObservation(
                    raids = listOf(
                        RaidObservedTarget(
                            id = target.raidId,
                            name = target.name,
                            playable = true,
                            status = RaidObservedStatus.COMPLETED,
                            statusText = "보상 확인",
                            joined = true,
                            actions = emptySet(),
                            rewardWindow = rewardWindow,
                        ),
                    ),
                    applied = false,
                    registrationWait = false,
                    globalActions = setOf(RaidIntentKind.REWARD),
                )
            },
            TimeProvider { now },
        )

        assertIs<RaidDirective.Hold>(module.decide(1).directive)
        rewardWindow = RaidRewardWindowObservation.Available
        val execute = assertIs<RaidDirective.Execute>(module.decide(1).directive)

        assertEquals(RaidIntentKind.REWARD, execute.intent.kind)
        assertEquals(null, store.state.openCycle?.rewardRecovery)
    }

    @Test
    fun `보상 창이 absent이면 외부 상태 진전으로 종결하고 보상 POST를 만들지 않는다`() {
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
                    statusText = "보상 없음",
                    joined = true,
                    actions = emptySet(),
                    rewardWindow = RaidRewardWindowObservation.Absent,
                ),
            ),
            applied = false,
            registrationWait = false,
            globalActions = setOf(RaidIntentKind.REWARD),
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { observation }, TimeProvider { now })

        val complete = assertIs<RaidDirective.Complete>(module.decide(1).directive)

        assertEquals(RaidCycleOutcomeKind.SUPERSEDED_BY_OBSERVED_RAID, complete.outcome.kind)
        assertEquals("RAID_REWARD_WINDOW_ABSENT_SUPERSEDED", complete.reasonCode)
        assertEquals(null, store.state.openCycle)
    }

    @Test
    fun `불명확 보상은 읽기 전용 5회 뒤 새 identity 한 번만 허용하고 두 번째는 held한다`() {
        val target = target("raid-auto", 0)
        var current = now
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                RaidCycleSnapshot(1, 7, target.raidId, target.name, RaidAutomationCycleStatus.REWARD_PENDING, null),
            ),
        )
        val available = RaidObservation(
            raids = listOf(
                RaidObservedTarget(
                    id = target.raidId,
                    name = target.name,
                    playable = true,
                    status = RaidObservedStatus.COMPLETED,
                    statusText = "보상 확인 가능",
                    joined = true,
                    actions = emptySet(),
                    rewardWindow = RaidRewardWindowObservation.Available,
                ),
            ),
            applied = false,
            registrationWait = false,
            globalActions = setOf(RaidIntentKind.REWARD),
            observedAt = current,
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { available.copy(observedAt = current) }, TimeProvider { current })
        fun attempt(identity: String) = RaidAttempt(
            entryId = 7,
            kind = RaidIntentKind.REWARD,
            raidId = target.raidId,
            requestRaidId = null,
            executionIdentity = identity,
        )

        repeat(4) {
            assertIs<RaidRecordResult.NeedsRecheck>(module.recordObservedResult(
                1,
                attempt("reward-1"),
                RaidResultObservation.Page(available.copy(observedAt = current)),
            ))
            current = current.plusSeconds(10)
        }
        assertIs<RaidRecordResult.RewardRetryReady>(module.recordObservedResult(
            1,
            attempt("reward-1"),
            RaidResultObservation.Page(available.copy(observedAt = current)),
        ))
        assertIs<RaidDirective.Execute>(module.decide(1).directive)

        current = current.plusSeconds(10)
        repeat(4) {
            assertIs<RaidRecordResult.NeedsRecheck>(module.recordObservedResult(
                1,
                attempt("reward-2"),
                RaidResultObservation.Page(available.copy(observedAt = current)),
            ))
            current = current.plusSeconds(10)
        }
        assertIs<RaidRecordResult.RewardHeld>(module.recordObservedResult(
            1,
            attempt("reward-2"),
            RaidResultObservation.Page(available.copy(observedAt = current)),
        ))
        val held = assertIs<RaidDirective.Hold>(module.decide(1).directive)

        assertEquals("RAID_REWARD_RESULT_HELD", held.reasonCode)
        assertEquals(true, store.state.openCycle?.rewardRecovery?.held)
        assertEquals(1, store.state.openCycle?.rewardRecovery?.retryCount)
    }

    @Test
    fun `상시 보상 버튼만으로 보상 요청 미적용을 단정하지 않는다`() {
        val target = target("raid-auto", 0)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                RaidCycleSnapshot(1, 7, target.raidId, target.name, RaidAutomationCycleStatus.REWARD_PENDING, null),
            ),
        )
        val rejected = RaidObservation(
            raids = listOf(
                RaidObservedTarget(
                    id = target.raidId,
                    name = target.name,
                    playable = true,
                    status = RaidObservedStatus.COMPLETED,
                    statusText = "보상 상태 재확인 필요",
                    joined = true,
                    actions = emptySet(),
                    rewardWindow = RaidRewardWindowObservation.Incomplete(),
                ),
            ),
            applied = false,
            registrationWait = false,
            globalActions = setOf(RaidIntentKind.REWARD),
            observedAt = now,
            fresh = true,
        )
        val module = DefaultRaidCycleModule(
            store,
            RaidObservationReader { error("retry deadline 전에 GET하면 안 됩니다") },
            TimeProvider { now },
        )

        val result = module.recordObservedResult(
            1,
            RaidAttempt(7, RaidIntentKind.REWARD, target.raidId, requestRaidId = null),
            RaidResultObservation.Page(rejected),
        )
        assertIs<RaidRecordResult.NeedsRecheck>(result)
        assertEquals(RaidAutomationCycleStatus.REWARD_PENDING, store.state.openCycle?.status)
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

        val complete = assertIs<RaidDirective.Complete>(module.decide(1).directive)

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

        val refresh = assertIs<RaidIntent.Town>(assertIs<RaidDirective.Execute>(module.decide(1).directive).intent)

        assertEquals(RaidIntentKind.REFRESH, refresh.kind)
        assertEquals(null, refresh.requestRaidId)
    }

    @Test
    fun `보상 증명 뒤 REFRESH가 성공하면 재등록 대기가 없어도 사이클을 완료한다`() {
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
        assertEquals(null, store.state.openCycle)
        assertEquals(true, store.lastAdvanceRotation)
    }

    @Test
    fun `현재 순환 대상이 아닌 설정 레이드를 편입해 완료하면 기존 순환 위치를 유지한다`() {
        val currentTarget = target("raid-a", 0)
        val adoptedTarget = target("raid-b", 1)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(currentTarget, adoptedTarget), currentTarget.raidId),
                RaidCycleSnapshot(
                    1,
                    7,
                    adoptedTarget.raidId,
                    adoptedTarget.name,
                    RaidAutomationCycleStatus.POST_REWARD_CHECK,
                    null,
                ),
            ),
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { error("GET should not be used") }, TimeProvider { now })
        val refreshed = RaidObservation(
            raids = listOf(
                RaidObservedTarget(
                    id = adoptedTarget.raidId,
                    name = adoptedTarget.name,
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
            RaidAttempt(7, RaidIntentKind.REFRESH, adoptedTarget.raidId, null),
            RaidResultObservation.Page(refreshed),
        ))

        assertEquals(RaidCycleOutcomeKind.COMPLETED, recorded.completion?.kind)
        assertEquals(false, store.lastAdvanceRotation)
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

        val recorded = assertIs<RaidRecordResult.EntryWait>(module.recordObservedResult(
            1,
            RaidAttempt(7, RaidIntentKind.REFRESH, target.raidId, null),
            RaidResultObservation.Page(refreshed),
        ))

        assertEquals(RaidCycleOutcomeKind.COMPLETED, recorded.completion?.kind)
        assertEquals(now.plusSeconds(10_000), recorded.at)
        assertEquals(true, store.lastAdvanceRotation)
    }

    @Test
    fun `보상 버튼이 계속 보여도 REFRESH의 재등록 대기로 사이클을 완료한다`() {
        val target = target("raid-auto", 0)
        val store = InMemoryRaidCycleStore(
            RaidCycleAccountState(
                RaidCycleConfiguration(7, true, listOf(target), target.raidId),
                RaidCycleSnapshot(1, 7, target.raidId, target.name, RaidAutomationCycleStatus.POST_REWARD_CHECK, null),
            ),
        )
        val module = DefaultRaidCycleModule(
            store,
            RaidObservationReader { error("GET should not be used") },
            TimeProvider { now },
        )
        val refreshed = RaidObservation(
            raids = listOf(observed(target, resetRequired = false)),
            applied = false,
            registrationWait = true,
            registrationWaitSeconds = 10_000,
            globalActions = setOf(RaidIntentKind.REWARD),
        )

        val result = module.recordObservedResult(
            1,
            RaidAttempt(7, RaidIntentKind.REFRESH, target.raidId, null),
            RaidResultObservation.Page(refreshed),
        )

        val wait = assertIs<RaidRecordResult.EntryWait>(result)
        assertEquals(RaidCycleOutcomeKind.COMPLETED, wait.completion?.kind)
        assertEquals(now.plusSeconds(10_000), wait.at)
        assertEquals(null, store.state.openCycle)
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

        val wait = assertIs<RaidDirective.WaitUntil>(module.decide(1).directive)

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

        val hold = assertIs<RaidDirective.Hold>(module.decide(1).directive)

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

        val hold = assertIs<RaidDirective.Hold>(module.decide(1).directive)

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

        val directive = assertIs<RaidDirective.Execute>(module.decide(1).directive)
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

        val directive = assertIs<RaidDirective.Execute>(module.decide(1).directive)

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

        val hold = assertIs<RaidDirective.Hold>(module.decide(1).directive)

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
            val battle = assertIs<RaidIntent.Battle>(assertIs<RaidDirective.Execute>(module.decide(1).directive).intent)
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
    fun `실행 가능 상태 뒤 새 쿨타임은 외부 상태 변경으로 복구를 해제한다`() {
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

        val wait = assertIs<RaidDirective.WaitUntil>(module.decide(1).directive)

        assertEquals(RaidWaitReason.BATTLE_COOLDOWN, wait.reason)
        assertEquals(true, wait.message.contains("외부 상태 변경"))
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

        val wait = assertIs<RaidDirective.WaitUntil>(module.decide(1).directive)

        assertEquals(RaidWaitReason.BATTLE_COOLDOWN, wait.reason)
        assertEquals(recovery.chainId, store.state.openCycle?.battleRecovery?.chainId)
    }

    @Test
    fun `legacy 복구도 재전송 뒤 새 쿨타임만으로 현재 요청 성공을 귀속하지 않는다`() {
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

        val retransmission = assertIs<RaidIntent.Battle>(assertIs<RaidDirective.Execute>(module.decide(1).directive).intent)
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

        val wait = assertIs<RaidDirective.WaitUntil>(module.decide(1).directive)

        assertEquals(RaidWaitReason.BATTLE_COOLDOWN, wait.reason)
        assertEquals(true, wait.message.contains("외부 상태 변경"))
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

        val complete = assertIs<RaidDirective.Complete>(module.decide(1).directive)

        assertEquals(RaidCycleOutcomeKind.ABORTED_REGISTRATION_LOST, complete.outcome.kind)
        assertEquals("RAID_BATTLE_RECOVERY_SUPERSEDED", complete.reasonCode)
        assertEquals(null, store.state.openCycle)
    }

    @Test
    fun `정상 전투 종료 2초 뒤 같은 맵이 실행 가능해 보여도 로컬 안전 게이트가 재전투를 막는다`() {
        val party = ResolvedAutomationParty(
            listOf("character-1"),
            listOf(BattlePatternLoadRequest("character-1", 1)),
        )
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
                inBattleObservation(
                    target,
                    RaidBattleAvailability.RUNNABLE,
                    RaidObservedBattle("raid", "raid001"),
                )
            },
            TimeProvider { current },
        )

        module.recordObservedResult(
            1,
            RaidAttempt(
                entryId = 7,
                kind = RaidIntentKind.BATTLE,
                raidId = target.raidId,
                executionIdentity = "execution-0",
                categoryId = "raid",
                mapCode = "raid001",
                submittedAt = current.minusSeconds(15),
                submittedFromRunnable = true,
            ),
            RaidResultObservation.BattleCompleted,
        )
        current = current.plusSeconds(2)

        val wait = assertIs<RaidDirective.WaitUntil>(module.decide(1).directive)

        assertEquals(RaidWaitReason.BATTLE_COOLDOWN, wait.reason)
        assertEquals(now.plusSeconds(120), wait.at)
    }

    @Test
    fun `활성 전투 안전 게이트의 불완전 관측은 권위 baseline으로 승격하지 않는다`() {
        val target = target("raid-a", 0)
        val gate = RaidBattleSafetyGate(
            raidId = target.raidId,
            categoryId = "raid",
            mapCode = "raid001",
            executionIdentity = "execution-0",
            startedAt = now,
            notBefore = now.plusSeconds(120),
            source = RaidCooldownSource.LOCAL_FALLBACK,
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
                    battleSafetyGate = gate,
                ),
            ),
        )
        val observation = inBattleObservation(target, RaidBattleAvailability.INCOMPLETE, battle = null)
        val module = DefaultRaidCycleModule(
            store,
            RaidObservationReader { observation },
            TimeProvider { now },
        )

        val decision = module.decide(1)
        val wait = assertIs<RaidDirective.WaitUntil>(decision.directive)

        assertEquals(RaidWaitReason.BATTLE_COOLDOWN, wait.reason)
        assertEquals(null, decision.authoritativeState)
    }

    @Test
    fun `fallback enforcement를 끄면 기존 로컬 게이트를 지우고 fresh runnable 전투를 허용한다`() {
        val party = ResolvedAutomationParty(
            listOf("character-1"),
            listOf(BattlePatternLoadRequest("character-1", 1)),
        )
        val target = target("raid-a", 0, party)
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
                    battleSafetyGate = RaidBattleSafetyGate(
                        raidId = target.raidId,
                        categoryId = "raid",
                        mapCode = "raid001",
                        executionIdentity = "execution-0",
                        startedAt = now,
                        notBefore = now.plusSeconds(120),
                        source = RaidCooldownSource.LOCAL_FALLBACK,
                    ),
                ),
            ),
        )
        val module = DefaultRaidCycleModule(
            store,
            RaidObservationReader {
                inBattleObservation(target, RaidBattleAvailability.RUNNABLE, RaidObservedBattle("raid", "raid001"))
            },
            TimeProvider { now },
            RaidAutomationProperties(fallbackEnforcementEnabled = false),
        )

        assertIs<RaidDirective.Execute>(module.decide(1).directive)
        assertEquals(null, store.state.openCycle?.battleSafetyGate)
    }

    @Test
    fun `HOF 직접 쿨타임은 활성 로컬 fallback deadline을 대체한다`() {
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
                RaidCycleSnapshot(1, 7, target.raidId, target.name, RaidAutomationCycleStatus.IN_BATTLE, null),
            ),
        )
        val module = DefaultRaidCycleModule(store, RaidObservationReader { observation }, TimeProvider { current })
        module.recordObservedResult(
            1,
            RaidAttempt(
                entryId = 7,
                kind = RaidIntentKind.BATTLE,
                raidId = target.raidId,
                executionIdentity = "execution-0",
                categoryId = "raid",
                mapCode = "raid001",
                finishedAt = current,
            ),
            RaidResultObservation.BattleCompleted,
        )
        current = current.plusSeconds(2)
        observation = inBattleObservation(
            target,
            RaidBattleAvailability.COOLDOWN,
            RaidObservedBattle("raid", "raid001", 90, RaidCooldownSource.HOF_DIRECT),
        )

        val wait = assertIs<RaidDirective.WaitUntil>(module.decide(1).directive)

        assertEquals(now.plusSeconds(92), wait.at)
        assertEquals(RaidCooldownSource.HOF_DIRECT, wait.cooldownSource)
        assertEquals(RaidCooldownSource.HOF_DIRECT, store.state.openCycle?.battleSafetyGate?.source)
    }

    @Test
    fun `fallback 만료 뒤 fresh runnable 관측이 있어야 게이트를 해제하고 새 전투를 선택한다`() {
        val party = ResolvedAutomationParty(
            listOf("character-1"),
            listOf(BattlePatternLoadRequest("character-1", 1)),
        )
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
        module.recordObservedResult(
            1,
            RaidAttempt(
                entryId = 7,
                kind = RaidIntentKind.BATTLE,
                raidId = target.raidId,
                executionIdentity = "execution-0",
                categoryId = "raid",
                mapCode = "raid001",
                finishedAt = current,
            ),
            RaidResultObservation.BattleCompleted,
        )

        assertIs<RaidDirective.WaitUntil>(module.decide(1).directive)
        current = current.plusSeconds(120)
        assertIs<RaidDirective.Execute>(module.decide(1).directive)
        assertEquals(null, store.state.openCycle?.battleSafetyGate)
    }

    @Test
    fun `fallback 만료 뒤 불완전 권위 관측 5회면 이 레이드 전투만 held한다`() {
        val target = target("raid-a", 0)
        var current = now
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
                    battleSafetyGate = RaidBattleSafetyGate(
                        raidId = target.raidId,
                        categoryId = "raid",
                        mapCode = "raid001",
                        executionIdentity = "execution-0",
                        startedAt = now.minusSeconds(120),
                        notBefore = now,
                        source = RaidCooldownSource.LOCAL_FALLBACK,
                    ),
                ),
            ),
        )
        val module = DefaultRaidCycleModule(
            store,
            RaidObservationReader {
                inBattleObservation(target, RaidBattleAvailability.INCOMPLETE, null)
            },
            TimeProvider { current },
        )

        repeat(4) {
            val hold = assertIs<RaidDirective.Hold>(module.decide(1).directive)
            assertEquals("RAID_BATTLE_GATE_OBSERVATION_INCOMPLETE", hold.reasonCode)
            current = current.plusSeconds(10)
        }
        val held = assertIs<RaidDirective.Hold>(module.decide(1).directive)

        assertEquals("RAID_BATTLE_GATE_HELD_5", held.reasonCode)
        assertEquals(true, store.state.openCycle?.battleSafetyGate?.held)
    }

    @Test
    fun `배포 전 열린 전투 사이클 fallback 이관은 재판단마다 deadline을 연장하지 않는다`() {
        val target = target("raid-a", 0)
        var current = now
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
                    battleSafetyVersion = 0,
                ),
            ),
        )
        val module = DefaultRaidCycleModule(
            store,
            RaidObservationReader {
                inBattleObservation(target, RaidBattleAvailability.RUNNABLE, RaidObservedBattle("raid", "raid001"))
            },
            TimeProvider { current },
        )

        val first = assertIs<RaidDirective.WaitUntil>(module.decide(1).directive)
        current = current.plusSeconds(1)
        val second = assertIs<RaidDirective.WaitUntil>(module.decide(1).directive)

        assertEquals(now.plusSeconds(120), first.at)
        assertEquals(first.at, second.at)
        assertEquals(RaidCooldownSource.DEPLOYMENT_FALLBACK, second.cooldownSource)
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

        override fun saveBattleSafetyGate(
            accountId: Long,
            raidId: String,
            gate: RaidBattleSafetyGate,
            now: Instant,
        ): RaidCycleSnapshot {
            val cycle = requireNotNull(state.openCycle).copy(
                battleSafetyGate = gate,
                battleSafetyVersion = CURRENT_RAID_BATTLE_SAFETY_VERSION,
            )
            state = state.copy(openCycle = cycle)
            return cycle
        }

        override fun clearBattleSafetyGate(accountId: Long, raidId: String, now: Instant): RaidCycleSnapshot {
            val cycle = requireNotNull(state.openCycle).copy(
                battleSafetyGate = null,
                battleSafetyVersion = CURRENT_RAID_BATTLE_SAFETY_VERSION,
            )
            state = state.copy(openCycle = cycle)
            return cycle
        }

        override fun saveRewardRecovery(
            accountId: Long,
            raidId: String,
            recovery: RaidRewardRecovery,
            now: Instant,
        ): RaidCycleSnapshot {
            val cycle = requireNotNull(state.openCycle).copy(rewardRecovery = recovery)
            state = state.copy(openCycle = cycle)
            return cycle
        }

        override fun clearRewardRecovery(accountId: Long, raidId: String, now: Instant): RaidCycleSnapshot {
            val cycle = requireNotNull(state.openCycle).copy(rewardRecovery = null)
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
