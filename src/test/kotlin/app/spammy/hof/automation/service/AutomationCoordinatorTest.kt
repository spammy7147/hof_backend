package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.AutomationType
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AutomationCoordinatorTest {
    private val action = QuestAction.Claim("q", "a")

    @Test
    fun `warnings do not block lower priority runnable and are retained`() {
        val coordinator = AutomationCoordinator(
            quest = AutomationHandler<QuestAutomationSnapshot> { HandlerEvaluation.ConfigurationWarning("broken quest") },
            battle = AutomationHandler<BattleMapAutomationSnapshot> { HandlerEvaluation.Runnable(action) },
            adventure = AutomationHandler<AdventureMapAutomationSnapshot> { HandlerEvaluation.Skipped },
        )

        val result = coordinator.coordinate(
            AutomationCoordinatorSnapshot(
                entries = listOf(
                    AutomationCoordinatorEntry(1, AutomationType.QUEST, quest = questSnapshot()),
                    AutomationCoordinatorEntry(2, AutomationType.BATTLE_MAP, battle = battleSnapshot()),
                ),
            ),
        )

        val runnable = assertIs<AutomationCoordination.Runnable>(result)
        assertEquals(2, runnable.entryId)
        assertEquals(listOf("broken quest"), runnable.warnings)
        assertEquals(
            listOf(
                Triple(1L, AutomationType.QUEST, AutomationDecisionOutcome.CONFIGURATION_WARNING),
                Triple(2L, AutomationType.BATTLE_MAP, AutomationDecisionOutcome.SELECTED),
            ),
            runnable.trace.map { Triple(it.entryId, it.type, it.outcome) },
        )
    }

    @Test
    fun `returns earliest unavailable when no handler is runnable`() {
        val later = Instant.parse("2026-07-16T02:00:00Z")
        val earlier = Instant.parse("2026-07-16T01:00:00Z")
        val coordinator = AutomationCoordinator(
            quest = AutomationHandler<QuestAutomationSnapshot> { HandlerEvaluation.Unavailable(later) },
            battle = AutomationHandler<BattleMapAutomationSnapshot> { HandlerEvaluation.Unavailable(earlier) },
            adventure = AutomationHandler<AdventureMapAutomationSnapshot> { HandlerEvaluation.Skipped },
        )

        val result = coordinator.coordinate(
            AutomationCoordinatorSnapshot(
                listOf(
                    AutomationCoordinatorEntry(1, AutomationType.QUEST, quest = questSnapshot()),
                    AutomationCoordinatorEntry(2, AutomationType.BATTLE_MAP, battle = battleSnapshot()),
                ),
            ),
        )

        assertEquals(earlier, assertIs<AutomationCoordination.Unavailable>(result).nextRunAt)
    }

    @Test
    fun `fatal stops immediately by priority`() {
        val coordinator = AutomationCoordinator(
            quest = AutomationHandler<QuestAutomationSnapshot> {
                HandlerEvaluation.Fatal(AutomationStopReason.NETWORK, "fatal")
            },
            battle = AutomationHandler<BattleMapAutomationSnapshot> { HandlerEvaluation.Runnable(action) },
            adventure = AutomationHandler<AdventureMapAutomationSnapshot> { HandlerEvaluation.Skipped },
        )
        val result = coordinator.coordinate(
            AutomationCoordinatorSnapshot(
                listOf(
                    AutomationCoordinatorEntry(1, AutomationType.QUEST, quest = questSnapshot()),
                    AutomationCoordinatorEntry(2, AutomationType.BATTLE_MAP, battle = battleSnapshot()),
                ),
            ),
        )
        assertIs<AutomationCoordination.Fatal>(result)
    }

    private fun questSnapshot() = QuestAutomationSnapshot(7, emptyList(), emptyList(), emptyList(), emptyMap(), emptyMap(), emptyList(), Instant.EPOCH)
    private fun battleSnapshot() = BattleMapAutomationSnapshot(7, emptyList(), emptyList(), emptyMap(), null, emptySet(), "id", Instant.EPOCH)
}
