package app.spammy.hof.automation.raid

import app.spammy.hof.automation.config.RaidAutomationProperties
import app.spammy.hof.automation.entity.RaidAutomationCycleStatus
import app.spammy.hof.common.time.TimeProvider
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertIs
import org.mockito.Mockito

class RaidBattlePreSubmitGuardTest {
    private val now = Instant.parse("2026-08-23T00:02:01Z")
    private val store = Mockito.mock(RaidCycleStore::class.java)
    private val observations = Mockito.mock(RaidObservationReader::class.java)
    private val guard = DefaultRaidBattlePreSubmitGuard(store, observations, TimeProvider { now })

    @Test
    fun `expired safety deadline still blocks submission until the decision module clears the gate`() {
        Mockito.`when`(store.load(7)).thenReturn(state(
            gate = RaidBattleSafetyGate(
                raidId = "Raid001",
                categoryId = "raid_hunt",
                mapCode = "raid-map-1",
                executionIdentity = "battle-1",
                startedAt = now.minusSeconds(121),
                notBefore = now.minusSeconds(1),
                source = RaidCooldownSource.LOCAL_FALLBACK,
            ),
        ))

        assertIs<RaidBattlePreSubmitResult.Changed>(
            guard.validate(7, "Raid001", "raid_hunt", "raid-map-1"),
        )
        Mockito.verifyNoInteractions(observations)
    }

    @Test
    fun `fallback enforcement가 꺼지면 기존 로컬 게이트는 제출 직전 검사를 막지 않는다`() {
        Mockito.`when`(store.load(7)).thenReturn(state(
            gate = RaidBattleSafetyGate(
                raidId = "Raid001",
                categoryId = "raid_hunt",
                mapCode = "raid-map-1",
                executionIdentity = "battle-1",
                startedAt = now.minusSeconds(30),
                notBefore = now.plusSeconds(90),
                source = RaidCooldownSource.LOCAL_FALLBACK,
            ),
        ))
        Mockito.`when`(observations.read(7)).thenReturn(observation(
            target = target(
                battle = RaidObservedBattle("raid_hunt", "raid-map-1"),
                availability = RaidBattleAvailability.RUNNABLE,
            ),
        ))
        val disabledGuard = DefaultRaidBattlePreSubmitGuard(
            store,
            observations,
            TimeProvider { now },
            RaidAutomationProperties(fallbackEnforcementEnabled = false),
        )

        assertIs<RaidBattlePreSubmitResult.Ready>(
            disabledGuard.validate(7, "Raid001", "raid_hunt", "raid-map-1"),
        )
    }

    @Test
    fun `fallback enforcement가 꺼져도 HOF 직접 게이트는 제출을 계속 막는다`() {
        Mockito.`when`(store.load(7)).thenReturn(state(
            gate = RaidBattleSafetyGate(
                raidId = "Raid001",
                categoryId = "raid_hunt",
                mapCode = "raid-map-1",
                executionIdentity = "battle-1",
                startedAt = now.minusSeconds(30),
                notBefore = now.plusSeconds(90),
                source = RaidCooldownSource.HOF_DIRECT,
            ),
        ))
        val disabledGuard = DefaultRaidBattlePreSubmitGuard(
            store,
            observations,
            TimeProvider { now },
            RaidAutomationProperties(fallbackEnforcementEnabled = false),
        )

        assertIs<RaidBattlePreSubmitResult.Changed>(
            disabledGuard.validate(7, "Raid001", "raid_hunt", "raid-map-1"),
        )
        Mockito.verifyNoInteractions(observations)
    }

    @Test
    fun `fresh exact runnable raid battle passes immediately before submission`() {
        Mockito.`when`(store.load(7)).thenReturn(state())
        Mockito.`when`(observations.read(7)).thenReturn(observation(
            target = target(
                battle = RaidObservedBattle("raid_hunt", "raid-map-1"),
                availability = RaidBattleAvailability.RUNNABLE,
            ),
        ))

        assertIs<RaidBattlePreSubmitResult.Ready>(
            guard.validate(7, "Raid001", "raid_hunt", "raid-map-1"),
        )
    }

    @Test
    fun `incomplete fresh battle observation never authorizes a post`() {
        Mockito.`when`(store.load(7)).thenReturn(state())
        Mockito.`when`(observations.read(7)).thenReturn(observation(
            target = target(battle = null, availability = RaidBattleAvailability.INCOMPLETE),
        ))

        assertIs<RaidBattlePreSubmitResult.Incomplete>(
            guard.validate(7, "Raid001", "raid_hunt", "raid-map-1"),
        )
    }

    private fun state(gate: RaidBattleSafetyGate? = null) = RaidCycleAccountState(
        configuration = null,
        openCycle = RaidCycleSnapshot(
            id = 1,
            entryId = 9,
            raidId = "Raid001",
            raidName = "고블린 전투 마차",
            status = RaidAutomationCycleStatus.IN_BATTLE,
            nextCheckAt = null,
            battleSafetyGate = gate,
        ),
    )

    private fun observation(target: RaidObservedTarget) = RaidObservation(
        raids = listOf(target),
        applied = true,
        registrationWait = false,
        observedAt = now,
        fresh = true,
    )

    private fun target(
        battle: RaidObservedBattle?,
        availability: RaidBattleAvailability,
    ) = RaidObservedTarget(
        id = "Raid001",
        name = "고블린 전투 마차",
        playable = true,
        status = RaidObservedStatus.IN_BATTLE,
        joined = true,
        actions = setOf(RaidIntentKind.BATTLE),
        battle = battle,
        battleAvailability = availability,
    )
}
