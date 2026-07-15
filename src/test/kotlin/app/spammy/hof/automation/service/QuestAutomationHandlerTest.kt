package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.automation.entity.BattleAutomationDailyProgressEntity
import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.automation.policy.AutomationMapState
import app.spammy.hof.automation.repository.BattleAutomationDailyProgressCommandRepository
import app.spammy.hof.automation.repository.TypedAutomationQueryRepository
import app.spammy.hof.battle.service.BattleMapAliasResolution
import app.spammy.hof.quest.model.QuestMission
import app.spammy.hof.quest.model.QuestMissionType
import app.spammy.hof.quest.model.QuestSection
import app.spammy.hof.quest.model.QuestSnapshot
import app.spammy.hof.quest.model.QuestState
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

class QuestAutomationHandlerTest {
    private val resolver = QuestMapTargetResolver { _, target ->
        when (target) {
            "manual target" -> BattleMapAliasResolution.Resolved("battle_map", "auto-map", "Auto")
            "missing" -> BattleMapAliasResolution.Missing
            "ambiguous" -> BattleMapAliasResolution.Ambiguous
            else -> BattleMapAliasResolution.Resolved("battle_map", "clear-map", "Clear")
        }
    }
    private val progress = RecordingProgressStore()
    private val handler = QuestAutomationHandler(resolver, progress)

    @Test
    fun claimableBeatsAvailableAndProducesClaimAction() {
        val result = handler.evaluate(snapshot(
            quests = listOf(
                quest("available", QuestState.AVAILABLE, 0, immediate()),
                quest("claimable", QuestState.CLAIMABLE, 1, immediate()),
            ),
            selections = listOf(selection("available"), selection("claimable")),
        ))

        assertEquals("claimable", assertIs<QuestAction.Claim>(assertIs<HandlerEvaluation.Runnable>(result).action).questCode)
    }

    @Test
    fun immediatelyCompletableAcceptBeatsCombat() {
        val result = handler.evaluate(snapshot(
            quests = listOf(
                quest("combat", QuestState.ACTIVE, 0, monster("kill")),
                quest("turn-in", QuestState.AVAILABLE, 1, item(completable = true)),
            ),
            selections = listOf(
                selection("combat", maps = listOf(map("kill", "combat-map", 0))),
                selection("turn-in"),
            ),
            states = listOf(state("combat-map")),
        ))

        assertEquals("turn-in", assertIs<QuestAction.Accept>(assertIs<HandlerEvaluation.Runnable>(result).action).questCode)
    }

    @Test
    fun monsterBalancesByCurrentCycleCountThenExecutionOrder() {
        val base = snapshot(
            quests = listOf(quest("q", QuestState.ACTIVE, 0, monster("kill"))),
            selections = listOf(selection("q", maps = listOf(map("kill", "map-a", 0), map("kill", "map-b", 1)))),
            states = listOf(state("map-a"), state("map-b")),
            cycles = mapOf("q" to "7"),
            counters = mapOf(counterKey("q", "7", "kill", "map-a") to 4, counterKey("q", "7", "kill", "map-b") to 3),
        )
        val lowerCount = battle(handler.evaluate(base))
        assertEquals("map-b", lowerCount.mapCode)
        assertEquals(1, lowerCount.battleCount)

        val equal = battle(handler.evaluate(base.copy(counters = base.counters.mapValues { 4 })))
        assertEquals("map-a", equal.mapCode)
    }

    @Test
    fun manualOverrideWinsInsteadOfAutomaticAlias() {
        val result = handler.evaluate(snapshot(
            quests = listOf(quest("q", QuestState.ACTIVE, 0, mapClear("clear", "manual target"))),
            selections = listOf(selection("q", maps = listOf(map("clear", "manual-map", 0, manual = true)))),
            states = listOf(state("manual-map"), state("auto-map")),
        ))

        assertEquals("manual-map", battle(result).mapCode)
    }

    @Test
    fun incompleteItemOnlyQuestIsSkipped() {
        val result = handler.evaluate(snapshot(
            quests = listOf(quest("q", QuestState.AVAILABLE, 0, item(completable = false))),
            selections = listOf(selection("q")),
        ))

        assertIs<HandlerEvaluation.Skipped>(result)
    }

    @Test
    fun monsterPrecedesMapClearEvenWhenMapClearAppearsFirst() {
        val result = handler.evaluate(snapshot(
            quests = listOf(
                quest("clear", QuestState.ACTIVE, 0, mapClear("clear-mission", "clear target")),
                quest("monster", QuestState.ACTIVE, 1, monster("kill")),
            ),
            selections = listOf(
                selection("clear"),
                selection("monster", maps = listOf(map("kill", "monster-map", 0))),
            ),
            states = listOf(state("clear-map"), state("monster-map")),
        ))

        assertEquals("monster", battle(result).questCode)
    }

    @Test
    fun unrunnableLowestCountMapIsFiltered() {
        val result = handler.evaluate(snapshot(
            quests = listOf(quest("q", QuestState.ACTIVE, 0, monster("kill"))),
            selections = listOf(selection("q", maps = listOf(map("kill", "blocked", 0), map("kill", "ready", 1)))),
            states = listOf(state("blocked", keyCount = 0), state("ready")),
            cycles = mapOf("q" to "2"),
            counters = mapOf(counterKey("q", "2", "kill", "blocked") to 0, counterKey("q", "2", "kill", "ready") to 8),
        ))

        assertEquals("ready", battle(result).mapCode)
    }

    @Test
    fun missingOrAmbiguousAutomaticMapReturnsWarning() {
        listOf("missing", "ambiguous").forEach { target ->
            val result = handler.evaluate(snapshot(
                quests = listOf(quest("q", QuestState.ACTIVE, 0, mapClear("clear", target))),
                selections = listOf(selection("q")),
            ))
            assertIs<HandlerEvaluation.ConfigurationWarning>(result)
        }
    }

    @Test
    fun presetModeIsPreservedWithoutResolvingPrimary() {
        val primary = battle(handler.evaluate(combatSnapshot(map("kill", "map", 0, presetMode = PresetSelectionMode.PRIMARY))))
        assertEquals(PresetSelectionMode.PRIMARY, primary.preset.mode)
        assertEquals(null, primary.preset.presetId)

        val explicit = battle(handler.evaluate(combatSnapshot(map("kill", "map", 0, presetMode = PresetSelectionMode.EXPLICIT, presetId = 41))))
        assertEquals(QuestPresetSelection(PresetSelectionMode.EXPLICIT, 41), explicit.preset)
    }

    @Test
    fun invalidExplicitPresetReturnsWarningWithoutFallback() {
        val result = handler.evaluate(combatSnapshot(map("kill", "map", 0, presetMode = PresetSelectionMode.EXPLICIT)))
        assertIs<HandlerEvaluation.ConfigurationWarning>(result)
    }

    @Test
    fun acceptSuccessStartsNewCycleAndOnlyVictoryIncrementsChosenMap() {
        val first = QuestAction.Accept("q", "accept")
        assertEquals("1", handler.onAcceptSucceeded(ACCOUNT_ID, first))
        assertEquals("2", handler.onAcceptSucceeded(ACCOUNT_ID, first))

        val action = QuestAction.Battle("q", "2", "kill", QuestMissionType.MONSTER_KILL, "battle_map", "chosen", "Chosen", QuestPresetSelection(PresetSelectionMode.PRIMARY), 1)
        handler.onBattleCompleted(ACCOUNT_ID, action, QuestBattleOutcome.VICTORY)
        handler.onBattleCompleted(ACCOUNT_ID, action, QuestBattleOutcome.DEFEAT)
        handler.onBattleCompleted(ACCOUNT_ID, action, QuestBattleOutcome.NETWORK_FAILURE)

        assertEquals(listOf(action), progress.victories)
    }

    @Test
    fun repeatedQuestRetainsSelectionButNewCycleStartsBalancingAtZero() {
        val selection = selection("q", maps = listOf(map("kill", "first", 0), map("kill", "second", 1)))
        progress.cycles[ACCOUNT_ID to "q"] = "8"
        val old = snapshot(
            quests = listOf(quest("q", QuestState.ACTIVE, 0, monster("kill"))),
            selections = listOf(selection),
            states = listOf(state("first"), state("second")),
            cycles = mapOf("q" to "8"),
            counters = mapOf(counterKey("q", "8", "kill", "first") to 9, counterKey("q", "8", "kill", "second") to 2),
        )
        assertEquals("second", battle(handler.evaluate(old)).mapCode)

        val newCycle = handler.onAcceptSucceeded(ACCOUNT_ID, QuestAction.Accept("q", "accept"))
        assertEquals("9", newCycle)
        assertEquals("first", battle(handler.evaluate(old.copy(currentCycles = mapOf("q" to newCycle), counters = emptyMap()))).mapCode)
    }

    private fun combatSnapshot(map: QuestAutomationMapSelection) = snapshot(
        quests = listOf(quest("q", QuestState.ACTIVE, 0, monster("kill"))),
        selections = listOf(selection("q", maps = listOf(map))),
        states = listOf(state(map.mapCode)),
    )

    private fun battle(result: HandlerEvaluation) =
        assertIs<QuestAction.Battle>(assertIs<HandlerEvaluation.Runnable>(result).action)

    private fun snapshot(
        quests: List<QuestSnapshot>,
        selections: List<QuestAutomationSelection>,
        states: List<AutomationMapState> = emptyList(),
        cycles: Map<String, String> = emptyMap(),
        counters: Map<QuestCounterKey, Int> = emptyMap(),
    ) = QuestAutomationSnapshot(ACCOUNT_ID, quests, selections, states, cycles, counters, NOW)

    private fun quest(code: String, state: QuestState, order: Int, vararg missions: QuestMission) =
        QuestSnapshot(code, code, state, if (state == QuestState.AVAILABLE) QuestSection.AVAILABLE else QuestSection.ACTIVE, order, missions.toList(), "$code-action")

    private fun immediate() = QuestMission("immediate", QuestMissionType.IMMEDIATE, null, null, true)
    private fun item(completable: Boolean) = QuestMission("item", QuestMissionType.ITEM_TURN_IN, null, null, completable)
    private fun monster(key: String) = QuestMission(key, QuestMissionType.MONSTER_KILL, "monster", null, false)
    private fun mapClear(key: String, target: String) = QuestMission(key, QuestMissionType.MAP_CLEAR, target, null, false)

    private fun selection(code: String, enabled: Boolean = true, maps: List<QuestAutomationMapSelection> = emptyList()) =
        QuestAutomationSelection(code, enabled, maps)

    private fun map(
        mission: String,
        code: String,
        order: Int,
        manual: Boolean = false,
        presetMode: PresetSelectionMode = PresetSelectionMode.PRIMARY,
        presetId: Long? = null,
    ) = QuestAutomationMapSelection(mission, "battle_map", code, code, QuestPresetSelection(presetMode, presetId), order, manual)

    private fun state(code: String, keyCount: Int? = null) = AutomationMapState(
        "battle_map", code, code, true, true, null, null, null, null, keyCount,
    )

    private fun counterKey(quest: String, cycle: String, mission: String, map: String) =
        QuestCounterKey(quest, cycle, mission, "battle_map", map)

    private class RecordingProgressStore : QuestAutomationProgressStore {
        val cycles = mutableMapOf<Pair<Long, String>, String>()
        val victories = mutableListOf<QuestAction.Battle>()
        override fun startNewCycle(accountId: Long, questCode: String): String {
            val key = accountId to questCode
            return ((cycles[key]?.toLongOrNull() ?: 0) + 1).toString().also { cycles[key] = it }
        }
        override fun recordVictory(accountId: Long, action: QuestAction.Battle) { victories += action }
    }

    private companion object {
        const val ACCOUNT_ID = 17L
        val NOW: Instant = Instant.parse("2026-07-15T00:00:00Z")
    }
}

@SpringBootTest
@ActiveProfiles("test")
class QuestAutomationProgressStorePersistenceTest {
    @Autowired private lateinit var accountRepository: HofAccountRepository
    @Autowired private lateinit var progressStore: JpaQuestAutomationProgressStore
    @Autowired private lateinit var queryRepository: TypedAutomationQueryRepository
    @Autowired private lateinit var dailyRepository: BattleAutomationDailyProgressCommandRepository

    @Test
    fun cyclesAdvanceAndConcurrentVictoriesIncrementOnlyQuestCounter() {
        val account = accountRepository.save(
            HofAccountEntity(loginId = "quest-progress-${System.nanoTime()}", encryptedPassword = "encrypted", createdAt = NOW),
        )
        dailyRepository.save(
            BattleAutomationDailyProgressEntity(
                account = account,
                progressDate = LocalDate.parse("2026-07-15"),
                categoryId = "battle_map",
                mapCode = "chosen",
                source = "battle_map",
                successfulRuns = 23,
                updatedAt = NOW,
            ),
        )
        assertEquals("1", progressStore.startNewCycle(account.id, "q"))
        assertEquals("2", progressStore.startNewCycle(account.id, "q"))
        val action = QuestAction.Battle(
            "q", "2", "kill", QuestMissionType.MONSTER_KILL, "battle_map", "chosen", "Chosen",
            QuestPresetSelection(PresetSelectionMode.PRIMARY), 1,
        )

        val pool = Executors.newFixedThreadPool(4)
        repeat(12) { pool.submit { progressStore.recordVictory(account.id, action) } }
        pool.shutdown()
        check(pool.awaitTermination(20, TimeUnit.SECONDS))

        assertEquals(12, queryRepository.findQuestMapWins(account.id, "q", "2", "kill", "battle_map", "chosen"))
        assertEquals(23, queryRepository.findBattleWins(account.id, LocalDate.parse("2026-07-15"), "battle_map", "chosen"))
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-15T00:00:00Z")
    }
}
