package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.automation.entity.BattleAutomationDailyProgressEntity
import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.automation.repository.BattleAutomationDailyProgressCommandRepository
import app.spammy.hof.automation.repository.TypedAutomationQueryRepository
import app.spammy.hof.battle.service.BattleMapIdentityCandidate
import app.spammy.hof.battle.model.BattleMapKeyMode
import app.spammy.hof.quest.model.QuestMission
import app.spammy.hof.quest.model.QuestMissionType
import app.spammy.hof.quest.model.QuestProgress
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
import kotlin.test.assertNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

class QuestAutomationHandlerTest {
    private val progress = RecordingProgressStore()
    private val handler = QuestAutomationHandler(progress)

    @Test
    fun `quest battle map follows shared 100 and 300 TIME rules`() {
        fun actionAt(timeCurrent: Int) = battle(handler.evaluate(snapshot(
            quests = listOf(quest(
                "q",
                QuestState.ACTIVE,
                0,
                QuestMission("kill", QuestMissionType.MONSTER_KILL, "monster", QuestProgress(0, 10), false),
            )),
            selections = listOf(selection("q", maps = listOf(map("kill", "common", 0, category = "battle_map")))),
            states = listOf(state("common", category = "battle_map", supportsThreeBattles = true)),
            timeCurrent = timeCurrent,
        )))

        assertEquals(1, actionAt(100).battleCount)
        assertEquals(3, actionAt(300).battleCount)
    }

    @Test
    fun `quest adventure map uses map cost and null fallback`() {
        val waiting = handler.evaluate(snapshot(
            quests = listOf(quest("q", QuestState.ACTIVE, 0, monster("kill"))),
            selections = listOf(selection("q", maps = listOf(map("kill", "adventure", 0, category = "adventure_map")))),
            states = listOf(state("adventure", category = "adventure_map", requiredTime = 50)),
            timeCurrent = 49,
        ))
        assertEquals(NOW.plusMillis(1_600), assertIs<HandlerEvaluation.Unavailable>(waiting).nextRunAt)

        val fallback = battle(handler.evaluate(snapshot(
            quests = listOf(quest("q", QuestState.ACTIVE, 0, monster("kill"))),
            selections = listOf(selection("q", maps = listOf(map("kill", "adventure", 0, category = "adventure_map")))),
            states = listOf(state("adventure", category = "adventure_map", requiredTime = null)),
            timeCurrent = 100,
        )))
        assertEquals(1, fallback.battleCount)
    }

    @Test
    fun availableQuestIsAcceptedBeforeClaimableQuest() {
        val result = handler.evaluate(snapshot(
            quests = listOf(
                quest("available", QuestState.AVAILABLE, 1, immediate()),
                quest("claimable", QuestState.CLAIMABLE, 0, immediate()),
            ),
            selections = listOf(selection("available"), selection("claimable")),
        ))

        assertEquals("available", assertIs<QuestAction.Accept>(assertIs<HandlerEvaluation.Runnable>(result).action).questKey)
    }

    @Test
    fun `configured waiting quest with no action number still matches by stable key`() {
        val questKey = "q:stable-quest-key"
        val waiting = QuestSnapshot(
            questKey = questKey,
            name = "마을 지하 수로",
            state = QuestState.UNAVAILABLE,
            section = QuestSection.WAITING,
            sourceOrder = 0,
            missions = emptyList(),
            actionNo = null,
            displayCode = "0351",
        )

        val result = handler.evaluate(snapshot(
            quests = listOf(waiting),
            selections = listOf(selection(questKey)),
        ))

        assertIs<HandlerEvaluation.Skipped>(result)
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

        assertEquals("turn-in", assertIs<QuestAction.Accept>(assertIs<HandlerEvaluation.Runnable>(result).action).questKey)
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
    fun monsterUsesThreeBattlesOnlyWhenAtLeastThreeRemainAndMapSupportsIt() {
        fun count(current: Int, required: Int, supportsThreeBattles: Boolean) = battle(handler.evaluate(snapshot(
            quests = listOf(quest(
                "q",
                QuestState.ACTIVE,
                0,
                QuestMission(
                    "kill",
                    QuestMissionType.MONSTER_KILL,
                    "monster",
                    QuestProgress(current, required),
                    false,
                ),
            )),
            selections = listOf(selection("q", maps = listOf(map("kill", "map", 0)))),
            states = listOf(state("map", supportsThreeBattles = supportsThreeBattles)),
        ))).battleCount

        assertEquals(3, count(current = 2, required = 5, supportsThreeBattles = true))
        assertEquals(1, count(current = 3, required = 5, supportsThreeBattles = true))
        assertEquals(1, count(current = 2, required = 5, supportsThreeBattles = false))
    }

    @Test
    fun mapClearUsesThreeBattlesWhenAtLeastThreeRemainAndMapSupportsIt() {
        val result = handler.evaluate(snapshot(
            quests = listOf(quest(
                "q",
                QuestState.ACTIVE,
                0,
                QuestMission(
                    "clear",
                    QuestMissionType.MAP_CLEAR,
                    "target",
                    QuestProgress(1, 4),
                    false,
                ),
            )),
            selections = listOf(selection("q", maps = listOf(map("clear", "map", 0, manual = true)))),
            states = listOf(state("map", supportsThreeBattles = true)),
        ))

        assertEquals(3, battle(result).battleCount)
    }

    @Test
    fun `prepared quest actions retain human readable names mission label and progress`() {
        val mission = QuestMission(
            "kill", QuestMissionType.MONSTER_KILL, "  슬라임  ", QuestProgress(2, 5), false,
        )
        val result = handler.evaluate(snapshot(
            quests = listOf(quest("q", QuestState.ACTIVE, 0, mission)),
            selections = listOf(selection("q", maps = listOf(map("kill", "map-a", 0)))),
            states = listOf(state("map-a", mapName = "Map A")),
        ))

        val action = battle(result)
        assertEquals("q", action.questName)
        assertEquals("몬스터 처치 · 슬라임", action.missionLabel)
        assertEquals(2, action.missionCurrent)
        assertEquals(5, action.missionRequired)
        assertEquals("Map A", action.mapName)
    }

    @Test
    fun `configured monster map display uses matched live name instead of raw code`() {
        val result = handler.evaluate(snapshot(
            quests = listOf(quest("q", QuestState.ACTIVE, 0, monster("kill"))),
            selections = listOf(selection("q", maps = listOf(map("kill", "qmap", 0)))),
            states = listOf(state("qmap", mapName = "Live map")),
        ))

        assertEquals("Live map", battle(result).mapName)
    }

    @Test
    fun `manual map clear display uses matched live name and never falls back to raw code`() {
        val named = handler.evaluate(snapshot(
            quests = listOf(quest("q", QuestState.ACTIVE, 0, mapClear("clear", "target"))),
            selections = listOf(selection("q", maps = listOf(map("clear", "qmap", 0, manual = true)))),
            states = listOf(state("qmap", mapName = "Live map")),
        ))
        val blank = handler.evaluate(snapshot(
            quests = listOf(quest("q", QuestState.ACTIVE, 0, mapClear("clear", "target"))),
            selections = listOf(selection("q", maps = listOf(map("clear", "qmap", 0, manual = true)))),
            states = listOf(state("qmap", mapName = "   ")),
        ))

        assertEquals("Live map", battle(named).mapName)
        assertNull(battle(blank).mapName)
    }

    @Test
    fun `mission display labels cover every type and trim nonblank targets`() {
        val labels = QuestMissionType.entries.associateWith { type ->
            QuestMission("mission", type, "  대상  ", null, false).displayLabel()
        }

        assertEquals("몬스터 처치 · 대상", labels[QuestMissionType.MONSTER_KILL])
        assertEquals("맵 클리어 · 대상", labels[QuestMissionType.MAP_CLEAR])
        assertEquals("아이템 반납 · 대상", labels[QuestMissionType.ITEM_TURN_IN])
        assertEquals("즉시 완료 · 대상", labels[QuestMissionType.IMMEDIATE])
        assertEquals("기타 · 대상", labels[QuestMissionType.OTHER])
        assertEquals(
            "맵 클리어",
            QuestMission("mission", QuestMissionType.MAP_CLEAR, "   ", null, false).displayLabel(),
        )
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
    fun incompleteItemOnlyQuestIsAcceptedWhenSelected() {
        val result = handler.evaluate(snapshot(
            quests = listOf(quest("q", QuestState.AVAILABLE, 0, item(completable = false))),
            selections = listOf(selection("q")),
        ))

        assertEquals("q", assertIs<QuestAction.Accept>(assertIs<HandlerEvaluation.Runnable>(result).action).questKey)
    }

    @Test
    fun activeIncompleteItemTurnInWithoutConfiguredMapsIsSkipped() {
        val result = handler.evaluate(snapshot(
            quests = listOf(quest("q", QuestState.ACTIVE, 0, item(completable = false))),
            selections = listOf(selection("q")),
        ))

        assertIs<HandlerEvaluation.Skipped>(result)
    }

    @Test
    fun activeMonsterKillWithoutConfiguredMapsReturnsConfigurationWarning() {
        val result = handler.evaluate(snapshot(
            quests = listOf(quest("q", QuestState.ACTIVE, 0, monster("kill"))),
            selections = listOf(selection("q")),
        ))

        assertEquals(
            "q · monster 전투 맵 설정이 없습니다.",
            assertIs<HandlerEvaluation.ConfigurationWarning>(result).message,
        )
    }

    @Test
    fun unsupportedMissionIsAcceptedWhenSelected() {
        val result = handler.evaluate(snapshot(
            quests = listOf(
                quest("unsupported", QuestState.AVAILABLE, 0,
                    QuestMission("other", QuestMissionType.OTHER, null, null, completable = true)),
            ),
            selections = listOf(selection("unsupported")),
        ))

        assertEquals(
            "unsupported",
            assertIs<QuestAction.Accept>(assertIs<HandlerEvaluation.Runnable>(result).action).questKey,
        )
    }

    @Test
    fun availableMapClearQuestIsAcceptedBeforeActiveCombat() {
        val result = handler.evaluate(snapshot(
            quests = listOf(
                quest("available-clear", QuestState.AVAILABLE, 0, mapClear("clear", "target")),
                quest("active-combat", QuestState.ACTIVE, 1, monster("kill")),
            ),
            selections = listOf(
                selection("available-clear"),
                selection("active-combat", maps = listOf(map("kill", "combat-map", 0))),
            ),
            states = listOf(state("combat-map")),
        ))

        assertEquals(
            "available-clear",
            assertIs<QuestAction.Accept>(assertIs<HandlerEvaluation.Runnable>(result).action).questKey,
        )
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

        assertEquals("monster", battle(result).questKey)
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
    fun visibleUnlimitedMapWithoutACountRunsWhileHiddenLimitedMapWithKeysIsSkipped() {
        val unlimited = handler.evaluate(snapshot(
            quests = listOf(quest("q", QuestState.ACTIVE, 0, monster("kill"))),
            selections = listOf(selection("q", maps = listOf(map("kill", "unlimited", 0)))),
            states = listOf(state("unlimited", keyMode = BattleMapKeyMode.UNLIMITED)),
        ))
        assertEquals("unlimited", battle(unlimited).mapCode)

        val hidden = handler.evaluate(snapshot(
            quests = listOf(quest("q", QuestState.ACTIVE, 0, monster("kill"))),
            selections = listOf(selection("q", maps = listOf(map("kill", "hidden", 0)))),
            states = listOf(state("hidden", visible = false, keyMode = BattleMapKeyMode.LIMITED, keyCount = 10)),
        ))
        assertIs<HandlerEvaluation.Skipped>(hidden)
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
            assertEquals(
                "q · $target 전투 맵 설정이 없습니다.",
                assertIs<HandlerEvaluation.ConfigurationWarning>(result).message,
            )
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
    fun acceptSuccessStartsNewCycleAndBattleResultRecordsOnlyVictories() {
        val first = QuestAction.Accept("q", "accept")
        assertEquals("1", handler.onAcceptSucceeded(ACCOUNT_ID, "accept-result-1", first))
        assertEquals("2", handler.onAcceptSucceeded(ACCOUNT_ID, "accept-result-2", first))

        val action = QuestAction.Battle("q", "2", "kill", QuestMissionType.MONSTER_KILL, "battle_map", "chosen", "Chosen", QuestPresetSelection(PresetSelectionMode.PRIMARY), 3)
        handler.onBattleCompleted(
            ACCOUNT_ID,
            "battle-result-1",
            action,
            listOf(
                BattleAutomationRoundOutcome.VICTORY,
                BattleAutomationRoundOutcome.DEFEAT,
                BattleAutomationRoundOutcome.VICTORY,
            ),
        )

        assertEquals(listOf(action to 2), progress.results)
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
        timeCurrent: Int? = 6000,
    ) = QuestAutomationSnapshot(
        ACCOUNT_ID,
        quests,
        selections,
        states,
        cycles,
        counters,
        identities,
        NOW,
        timeSnapshot = timeCurrent?.let { AutomationTimeSnapshot(it, 6000, NOW) },
    )

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
        category: String = "battle_map",
    ) = QuestAutomationMapSelection(mission, category, code, QuestPresetSelection(presetMode, presetId), order, manual)

    private fun state(
        code: String,
        mapName: String = code,
        visible: Boolean = true,
        keyCount: Int? = null,
        keyMode: BattleMapKeyMode = if (keyCount == null) BattleMapKeyMode.UNKNOWN else BattleMapKeyMode.LIMITED,
        cooldownUntil: Instant? = null,
        supportsThreeBattles: Boolean = false,
        category: String = "battle_map",
        requiredTime: Int? = null,
    ) = AutomationMapState(
        category, code, mapName, visible, true, cooldownUntil, null, null, null, keyMode, keyCount,
        supportsThreeBattles, requiredTime,
    )

    private fun counterKey(quest: String, cycle: String, mission: String, map: String) =
        QuestCounterKey(quest, cycle, mission, "battle_map", map)

    private fun identity(code: String, vararg aliases: String) =
        BattleMapIdentityCandidate("battle_map", code, code, aliases.toSet())

    private class RecordingProgressStore : QuestAutomationProgressStore {
        val cycles = mutableMapOf<Pair<Long, String>, String>()
        val results = mutableListOf<Pair<QuestAction.Battle, Int>>()
        override fun startNewCycle(accountId: Long, resultId: String, questKey: String): String {
            val key = accountId to questKey
            return ((cycles[key]?.toLongOrNull() ?: 0) + 1).toString().also { cycles[key] = it }
        }
        override fun recordBattleResult(accountId: Long, resultId: String, action: QuestAction.Battle, victoryCount: Int) {
            results += action to victoryCount
        }
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
            QuestPresetSelection(PresetSelectionMode.PRIMARY), 3,
        )

        val pool = Executors.newFixedThreadPool(4)
        val futures = List(12) {
            pool.submit { progressStore.recordBattleResult(account.id, "duplicate-victory", action, 2) }
        }
        pool.shutdown()
        check(pool.awaitTermination(20, TimeUnit.SECONDS))
        futures.forEach { it.get() }

        assertEquals(2, queryRepository.findQuestMapWins(account.id, "q", "2", "kill", "battle_map", "chosen"))
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
        val first = battleAction(cycle = "1", mapCode = "first-map", battleCount = 3)
        progressStore.recordBattleResult(account.id, "battle-result", first, 2)

        assertFailsWith<QuestAutomationResultConflictException> {
            progressStore.recordBattleResult(account.id, "battle-result", first, 1)
        }
        assertFailsWith<QuestAutomationResultConflictException> {
            progressStore.recordBattleResult(account.id, "battle-result", battleAction(cycle = "2", mapCode = "first-map"), 1)
        }
        assertFailsWith<QuestAutomationResultConflictException> {
            progressStore.recordBattleResult(account.id, "battle-result", battleAction(cycle = "1", mapCode = "second-map"), 1)
        }
        assertFailsWith<QuestAutomationResultConflictException> {
            progressStore.startNewCycle(account.id, "battle-result", "q")
        }

        assertEquals(2, queryRepository.findQuestMapWins(account.id, "q", "1", "kill", "battle_map", "first-map"))
        assertEquals(0, queryRepository.findQuestMapWins(account.id, "q", "2", "kill", "battle_map", "second-map"))
        assertEquals(null, queryRepository.findQuestCycle(account.id, "q"))
    }

    @Test
    fun `quest display context does not change victory replay identity`() {
        val account = accountRepository.save(
            HofAccountEntity(loginId = "quest-display-${System.nanoTime()}", encryptedPassword = "encrypted", createdAt = NOW),
        )
        val original = battleAction(cycle = "1", mapCode = "map").copy(
            questName = "이전 퀘스트 이름",
            missionLabel = "몬스터 처치 · 이전 대상",
            missionCurrent = 1,
            missionRequired = 5,
        )
        progressStore.recordBattleResult(account.id, "display-result", original, 1)

        progressStore.recordBattleResult(
            account.id,
            "display-result",
            original.copy(questName = "새 퀘스트 이름", missionLabel = "몬스터 처치 · 새 대상", missionCurrent = 4),
            1,
        )

        assertEquals(1, queryRepository.findQuestMapWins(account.id, "q", "1", "kill", "battle_map", "map"))
    }

    private fun battleAction(cycle: String, mapCode: String, battleCount: Int = 1) = QuestAction.Battle(
        "q", cycle, "kill", QuestMissionType.MONSTER_KILL, "battle_map", mapCode, mapCode,
        QuestPresetSelection(PresetSelectionMode.PRIMARY), battleCount,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-15T00:00:00Z")
    }
}
