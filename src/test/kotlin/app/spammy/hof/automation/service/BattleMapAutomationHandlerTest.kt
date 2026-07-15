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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
    fun executionOrderTiesUseStableCategoryAndMapIdentity() {
        val result = handler.evaluate(snapshot(
            settings = listOf(setting("z-map", 1, order = 4), setting("a-map", 1, order = 4)),
            progress = emptyMap(),
            states = listOf(state("z-map"), state("a-map")),
        ))

        assertEquals("a-map", assertIs<BattleMapAutomationAction>(assertIs<HandlerEvaluation.Runnable>(result).action).mapCode)
    }

    @Test
    fun countsZeroThroughThreeVictoriesOnlyFromCompleteTerminalResults() {
        val action = runnable(target = 10, progress = 0, supportsThree = true)
        listOf(0, 1, 2, 3).forEach { victoryCount ->
            handler.onBattleCompleted(
                action = action.copy(executionIdentity = "execution-$victoryCount"),
                source = BattleAutomationActionSource.BATTLE_MAP_AUTOMATION,
                resultIdentity = "result-$victoryCount",
                outcomes = List(victoryCount) { BattleAutomationRoundOutcome.VICTORY } +
                    List(3 - victoryCount) { BattleAutomationRoundOutcome.DEFEAT },
                reconciler = unprovenReconciler(),
            )
        }
        handler.onBattleCompleted(
            action.copy(executionIdentity = "quest-execution"),
            BattleAutomationActionSource.QUEST_AUTOMATION,
            "quest",
            listOf(BattleAutomationRoundOutcome.VICTORY),
            unprovenReconciler(),
        )
        handler.onBattleCompleted(
            action.copy(executionIdentity = "adventure-execution"),
            BattleAutomationActionSource.ADVENTURE_AUTOMATION,
            "adventure",
            listOf(BattleAutomationRoundOutcome.VICTORY),
            unprovenReconciler(),
        )

        assertEquals(listOf(0, 1, 2, 3), progressStore.recorded.map { it.evidence.victoryCount })
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 4])
    fun incompleteOrExtraRoundArraysNeverWrite(size: Int) {
        val action = runnable(target = 10, progress = 0, supportsThree = true)
        val result = handler.onBattleCompleted(
            action,
            BattleAutomationActionSource.BATTLE_MAP_AUTOMATION,
            "partial-$size",
            List(size) { BattleAutomationRoundOutcome.VICTORY },
            unprovenReconciler(),
        )

        assertIs<BattleOutcomeResolution.Fatal>(result)
        assertEquals(emptyList(), progressStore.recorded)
    }

    @Test
    fun networkOrUnknownRoundNeverWritesAndLaterProvenCompleteEvidenceRepairsOnce() {
        val action = runnable(target = 10, progress = 0, supportsThree = true)
        listOf(
            BattleAutomationRoundOutcome.DRAW,
            BattleAutomationRoundOutcome.NETWORK_FAILURE,
            BattleAutomationRoundOutcome.UNKNOWN,
        ).forEach { invalid ->
            val result = handler.onBattleCompleted(
                action,
                BattleAutomationActionSource.BATTLE_MAP_AUTOMATION,
                "invalid-${invalid.name}",
                listOf(BattleAutomationRoundOutcome.VICTORY, invalid, BattleAutomationRoundOutcome.DEFEAT),
                unprovenReconciler(),
            )
            assertIs<BattleOutcomeResolution.Fatal>(result)
        }

        val evidence = evidence(
            action,
            "authoritative-result",
            listOf(BattleAutomationRoundOutcome.VICTORY, BattleAutomationRoundOutcome.DEFEAT, BattleAutomationRoundOutcome.VICTORY),
        )
        val repaired = handler.onBattleCompleted(
            action,
            BattleAutomationActionSource.BATTLE_MAP_AUTOMATION,
            "partial-result",
            listOf(BattleAutomationRoundOutcome.VICTORY),
        ) { BattleOutcomeReconciliation.Proven(evidence) }

        assertIs<BattleOutcomeResolution.Applied>(repaired)
        assertEquals(2, progressStore.recorded.single().evidence.victoryCount)
    }

    @ParameterizedTest
    @ValueSource(strings = ["account", "execution", "category", "map", "count"])
    fun mismatchedReconciliationEvidenceIsFatalAndNeverWrites(field: String) {
        val action = runnable(target = 10, progress = 0, supportsThree = true)
        val valid = evidence(action, "authoritative", List(3) { BattleAutomationRoundOutcome.VICTORY })
        val mismatch = when (field) {
            "account" -> valid.copy(accountId = action.accountId + 1)
            "execution" -> valid.copy(executionIdentity = "other")
            "category" -> valid.copy(categoryId = "other")
            "map" -> valid.copy(mapCode = "other")
            "count" -> valid.copy(battleCount = 1)
            else -> error(field)
        }
        val result = handler.onBattleCompleted(
            action,
            BattleAutomationActionSource.BATTLE_MAP_AUTOMATION,
            "partial",
            listOf(BattleAutomationRoundOutcome.VICTORY),
        ) { BattleOutcomeReconciliation.Proven(mismatch) }

        assertEquals(AutomationStopReason.NETWORK, assertIs<BattleOutcomeResolution.Fatal>(result).evaluation.reason)
        assertEquals(emptyList(), progressStore.recorded)
    }

    @Test
    fun targetEditsReuseSameDayProgressInsteadOfResettingIt() {
        val lower = handler.evaluate(snapshot(listOf(setting("map", 4)), mapOf("map" to 5), listOf(state("map"))))
        val higher = handler.evaluate(snapshot(listOf(setting("map", 8)), mapOf("map" to 5), listOf(state("map"))))

        assertIs<HandlerEvaluation.Skipped>(lower)
        assertEquals(3, assertIs<BattleMapAutomationAction>(assertIs<HandlerEvaluation.Runnable>(higher).action).battleCount)
    }

    @Test
    fun derivesActionProgressDateAtTheKoreaMidnightBoundary() {
        val before = handler.evaluate(snapshot(
            listOf(setting("map", 1)), emptyMap(), listOf(state("map")), Instant.parse("2026-07-15T14:59:59Z"),
        ))
        val after = handler.evaluate(snapshot(
            listOf(setting("map", 1)), emptyMap(), listOf(state("map")), Instant.parse("2026-07-15T15:00:01Z"),
        ))

        assertEquals("2026-07-15", assertIs<BattleMapAutomationAction>(assertIs<HandlerEvaluation.Runnable>(before).action).progressDate.toString())
        assertEquals("2026-07-16", assertIs<BattleMapAutomationAction>(assertIs<HandlerEvaluation.Runnable>(after).action).progressDate.toString())
    }

    @ParameterizedTest
    @ValueSource(strings = ["available", "attempt", "win", "key"])
    fun knownCapacityBelowThreeFallsBackToOneBattle(field: String) {
        val constrained = when (field) {
            "available" -> state("map", availableCount = 2)
            "attempt" -> state("map", attemptRemaining = 2)
            "win" -> state("map", winRemaining = 1)
            "key" -> state("map", keyCount = 2)
            else -> error(field)
        }
        val action = assertIs<BattleMapAutomationAction>(assertIs<HandlerEvaluation.Runnable>(handler.evaluate(
            snapshot(listOf(setting("map", 10)), emptyMap(), listOf(constrained)),
        )).action)

        assertEquals(1, action.battleCount)
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
        evaluationInstant: Instant = Instant.parse("2026-07-15T00:00:00Z"),
    ) = BattleMapAutomationSnapshot(
        accountId = 7,
        settings = settings,
        mapStates = states,
        successfulRuns = progress.mapKeys { (mapCode) -> BattleMapProgressIdentity("battle_map", mapCode) },
        primaryPresetId = 10,
        availablePresetIds = setOf(10L, 20L),
        executionIdentity = "execution-1",
        evaluationInstant = evaluationInstant,
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
        availableCount: Int? = null,
        attemptRemaining: Int? = null,
        winRemaining: Int? = null,
        keyCount: Int? = null,
    ) = BattleMapRunnableState(
        "battle_map", mapCode, visible, enabled = true, supportsThreeBattles = supportsThree,
        availableCount = availableCount, attemptRemaining = attemptRemaining, winRemaining = winRemaining, keyCount = keyCount,
    )

    private fun evidence(
        action: BattleMapAutomationAction,
        resultIdentity: String,
        outcomes: List<BattleAutomationRoundOutcome>,
    ) = BattleAuthoritativeOutcomeEvidence(
        action.accountId, action.executionIdentity, action.categoryId, action.mapCode,
        action.battleCount, resultIdentity, outcomes,
    )

    private fun unprovenReconciler() = BattleOutcomeReconciler {
        BattleOutcomeReconciliation.Unproven("No exact authoritative recent result.")
    }
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
            pool.submit { progressStore.recordResult(action, evidence(action, "same-result", victories = 2)) }
        }
        pool.shutdown()
        check(pool.awaitTermination(20, TimeUnit.SECONDS))
        futures.forEach { it.get() }
        assertEquals(2, queryRepository.findBattleWins(account.id, DATE, "battle_map", "map"))

        val second = action(account.id, "execution-2")
        progressStore.recordResult(second, evidence(second, "different-result", victories = 1))
        assertEquals(3, queryRepository.findBattleWins(account.id, DATE, "battle_map", "map"))

        assertFailsWith<BattleMapAutomationResultConflictException> {
            val conflict = action(account.id, "conflicting-execution")
            progressStore.recordResult(conflict, evidence(conflict, "same-result", victories = 3))
        }
        assertEquals(3, queryRepository.findBattleWins(account.id, DATE, "battle_map", "map"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["date", "category", "map", "preset-mode", "preset-id", "preset-null", "battle-count", "source"])
    fun replayingAnIdentityWithAnyChangedActionSemanticIsRejected(field: String) {
        val account = accountRepository.save(
            HofAccountEntity(loginId = "battle-fingerprint-$field-${System.nanoTime()}", encryptedPassword = "encrypted", createdAt = NOW),
        )
        val original = action(account.id, "semantic-execution")
        progressStore.recordResult(original, evidence(original, "semantic-result", victories = 1))
        val changed = when (field) {
            "date" -> original.copy(progressDate = DATE.plusDays(1))
            "category" -> original.copy(categoryId = "other-category")
            "map" -> original.copy(mapCode = "other-map")
            "preset-mode" -> original.copy(presetMode = PresetSelectionMode.EXPLICIT)
            "preset-id" -> original.copy(presetId = 20)
            "preset-null" -> original.copy(presetId = null)
            "battle-count" -> original.copy(battleCount = 1)
            "source" -> original.copy(source = BattleAutomationActionSource.QUEST_AUTOMATION)
            else -> error("Unknown field $field")
        }

        assertFailsWith<BattleMapAutomationResultConflictException> {
            progressStore.recordResult(changed, evidence(changed, "semantic-result", victories = 1))
        }
        assertEquals(1, queryRepository.findBattleWins(account.id, DATE, "battle_map", "map"))
    }

    @Test
    fun incompleteEvidenceCreatesNoLedgerAndCanLaterBeRepairedWithTheSameIdentities() {
        val account = accountRepository.save(
            HofAccountEntity(loginId = "battle-incomplete-${System.nanoTime()}", encryptedPassword = "encrypted", createdAt = NOW),
        )
        val action = action(account.id, "repairable-execution")
        val invalidEvidence = listOf(
            emptyList(),
            listOf(BattleAutomationRoundOutcome.VICTORY),
            listOf(BattleAutomationRoundOutcome.VICTORY, BattleAutomationRoundOutcome.DEFEAT),
            List(4) { BattleAutomationRoundOutcome.VICTORY },
            listOf(BattleAutomationRoundOutcome.VICTORY, BattleAutomationRoundOutcome.NETWORK_FAILURE, BattleAutomationRoundOutcome.DEFEAT),
        )
        invalidEvidence.forEach { outcomes ->
            assertFailsWith<IllegalArgumentException> {
                progressStore.recordResult(
                    action,
                    BattleAuthoritativeOutcomeEvidence(
                        action.accountId, action.executionIdentity, action.categoryId, action.mapCode,
                        action.battleCount, "repairable-result", outcomes,
                    ),
                )
            }
        }
        assertEquals(null, queryRepository.findBattleProcessedResult(account.id, "repairable-result"))
        assertEquals(0, queryRepository.findBattleWins(account.id, DATE, "battle_map", "map"))

        progressStore.recordResult(action, evidence(action, "repairable-result", victories = 2))

        assertEquals(2, queryRepository.findBattleWins(account.id, DATE, "battle_map", "map"))
    }

    private fun action(accountId: Long, executionIdentity: String) = BattleMapAutomationAction(
        accountId = accountId,
        progressDate = DATE,
        categoryId = "battle_map",
        mapCode = "map",
        presetMode = PresetSelectionMode.PRIMARY,
        presetId = 10,
        battleCount = 3,
        executionIdentity = executionIdentity,
    )

    private fun evidence(action: BattleMapAutomationAction, resultIdentity: String, victories: Int) =
        BattleAuthoritativeOutcomeEvidence(
            accountId = action.accountId,
            executionIdentity = action.executionIdentity,
            categoryId = action.categoryId,
            mapCode = action.mapCode,
            battleCount = action.battleCount,
            resultIdentity = resultIdentity,
            outcomes = List(victories) { BattleAutomationRoundOutcome.VICTORY } +
                List(action.battleCount - victories) { BattleAutomationRoundOutcome.DEFEAT },
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
        evidence: BattleAuthoritativeOutcomeEvidence,
    ) {
        recorded += Recorded(action, evidence)
    }

    data class Recorded(
        val action: BattleMapAutomationAction,
        val evidence: BattleAuthoritativeOutcomeEvidence,
    )
}
