package app.spammy.hof.automation.service

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AutomationTimeBudgetTest {
    private val policy = BattleTimePolicy()

    @Test
    fun `estimate adds one TIME per elapsed second and clamps to maximum`() {
        val snapshot = AutomationTimeSnapshot(50, 100, NOW)
        assertEquals(50, snapshot.estimateAt(NOW))
        assertEquals(80, snapshot.estimateAt(NOW.plusSeconds(30)))
        assertEquals(100, snapshot.estimateAt(NOW.plusSeconds(70)))
        assertEquals(50, snapshot.estimateAt(NOW.minusSeconds(10)))
    }

    @Test
    fun `battle map preserves 1500 TIME and chooses one or three only above the reserve`() {
        assertEquals(
            NOW.plusSeconds(1),
            assertIs<BattleTimeDecision.Wait>(
                policy.forBattleMap(time(1500), NOW, 10, true, true),
            ).nextRunAt,
        )
        assertEquals(3, run(policy.forBattleMap(time(1501), NOW, 10, true, true)).battleCount)
        assertEquals(1, run(policy.forBattleMap(time(1501), NOW, 2, true, true)).battleCount)
        assertEquals(1, run(policy.forBattleMap(time(1501), NOW, 10, false, true)).battleCount)
        assertEquals(1, run(policy.forBattleMap(time(1501), NOW, 10, true, false)).battleCount)
    }

    @Test
    fun `quest combat keeps exact round cost boundaries`() {
        assertIs<BattleTimeDecision.Wait>(policy.forQuestCombat(time(99), NOW, 10, true, true))
        assertEquals(1, run(policy.forQuestCombat(time(100), NOW, 10, true, true)).battleCount)
        assertEquals(1, run(policy.forQuestCombat(time(299), NOW, 10, true, true)).battleCount)
        assertEquals(3, run(policy.forQuestCombat(time(300), NOW, 10, true, true)).battleCount)
    }

    @Test
    fun `battle map with maximum TIME at the reserve uses the reconciliation interval`() {
        val constrained = AutomationTimeSnapshot(current = 1500, max = 1500, observedAt = NOW)

        val waiting = assertIs<BattleTimeDecision.Wait>(
            policy.forBattleMap(constrained, NOW, 10, true, true),
        )

        assertEquals(NOW.plus(Duration.ofMinutes(30)), waiting.nextRunAt)
    }

    @Test
    fun `adventure uses map cost zero and null fallback`() {
        assertEquals(0, run(policy.forAdventureMap(null, NOW, 0)).requiredTime)
        assertIs<BattleTimeDecision.Wait>(policy.forAdventureMap(time(99), NOW, 100))
        assertEquals(100, run(policy.forAdventureMap(time(100), NOW, null)).requiredTime)
        assertEquals(true, run(policy.forAdventureMap(time(100), NOW, null)).usedFallback)
    }

    @Test
    fun `wait schedules the exact estimated deficit and missing observation retries safely`() {
        assertEquals(
            NOW.plusSeconds(40),
            assertIs<BattleTimeDecision.Wait>(policy.forAdventureMap(time(60), NOW, 100)).nextRunAt,
        )
        assertEquals(
            NOW.plusSeconds(10),
            assertIs<BattleTimeDecision.Wait>(policy.forBattleMap(null, NOW, 10, true, true)).nextRunAt,
        )
    }

    private fun time(current: Int) = AutomationTimeSnapshot(current, 6000, NOW)

    private fun run(decision: BattleTimeDecision) = assertIs<BattleTimeDecision.Run>(decision)

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-25T00:00:00Z")
    }
}
