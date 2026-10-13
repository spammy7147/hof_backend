package app.spammy.hof.automation.convergence

import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.automation.service.QuestAction
import app.spammy.hof.automation.service.QuestAutomationSnapshot
import app.spammy.hof.automation.service.QuestPresetSelection
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.quest.model.*
import java.time.Instant
import kotlin.test.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class QuestBattleHeldRecoveryTest {
    private val now = Instant.parse("2026-10-13T02:00:00Z")
    private val store = InMemoryConvergenceStore()
    private val module = DefaultAutomationActionConvergenceModule(store, TimeProvider { now })

    @ParameterizedTest
    @CsvSource("LEGACY,MAP_CLEAR", "SHADOW,MAP_CLEAR", "ACTIVE,MAP_CLEAR",
        "LEGACY,MONSTER_KILL", "SHADOW,MONSTER_KILL", "ACTIVE,MONSTER_KILL")
    fun `보류 뒤 완전한 같은 진행도로 새 전투를 판단하고 이전 결과는 미관측으로 보존한다`(
        mode: AutomationConvergenceMode, type: QuestMissionType,
    ) {
        val action = action(type)
        val held = hold(action)

        assertNull(module.openSelection(7, 11, snapshot(action), mode).block(action))
        assertTrue(store.findSuppressedBaselines(7).isEmpty())
        assertEquals(ActionConvergenceResult.RESULT_UNOBSERVED, store.get(held.attemptId)?.result)
        assertEquals("PENDING_BUDGET_EXHAUSTED", store.get(held.attemptId)?.reasonCode)
        assertEquals(0, store.get(held.attemptId)?.successfulObservationCount)
        val next = assertIs<ConvergenceDirective.Submit>(module.prepare(7,
            held.selection.copy(executionIdentity = "새-전투")))
        assertNotEquals(held.attemptId, next.attemptId)
        assertEquals("새-전투", next.selection.executionIdentity)
    }

    @ParameterizedTest
    @ValueSource(strings = ["INCOMPLETE", "MISSING", "NEGATIVE", "INVALID_REQUIRED", "DUPLICATE_MISSION",
        "DUPLICATE_QUEST", "OTHER_ACCOUNT", "STALE", "POLICY", "CAPTCHA", "OBSERVATION_ONLY", "CLAIM", "NO_SNAPSHOT"])
    fun `불완전한 관측과 다른 보호 사유는 진행도 복구로 해제하지 않는다`(condition: String) {
        val action = action()
        val base = selection(action)
        val original = when (condition) {
            "POLICY" -> base.copy(policyVersion = "unsupported-policy")
            "OBSERVATION_ONLY" -> base.copy(observationOnly = true)
            "CLAIM" -> base.copy(actionKind = AutomationActionKind.QUEST_CLAIM)
            else -> base
        }
        val record = store.createOrGet(7, original, now.minusSeconds(181)).apply {
            result = ActionConvergenceResult.RESULT_UNOBSERVED
            finishedAt = now.minusSeconds(1)
            reasonCode = "PENDING_BUDGET_EXHAUSTED"
        }
        store.save(record)
        val observed = snapshot(action)
        val quest = observed.quests.single()
        val mission = quest.missions.single()
        val changedQuest = when (condition) {
            "MISSING" -> quest.copy(missions = listOf(mission.copy(progress = null)))
            "NEGATIVE" -> quest.copy(missions = listOf(mission.copy(progress = QuestProgress(-1, 2))))
            "INVALID_REQUIRED" -> quest.copy(missions = listOf(mission.copy(progress = QuestProgress(0, 0))))
            "DUPLICATE_MISSION" -> quest.copy(missions = listOf(mission, mission))
            else -> quest
        }
        val latest = when (condition) {
            "INCOMPLETE" -> observed.copy(pageComplete = false)
            "OTHER_ACCOUNT" -> observed.copy(accountId = 8)
            "STALE" -> observed.copy(now = now.minusSeconds(2))
            "DUPLICATE_QUEST" -> observed.copy(quests = listOf(quest, quest))
            "NO_SNAPSHOT" -> null
            else -> observed.copy(quests = listOf(changedQuest))
        }
        if (condition == "CAPTCHA") module.requireBattleGate(7, 1, "캡차 필요", now)

        assertNotNull(module.openSelection(7, 11, latest, AutomationConvergenceMode.ACTIVE).block(action))
        assertEquals(setOf(original.baselineFingerprint), store.findSuppressedBaselines(7)[original.scope])
        assertEquals(ActionConvergenceResult.RESULT_UNOBSERVED, store.get(record.attemptId)?.result)
    }

    @Test
    fun `아직 결과 확인 중인 같은 퀘스트는 새 전투를 허용하지 않는다`() {
        val action = action()
        val pending = assertIs<ConvergenceDirective.Submit>(module.prepare(7, selection(action)))
        assertNotNull(module.openSelection(7, 11, snapshot(action), AutomationConvergenceMode.ACTIVE).block(action))
        assertEquals(ActionConvergenceResult.PENDING, store.get(pending.attemptId)?.result)
    }

    @Test
    fun `다른 미션의 후보는 같은 퀘스트 안의 보류를 풀지 않는다`() {
        val heldAction = action()
        val held = hold(heldAction)
        val other = heldAction.copy(missionKey = "다른-미션", mapCode = "other")
        val both = snapshot(heldAction).let { snapshot -> snapshot.copy(quests = listOf(
            snapshot.quests.single().copy(missions = snapshot.quests.single().missions + snapshot(other).quests.single().missions))) }
        assertNull(module.openSelection(7, 11, both, AutomationConvergenceMode.ACTIVE).block(other))
        assertEquals(setOf(held.selection.baselineFingerprint), store.findSuppressedBaselines(7)[held.selection.scope])
    }

    private fun action(type: QuestMissionType = QuestMissionType.MAP_CLEAR) = QuestAction.Battle(
        "quest-1", "1", "mission-1", type, "battle_map", "map-1", "검증 맵",
        QuestPresetSelection(PresetSelectionMode.PRIMARY, 3), missionCurrent = 0, missionRequired = 2,
    )

    private fun selection(action: QuestAction.Battle): SelectedAutomationAction {
        val preview = StoredActionConvergenceSelectionFactory().preview(11, action)
        return SelectedAutomationAction(11, "이전-전투", preview.actionKind, preview.scope,
            ProductionActionEvidenceInterpreter.VERSION_1, requireNotNull(preview.baselineFingerprint))
    }

    private fun hold(action: QuestAction.Battle): ActionConvergenceRecord {
        val selected = selection(action)
        module.holdUnresolved(7, selected,
            AutomationActionEvidence.ResultUnobserved(now.minusSeconds(1), "503으로 관측하지 못함"),
            0, now.minusSeconds(181))
        return requireNotNull(store.get(7, selected.executionIdentity))
    }

    private fun snapshot(action: QuestAction.Battle) = QuestAutomationSnapshot(
        7, listOf(QuestSnapshot(action.questKey, "검증 퀘스트", QuestState.ACTIVE, QuestSection.ACTIVE, 0,
            listOf(QuestMission(action.missionKey, action.missionType, "검증 맵",
                QuestProgress(requireNotNull(action.missionCurrent), requireNotNull(action.missionRequired)), false)), "current-action")),
        emptyList(), emptyList(), mapOf(action.questKey to action.questCycle), emptyMap(), emptyList(), now,
        pageComplete = true,
    )
}
