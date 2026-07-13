package app.spammy.hof.automation.policy

import app.spammy.hof.quest.model.QuestSnapshot
import app.spammy.hof.quest.model.QuestState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class AdventureAndSelectedQuestPolicyTest {
    private val adventure = AdventureMapPolicy()
    private val selectedQuest = SelectedQuestPolicy()
    private val now = Instant.parse("2026-07-13T00:00:00Z")

    @Test
    fun oldestReadyCooldownWinsAndFutureOrInvisibleMapsAreSkipped() {
        val candidates = listOf(
            adventure("future", 0, cooldown = now.plusSeconds(60)),
            adventure("recent", 0, cooldown = now.minusSeconds(10)),
            adventure("old", 2, cooldown = now.minusSeconds(120)),
            adventure("hidden", 0, cooldown = now.minusSeconds(300), visible = false),
        )

        assertEquals("old", adventure.selectCooldown(candidates, now)?.mapCode)
    }

    @Test
    fun exhaustedDailyMapIsSkipped() {
        assertNull(adventure.selectDaily(listOf(adventure("done", 0, wins = 0))))
        assertEquals("ready", adventure.selectDaily(listOf(adventure("ready", 0, wins = 1)))?.mapCode)
    }

    @Test
    fun onlyASelectedNormalQuestCanBeActedOn() {
        val quests = listOf(
            QuestSnapshot("0997", "not-selected", QuestState.CLAIMABLE, null, "a"),
            QuestSnapshot("0998", "selected", QuestState.AVAILABLE, null, "b"),
        )

        assertEquals("0998", assertIs<QuestDecision.Accept>(selectedQuest.decide(quests, setOf("0998"))).questId)
    }

    private fun adventure(
        code: String,
        order: Int,
        cooldown: Instant? = null,
        visible: Boolean = true,
        wins: Int? = null,
    ) = AdventureCandidate(code, code, order, visible, true, cooldown, wins, null, null)
}
