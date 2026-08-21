package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.automation.entity.AutomationEntryEntity
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.AutomationWorkSessionEntity
import app.spammy.hof.automation.entity.AutomationWorkStatus
import app.spammy.hof.automation.entity.AutomationWorkType
import app.spammy.hof.automation.entity.BattleAutomationDailyProgressEntity
import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.automation.entity.QuestAutomationProcessedResultEntity
import app.spammy.hof.automation.entity.QuestAutomationResultKind
import app.spammy.hof.automation.entity.QuestMapExecutionCounterEntity
import app.spammy.hof.automation.repository.AutomationEntryCommandRepository
import app.spammy.hof.automation.repository.AutomationWorkSessionCommandRepository
import app.spammy.hof.automation.repository.AutomationWorkSessionQueryRepository
import app.spammy.hof.automation.repository.BattleAutomationDailyProgressCommandRepository
import app.spammy.hof.automation.repository.QuestAutomationProcessedResultCommandRepository
import app.spammy.hof.automation.repository.QuestMapExecutionCounterCommandRepository
import app.spammy.hof.automation.repository.TypedAutomationQueryRepository
import app.spammy.hof.battle.service.BattleMapIdentityCandidate
import app.spammy.hof.battle.model.BattleMapKeyMode
import app.spammy.hof.quest.model.QuestMission
import app.spammy.hof.quest.model.QuestMissionType
import app.spammy.hof.quest.model.QuestProgress
import app.spammy.hof.quest.model.QuestSection
import app.spammy.hof.quest.model.QuestSnapshot
import app.spammy.hof.quest.model.QuestState
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.HexFormat
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

class QuestWorkCycleModuleTest {
    private val progress = RecordingProgressStore()
    private val handler = DefaultQuestWorkCycleModule(progress)

    @Test
    fun `quest battle map follows shared 100 and 300 TIME rules`() {
        fun actionAt(timeCurrent: Int) = battle(handler.decideNext(snapshot(
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
        val waiting = handler.decideNext(snapshot(
            quests = listOf(quest("q", QuestState.ACTIVE, 0, monster("kill"))),
            selections = listOf(selection("q", maps = listOf(map("kill", "adventure", 0, category = "adventure_map")))),
            states = listOf(state("adventure", category = "adventure_map", requiredTime = 50)),
            timeCurrent = 49,
        ))
        assertEquals(NOW.plusMillis(1_600), assertIs<QuestDirective.WaitUntil>(waiting).nextRunAt)

        val fallback = battle(handler.decideNext(snapshot(
            quests = listOf(quest("q", QuestState.ACTIVE, 0, monster("kill"))),
            selections = listOf(selection("q", maps = listOf(map("kill", "adventure", 0, category = "adventure_map")))),
            states = listOf(state("adventure", category = "adventure_map", requiredTime = null)),
            timeCurrent = 100,
        )))
        assertEquals(1, fallback.battleCount)
    }

    @Test
    fun availableQuestIsAcceptedBeforeClaimableQuest() {
        val result = handler.decideNext(snapshot(
            quests = listOf(
                quest("available", QuestState.AVAILABLE, 1, immediate()),
                quest("claimable", QuestState.CLAIMABLE, 0, immediate()),
            ),
            selections = listOf(selection("available"), selection("claimable")),
        ))

        assertEquals("available", assertIs<QuestAction.Accept>(assertIs<QuestDirective.Execute>(result).action).questKey)
    }

    @Test
    fun `claimable quest with TIME reward runs when reward reaches but does not exceed max`() {
        val result = handler.decideNext(snapshot(
            quests = listOf(
                quest("claimable", QuestState.CLAIMABLE, 0, immediate())
                    .copy(rewards = listOf("Time +2,000")),
            ),
            selections = listOf(selection("claimable")),
            timeCurrent = 4_000,
        ))

        assertEquals(
            "claimable",
            assertIs<QuestAction.Claim>(assertIs<QuestDirective.Execute>(result).action).questKey,
        )
    }

    @Test
    fun `claimable quest is skipped when TIME reward would exceed max`() {
        val result = handler.decideNext(snapshot(
            quests = listOf(
                quest("claimable", QuestState.CLAIMABLE, 0, immediate())
                    .copy(rewards = listOf("Time +2000")),
            ),
            selections = listOf(selection("claimable")),
            timeCurrent = 4_001,
        ))

        assertIs<QuestDirective.Skip>(result)
    }

    @Test
    fun `unsafe TIME reward does not hide a later safe claimable quest`() {
        val result = handler.decideNext(snapshot(
            quests = listOf(
                quest("unsafe", QuestState.CLAIMABLE, 0, immediate())
                    .copy(rewards = listOf("Time +2000")),
                quest("safe", QuestState.CLAIMABLE, 1, immediate())
                    .copy(rewards = listOf("Gold x10")),
            ),
            selections = listOf(selection("unsafe"), selection("safe")),
            timeCurrent = 4_001,
        ))

        assertEquals(
            "safe",
            assertIs<QuestAction.Claim>(assertIs<QuestDirective.Execute>(result).action).questKey,
        )
    }

    @Test
    fun `TIME reward is not claimed without a current TIME snapshot`() {
        val result = handler.decideNext(snapshot(
            quests = listOf(
                quest("claimable", QuestState.CLAIMABLE, 0, immediate())
                    .copy(rewards = listOf("Time +2000")),
            ),
            selections = listOf(selection("claimable")),
            timeCurrent = null,
        ))

        assertIs<QuestDirective.Skip>(result)
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

        val result = handler.decideNext(snapshot(
            quests = listOf(waiting),
            selections = listOf(selection(questKey)),
        ))

        assertIs<QuestDirective.Skip>(result)
    }

    @Test
    fun immediatelyCompletableAcceptBeatsCombat() {
        val result = handler.decideNext(snapshot(
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

        assertEquals("turn-in", assertIs<QuestAction.Accept>(assertIs<QuestDirective.Execute>(result).action).questKey)
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
        val lowerCount = battle(handler.decideNext(base))
        assertEquals("map-b", lowerCount.mapCode)
        assertEquals(1, lowerCount.battleCount)

        val equal = battle(handler.decideNext(base.copy(counters = base.counters.mapValues { 4 })))
        assertEquals("map-a", equal.mapCode)
    }

    @Test
    fun monsterUsesThreeBattlesOnlyWhenAtLeastThreeRemainAndMapSupportsIt() {
        fun count(current: Int, required: Int, supportsThreeBattles: Boolean) = battle(handler.decideNext(snapshot(
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
        val result = handler.decideNext(snapshot(
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
        val result = handler.decideNext(snapshot(
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
        val result = handler.decideNext(snapshot(
            quests = listOf(quest("q", QuestState.ACTIVE, 0, monster("kill"))),
            selections = listOf(selection("q", maps = listOf(map("kill", "qmap", 0)))),
            states = listOf(state("qmap", mapName = "Live map")),
        ))

        assertEquals("Live map", battle(result).mapName)
    }

    @Test
    fun `manual map clear display uses matched live name and never falls back to raw code`() {
        val named = handler.decideNext(snapshot(
            quests = listOf(quest("q", QuestState.ACTIVE, 0, mapClear("clear", "target"))),
            selections = listOf(selection("q", maps = listOf(map("clear", "qmap", 0, manual = true)))),
            states = listOf(state("qmap", mapName = "Live map")),
        ))
        val blank = handler.decideNext(snapshot(
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
        val result = handler.decideNext(snapshot(
            quests = listOf(quest("q", QuestState.ACTIVE, 0, mapClear("clear", "manual target"))),
            selections = listOf(selection("q", maps = listOf(map("clear", "manual-map", 0, manual = true)))),
            states = listOf(state("manual-map"), state("auto-map")),
        ))

        assertEquals("manual-map", battle(result).mapCode)
    }

    @Test
    fun incompleteItemOnlyQuestIsAcceptedWhenSelected() {
        val result = handler.decideNext(snapshot(
            quests = listOf(quest("q", QuestState.AVAILABLE, 0, item(completable = false))),
            selections = listOf(selection("q")),
        ))

        assertEquals("q", assertIs<QuestAction.Accept>(assertIs<QuestDirective.Execute>(result).action).questKey)
    }

    @Test
    fun activeIncompleteItemTurnInWithoutConfiguredMapsIsSkipped() {
        val result = handler.decideNext(snapshot(
            quests = listOf(quest("q", QuestState.ACTIVE, 0, item(completable = false))),
            selections = listOf(selection("q")),
        ))

        assertIs<QuestDirective.Skip>(result)
    }

    @Test
    fun activeMonsterKillWithoutConfiguredMapsReturnsConfigurationWarning() {
        val result = handler.decideNext(snapshot(
            quests = listOf(quest("q", QuestState.ACTIVE, 0, monster("kill"))),
            selections = listOf(selection("q")),
        ))

        assertEquals(
            "q · monster 전투 맵 설정이 없습니다.",
            assertIs<QuestDirective.Hold>(result).message,
        )
    }

    @Test
    fun unsupportedMissionIsAcceptedWhenSelected() {
        val result = handler.decideNext(snapshot(
            quests = listOf(
                quest("unsupported", QuestState.AVAILABLE, 0,
                    QuestMission("other", QuestMissionType.OTHER, null, null, completable = true)),
            ),
            selections = listOf(selection("unsupported")),
        ))

        assertEquals(
            "unsupported",
            assertIs<QuestAction.Accept>(assertIs<QuestDirective.Execute>(result).action).questKey,
        )
    }

    @Test
    fun availableMapClearQuestIsAcceptedBeforeActiveCombat() {
        val result = handler.decideNext(snapshot(
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
            assertIs<QuestAction.Accept>(assertIs<QuestDirective.Execute>(result).action).questKey,
        )
    }

    @Test
    fun monsterPrecedesMapClearEvenWhenMapClearAppearsFirst() {
        val result = handler.decideNext(snapshot(
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
        val result = handler.decideNext(snapshot(
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
        val unlimited = handler.decideNext(snapshot(
            quests = listOf(quest("q", QuestState.ACTIVE, 0, monster("kill"))),
            selections = listOf(selection("q", maps = listOf(map("kill", "unlimited", 0)))),
            states = listOf(state("unlimited", keyMode = BattleMapKeyMode.UNLIMITED)),
        ))
        assertEquals("unlimited", battle(unlimited).mapCode)

        val hidden = handler.decideNext(snapshot(
            quests = listOf(quest("q", QuestState.ACTIVE, 0, monster("kill"))),
            selections = listOf(selection("q", maps = listOf(map("kill", "hidden", 0)))),
            states = listOf(state("hidden", visible = false, keyMode = BattleMapKeyMode.LIMITED, keyCount = 10)),
        ))
        assertIs<QuestDirective.Skip>(hidden)
    }

    @Test
    fun laterRunnableMonsterMissionIsNotStarvedByEarlierWarningOrUnavailable() {
        val result = handler.decideNext(snapshot(
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
        val result = handler.decideNext(snapshot(
            quests = listOf(quest("q", QuestState.ACTIVE, 0, monster("cooldown"), monster("missing"))),
            selections = listOf(selection("q", maps = listOf(map("cooldown", "later", 0)))),
            states = listOf(state("later", cooldownUntil = NOW.plusSeconds(120))),
        ))

        assertIs<QuestDirective.Hold>(result)
    }

    @Test
    fun invalidLowerCountMonsterPresetDoesNotHideValidRunnableAlternative() {
        val result = handler.decideNext(snapshot(
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
        val result = handler.decideNext(snapshot(
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
        val result = handler.decideNext(snapshot(
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
            val result = handler.decideNext(snapshot(
                quests = listOf(quest("q", QuestState.ACTIVE, 0, mapClear("clear", target))),
                selections = listOf(selection("q")),
                identities = if (target == "ambiguous") listOf(
                    identity("first", target),
                    identity("second", target),
                ) else emptyList(),
            ))
            assertEquals(
                "q · $target 전투 맵 설정이 없습니다.",
                assertIs<QuestDirective.Hold>(result).message,
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

        val first = handler.decideNext(context)
        val second = handler.decideNext(context)

        assertEquals(first, second)
        assertEquals("frost", battle(first).mapCode)
        assertEquals(original, context)
    }

    @Test
    fun presetModeIsPreservedWithoutResolvingPrimary() {
        val primary = battle(handler.decideNext(combatSnapshot(map("kill", "map", 0, presetMode = PresetSelectionMode.PRIMARY))))
        assertEquals(PresetSelectionMode.PRIMARY, primary.preset.mode)
        assertEquals(null, primary.preset.presetId)

        val explicit = battle(handler.decideNext(combatSnapshot(map("kill", "map", 0, presetMode = PresetSelectionMode.EXPLICIT, presetId = 41))))
        assertEquals(QuestPresetSelection(PresetSelectionMode.EXPLICIT, 41), explicit.preset)
    }

    @Test
    fun invalidExplicitPresetReturnsWarningWithoutFallback() {
        val result = handler.decideNext(combatSnapshot(map("kill", "map", 0, presetMode = PresetSelectionMode.EXPLICIT)))
        assertEquals("QUEST_PRESET_INVALID", assertIs<QuestDirective.Hold>(result).reasonCode)
    }

    @Test
    fun `몬스터 처치 맵 쿨타임은 정확한 다음 판단 시각을 반환한다`() {
        val retryAt = NOW.plusSeconds(73)
        val result = handler.decideNext(snapshot(
            quests = listOf(quest("q", QuestState.ACTIVE, 0, monster("kill"))),
            selections = listOf(selection("q", maps = listOf(map("kill", "map", 0)))),
            states = listOf(state("map", cooldownUntil = retryAt)),
        ))

        val wait = assertIs<QuestDirective.WaitUntil>(result)
        assertEquals(retryAt, wait.nextRunAt)
        assertEquals("QUEST_BATTLE_COOLDOWN", wait.reasonCode)
    }

    @Test
    fun `다른 이유로 실행 불가능한 맵은 다음 쿨타임 판단에서 제외한다`() {
        val retryAt = NOW.plusSeconds(73)
        val result = handler.decideNext(snapshot(
            quests = listOf(quest("q", QuestState.ACTIVE, 0, monster("kill"))),
            selections = listOf(selection("q", maps = listOf(
                map("kill", "hidden", 0),
                map("kill", "exhausted", 1),
                map("kill", "cooling", 2),
            ))),
            states = listOf(
                state("hidden", visible = false, cooldownUntil = NOW.plusSeconds(10)),
                state("exhausted", cooldownUntil = NOW.plusSeconds(20), winRemaining = 0),
                state("cooling", cooldownUntil = retryAt),
            ),
        ))

        assertEquals(retryAt, assertIs<QuestDirective.WaitUntil>(result).nextRunAt)
    }

    @Test
    fun `자동 맵이 쿨타임 외 조건으로 막히면 불필요한 재확인을 예약하지 않는다`() {
        val result = handler.decideNext(snapshot(
            quests = listOf(quest("q", QuestState.ACTIVE, 0, mapClear("clear", "target"))),
            selections = listOf(selection("q")),
            states = listOf(state("target", cooldownUntil = NOW.plusSeconds(20), attemptRemaining = 0)),
            identities = listOf(identity("target", "target")),
        ))

        assertIs<QuestDirective.Skip>(result)
    }

    @Test
    fun acceptSuccessStartsNewCycleAndBattleResultRecordsOnlyVictories() {
        val page = QuestResultObservation.Page(
            listOf(quest("q", QuestState.ACTIVE, 0, immediate())),
        )
        assertEquals(
            "1",
            assertIs<QuestRecordResult.Recorded>(
                handler.recordObservedResult(
                    ACCOUNT_ID,
                    QuestAttempt.Accept("accept-result-1", "q", "accept"),
                    page,
                ),
            ).questCycle,
        )
        assertEquals(
            "2",
            assertIs<QuestRecordResult.Recorded>(
                handler.recordObservedResult(
                    ACCOUNT_ID,
                    QuestAttempt.Accept("accept-result-2", "q", "accept"),
                    page,
                ),
            ).questCycle,
        )

        val action = QuestAction.Battle("q", "2", "kill", QuestMissionType.MONSTER_KILL, "battle_map", "chosen", "Chosen", QuestPresetSelection(PresetSelectionMode.PRIMARY), 3)
        assertIs<QuestRecordResult.Recorded>(handler.recordObservedResult(
            ACCOUNT_ID,
            QuestAttempt.Battle("battle-result-1", action),
            QuestResultObservation.BattleRounds(
                listOf(
                    BattleAutomationRoundOutcome.VICTORY,
                    BattleAutomationRoundOutcome.DEFEAT,
                    BattleAutomationRoundOutcome.VICTORY,
                ),
            ),
        ))

        assertEquals(listOf(action to 2), progress.results)
    }

    @Test
    fun `수락과 보상은 권위 퀘스트 상태가 적용을 증명할 때만 기록한다`() {
        val accept = QuestAttempt.Accept("accept-result", "q", "q-action")

        assertIs<QuestRecordResult.NotApplied>(
            handler.recordObservedResult(
                ACCOUNT_ID,
                accept,
                QuestResultObservation.Page(listOf(quest("q", QuestState.AVAILABLE, 0, immediate()))),
            ),
        )
        assertNull(progress.cycles[ACCOUNT_ID to "q"])

        assertIs<QuestRecordResult.NeedsRecheck>(
            handler.recordObservedResult(
                ACCOUNT_ID,
                accept,
                QuestResultObservation.Page(emptyList()),
            ),
        )
        assertNull(progress.cycles[ACCOUNT_ID to "q"])

        assertEquals(
            "1",
            assertIs<QuestRecordResult.Recorded>(
                handler.recordObservedResult(
                    ACCOUNT_ID,
                    accept,
                    QuestResultObservation.Page(listOf(quest("q", QuestState.ACTIVE, 0, immediate()))),
                ),
            ).questCycle,
        )

        val claim = QuestAttempt.Claim("claim-result", "q", "q-action")
        assertIs<QuestRecordResult.NotApplied>(
            handler.recordObservedResult(
                ACCOUNT_ID,
                claim,
                QuestResultObservation.Page(listOf(quest("q", QuestState.CLAIMABLE, 0, immediate()))),
            ),
        )
        assertIs<QuestRecordResult.Recorded>(
            handler.recordObservedResult(
                ACCOUNT_ID,
                claim,
                QuestResultObservation.Page(emptyList()),
            ),
        )
    }

    @Test
    fun `불명확한 전투는 권위 mission 증가분이 저장 batch에 맞을 때만 결과를 기록한다`() {
        val action = QuestAction.Battle(
            questKey = "q",
            questCycle = "3",
            missionKey = "kill",
            missionType = QuestMissionType.MONSTER_KILL,
            categoryId = "battle_map",
            mapCode = "chosen",
            mapName = "Chosen",
            preset = QuestPresetSelection(PresetSelectionMode.PRIMARY),
            battleCount = 3,
            missionCurrent = 2,
            missionRequired = 8,
        )
        val attempt = QuestAttempt.Battle("battle-execution", action)

        assertIs<QuestRecordResult.Recorded>(
            handler.recordObservedResult(
                ACCOUNT_ID,
                attempt,
                QuestResultObservation.Page(
                    listOf(
                        quest(
                            "q",
                            QuestState.ACTIVE,
                            0,
                            QuestMission(
                                "kill",
                                QuestMissionType.MONSTER_KILL,
                                "monster",
                                QuestProgress(4, 8),
                                false,
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(listOf(action to 2), progress.results)

        assertIs<QuestRecordResult.NeedsRecheck>(
            handler.recordObservedResult(
                ACCOUNT_ID,
                attempt.copy(resultIdentity = "unchanged"),
                QuestResultObservation.Page(
                    listOf(
                        quest(
                            "q",
                            QuestState.ACTIVE,
                            0,
                            QuestMission(
                                "kill",
                                QuestMissionType.MONSTER_KILL,
                                "monster",
                                QuestProgress(2, 8),
                                false,
                            ),
                        ),
                    ),
                ),
            ),
        )
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
        assertEquals("second", battle(handler.decideNext(old)).mapCode)

        val newCycle = assertIs<QuestRecordResult.Recorded>(
            handler.recordObservedResult(
                ACCOUNT_ID,
                QuestAttempt.Accept("repeat-result", "q", "accept"),
                QuestResultObservation.Page(listOf(quest("q", QuestState.ACTIVE, 0, immediate()))),
            ),
        ).questCycle
        assertEquals("9", newCycle)
        assertEquals("first", battle(handler.decideNext(old.copy(currentCycles = mapOf("q" to requireNotNull(newCycle)), counters = emptyMap()))).mapCode)
    }

    private fun combatSnapshot(map: QuestAutomationMapSelection) = snapshot(
        quests = listOf(quest("q", QuestState.ACTIVE, 0, monster("kill"))),
        selections = listOf(selection("q", maps = listOf(map))),
        states = listOf(state(map.mapCode)),
    )

    private fun battle(result: QuestDirective) =
        assertIs<QuestAction.Battle>(assertIs<QuestDirective.Execute>(result).action)

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
        enabled: Boolean = true,
        winRemaining: Int? = null,
        attemptRemaining: Int? = null,
        availableCount: Int? = null,
    ) = AutomationMapState(
        category, code, mapName, visible, enabled, cooldownUntil, winRemaining, attemptRemaining, availableCount, keyMode, keyCount,
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
        override fun findRecordedBattleVictoryCount(
            accountId: Long,
            resultId: String,
            action: QuestAction.Battle,
        ): Int? = null
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
    @Autowired private lateinit var entryRepository: AutomationEntryCommandRepository
    @Autowired private lateinit var workSessionRepository: AutomationWorkSessionCommandRepository
    @Autowired private lateinit var workQueries: AutomationWorkSessionQueryRepository
    @Autowired private lateinit var processedResultRepository: QuestAutomationProcessedResultCommandRepository
    @Autowired private lateinit var counterRepository: QuestMapExecutionCounterCommandRepository
    @Autowired private lateinit var progressStore: JpaQuestAutomationProgressStore
    @Autowired private lateinit var queryRepository: TypedAutomationQueryRepository
    @Autowired private lateinit var dailyRepository: BattleAutomationDailyProgressCommandRepository
    @Autowired private lateinit var questWorkCycle: QuestWorkCycleModule

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
    fun `정상 전투 기록 뒤 같은 stored action을 재조정해도 counter와 work progress는 한 번만 반영된다`() {
        val account = accountRepository.save(
            HofAccountEntity(loginId = "quest-reconcile-${System.nanoTime()}", encryptedPassword = "encrypted", createdAt = NOW),
        )
        val entry = entryRepository.save(
            AutomationEntryEntity(
                account = account,
                type = AutomationType.QUEST,
                priority = 0,
                enabled = true,
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )
        val action = battleAction(cycle = "1", mapCode = "chosen", battleCount = 3).copy(
            missionType = QuestMissionType.MAP_CLEAR,
            missionCurrent = 2,
            missionRequired = 8,
        )
        workSessionRepository.save(
            AutomationWorkSessionEntity(
                account = account,
                entry = entry,
                workType = AutomationWorkType.QUEST,
                targetKey = action.questKey,
                status = AutomationWorkStatus.RUNNING,
                configVersion = entry.updatedAt.toString(),
                questCycle = action.questCycle,
                missionKey = action.missionKey,
                missionType = action.missionType.name,
                observedCurrent = action.missionCurrent,
                observedRequired = action.missionRequired,
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )
        val attempt = QuestAttempt.Battle("stable-execution-identity", action)

        assertIs<QuestRecordResult.Recorded>(
            questWorkCycle.recordObservedResult(
                account.id,
                attempt,
                QuestResultObservation.BattleRounds(
                    listOf(
                        BattleAutomationRoundOutcome.VICTORY,
                        BattleAutomationRoundOutcome.DEFEAT,
                        BattleAutomationRoundOutcome.VICTORY,
                    ),
                ),
            ),
        )
        assertIs<QuestRecordResult.Recorded>(
            questWorkCycle.recordObservedResult(
                account.id,
                attempt,
                QuestResultObservation.Page(
                    listOf(
                        QuestSnapshot(
                            questKey = action.questKey,
                            name = action.questKey,
                            state = QuestState.ACTIVE,
                            section = QuestSection.ACTIVE,
                            sourceOrder = 0,
                            missions = listOf(
                                QuestMission(
                                    key = action.missionKey,
                                    type = action.missionType,
                                    target = null,
                                    progress = QuestProgress(4, 8),
                                    completable = false,
                                ),
                            ),
                            actionNo = null,
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            2,
            queryRepository.findQuestMapWins(
                account.id,
                action.questKey,
                action.questCycle,
                action.missionKey,
                action.categoryId,
                action.mapCode,
            ),
        )
        assertEquals(4, workQueries.findRunning(account.id)?.observedCurrent)
    }

    @Test
    fun `구형 map clear 결과는 진행도를 보수적으로 보완하고 새 fingerprint로 승격한다`() {
        val account = accountRepository.save(
            HofAccountEntity(loginId = "quest-legacy-${System.nanoTime()}", encryptedPassword = "encrypted", createdAt = NOW),
        )
        val entry = entryRepository.save(
            AutomationEntryEntity(account = account, type = AutomationType.QUEST, priority = 0, enabled = true, createdAt = NOW, updatedAt = NOW),
        )
        val action = battleAction(cycle = "1", mapCode = "chosen", battleCount = 3).copy(
            missionType = QuestMissionType.MAP_CLEAR,
            missionCurrent = 2,
            missionRequired = 8,
        )
        workSessionRepository.save(
            AutomationWorkSessionEntity(
                account = account,
                entry = entry,
                workType = AutomationWorkType.QUEST,
                targetKey = action.questKey,
                status = AutomationWorkStatus.RUNNING,
                configVersion = entry.updatedAt.toString(),
                questCycle = action.questCycle,
                missionKey = action.missionKey,
                missionType = action.missionType.name,
                observedCurrent = action.missionCurrent,
                observedRequired = action.missionRequired,
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )
        processedResultRepository.save(
            QuestAutomationProcessedResultEntity(
                account = account,
                resultKind = QuestAutomationResultKind.BATTLE_VICTORY,
                resultIdentity = "legacy-result",
                actionFingerprint = legacyBattleFingerprint(action, 2),
                processedAt = NOW,
            ),
        )
        counterRepository.save(
            QuestMapExecutionCounterEntity(
                account = account,
                questKey = action.questKey,
                questCycle = action.questCycle,
                missionKey = action.missionKey,
                categoryId = action.categoryId,
                mapCode = action.mapCode,
                successfulRuns = 2,
            ),
        )
        val attempt = QuestAttempt.Battle("legacy-result", action)

        repeat(2) {
            assertIs<QuestRecordResult.Recorded>(
                questWorkCycle.recordObservedResult(account.id, attempt, QuestResultObservation.Page(emptyList())),
            )
        }

        assertEquals(2, queryRepository.findQuestMapWins(account.id, "q", "1", "kill", "battle_map", "chosen"))
        assertEquals(4, workQueries.findRunning(account.id)?.observedCurrent)
        assertFailsWith<QuestAutomationResultConflictException> {
            progressStore.recordBattleResult(
                account.id,
                "legacy-result",
                action.copy(missionType = QuestMissionType.MONSTER_KILL),
                2,
            )
        }
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
            progressStore.recordBattleResult(
                account.id,
                "battle-result",
                first.copy(missionType = QuestMissionType.MAP_CLEAR),
                2,
            )
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

    private fun legacyBattleFingerprint(action: QuestAction.Battle, victoryCount: Int): String {
        val fields = listOf(
            QuestAutomationResultKind.BATTLE_VICTORY.name,
            action.questKey,
            action.questCycle,
            action.missionKey,
            action.categoryId,
            action.mapCode,
            action.battleCount.toString(),
            victoryCount.toString(),
        )
        val canonical = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(fields.size)
                fields.forEach { field ->
                    val encoded = field.toByteArray(StandardCharsets.UTF_8)
                    output.writeInt(encoded.size)
                    output.write(encoded)
                }
            }
            bytes.toByteArray()
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical))
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-15T00:00:00Z")
    }
}
