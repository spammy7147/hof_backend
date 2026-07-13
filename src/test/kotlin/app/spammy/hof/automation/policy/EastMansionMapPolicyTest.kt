package app.spammy.hof.automation.policy

import kotlin.test.Test
import kotlin.test.assertEquals

class EastMansionMapPolicyTest {
    private val policy = EastMansionMapPolicy()

    @Test
    fun fixedMapRunsUntilItsKeyIsGoneAndThenFallsBackToTheCorridor() {
        assertEquals("Noble1021", policy.select(listOf(candidate("Noble1021", 1, 0))).mapCode)
        assertEquals("Noble102", policy.select(listOf(candidate("Noble1021", 0, 0))).mapCode)
    }

    @Test
    fun multipleKeyedMapsConsumeTheGreatestRemainingKeyCountWithStableOrderTies() {
        val selected = listOf(
            candidate("Noble1021", 12, 0),
            candidate("Noble1022", 8, 1),
            candidate("Noble1023", 12, 2),
            candidate("Noble102", null, 3),
        )

        assertEquals("Noble1021", policy.select(selected).mapCode)
    }

    @Test
    fun corridorIsUsedWhenNoSelectedKeyedMapCanRun() {
        val selected = listOf(candidate("Noble1022", 0, 0), candidate("Noble1023", 0, 1))

        assertEquals("Noble102", policy.select(selected).mapCode)
    }

    private fun candidate(code: String, keys: Int?, order: Int) = KeyQuestMapCandidate(
        mapCode = code,
        mapName = code,
        keyCount = keys,
        executionOrder = order,
    )
}
