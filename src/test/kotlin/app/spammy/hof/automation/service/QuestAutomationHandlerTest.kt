package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.automation.entity.BattleAutomationDailyProgressEntity
import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.automation.repository.BattleAutomationDailyProgressCommandRepository
import app.spammy.hof.automation.repository.TypedAutomationQueryRepository
import app.spammy.hof.battle.service.BattleMapIdentityCandidate
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
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

class QuestAutomationHandlerTest {
    private val progress = RecordingProgressStore()
    private val handler = QuestAutomationHandler(progress)

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
    fun completableUnsupportedMissionDoesNotCauseAccept() {
        val result = handler.evaluate(snapshot(
            quests = listOf(
                quest("unsupported", QuestState.AVAILABLE, 0,
                    QuestMission("other", QuestMissionType.OTHER, null, null, completable = true)),
            ),
            selections = listOf(selection("unsupported")),
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
    fun laterRunnableMonsterMissionIsNotStarvedByEarlierWarningOrUnavailable() {
        val result = handler.evaluate(snapshot(
            quests = listOf(quest(
                "q", QuestState.ACTIVE, 0,
                monster("missing-config"),
                monster("cooldown"),
                monster("ready"),
            )),
            selections = listOf(selection("q", maps = listOf(
                map("cooldown", "cooldown-map", 0),
                map("ready", "ready-map", 0),
            ))),
            states = listOf(
                state("cooldown-map", cooldownUntil = NOW.plusSeconds(60)),
                state("ready-map"),
            ),
        ))

        assertEquals("ready", battle(result).missionKey)
    }

    @Test
    fun configurationWarningWinsOnlyAfterAllMonsterMissionsHaveNoRunnableAction() {
        val result = handler.evaluate(snapshot(
            quests = listOf(quest("q", QuestState.ACTIVE, 0, monster("cooldown"), monster("missing"))),
            selections = listOf(selection("q", maps = listOf(map("cooldown", "later", 0)))),
            states = listOf(state("later", cooldownUntil = NOW.plusSeconds(120))),
        ))

        assertIs<HandlerEvaluation.ConfigurationWarning>(result)
    }

    @Test
    fun invalidLowerCountMonsterPresetDoesNotHideValidRunnableAlternative() {
        val result = handler.evaluate(snapshot(
            quests = listOf(quest("q", QuestState.ACTIVE, 0, monster("kill"))),
            selections = listOf(selection("q", maps = listOf(
                map("kill", "invalid", 0, presetMode = PresetSelectionMode.EXPLICIT),
                map("kill", "valid", 1),
            ))),
            states = listOf(state("invalid"), state("valid")),
            cycles = mapOf("q" to "3"),
            counters = mapOf(
                counterKey("q", "3", "kill", "invalid") to 0,
                counterKey("q", "3", "kill", "valid") to 9,
            ),
        ))

        assertEquals("valid", battle(result).mapCode)
    }

    @Test
    fun laterRunnableManualMapWinsWhenEarlierManualRowsAreInvalidOrBlocked() {
        val result = handler.evaluate(snapshot(
            quests = listOf(quest("q", QuestState.ACTIVE, 0, mapClear("clear", "automatic"))),
            selections = listOf(selection("q", maps = listOf(
                map("clear", "invalid", 0, manual = true, presetMode = PresetSelectionMode.EXPLICIT),
                map("clear", "blocked", 1, manual = true),
                map("clear", "ready", 2, manual = true),
            ))),
            states = listOf(state("invalid"), state("blocked", keyCount = 0), state("ready")),
            identities = listOf(identity("automatic", "automatic")),
        ))

        assertEquals("ready", battle(result).mapCode)
    }

    @Test
    fun laterRunnableMapClearMissionIsNotStarvedByEarlierConfigurationWarning() {
        val result = handler.evaluate(snapshot(
            quests = listOf(quest(
                "q", QuestState.ACTIVE, 0,
                mapClear("missing", "missing target"),
                mapClear("ready", "ready target"),
            )),
            selections = listOf(selection("q")),
            states = listOf(state("ready-map")),
            identities = listOf(identity("ready-map", "ready target")),
        ))

        assertEquals("ready", battle(result).missionKey)
    }

    @Test
    fun missingOrAmbiguousAutomaticMapReturnsWarning() {
        listOf("missing", "ambiguous").forEach { target ->
            val result = handler.evaluate(snapshot(
                quests = listOf(quest("q", QuestState.ACTIVE, 0, mapClear("clear", target))),
                selections = listOf(selection("q")),
                identities = if (target == "ambiguous") listOf(
                    identity("first", target),
                    identity("second", target),
                ) else emptyList(),
            ))
            assertIs<HandlerEvaluation.ConfigurationWarning>(result)
        }
    }

    @Test
    fun repeatedEvaluationIsDeterministicAndDoesNotMutateSnapshot() {
        val context = snapshot(
            quests = listOf(quest("q", QuestState.ACTIVE, 0, mapClear("clear", "Frost / 서리 숲"))),
            selections = listOf(selection("q")),
            states = listOf(state("frost")),
            identities = listOf(identity("frost", "Frost - 서리 숲")),
        )
        val original = context.copy()

        val first = handler.evaluate(context)
        val second = handler.evaluate(context)

        assertEquals(first, second)
        assertEquals("frost", battle(first).mapCode)
        assertEquals(original, context)
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
        assertEquals("1", handler.onAcceptSucceeded(ACCOUNT_ID, "accept-result-1", first))
        assertEquals("2", handler.onAcceptSucceeded(ACCOUNT_ID, "accept-result-2", first))

        val action = QuestAction.Battle("q", "2", "kill", QuestMissionType.MONSTER_KILL, "battle_map", "chosen", "Chosen", QuestPresetSelection(PresetSelectionMode.PRIMARY), 1)
        handler.onBattleCompleted(ACCOUNT_ID, "battle-result-1", action, QuestBattleOutcome.VICTORY)
        handler.onBattleCompleted(ACCOUNT_ID, "battle-result-2", action, QuestBattleOutcome.DEFEAT)
        handler.onBattleCompleted(ACCOUNT_ID, "battle-result-3", action, QuestBattleOutcome.NETWORK_FAILURE)

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

        val newCycle = handler.onAcceptSucceeded(ACCOUNT_ID, "repeat-result", QuestAction.Accept("q", "accept"))
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
        identities: List<BattleMapIdentityCandidate> = emptyList(),
    ) = QuestAutomationSnapshot(ACCOUNT_ID, quests, selections, states, cycles, counters, identities, NOW)

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

    private fun state(code: String, keyCount: Int? = null, cooldownUntil: Instant? = null) = AutomationMapState(
        "battle_map", code, code, true, true, cooldownUntil, null, null, null, keyCount,
    )

    private fun counterKey(quest: String, cycle: String, mission: String, map: String) =
        QuestCounterKey(quest, cycle, mission, "battle_map", map)

    private fun identity(code: String, vararg aliases: String) =
        BattleMapIdentityCandidate("battle_map", code, code, aliases.toSet())

    private class RecordingProgressStore : QuestAutomationProgressStore {
        val cycles = mutableMapOf<Pair<Long, String>, String>()
        val victories = mutableListOf<QuestAction.Battle>()
        override fun startNewCycle(accountId: Long, resultId: String, questCode: String): String {
            val key = accountId to questCode
            return ((cycles[key]?.toLongOrNull() ?: 0) + 1).toString().also { cycles[key] = it }
        }
        override fun recordVictory(accountId: Long, resultId: String, action: QuestAction.Battle) { victories += action }
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
        assertEquals("1", progressStore.startNewCycle(account.id, "accept-1", "q"))
        assertEquals("2", progressStore.startNewCycle(account.id, "accept-2", "q"))
        val action = QuestAction.Battle(
            "q", "2", "kill", QuestMissionType.MONSTER_KILL, "battle_map", "chosen", "Chosen",
            QuestPresetSelection(PresetSelectionMode.PRIMARY), 1,
        )

        val pool = Executors.newFixedThreadPool(4)
        val futures = List(12) {
            pool.submit { progressStore.recordVictory(account.id, "duplicate-victory", action) }
        }
        pool.shutdown()
        check(pool.awaitTermination(20, TimeUnit.SECONDS))
        futures.forEach { it.get() }

        assertEquals(1, queryRepository.findQuestMapWins(account.id, "q", "2", "kill", "battle_map", "chosen"))
        assertEquals(23, queryRepository.findBattleWins(account.id, LocalDate.parse("2026-07-15"), "battle_map", "chosen"))
    }

    @Test
    fun concurrentDuplicateAcceptResultAdvancesCycleExactlyOnce() {
        val account = accountRepository.save(
            HofAccountEntity(loginId = "quest-accept-${System.nanoTime()}", encryptedPassword = "encrypted", createdAt = NOW),
        )
        val pool = Executors.newFixedThreadPool(4)
        val futures = List(12) {
            pool.submit<String> { progressStore.startNewCycle(account.id, "same-accept-result", "repeat-q") }
        }
        pool.shutdown()
        check(pool.awaitTermination(20, TimeUnit.SECONDS))

        assertEquals(setOf("1"), futures.map { it.get() }.toSet())
        assertEquals(1L, queryRepository.findQuestCycle(account.id, "repeat-q")?.currentCycle)
    }

    @Test
    fun rejectsBlankAndOversizedResultIdentitiesBeforeMutation() {
        val account = accountRepository.save(
            HofAccountEntity(loginId = "quest-invalid-id-${System.nanoTime()}", encryptedPassword = "encrypted", createdAt = NOW),
        )

        assertFailsWith<IllegalArgumentException> { progressStore.startNewCycle(account.id, "  ", "q") }
        assertFailsWith<IllegalArgumentException> {
            progressStore.startNewCycle(account.id, "x".repeat(129), "q")
        }
        assertEquals(null, queryRepository.findQuestCycle(account.id, "q"))
    }

    @Test
    fun sameResultIdentityCannotBeReusedForAnotherAcceptQuest() {
        val account = accountRepository.save(
            HofAccountEntity(loginId = "quest-accept-conflict-${System.nanoTime()}", encryptedPassword = "encrypted", createdAt = NOW),
        )
        assertEquals("1", progressStore.startNewCycle(account.id, "shared-result", "first-q"))

        assertFailsWith<QuestAutomationResultConflictException> {
            progressStore.startNewCycle(account.id, "shared-result", "second-q")
        }

        assertEquals(1L, queryRepository.findQuestCycle(account.id, "first-q")?.currentCycle)
        assertEquals(null, queryRepository.findQuestCycle(account.id, "second-q"))
    }

    @Test
    fun sameResultIdentityCannotBeReusedForAnotherBattlePayloadOrKind() {
        val account = accountRepository.save(
            HofAccountEntity(loginId = "quest-battle-conflict-${System.nanoTime()}", encryptedPassword = "encrypted", createdAt = NOW),
        )
        val first = battleAction(cycle = "1", mapCode = "first-map")
        progressStore.recordVictory(account.id, "battle-result", first)

        assertFailsWith<QuestAutomationResultConflictException> {
            progressStore.recordVictory(account.id, "battle-result", battleAction(cycle = "2", mapCode = "first-map"))
        }
        assertFailsWith<QuestAutomationResultConflictException> {
            progressStore.recordVictory(account.id, "battle-result", battleAction(cycle = "1", mapCode = "second-map"))
        }
        assertFailsWith<QuestAutomationResultConflictException> {
            progressStore.startNewCycle(account.id, "battle-result", "q")
        }

        assertEquals(1, queryRepository.findQuestMapWins(account.id, "q", "1", "kill", "battle_map", "first-map"))
        assertEquals(0, queryRepository.findQuestMapWins(account.id, "q", "2", "kill", "battle_map", "second-map"))
        assertEquals(null, queryRepository.findQuestCycle(account.id, "q"))
    }

    private fun battleAction(cycle: String, mapCode: String) = QuestAction.Battle(
        "q", cycle, "kill", QuestMissionType.MONSTER_KILL, "battle_map", mapCode, mapCode,
        QuestPresetSelection(PresetSelectionMode.PRIMARY), 1,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-15T00:00:00Z")
    }
}
