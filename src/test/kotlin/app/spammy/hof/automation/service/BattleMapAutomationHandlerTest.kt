package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.automation.repository.TypedAutomationQueryRepository
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

class BattleMapAutomationHandlerTest {
    private val progressStore = RecordingBattleProgressStore()
    private val handler = BattleMapAutomationHandler(progressStore)

    @Test
    fun selectsThreeBattlesForRemainingTwoOrMoreAndOneForTheLastRun() {
        assertEquals(3, runnable(target = 10, progress = 8, supportsThree = true).battleCount)
        assertEquals(1, runnable(target = 10, progress = 9, supportsThree = true).battleCount)
        assertEquals(1, runnable(target = 10, progress = 4, supportsThree = false).battleCount)
    }

    @Test
    fun remainingTwoIntentionallySelectsThreeBattles() {
        assertEquals(3, runnable(target = 10, progress = 8, supportsThree = true).battleCount)
    }

    @Test
    fun completedInvalidAndBlockedSettingsDoNotHideALaterRunnableSetting() {
        val context = snapshot(
            settings = listOf(
                setting("complete", target = 2, order = 0),
                setting("bad-preset", target = 2, order = 1, preset = BattleMapPresetSelection(PresetSelectionMode.EXPLICIT)),
                setting("blocked", target = 2, order = 2),
                setting("later", target = 2, order = 3),
            ),
            progress = mapOf("complete" to 2),
            states = listOf(
                state("complete"),
                state("bad-preset"),
                state("blocked", visible = false),
                state("later"),
            ),
        )

        val action = assertIs<BattleMapAutomationAction>(assertIs<HandlerEvaluation.Runnable>(handler.evaluate(context)).action)
        assertEquals("later", action.mapCode)
    }

    @Test
    fun countsOnlyVictoryRoundsForCanonicalBattleMapAutomationResults() {
        val action = runnable(target = 10, progress = 0, supportsThree = true)
        listOf(0, 1, 2, 3).forEach { victoryCount ->
            handler.onBattleCompleted(
                resultIdentity = "result-$victoryCount",
                action = action.copy(executionIdentity = "execution-$victoryCount"),
                source = BattleAutomationActionSource.BATTLE_MAP_AUTOMATION,
                outcomes = List(victoryCount) { BattleAutomationRoundOutcome.VICTORY } +
                    List(3 - victoryCount) { BattleAutomationRoundOutcome.DEFEAT },
            )
        }
        handler.onBattleCompleted(
            "network",
            action.copy(executionIdentity = "network-execution"),
            BattleAutomationActionSource.BATTLE_MAP_AUTOMATION,
            listOf(BattleAutomationRoundOutcome.NETWORK_FAILURE),
        )
        handler.onBattleCompleted(
            "quest",
            action.copy(executionIdentity = "quest-execution"),
            BattleAutomationActionSource.QUEST_AUTOMATION,
            listOf(BattleAutomationRoundOutcome.VICTORY),
        )
        handler.onBattleCompleted(
            "adventure",
            action.copy(executionIdentity = "adventure-execution"),
            BattleAutomationActionSource.ADVENTURE_AUTOMATION,
            listOf(BattleAutomationRoundOutcome.VICTORY),
        )

        assertEquals(listOf(0, 1, 2, 3, 0), progressStore.recorded.map { it.victories })
    }

    @Test
    fun ambiguousOutcomeIsAppliedWhenProvenAndFatalWithoutChangingProgressWhenUnproven() {
        val action = runnable(target = 10, progress = 0, supportsThree = true)
        val proven = handler.reconcileAmbiguousOutcome(action) {
            BattleOutcomeReconciliation.Proven("authoritative-result", listOf(BattleAutomationRoundOutcome.VICTORY))
        }
        val unproven = handler.reconcileAmbiguousOutcome(action.copy(executionIdentity = "other-execution")) {
            BattleOutcomeReconciliation.Unproven("No exact recent battle matches the prepared execution.")
        }

        assertIs<BattleOutcomeReconciliation.Applied>(proven)
        val fatal = assertIs<BattleOutcomeReconciliation.Fatal>(unproven)
        assertEquals(AutomationStopReason.NETWORK, fatal.evaluation.reason)
        assertEquals(1, progressStore.recorded.sumOf { it.victories })
    }

    @Test
    fun targetEditsReuseSameDayProgressInsteadOfResettingIt() {
        val lower = handler.evaluate(snapshot(listOf(setting("map", 4)), mapOf("map" to 5), listOf(state("map"))))
        val higher = handler.evaluate(snapshot(listOf(setting("map", 8)), mapOf("map" to 5), listOf(state("map"))))

        assertIs<HandlerEvaluation.Skipped>(lower)
        assertEquals(3, assertIs<BattleMapAutomationAction>(assertIs<HandlerEvaluation.Runnable>(higher).action).battleCount)
    }

    private fun runnable(target: Int, progress: Int, supportsThree: Boolean): BattleMapAutomationAction {
        val result = handler.evaluate(
            snapshot(
                settings = listOf(setting("map", target = target)),
                progress = mapOf("map" to progress),
                states = listOf(state("map", supportsThree = supportsThree)),
            ),
        )
        return assertIs<BattleMapAutomationAction>(assertIs<HandlerEvaluation.Runnable>(result).action)
    }

    private fun snapshot(
        settings: List<BattleMapAutomationSetting>,
        progress: Map<String, Int>,
        states: List<BattleMapRunnableState>,
    ) = BattleMapAutomationSnapshot(
        accountId = 7,
        progressDate = LocalDate.of(2026, 7, 15),
        settings = settings,
        mapStates = states,
        successfulRuns = progress.mapKeys { (mapCode) -> BattleMapProgressIdentity("battle_map", mapCode) },
        primaryPresetId = 10,
        availablePresetIds = setOf(10L, 20L),
        executionIdentity = "execution-1",
    )

    private fun setting(
        mapCode: String,
        target: Int,
        order: Int = 0,
        preset: BattleMapPresetSelection = BattleMapPresetSelection(PresetSelectionMode.PRIMARY),
    ) = BattleMapAutomationSetting(true, "battle_map", mapCode, target, preset, order)

    private fun state(
        mapCode: String,
        visible: Boolean = true,
        supportsThree: Boolean = true,
    ) = BattleMapRunnableState("battle_map", mapCode, visible, enabled = true, supportsThreeBattles = supportsThree)
}

@SpringBootTest
@ActiveProfiles("test")
class BattleMapAutomationProgressStorePersistenceTest {
    @Autowired private lateinit var accountRepository: HofAccountRepository
    @Autowired private lateinit var progressStore: JpaBattleMapAutomationProgressStore
    @Autowired private lateinit var queryRepository: TypedAutomationQueryRepository

    @Test
    fun concurrentReplayCountsOnceDifferentResultsCountAndConflictingReuseIsRejected() {
        val account = accountRepository.save(
            HofAccountEntity(loginId = "battle-progress-${System.nanoTime()}", encryptedPassword = "encrypted", createdAt = NOW),
        )
        val action = action(account.id, "execution-1")
        val pool = Executors.newFixedThreadPool(4)
        val futures = List(10) {
            pool.submit { progressStore.recordResult(action, "same-result", "a".repeat(64), 2) }
        }
        pool.shutdown()
        check(pool.awaitTermination(20, TimeUnit.SECONDS))
        futures.forEach { it.get() }
        assertEquals(2, queryRepository.findBattleWins(account.id, DATE, "battle_map", "map"))

        progressStore.recordResult(action(account.id, "execution-2"), "different-result", "b".repeat(64), 1)
        assertEquals(3, queryRepository.findBattleWins(account.id, DATE, "battle_map", "map"))

        assertFailsWith<BattleMapAutomationResultConflictException> {
            progressStore.recordResult(action(account.id, "conflicting-execution"), "same-result", "c".repeat(64), 3)
        }
        assertEquals(3, queryRepository.findBattleWins(account.id, DATE, "battle_map", "map"))
    }

    private fun action(accountId: Long, executionIdentity: String) = BattleMapAutomationAction(
        accountId, DATE, "battle_map", "map", 10, 3, executionIdentity,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-15T00:00:00Z")
        val DATE: LocalDate = LocalDate.parse("2026-07-15")
    }
}

private class RecordingBattleProgressStore : BattleMapAutomationProgressStore {
    val recorded = mutableListOf<Recorded>()

    override fun recordResult(
        action: BattleMapAutomationAction,
        resultIdentity: String,
        outcomeFingerprint: String,
        victories: Int,
    ) {
        recorded += Recorded(action, resultIdentity, outcomeFingerprint, victories)
    }

    data class Recorded(
        val action: BattleMapAutomationAction,
        val resultIdentity: String,
        val outcomeFingerprint: String,
        val victories: Int,
    )
}
