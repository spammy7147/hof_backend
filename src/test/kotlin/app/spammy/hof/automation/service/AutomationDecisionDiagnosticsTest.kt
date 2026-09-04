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
}
