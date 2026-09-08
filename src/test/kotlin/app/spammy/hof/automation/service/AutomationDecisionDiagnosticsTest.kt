package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.battle.dto.BattlePatternLoadRequest
import app.spammy.hof.town.home.dto.HomeQuestResponse
import app.spammy.hof.town.home.model.HomeQuestState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import app.spammy.hof.town.fishing.dto.*
import app.spammy.hof.town.fishing.model.*
import tools.jackson.module.kotlin.jacksonObjectMapper

class AutomationDecisionDiagnosticsTest {
    @Test
    fun `퀘스트 맵 프리셋은 해석 결과만 남기고 전송할 파티와 패턴은 제외한다`() {
        val now = Instant.parse("2026-09-04T12:00:00Z")
        val party = ResolvedAutomationParty(listOf("private-character"), listOf(BattlePatternLoadRequest("private-character", 8)))
        val snapshot = AutomationEntrySnapshot(
            id = 1, type = AutomationType.QUEST,
            quest = QuestAutomationSnapshot(
                accountId = 9, quests = emptyList(), mapStates = emptyList(), currentCycles = emptyMap(),
                counters = emptyMap(), mapIdentityCandidates = emptyList(), now = now,
                selections = listOf(QuestAutomationSelection("quest-a", true, listOf(QuestAutomationMapSelection(
                    "mission-a", "battle_map", "map-a",
                    QuestPresetSelection(PresetSelectionMode.EXPLICIT, 3, resolutionChecked = true, resolvedParty = party),
                    0, false,
                )))),
            ),
        )
        val json = AutomationDecisionDiagnostics.capture("ENTRY_EVALUATION", now, snapshot)
        val map = jacksonObjectMapper().readTree(json)["snapshot"]["selections"][0]["maps"][0]
        assertEquals(3, map["presetId"].asInt())
        assertEquals(true, map["partyResolved"].asBoolean())
        assertFalse(json.contains("private-character"))
        assertFalse(json.contains("patternLoads"))
    }

    @Test
    fun `진단은 관측 상태와 순서를 남기고 원문과 제출 식별자는 제외한다`() {
        val now = Instant.parse("2026-09-04T12:00:00Z")
        val snapshot = AutomationEntrySnapshot(
            id = 1, type = AutomationType.HOME_QUEST,
            homeQuest = HomeQuestAutomationSnapshot(
                accountId = 9, now = now,
                selections = listOf(HomeQuestAutomationSelection("quest-a", "퀘스트 A", true, 3)),
                quests = listOf(HomeQuestResponse(
                    "quest-a", "퀘스트 A", HomeQuestState.AVAILABLE,
                    "private-mission", "private-reward", listOf("<html>private-response</html>"), "private-action",
                )),
                timeSnapshot = AutomationTimeSnapshot(40, 100, now),
                workSessionId = 12,
            ),
        )
        val json = AutomationDecisionDiagnostics.capture("ENTRY_EVALUATION", now, snapshot)
        val context = jacksonObjectMapper().readTree(json)["snapshot"]
        assertEquals("AVAILABLE", context["quests"][0]["state"].asString())
        assertEquals(true, context["quests"][0]["actionPresent"].asBoolean())
        assertEquals(3, context["selections"][0]["order"].asInt())
        assertEquals(40, context["time"]["current"].asInt())
        assertEquals(12, context["workSessionId"].asInt())
        assertFalse(json.contains("private-"))
        assertFalse(json.contains("accountId"))
    }

    @Test
    fun `낚시 실행 진단은 원문과 제출값을 제외하고 현재 상태와 복구 의도를 보존한다`() {
        val now = Instant.parse("2026-09-08T00:00:00Z")
        val response = FishingResponse(
            notice = "private-cookie", remainingCasts = 5, waterStatus = "private-html",
            baitCount = 1, shiningBaitCount = 0, escapeSeconds = null, combo = 0,
            locationName = "private-location", primaryAction = FishingPrimaryAction.NONE,
            availableActions = emptySet(), lastOutcome = null, blockedByBattle = true,
            battleTarget = FishingBattleTargetResponse("battle_map", "Fish03", "private-name"),
            catches = emptyList(), result = TownActionResultResponse("UNKNOWN", listOf("private-token"), emptyList()),
        )
        val stored = StoredTypedAutomationAction(2, "execution-3",
            StoredTypedActionPayload.FishingTown(FishingAction.START, FishingPrimaryAction.START, 5))
        val context = AutomationDecisionDiagnostics.actionResult(
            AutomationDecisionDiagnostics.fishingAction(stored, response, "DIRECT_RESPONSE", now), "FISHING_BATTLE_RECOVERED_FROM_START", now.plusSeconds(3))
        val node = jacksonObjectMapper().readTree(context)
        assertEquals("Fish03", node["fishing"]["battleMapCode"].asString())
        assertEquals(true, node["recheckRequired"].asBoolean())
        assertEquals("execution-3", node["executionIdentity"].asString())
        assertEquals(now.plusSeconds(3).toString(), node["nextCheckAt"].asString())
        assertFalse(context.contains("private-"))
    }

    @Test
    fun `큰 진단도 이스케이프한 뒤 저장 크기 제한과 잘림 표시를 지킨다`() {
        val context = AutomationDecisionDiagnostics.encode(mapOf("stage" to "ACTION_RESULT", "mapCode" to "\\".repeat(20_000)))
        assertTrue(context.length <= 16_384, "실제 JSON 크기: ${context.length}")
        val node = jacksonObjectMapper().readTree(context)
        assertTrue(node["truncated"].asBoolean())
        assertEquals("ACTION_RESULT", node["stage"].asString())
    }

}
