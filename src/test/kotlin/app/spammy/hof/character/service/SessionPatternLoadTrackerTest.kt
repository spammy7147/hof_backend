package app.spammy.hof.character.service

import app.spammy.hof.battle.dto.BattlePatternLoadRequest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SessionPatternLoadTrackerTest {
    private val tracker = SessionPatternLoadTracker()
    private val first = BattlePatternLoadRequest("character-1", 1)
    private val second = BattlePatternLoadRequest("character-2", 2)

    @Test
    fun successfulLoadsAreSkippedWithinTheSameAccountSession() {
        val cookies = mapOf("PHPSESSID" to "session-a")

        tracker.withSession(1L, cookies) { session ->
            assertEquals(listOf(first, second), session.requiredLoads(listOf(first, second)))
            session.recordLoaded(first)
            session.recordLoaded(second)
        }

        tracker.withSession(1L, cookies) { session ->
            assertEquals(emptyList(), session.requiredLoads(listOf(first, second)))
        }
    }

    @Test
    fun onlyChangedCharacterSlotsAreRequired() {
        val cookies = mapOf("PHPSESSID" to "session-a")
        tracker.withSession(1L, cookies) { session ->
            session.recordLoaded(first)
            session.recordLoaded(second)
        }
        val changed = second.copy(slot = 3)

        tracker.withSession(1L, cookies) { session ->
            assertEquals(listOf(changed), session.requiredLoads(listOf(first, changed)))
        }
    }

    @Test
    fun changedSessionsAndDifferentAccountsDoNotReuseLoads() {
        tracker.withSession(1L, mapOf("PHPSESSID" to "session-a")) { session ->
            session.recordLoaded(first)
        }

        tracker.withSession(1L, mapOf("PHPSESSID" to "session-b")) { session ->
            assertEquals(listOf(first), session.requiredLoads(listOf(first)))
        }
        tracker.withSession(2L, mapOf("PHPSESSID" to "session-a")) { session ->
            assertEquals(listOf(first), session.requiredLoads(listOf(first)))
        }
    }
}
