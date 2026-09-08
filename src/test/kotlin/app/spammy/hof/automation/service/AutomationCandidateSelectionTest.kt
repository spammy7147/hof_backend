package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.automation.convergence.*
import app.spammy.hof.automation.entity.*
import app.spammy.hof.automation.raid.RaidCycleModule
import app.spammy.hof.automation.repository.*
import app.spammy.hof.battle.model.BattleMapKeyMode
import app.spammy.hof.battle.dto.BattlePatternLoadRequest
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.quest.model.*
import app.spammy.hof.town.home.dto.HomeQuestResponse
import app.spammy.hof.town.home.model.HomeQuestState
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito
import tools.jackson.module.kotlin.jacksonObjectMapper

/** 실제 후보 판단과 공통 수렴 가드를 연결하고 DB·외부 관측 포트만 대체한다. */
class AutomationCandidateSelectionTest {
    private val now = Instant.parse("2026-09-04T00:00:00Z")
    private val account = HofAccountEntity(7, "candidate", "encrypted", now)
    private val typed = Mockito.mock(TypedAutomationQueryRepository::class.java)
    private val work = Mockito.mock(AutomationWorkSessionQueryRepository::class.java)
    private val loader = Mockito.mock(TypedAutomationSnapshotLoader::class.java)
    private val lifecycle = Mockito.mock(AutomationWorkLifecycle::class.java)
    private val progress = Mockito.mock(QuestAutomationProgressStore::class.java)
    private val store = InMemoryConvergenceStore()
    private val factory = StoredActionConvergenceSelectionFactory()
    private val lower = AutomationEntryEntity(11, account, AutomationType.BATTLE_MAP, 1, true, now, now)
    private val lowerAction = BattleMapAutomationAction(7, LocalDate.parse("2026-09-04"), "battle_map", "lower",
        PresetSelectionMode.PRIMARY, 1, 1, "lower-execution")
    private val selector = AutomationTargetSelector(typed, work, loader, lifecycle, TimeProvider { now },
        Mockito.mock(RaidCycleModule::class.java), DefaultQuestWorkCycleModule(progress),
        AutomationHandler { HandlerEvaluation.Runnable(lowerAction) },
        AutomationHandler { HandlerEvaluation.Skipped }, AutomationHandler { HandlerEvaluation.Skipped },
        AutomationHandler { HandlerEvaluation.Skipped }, HomeQuestAutomationHandler(),
        convergenceRollout = AutomationConvergenceRollout(AutomationConvergenceProperties(mode = AutomationConvergenceMode.ACTIVE)),
        convergenceModule = DefaultAutomationActionConvergenceModule(store, TimeProvider { now }))

    @ParameterizedTest
    @EnumSource(value = AutomationType::class, names = ["HOME_QUEST", "QUEST"])
    fun `보류된 완료 A 뒤의 완료 B를 새 수락과 하위 항목보다 먼저 선택한다`(type: AutomationType) {
        configure(type)
        hold(action(type, "A"))

        val selected = assertIs<AutomationCoordination.Runnable>(selector.select(7))

        assertEquals(action(type, "B"), selected.action)
        assertEquals(1, selected.trace.size)
        val diagnostic = jacksonObjectMapper().readTree(selected.trace.single().diagnosticContext)
        val excluded = diagnostic["excludedCandidates"].single()
        assertEquals("A", excluded["targetKey"].asString())
        assertEquals("CONVERGENCE_SCOPE_BLOCKED", excluded["reasonCode"].asString())
        assertEquals(now.toString(), excluded["observedAt"].asString())
        assertEquals(AutomationDecisionOutcome.SELECTED, selected.trace.single().outcome)
        Mockito.verify(loader).loadEntry(7, 10, null, null)
        Mockito.verify(loader, Mockito.never()).loadEntry(7, lower.id, null, null)
        Mockito.verifyNoInteractions(progress)
    }

    @ParameterizedTest
    @EnumSource(value = AutomationType::class, names = ["HOME_QUEST", "QUEST"])
    fun `모든 후보가 막히면 유한하게 스킵하고 다음 판단은 다시 첫 항목부터 확인한다`(type: AutomationType) {
        configure(type)
        val a = hold(action(type, "A"))
        hold(action(type, "B"))
        hold(action(type, "C", claim = false))

        val lowerSelected = assertIs<AutomationCoordination.Runnable>(selector.select(7))

        assertEquals(lowerAction, lowerSelected.action)
        assertEquals(listOf(10L, 11L), lowerSelected.trace.map { it.entryId })
        assertEquals(AutomationDecisionOutcome.SKIPPED, lowerSelected.trace.first().outcome)
        val diagnostic = jacksonObjectMapper().readTree(lowerSelected.trace.first().diagnosticContext)
        assertEquals(listOf("A", "B", "C"), diagnostic["excludedCandidates"].toList().map { it["targetKey"].asString() })
        assertEquals(3, store.findSuppressedBaselines(7).size)
        store.releaseSuppression(7, a.attemptId, now)

        assertEquals(action(type, "A"), assertIs<AutomationCoordination.Runnable>(selector.select(7)).action)
        Mockito.verify(loader, Mockito.times(2)).loadEntry(7, 10, null, null)
        Mockito.verify(loader, Mockito.times(1)).loadEntry(7, 11, null, null)
        Mockito.verifyNoInteractions(progress)
    }

    @ParameterizedTest
    @EnumSource(value = AutomationType::class, names = ["HOME_QUEST", "QUEST"])
    fun `실행 중 A는 먼저 양보하며 다음 판단의 B가 A 작업에 귀속되지 않는다`(type: AutomationType) {
        configure(type)
        val running = AutomationWorkSessionView(id = 30, accountId = 7, entryId = 10, entryPriority = 0,
            workType = AutomationWorkType.valueOf(type.name), targetKey = "A", status = AutomationWorkStatus.RUNNING, materialName = null, nextCheckAt = null)
        Mockito.`when`(work.findRunning(7)).thenReturn(running, null)
        Mockito.`when`(progress.findRunningWork(7, 30)).thenReturn(QuestRunningWorkSnapshot(30, "A", null, revision = running.revision))
        hold(action(type, "A"))

        assertIs<AutomationCoordination.CycleBoundary>(selector.select(7))

        Mockito.verify(lifecycle).waitForCooldown(7, 30, now.plusSeconds(30))
        Mockito.verify(typed, Mockito.never()).findEntries(7)
        Mockito.verify(loader, Mockito.never()).loadEntry(7, 10, null, null)
        Mockito.verify(lifecycle, Mockito.never()).applyTransition(7, 30, AutomationWorkTransition.Complete)
        Mockito.`when`(work.findWaiting(7)).thenReturn(listOf(running.copy(
            status = AutomationWorkStatus.WAITING_COOLDOWN, nextCheckAt = now.plusSeconds(30))))

        val selected = assertIs<AutomationCoordination.Runnable>(selector.select(7))

        assertEquals(action(type, "B"), selected.action)
        assertNull(selected.trace.single().workSessionId)
        Mockito.verify(loader).loadEntry(7, 10, "A", null)
        Mockito.verify(loader).loadEntry(7, 10, null, null)
        Mockito.verify(lifecycle, Mockito.never()).resumeForCheck(7, 30)
    }

    @Test
    fun `A 보류와 B 식별자 누락이 함께 있으면 B 재확인 사유와 A 제외 진단을 함께 남긴다`() {
        configure(AutomationType.HOME_QUEST)
        hold(action(AutomationType.HOME_QUEST, "A"))
        Mockito.`when`(loader.loadEntry(7, 10, null, null)).thenReturn(AutomationEntrySnapshot(10, AutomationType.HOME_QUEST,
            homeQuest = HomeQuestAutomationSnapshot(7,
                listOf(HomeQuestResponse("A", "A", HomeQuestState.CLAIMABLE, null, null, emptyList(), "A-action"),
                    HomeQuestResponse("B", "B", HomeQuestState.AVAILABLE, null, null, emptyList(), null)),
                listOf(HomeQuestAutomationSelection("A", "A", true, 0), HomeQuestAutomationSelection("B", "B", true, 1)), now)))

        val selected = assertIs<AutomationCoordination.Runnable>(selector.select(7))

        assertEquals(lowerAction, selected.action)
        val trace = selected.trace.first()
        assertEquals(AutomationDecisionOutcome.SKIPPED, trace.outcome)
        assertEquals("HOME_ACTION_ID_MISSING", trace.reasonCode)
        assertEquals("B", trace.targetKey)
        assertEquals(now.plusSeconds(10), trace.nextRunAt)
        assertEquals("A", jacksonObjectMapper().readTree(trace.diagnosticContext)["excludedCandidates"][0]["targetKey"].asString())
        assertEquals(1, store.findActiveScopes(7).size)
        assertEquals(1, store.findSuppressedBaselines(7).size)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `일반 퀘스트의 막힌 전투 다음 전투를 선택하고 캡차 관문에서는 수락만 선택한다`(sameQuest: Boolean) {
        configure(AutomationType.QUEST)
        val party = ResolvedAutomationParty(listOf("1"), listOf(BattlePatternLoadRequest("1", 1)))
        val context = QuestAutomationSnapshot(7,
            listOf("A", "B").mapIndexed { order, id -> QuestSnapshot(id, id, QuestState.ACTIVE, QuestSection.ACTIVE, order,
                listOf(QuestMission(id, QuestMissionType.MONSTER_KILL, id, QuestProgress(0, 10), false)), null) } +
                QuestSnapshot("C", "C", QuestState.AVAILABLE, QuestSection.AVAILABLE, 2, emptyList(), "C-action"),
            listOf("A", "B").map { id -> QuestAutomationSelection(id, true,
                listOf(QuestAutomationMapSelection(id, "battle_map", id, QuestPresetSelection(PresetSelectionMode.PRIMARY), 0, false))) } +
                QuestAutomationSelection("C", true, emptyList()),
            listOf("A", "B").map { AutomationMapState("battle_map", it, it, true, true, null,
                null, null, null, BattleMapKeyMode.NOT_REQUIRED, null) },
            emptyMap(), emptyMap(), emptyList(), now, primaryPresetId = 1, primaryParty = party,
            timeSnapshot = AutomationTimeSnapshot(100, 100, now))
        val observed = if (sameQuest) context.copy(
            quests = listOf(context.quests.first().copy(questKey = "shared", missions = context.quests.take(2).flatMap { it.missions }), context.quests.last()),
            selections = listOf(QuestAutomationSelection("shared", true, context.selections.take(2).flatMap { it.maps }), context.selections.last()),
        ) else context
        Mockito.`when`(loader.loadEntry(7, 10, null, null)).thenReturn(AutomationEntrySnapshot(10, AutomationType.QUEST, quest = observed))
        val first = assertIs<QuestDirective.Execute>(DefaultQuestWorkCycleModule(progress).decideNext(observed)).action
        assertEquals("A", assertIs<QuestAction.Battle>(first).missionKey)
        hold(first)

        val next = assertIs<QuestAction.Battle>(assertIs<AutomationCoordination.Runnable>(selector.select(7)).action)
        assertEquals("B", next.missionKey)
        assertEquals(1, store.findSuppressedBaselines(7).size)
        assertEquals("B", assertIs<QuestAction.Battle>(assertIs<AutomationCoordination.Runnable>(selector.select(7)).action).missionKey)
        if (sameQuest) {
            hold(next)
            assertEquals("C", assertIs<QuestAction.Accept>(assertIs<AutomationCoordination.Runnable>(selector.select(7)).action).questKey)
            assertEquals(2, store.findSuppressedBaselines(7).values.single().size)
            val changed = observed.copy(quests = observed.quests.map { quest ->
                quest.copy(missions = quest.missions.map { mission ->
                    if (mission.key == "A") mission.copy(progress = QuestProgress(1, 10)) else mission
                })
            })
            Mockito.`when`(loader.loadEntry(7, 10, null, null)).thenReturn(AutomationEntrySnapshot(10, AutomationType.QUEST, quest = changed))
            assertEquals("A", assertIs<QuestAction.Battle>(assertIs<AutomationCoordination.Runnable>(selector.select(7)).action).missionKey)
            assertEquals(setOf(requireNotNull(factory.preview(10, next).baselineFingerprint)), store.findSuppressedBaselines(7).values.single())
        }
        store.openBattleGate(7, 1, "캡차", now)
        assertEquals("C", assertIs<QuestAction.Accept>(assertIs<AutomationCoordination.Runnable>(selector.select(7)).action).questKey)
        Mockito.verifyNoInteractions(progress)
        assertEquals(1, store.findSuppressedBaselines(7).size)
    }

    @ParameterizedTest
    @EnumSource(value = AutomationType::class, names = ["HOME_QUEST", "QUEST"])
    fun `최신 관측으로 보류 기준이 바뀌면 기존 정책대로 첫 후보를 다시 선택한다`(type: AutomationType) {
        configure(type)
        val old = when (val current = action(type, "A")) {
            is HomeQuestAutomationAction -> current.copy(actionId = "old-action")
            is QuestAction.Claim -> current.copy(actionNo = "old-action")
            else -> error("claim expected")
        }
        val record = hold(old)

        assertEquals(action(type, "A"), assertIs<AutomationCoordination.Runnable>(selector.select(7)).action)
        assertEquals(ActionConvergenceResult.HELD, store.get(record.attemptId)?.result)
        assertEquals(emptyMap(), store.findSuppressedBaselines(7))
    }

    private fun configure(type: AutomationType) {
        val entry = AutomationEntryEntity(10, account, type, 0, true, now, now)
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(entry, lower))
        fun snapshot(ids: List<String>) = when (type) {
            AutomationType.HOME_QUEST -> AutomationEntrySnapshot(10, type, homeQuest = HomeQuestAutomationSnapshot(7,
                ids.map { HomeQuestResponse(it, it, if (it == "C") HomeQuestState.AVAILABLE else HomeQuestState.CLAIMABLE,
                    null, null, emptyList(), "$it-action") },
                ids.mapIndexed { order, id -> HomeQuestAutomationSelection(id, id, true, order) }, now))
            else -> AutomationEntrySnapshot(10, type, quest = QuestAutomationSnapshot(7,
                ids.mapIndexed { order, id -> QuestSnapshot(id, id,
                    if (id == "C") QuestState.AVAILABLE else QuestState.CLAIMABLE,
                    if (id == "C") QuestSection.AVAILABLE else QuestSection.ACTIVE, order, emptyList(), "$id-action") },
                ids.map { QuestAutomationSelection(it, true, emptyList()) }, emptyList(), emptyMap(), emptyMap(), emptyList(), now))
        }
        Mockito.`when`(loader.loadEntry(7, 10, null, null)).thenReturn(snapshot(listOf("A", "B", "C")))
        Mockito.`when`(loader.loadEntry(7, 10, "A", null)).thenReturn(snapshot(listOf("A")))
        Mockito.`when`(loader.loadEntry(7, 11, null, null)).thenReturn(AutomationEntrySnapshot(11, AutomationType.BATTLE_MAP,
            battle = BattleMapAutomationSnapshot(7, emptyList(), emptyList(), emptyMap(), null, emptySet(), "lower", now)))
    }

    private fun action(type: AutomationType, id: String, claim: Boolean = true): PreparedAutomationAction = when (type) {
        AutomationType.HOME_QUEST -> HomeQuestAutomationAction(7, id, id, "$id-action",
            if (claim) HomeQuestAutomationActionType.CLAIM else HomeQuestAutomationActionType.ACCEPT)
        else -> if (claim) QuestAction.Claim(id, "$id-action", id, "0") else QuestAction.Accept(id, "$id-action", id, "0")
    }

    private fun hold(action: PreparedAutomationAction): ActionConvergenceRecord {
        val preview = factory.preview(10, action)
        return store.createOrGet(7, SelectedAutomationAction(10, UUID.randomUUID().toString(), preview.actionKind,
            preview.scope, "automation-action-convergence-v1", requireNotNull(preview.baselineFingerprint)), now).also {
            it.result = ActionConvergenceResult.HELD
            it.finishedAt = now
            store.save(it)
        }
    }
}
