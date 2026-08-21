package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.PresetSelectionMode
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AutomationCoordinatorTest {
    private val action = QuestAction.Claim("q", "a")

    @Test
    fun `warnings do not block lower priority runnable and are retained`() {
        val coordinator = AutomationCoordinator(
            quest = questModule(QuestDirective.Hold("broken quest", "CONFIGURATION_WARNING")),
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
            quest = questModule(QuestDirective.WaitUntil(later, "COOLDOWN", "wait")),
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
    fun `stale quest progress keeps current work and requests an immediate recheck`() {
        val now = Instant.parse("2026-07-16T01:00:00Z")
        val coordinator = AutomationCoordinator(
            quest = questModule(QuestDirective.Recheck(now, "QUEST_PROGRESS_STALE", "recheck")),
            battle = AutomationHandler<BattleMapAutomationSnapshot> { HandlerEvaluation.Skipped },
            adventure = AutomationHandler<AdventureMapAutomationSnapshot> { HandlerEvaluation.Skipped },
        )

        val result = assertIs<AutomationCoordination.Unavailable>(coordinator.coordinate(
            AutomationCoordinatorSnapshot(
                listOf(AutomationCoordinatorEntry(1, AutomationType.QUEST, quest = questSnapshot())),
            ),
        ))

        assertEquals(now, result.nextRunAt)
        assertEquals(AutomationWaitScope.HOLD_CURRENT_WORK, result.waitScope)
    }

    @Test
    fun `quest resource wait is translated to an opaque work transition`() {
        val coordinator = AutomationCoordinator(
            quest = questModule(QuestDirective.WaitForResource("steel ingot", 2)),
            battle = AutomationHandler<BattleMapAutomationSnapshot> { HandlerEvaluation.Skipped },
            adventure = AutomationHandler<AdventureMapAutomationSnapshot> { HandlerEvaluation.Skipped },
        )

        val result = assertIs<AutomationCoordination.Idle>(coordinator.coordinate(
            AutomationCoordinatorSnapshot(
                listOf(AutomationCoordinatorEntry(1, AutomationType.QUEST, quest = questSnapshot())),
            ),
        ))

        assertEquals(
            AutomationWorkTransition.WaitForResource("steel ingot", 2),
            result.workTransition,
        )
        assertEquals(AutomationDecisionOutcome.WAITING, result.trace.single().outcome)
    }

    @Test
    fun `fatal stops immediately by priority`() {
        val coordinator = AutomationCoordinator(
            quest = questModule(QuestDirective.Fatal(AutomationStopReason.NETWORK, "fatal")),
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

    @Test
    fun `selected adventure trace explains target preset and observed criteria`() {
        val selected = AdventureMapAutomationAction(
            accountId = 7,
            categoryId = "0003",
            mapCode = "0004",
            presetMode = PresetSelectionMode.EXPLICIT,
            presetId = 13,
            settingIdentity = 21,
            executionIdentity = "execution",
            mapName = "사막의 살인적",
            observedAttemptRemaining = 4,
            observedWinRemaining = 2,
            observedAvailableCount = 2,
        )
        val coordinator = AutomationCoordinator(
            quest = questModule(QuestDirective.Skip),
            battle = AutomationHandler<BattleMapAutomationSnapshot> { HandlerEvaluation.Skipped },
            adventure = AutomationHandler<AdventureMapAutomationSnapshot> { HandlerEvaluation.Runnable(selected) },
        )

        val result = assertIs<AutomationCoordination.Runnable>(coordinator.coordinate(
            AutomationCoordinatorSnapshot(listOf(AutomationCoordinatorEntry(3, AutomationType.ADVENTURE_MAP, adventure = AdventureMapAutomationSnapshot(
                accountId = 7, settings = emptyList(), mapStates = emptyList(), presetResolutions = emptyMap(),
                executionIdentities = emptyMap(), evaluationInstant = Instant.EPOCH,
            )))),
        ))
        val trace = result.trace.single()

        assertEquals("0003/0004", trace.targetKey)
        assertEquals("사막의 살인적", trace.targetName)
        assertEquals(13, trace.presetId)
        assertEquals("BATTLE", trace.actionKind)
        kotlin.test.assertTrue(trace.message.contains("남은 도전 4회"))
        kotlin.test.assertTrue(trace.message.contains("남은 승리 2회"))
        kotlin.test.assertTrue(trace.message.contains("실행 가능 2회"))
    }

    private fun questSnapshot() = QuestAutomationSnapshot(7, emptyList(), emptyList(), emptyList(), emptyMap(), emptyMap(), emptyList(), Instant.EPOCH)
    private fun battleSnapshot() = BattleMapAutomationSnapshot(7, emptyList(), emptyList(), emptyMap(), null, emptySet(), "id", Instant.EPOCH)

    private fun questModule(directive: QuestDirective) = object : QuestWorkCycleModule {
        override fun decideNext(snapshot: QuestAutomationSnapshot): QuestDirective = directive

        override fun recordObservedResult(
            accountId: Long,
            attempt: QuestAttempt,
            observation: QuestResultObservation,
        ): QuestRecordResult = error("not used")
    }
}
