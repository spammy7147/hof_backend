package app.spammy.hof.automation.service

import app.spammy.hof.automation.convergence.AutomationConvergenceSelectionConstraints
import app.spammy.hof.automation.convergence.AutomationConvergenceSelectionGuard
import app.spammy.hof.automation.convergence.AutomationActionEvidence
import app.spammy.hof.automation.convergence.AutomationActionKind
import app.spammy.hof.automation.convergence.AutomationConvergenceMode
import app.spammy.hof.automation.convergence.AutomationConvergenceProperties
import app.spammy.hof.automation.convergence.AutomationConvergenceRollout
import app.spammy.hof.automation.convergence.ConvergenceDirective
import app.spammy.hof.automation.convergence.AutomationIsolationScope
import app.spammy.hof.automation.convergence.AutomationIsolationScopeKind
import app.spammy.hof.automation.convergence.DefaultAutomationActionConvergenceModule
import app.spammy.hof.automation.convergence.InMemoryConvergenceStore
import app.spammy.hof.automation.convergence.StoreBackedAutomationConvergenceSelectionGuard
import app.spammy.hof.automation.convergence.StoredActionConvergenceSelectionFactory
import app.spammy.hof.automation.convergence.SelectedAutomationAction
import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.automation.entity.AutomationEntryEntity
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.AutomationWorkStatus
import app.spammy.hof.automation.entity.AutomationWorkType
import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.automation.raid.RaidCycleModule
import app.spammy.hof.automation.raid.RaidCycleOutcome
import app.spammy.hof.automation.raid.RaidCycleOutcomeKind
import app.spammy.hof.automation.raid.RaidAuthoritativeState
import app.spammy.hof.automation.raid.RaidDecision
import app.spammy.hof.automation.raid.RaidCooldownSource
import app.spammy.hof.automation.raid.RaidDirective
import app.spammy.hof.automation.raid.RaidIntent
import app.spammy.hof.automation.raid.RaidIntentKind
import app.spammy.hof.automation.raid.RaidHoldReason
import app.spammy.hof.automation.raid.RaidWaitReason
import app.spammy.hof.automation.repository.AutomationWorkSessionQueryRepository
import app.spammy.hof.automation.repository.AutomationWorkSessionView
import app.spammy.hof.automation.repository.TypedAutomationQueryRepository
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.quest.model.QuestMission
import app.spammy.hof.quest.model.QuestMissionType
import app.spammy.hof.quest.model.QuestProgress
import app.spammy.hof.quest.model.QuestSection
import app.spammy.hof.quest.model.QuestSnapshot
import app.spammy.hof.quest.model.QuestState
import app.spammy.hof.town.fishing.dto.FishingResponse
import app.spammy.hof.town.fishing.model.FishingPrimaryAction
import app.spammy.hof.town.raid.model.RaidAction
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.Mockito
import org.mockito.ArgumentCaptor
import app.spammy.hof.automation.history.AutomationDecisionJournal
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertFalse

class AutomationTargetSelectorTest {
    private val now = Instant.parse("2026-07-23T00:00:00Z")
    private val account = HofAccountEntity(7, "target-selector", "encrypted", now)
    private val questEntry = AutomationEntryEntity(10, account, AutomationType.QUEST, 0, true, now, now)
    private val battleEntry = AutomationEntryEntity(11, account, AutomationType.BATTLE_MAP, 1, true, now, now)
    private val adventureEntry = AutomationEntryEntity(12, account, AutomationType.ADVENTURE_MAP, 2, true, now, now)
    private val raidEntry = AutomationEntryEntity(13, account, AutomationType.RAID, 0, true, now, now)
    private val fishingEntry = AutomationEntryEntity(14, account, AutomationType.FISHING, 2, true, now, now)
    private val typed = Mockito.mock(TypedAutomationQueryRepository::class.java)
    private val work = Mockito.mock(AutomationWorkSessionQueryRepository::class.java)
    private val loader = Mockito.mock(TypedAutomationSnapshotLoader::class.java)
    private val lifecycle = Mockito.mock(AutomationWorkLifecycle::class.java)
    private val defaultRaidModule = Mockito.mock(RaidCycleModule::class.java)
    private val questRules = ScriptedQuestRules()
    private val battleRules = ScriptedHandler<BattleMapAutomationSnapshot>()
    private val adventureRules = ScriptedHandler<AdventureMapAutomationSnapshot>()
    private val unionRules = ScriptedHandler<UnionAutomationSnapshot>()
    private val fishingRules = ScriptedHandler<FishingAutomationSnapshot>()
    private val homeQuestRules = ScriptedHandler<HomeQuestAutomationSnapshot>()
    private val decisionJournal = Mockito.mock(AutomationDecisionJournal::class.java)
    private val selector = AutomationTargetSelector(
        typed,
        work,
        loader,
        lifecycle,
        TimeProvider { now },
        defaultRaidModule,
        questRules,
        battleRules,
        adventureRules,
        unionRules,
        fishingRules,
        homeQuestRules,
        decisionJournal = decisionJournal,
    )

    @Test
    fun `상태 조회 오류는 앞서 스킵한 항목과 실패 위치를 기록하고 원래 재시도 예외를 유지한다`() {
        val battle = battleDecisionEntry()
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(battleEntry, adventureEntry))
        Mockito.`when`(loader.loadEntry(7, battleEntry.id, null)).thenReturn(battle)
        battleRules.returns(requireNotNull(battle.battle), HandlerEvaluation.Skipped)
        val failure = SafeRetryableAutomationException("cookie=secret-cookie", java.io.IOException("token=secret-token"))
        Mockito.`when`(loader.loadEntry(7, adventureEntry.id, null)).thenThrow(failure)

        assertSame(failure, assertFailsWith<SafeRetryableAutomationException> { selector.select(7) })

        val recorded = ArgumentCaptor.forClass(AutomationCoordination::class.java)
        Mockito.verify(decisionJournal).appendDecision(Mockito.eq(7L), recorded.capture() ?: AutomationCoordination.Idle(emptyList()))
        assertIs<AutomationCoordination.Idle>(recorded.value)
        assertEquals(listOf(battleEntry.id, adventureEntry.id), recorded.value.trace.map { it.entryId })
        assertEquals(AutomationDecisionOutcome.SKIPPED, recorded.value.trace.first().outcome)
        val event = recorded.value.trace.last()
        assertEquals("SNAPSHOT_LOAD_FAILED", event.reasonCode)
        val json = requireNotNull(event.diagnosticContext)
        val context = jacksonObjectMapper().readTree(json)
        assertEquals("SNAPSHOT_LOAD", context["stage"].asString())
        assertEquals("java.io.IOException", context["errors"][1]["type"].asString())
        assertTrue(context["snapshot"].isNull)
        assertFalse(json.contains("secret-cookie"))
        assertFalse(json.contains("secret-token"))
    }

    @Test
    fun `판단 코드 오류는 성공적으로 읽은 상태와 실패 단계를 함께 보존한다`() {
        val battle = battleDecisionEntry()
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(battleEntry))
        Mockito.`when`(loader.loadEntry(7, battleEntry.id, null)).thenReturn(battle)
        // 응답을 지정하지 않은 handler가 평가 시점에 예외를 던진다.
        assertFailsWith<IllegalStateException> { selector.select(7) }
        val recorded = ArgumentCaptor.forClass(AutomationCoordination::class.java)
        Mockito.verify(decisionJournal).appendDecision(Mockito.eq(7L), recorded.capture() ?: AutomationCoordination.Idle(emptyList()))
        val event = recorded.value.trace.single()
        assertEquals("ENTRY_EVALUATION_FAILED", event.reasonCode)
        val context = jacksonObjectMapper().readTree(event.diagnosticContext)
        assertEquals(0, context["snapshot"]["targetCount"].asInt())
        assertEquals("java.lang.IllegalStateException", context["errors"][0]["type"].asString())
    }

    @Test
    fun `진단 저장 실패가 기존 오류의 복구 방식을 바꾸지 않는다`() {
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(battleEntry))
        val failure = SafeRetryableAutomationException("일시적인 연결 실패")
        Mockito.`when`(loader.loadEntry(7, battleEntry.id, null)).thenThrow(failure)
        Mockito.`when`(decisionJournal.appendDecision(Mockito.eq(7L), Mockito.any(AutomationCoordination::class.java)
            ?: AutomationCoordination.Idle(emptyList()))).thenThrow(IllegalStateException("DB unavailable"))
        assertSame(failure, assertFailsWith<SafeRetryableAutomationException> { selector.select(7) })
    }

    @Test
    fun `레이드 판단 예외도 해당 항목의 실패 이력으로 남긴다`() {
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(raidEntry))
        val failure = SafeRetryableAutomationException("레이드 관측 실패")
        Mockito.`when`(defaultRaidModule.decide(7)).thenThrow(failure)
        assertSame(failure, assertFailsWith<SafeRetryableAutomationException> { selector.select(7) })
        val recorded = ArgumentCaptor.forClass(AutomationCoordination::class.java)
        Mockito.verify(decisionJournal).appendDecision(Mockito.eq(7L), recorded.capture() ?: AutomationCoordination.Idle(emptyList()))
        assertEquals(raidEntry.id, recorded.value.trace.single().entryId)
        assertEquals("RAID_EVALUATION_FAILED", recorded.value.trace.single().reasonCode)
    }

    @Test
    fun `설정 변경 재판단은 오류 이력을 만들지 않는다`() {
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(battleEntry))
        Mockito.`when`(loader.loadEntry(7, battleEntry.id, null)).thenThrow(TypedAutomationConfigurationChangedException())
        assertFailsWith<TypedAutomationConfigurationChangedException> { selector.select(7) }
        Mockito.verifyNoInteractions(decisionJournal)
    }

    @Test
    fun `first runnable priority stops lower entry probes`() {
        val action = QuestAction.Accept("quest-1", "accept-1")
        val questSnapshot = AutomationEntrySnapshot(
            10,
            AutomationType.QUEST,
            quest = QuestAutomationSnapshot(
                accountId = 7,
                quests = emptyList(),
                selections = emptyList(),
                mapStates = emptyList(),
                currentCycles = emptyMap(),
                counters = emptyMap(),
                mapIdentityCandidates = emptyList(),
                now = now,
            ),
        )
        val directSelector = AutomationTargetSelector(
            typed = typed,
            work = work,
            loader = loader,
            lifecycle = lifecycle,
            timeProvider = TimeProvider { now },
            raidModule = defaultRaidModule,
            quest = fixedQuestRules(QuestDirective.Execute(action)),
            battle = AutomationHandler { HandlerEvaluation.Skipped },
            adventure = AutomationHandler { HandlerEvaluation.Skipped },
            union = AutomationHandler { HandlerEvaluation.Skipped },
            fishing = AutomationHandler { HandlerEvaluation.Skipped },
            homeQuest = AutomationHandler { HandlerEvaluation.Skipped },
        )
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(questEntry, battleEntry, adventureEntry))
        Mockito.`when`(work.findRunning(7)).thenReturn(null)
        Mockito.`when`(loader.loadEntry(7, 10, null)).thenReturn(questSnapshot)

        val selected = assertIs<AutomationCoordination.Runnable>(directSelector.select(7))

        assertEquals(10, selected.entryId)
        assertEquals(10, assertIs<AutomationCoordination.Runnable>(directSelector.select(7)).entryId)
        Mockito.verify(loader, Mockito.never()).loadEntry(7, 11, null)
        Mockito.verify(loader, Mockito.never()).loadEntry(7, 12, null)
    }

    @Test
    fun `open lower work keeps ownership before a reordered higher entry`() {
        val directSelector = AutomationTargetSelector(
            typed = typed,
            work = work,
            loader = loader,
            lifecycle = lifecycle,
            timeProvider = TimeProvider { now },
            raidModule = defaultRaidModule,
            quest = fixedQuestRules(QuestDirective.Execute(QuestAction.Accept("quest-1", "accept-1"))),
            battle = AutomationHandler { HandlerEvaluation.Skipped },
            adventure = AutomationHandler { HandlerEvaluation.Skipped },
            union = AutomationHandler { HandlerEvaluation.Skipped },
            fishing = AutomationHandler { HandlerEvaluation.Skipped },
            homeQuest = AutomationHandler { HandlerEvaluation.Skipped },
        )
        val running = session(
            id = 91,
            entry = battleEntry,
            workType = AutomationWorkType.BATTLE_MAP,
            targetKey = "battle_map/map-1",
            status = AutomationWorkStatus.RUNNING,
        )
        Mockito.`when`(work.findRunning(7)).thenReturn(running)
        Mockito.`when`(work.findWaiting(7)).thenReturn(emptyList())
        Mockito.`when`(
            typed.findEnabledEntriesBefore(7, battleEntry.priority, battleEntry.id),
        ).thenReturn(listOf(questEntry))
        Mockito.`when`(loader.loadEntry(7, battleEntry.id, running.targetKey, null)).thenReturn(battleDecisionEntry())
        Mockito.`when`(loader.loadEntry(7, questEntry.id, null, null)).thenReturn(questDecisionEntry())

        val boundary = assertIs<AutomationCoordination.CycleBoundary>(directSelector.select(7))

        assertEquals(listOf(battleEntry.id), boundary.trace.map(AutomationEvaluationTrace::entryId))
        Mockito.verify(loader, Mockito.never()).loadEntry(7, questEntry.id, null, null)
    }

    @Test
    fun `running work cycle keeps ownership while its next action is immediately runnable`() {
        val running = session(
            id = 91,
            entry = battleEntry,
            workType = AutomationWorkType.BATTLE_MAP,
            targetKey = "battle_map/map-1",
            status = AutomationWorkStatus.RUNNING,
        )
        val runningSnapshot = battleDecisionEntry()
        val runningAction = BattleMapAutomationAction(
            accountId = 7,
            progressDate = java.time.LocalDate.parse("2026-07-23"),
            categoryId = "battle_map",
            mapCode = "map-1",
            presetMode = PresetSelectionMode.PRIMARY,
            presetId = 3,
            battleCount = 1,
            executionIdentity = "running-cycle-action",
        )
        val directSelector = AutomationTargetSelector(
            typed = typed,
            work = work,
            loader = loader,
            lifecycle = lifecycle,
            timeProvider = TimeProvider { now },
            raidModule = defaultRaidModule,
            quest = fixedQuestRules(QuestDirective.Execute(QuestAction.Accept("quest-1", "accept-1"))),
            battle = AutomationHandler { HandlerEvaluation.Runnable(runningAction) },
            adventure = AutomationHandler { HandlerEvaluation.Skipped },
            union = AutomationHandler { HandlerEvaluation.Skipped },
            fishing = AutomationHandler { HandlerEvaluation.Skipped },
            homeQuest = AutomationHandler { HandlerEvaluation.Skipped },
        )
        Mockito.`when`(work.findRunning(7)).thenReturn(running)
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(questEntry, battleEntry))
        Mockito.`when`(loader.loadEntry(7, battleEntry.id, running.targetKey, null)).thenReturn(runningSnapshot)
        Mockito.`when`(loader.loadEntry(7, questEntry.id, null, null)).thenReturn(questDecisionEntry())

        val selected = assertIs<AutomationCoordination.Runnable>(directSelector.select(7))

        assertEquals(battleEntry.id, selected.entryId)
        assertEquals(runningAction, selected.action)
        Mockito.verify(loader, Mockito.never()).loadEntry(7, questEntry.id, null, null)
    }

    @Test
    fun `held map group parks its work and ends the current decision`() {
        val lowerQuest = AutomationEntryEntity(15, account, AutomationType.QUEST, 2, true, now, now)
        val running = session(
            id = 92,
            entry = battleEntry,
            workType = AutomationWorkType.BATTLE_MAP,
            targetKey = "battle_map/map-1",
            status = AutomationWorkStatus.RUNNING,
        )
        val parked = session(
            id = running.id,
            entry = battleEntry,
            workType = AutomationWorkType.BATTLE_MAP,
            targetKey = running.targetKey,
            status = AutomationWorkStatus.WAITING_COOLDOWN,
            nextCheckAt = now.plusSeconds(30),
        )
        val action = QuestAction.Accept("quest-1", "accept-1")
        val directSelector = AutomationTargetSelector(
            typed = typed,
            work = work,
            loader = loader,
            lifecycle = lifecycle,
            timeProvider = TimeProvider { now },
            raidModule = defaultRaidModule,
            quest = fixedQuestRules(QuestDirective.Execute(action)),
            battle = AutomationHandler {
                HandlerEvaluation.Unavailable(
                    nextRunAt = now.plusSeconds(30),
                    waitScope = AutomationWaitScope.HOLD_CURRENT_WORK,
                )
            },
            adventure = AutomationHandler { HandlerEvaluation.Skipped },
            union = AutomationHandler { HandlerEvaluation.Skipped },
            fishing = AutomationHandler { HandlerEvaluation.Skipped },
            homeQuest = AutomationHandler { HandlerEvaluation.Skipped },
        )
        Mockito.`when`(work.findRunning(7)).thenReturn(running)
        Mockito.`when`(work.findWaiting(7)).thenReturn(listOf(parked))
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(battleEntry, lowerQuest))
        Mockito.`when`(loader.loadEntry(7, battleEntry.id, running.targetKey, null)).thenReturn(battleDecisionEntry())
        Mockito.`when`(loader.loadEntry(7, lowerQuest.id, null, null)).thenReturn(questDecisionEntry(lowerQuest.id))

        val boundary = assertIs<AutomationCoordination.CycleBoundary>(directSelector.select(7))

        assertEquals(listOf(battleEntry.id), boundary.trace.map(AutomationEvaluationTrace::entryId))
        Mockito.verify(lifecycle).waitForCooldown(7, running.id, now.plusSeconds(30))
        Mockito.verify(loader, Mockito.never()).loadEntry(7, lowerQuest.id, null, null)
    }

    @Test
    fun `yielding a running work cycle ends the current decision before lower entries`() {
        val lowerQuest = AutomationEntryEntity(15, account, AutomationType.QUEST, 2, true, now, now)
        val running = session(
            id = 92,
            entry = battleEntry,
            workType = AutomationWorkType.BATTLE_MAP,
            targetKey = "battle_map/map-1",
            status = AutomationWorkStatus.RUNNING,
        )
        val directSelector = AutomationTargetSelector(
            typed = typed,
            work = work,
            loader = loader,
            lifecycle = lifecycle,
            timeProvider = TimeProvider { now },
            raidModule = defaultRaidModule,
            quest = fixedQuestRules(QuestDirective.Execute(QuestAction.Accept("quest-1", "accept-1"))),
            battle = AutomationHandler {
                HandlerEvaluation.Unavailable(
                    nextRunAt = now.plusSeconds(30),
                    waitScope = AutomationWaitScope.RELEASE_OTHER_AUTOMATIONS,
                )
            },
            adventure = AutomationHandler { HandlerEvaluation.Skipped },
            union = AutomationHandler { HandlerEvaluation.Skipped },
            fishing = AutomationHandler { HandlerEvaluation.Skipped },
            homeQuest = AutomationHandler { HandlerEvaluation.Skipped },
        )
        Mockito.`when`(work.findRunning(7)).thenReturn(running)
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(battleEntry, lowerQuest))
        Mockito.`when`(loader.loadEntry(7, battleEntry.id, running.targetKey, null)).thenReturn(battleDecisionEntry())
        Mockito.`when`(loader.loadEntry(7, lowerQuest.id, null, null)).thenReturn(questDecisionEntry(lowerQuest.id))

        val boundary = assertIs<AutomationCoordination.CycleBoundary>(directSelector.select(7))

        assertEquals(listOf(battleEntry.id), boundary.trace.map(AutomationEvaluationTrace::entryId))
        Mockito.verify(lifecycle).waitForCooldown(7, running.id, now.plusSeconds(30))
        Mockito.verify(loader, Mockito.never()).loadEntry(7, lowerQuest.id, null, null)
    }

    @Test
    fun `전투 gate가 열린 동안 전투 entry를 건너뛰고 비전투 퀘스트를 선택한다`() {
        val battleSnapshot = battleDecisionEntry()
        val questSnapshot = questDecisionEntry()
        val battleAction = BattleMapAutomationAction(
            accountId = 7L,
            progressDate = java.time.LocalDate.parse("2026-07-23"),
            categoryId = "battle_map",
            mapCode = "map-1",
            presetMode = PresetSelectionMode.PRIMARY,
            presetId = 3L,
            battleCount = 1,
            executionIdentity = "blocked-battle",
        )
        val questAction = QuestAction.Accept("quest-1", "accept-1")
        val guarded = AutomationTargetSelector(
            typed = typed,
            work = work,
            loader = loader,
            lifecycle = lifecycle,
            timeProvider = TimeProvider { now },
            raidModule = defaultRaidModule,
            quest = fixedQuestRules(QuestDirective.Execute(questAction)),
            battle = AutomationHandler { HandlerEvaluation.Runnable(battleAction) },
            adventure = AutomationHandler { HandlerEvaluation.Skipped },
            union = AutomationHandler { HandlerEvaluation.Skipped },
            fishing = AutomationHandler { HandlerEvaluation.Skipped },
            homeQuest = AutomationHandler { HandlerEvaluation.Skipped },
            convergenceGuard = AutomationConvergenceSelectionGuard {
                AutomationConvergenceSelectionConstraints(emptySet(), battleGateActive = true)
            },
            convergenceSelectionFactory = StoredActionConvergenceSelectionFactory(),
            convergenceRollout = AutomationConvergenceRollout(
                AutomationConvergenceProperties(mode = AutomationConvergenceMode.SHADOW),
            ),
        )
        Mockito.`when`(work.findRunning(7)).thenReturn(null)
        Mockito.`when`(work.findWaiting(7)).thenReturn(emptyList())
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(battleEntry, questEntry))
        Mockito.`when`(loader.loadEntry(7, battleEntry.id, null, null)).thenReturn(battleSnapshot)
        Mockito.`when`(loader.loadEntry(7, questEntry.id, null, null)).thenReturn(questSnapshot)

        val selected = assertIs<AutomationCoordination.Runnable>(guarded.select(7L))

        assertEquals(questEntry.id, selected.entryId)
        assertEquals(questAction, selected.action)
    }

    @Test
    fun `shadow에서도 legacy 결과 미관측 baseline만 보류하고 다른 entry를 선택한다`() {
        val store = InMemoryConvergenceStore()
        val convergence = DefaultAutomationActionConvergenceModule(store, TimeProvider { now })
        val factory = StoredActionConvergenceSelectionFactory()
        val questAction = QuestAction.Accept("quest-1", "accept-1")
        val preview = factory.preview(questEntry.id, questAction)
        val heldSelection = SelectedAutomationAction(
            entryId = questEntry.id,
            executionIdentity = "legacy-result-unobserved",
            actionKind = preview.actionKind,
            scope = preview.scope,
            policyVersion = StoredActionConvergenceSelectionFactory.POLICY_VERSION,
            baselineFingerprint = requireNotNull(preview.baselineFingerprint),
        )
        val attemptId = assertIs<ConvergenceDirective.Submit>(
            convergence.prepare(7L, heldSelection),
        ).attemptId
        convergence.record(
            attemptId,
            AutomationActionEvidence.ResultUnobserved(now, "legacy budget exhausted"),
        )
        val battleAction = BattleMapAutomationAction(
            accountId = 7L,
            progressDate = java.time.LocalDate.parse("2026-07-23"),
            categoryId = "battle_map",
            mapCode = "map-1",
            presetMode = PresetSelectionMode.PRIMARY,
            presetId = 3L,
            battleCount = 1,
            executionIdentity = "independent-battle",
        )
        val guarded = AutomationTargetSelector(
            typed = typed,
            work = work,
            loader = loader,
            lifecycle = lifecycle,
            timeProvider = TimeProvider { now },
            raidModule = defaultRaidModule,
            quest = fixedQuestRules(QuestDirective.Execute(questAction)),
            battle = AutomationHandler { HandlerEvaluation.Runnable(battleAction) },
            adventure = AutomationHandler { HandlerEvaluation.Skipped },
            union = AutomationHandler { HandlerEvaluation.Skipped },
            fishing = AutomationHandler { HandlerEvaluation.Skipped },
            homeQuest = AutomationHandler { HandlerEvaluation.Skipped },
            convergenceGuard = StoreBackedAutomationConvergenceSelectionGuard(store),
            convergenceSelectionFactory = factory,
            convergenceRollout = AutomationConvergenceRollout(
                AutomationConvergenceProperties(mode = AutomationConvergenceMode.SHADOW),
            ),
        )
        Mockito.`when`(work.findRunning(7)).thenReturn(null)
        Mockito.`when`(work.findWaiting(7)).thenReturn(emptyList())
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(questEntry, battleEntry))
        Mockito.`when`(loader.loadEntry(7, questEntry.id, null, null)).thenReturn(questDecisionEntry())
        Mockito.`when`(loader.loadEntry(7, battleEntry.id, null, null)).thenReturn(battleDecisionEntry())

        val selected = assertIs<AutomationCoordination.Runnable>(guarded.select(7L))

        assertEquals(battleEntry.id, selected.entryId)
        assertEquals(battleAction, selected.action)
    }

    @Test
    fun `shadow selector는 다른 최신 baseline을 본 뒤 새 사이클의 과거 baseline을 다시 선택한다`() {
        val store = InMemoryConvergenceStore()
        val convergence = DefaultAutomationActionConvergenceModule(store, TimeProvider { now })
        val factory = StoredActionConvergenceSelectionFactory()
        val originalAction = QuestAction.Accept("quest-1", "accept-1")
        val changedAction = QuestAction.Accept("quest-1", "accept-2")
        val originalPreview = factory.preview(questEntry.id, originalAction)
        val heldSelection = SelectedAutomationAction(
            entryId = questEntry.id,
            executionIdentity = "held-before-state-transition",
            actionKind = originalPreview.actionKind,
            scope = originalPreview.scope,
            policyVersion = StoredActionConvergenceSelectionFactory.POLICY_VERSION,
            baselineFingerprint = requireNotNull(originalPreview.baselineFingerprint),
        )
        val heldAttempt = assertIs<ConvergenceDirective.Submit>(
            convergence.prepare(7L, heldSelection),
        ).attemptId
        convergence.record(
            heldAttempt,
            AutomationActionEvidence.ResultUnobserved(now, "legacy budget exhausted"),
        )
        var currentAction = changedAction
        val guarded = AutomationTargetSelector(
            typed = typed,
            work = work,
            loader = loader,
            lifecycle = lifecycle,
            timeProvider = TimeProvider { now },
            raidModule = defaultRaidModule,
            quest = object : QuestWorkCycleModule {
                override fun decideNext(snapshot: QuestAutomationSnapshot): QuestDirective =
                    QuestDirective.Execute(currentAction)

                override fun recordObservedResult(
                    accountId: Long,
                    attempt: QuestAttempt,
                    observation: QuestResultObservation,
                ): QuestRecordResult = error("not used")
            },
            battle = AutomationHandler { HandlerEvaluation.Skipped },
            adventure = AutomationHandler { HandlerEvaluation.Skipped },
            union = AutomationHandler { HandlerEvaluation.Skipped },
            fishing = AutomationHandler { HandlerEvaluation.Skipped },
            homeQuest = AutomationHandler { HandlerEvaluation.Skipped },
            convergenceModule = convergence,
            convergenceGuard = StoreBackedAutomationConvergenceSelectionGuard(store),
            convergenceSelectionFactory = factory,
            convergenceRollout = AutomationConvergenceRollout(
                AutomationConvergenceProperties(mode = AutomationConvergenceMode.SHADOW),
            ),
        )
        Mockito.`when`(work.findRunning(7)).thenReturn(null)
        Mockito.`when`(work.findWaiting(7)).thenReturn(emptyList())
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(questEntry))
        Mockito.`when`(loader.loadEntry(7, questEntry.id, null, null)).thenReturn(questDecisionEntry())

        assertEquals(
            changedAction,
            assertIs<AutomationCoordination.Runnable>(guarded.select(7L)).action,
        )

        currentAction = originalAction

        assertEquals(
            originalAction,
            assertIs<AutomationCoordination.Runnable>(guarded.select(7L)).action,
        )
    }

    @Test
    fun `shadow raid selector는 완전한 비실행 상태 뒤 새 사이클 등록을 다시 선택한다`() {
        val store = InMemoryConvergenceStore()
        val convergence = DefaultAutomationActionConvergenceModule(store, TimeProvider { now })
        val factory = StoredActionConvergenceSelectionFactory()
        val raidModule = Mockito.mock(RaidCycleModule::class.java)
        val registerAction = RaidTownAutomationAction(
            accountId = 7L,
            action = RaidAction.REGISTER,
            raidId = "Raid001",
            targetRaidId = "Raid001",
            raidName = "고블린 전투 마차",
            observedStatus = "파티 모집 중 (신청 안됨)",
        )
        val registerPreview = factory.preview(raidEntry.id, registerAction)
        val heldSelection = SelectedAutomationAction(
            entryId = raidEntry.id,
            executionIdentity = "held-raid-registration",
            actionKind = registerPreview.actionKind,
            scope = registerPreview.scope,
            policyVersion = StoredActionConvergenceSelectionFactory.POLICY_VERSION,
            baselineFingerprint = requireNotNull(registerPreview.baselineFingerprint),
        )
        val heldAttempt = assertIs<ConvergenceDirective.Submit>(
            convergence.prepare(7L, heldSelection),
        ).attemptId
        convergence.record(
            heldAttempt,
            AutomationActionEvidence.ResultUnobserved(now, "legacy budget exhausted"),
        )
        val retryAt = now.plusSeconds(120)
        Mockito.`when`(raidModule.decide(7L)).thenReturn(
            RaidDecision(
                directive = RaidDirective.WaitUntil(
                    at = retryAt,
                    reason = RaidWaitReason.WAITING_TO_START,
                    message = "레이드 출발 가능 시각까지 기다립니다.",
                    entryId = raidEntry.id,
                    raidId = "Raid001",
                ),
                authoritativeState = RaidAuthoritativeState(
                    raidId = "Raid001",
                    target = null,
                    registrationWait = true,
                    registrationWaitSeconds = 90,
                    globalActions = emptySet(),
                ),
            ),
            RaidDecision(
                RaidDirective.Execute(
                    RaidIntent.Town(
                        entryId = raidEntry.id,
                        raidId = "Raid001",
                        raidName = "고블린 전투 마차",
                        kind = RaidIntentKind.REGISTER,
                        observedStatus = "파티 모집 중 (신청 안됨)",
                    ),
                ),
            ),
        )
        val guarded = AutomationTargetSelector(
            typed = typed,
            work = work,
            loader = loader,
            lifecycle = lifecycle,
            timeProvider = TimeProvider { now },
            raidModule = raidModule,
            quest = fixedQuestRules(QuestDirective.Skip),
            battle = AutomationHandler { HandlerEvaluation.Skipped },
            adventure = AutomationHandler { HandlerEvaluation.Skipped },
            union = AutomationHandler { HandlerEvaluation.Skipped },
            fishing = AutomationHandler { HandlerEvaluation.Skipped },
            homeQuest = AutomationHandler { HandlerEvaluation.Skipped },
            convergenceModule = convergence,
            convergenceGuard = StoreBackedAutomationConvergenceSelectionGuard(store),
            convergenceSelectionFactory = factory,
            convergenceRollout = AutomationConvergenceRollout(
                AutomationConvergenceProperties(mode = AutomationConvergenceMode.SHADOW),
            ),
        )
        Mockito.`when`(work.findRunning(7L)).thenReturn(null)
        Mockito.`when`(work.findWaiting(7L)).thenReturn(emptyList())
        Mockito.`when`(typed.findEntries(7L)).thenReturn(listOf(raidEntry))

        assertIs<AutomationCoordination.Idle>(guarded.select(7L))
        assertEquals(emptyMap(), store.findSuppressedBaselines(7L))

        val selected = assertIs<AutomationCoordination.Runnable>(guarded.select(7L))

        assertEquals(registerAction, selected.action)
    }

    @Test
    fun `a blocked running work session ends the decision before another session is resumed`() {
        val running = session(30, questEntry, AutomationWorkType.QUEST, "quest-1", AutomationWorkStatus.RUNNING)
        val due = session(
            31,
            questEntry,
            AutomationWorkType.QUEST,
            "quest-2",
            AutomationWorkStatus.YIELDED_PRIORITY,
            nextCheckAt = now,
        )
        val yieldedRunning = session(
            30,
            questEntry,
            AutomationWorkType.QUEST,
            "quest-1",
            AutomationWorkStatus.YIELDED_PRIORITY,
        )
        val questAction = QuestAction.Accept("quest-blocked", "accept-blocked")
        val factory = StoredActionConvergenceSelectionFactory()
        val preview = factory.preview(questEntry.id, questAction)
        val battleAction = BattleMapAutomationAction(
            accountId = 7L,
            progressDate = java.time.LocalDate.parse("2026-07-23"),
            categoryId = "battle_map",
            mapCode = "map-1",
            presetMode = PresetSelectionMode.PRIMARY,
            presetId = 3L,
            battleCount = 1,
            executionIdentity = "after-blocked-work-sessions",
        )
        val guarded = AutomationTargetSelector(
            typed = typed,
            work = work,
            loader = loader,
            lifecycle = lifecycle,
            timeProvider = TimeProvider { now },
            raidModule = defaultRaidModule,
            quest = fixedQuestRules(QuestDirective.Execute(questAction)),
            battle = AutomationHandler { HandlerEvaluation.Runnable(battleAction) },
            adventure = AutomationHandler { HandlerEvaluation.Skipped },
            union = AutomationHandler { HandlerEvaluation.Skipped },
            fishing = AutomationHandler { HandlerEvaluation.Skipped },
            homeQuest = AutomationHandler { HandlerEvaluation.Skipped },
            convergenceGuard = AutomationConvergenceSelectionGuard {
                AutomationConvergenceSelectionConstraints(
                    blockedScopes = emptySet(),
                    battleGateActive = false,
                    suppressedBaselines = mapOf(
                        preview.scope to setOf(requireNotNull(preview.baselineFingerprint)),
                    ),
                )
            },
            convergenceSelectionFactory = factory,
            convergenceRollout = AutomationConvergenceRollout(
                AutomationConvergenceProperties(mode = AutomationConvergenceMode.SHADOW),
            ),
        )
        Mockito.`when`(work.findRunning(7)).thenReturn(
            running,
            due.copy(status = AutomationWorkStatus.RUNNING, revision = due.revision + 1),
        )
        Mockito.`when`(work.findWaiting(7)).thenReturn(
            listOf(due),
            listOf(due),
            listOf(yieldedRunning),
            emptyList(),
        )
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(questEntry, battleEntry))
        Mockito.`when`(loader.loadEntry(7, questEntry.id, "quest-1")).thenReturn(questDecisionEntry())
        Mockito.`when`(loader.loadEntry(7, questEntry.id, "quest-2")).thenReturn(questDecisionEntry())
        Mockito.`when`(loader.loadEntry(7, questEntry.id, null, null)).thenReturn(questDecisionEntry())
        Mockito.`when`(loader.loadEntry(7, battleEntry.id, null, null)).thenReturn(battleDecisionEntry())

        val boundary = assertIs<AutomationCoordination.CycleBoundary>(guarded.select(7L))

        assertEquals(listOf(questEntry.id), boundary.trace.map(AutomationEvaluationTrace::entryId))
        Mockito.verify(lifecycle, Mockito.never()).resumeForCheck(7, due.id)
        Mockito.verify(lifecycle, Mockito.never()).resumeForCheck(7, running.id)
        Mockito.verify(loader, Mockito.never()).loadEntry(7, battleEntry.id, null, null)
    }

    @Test
    fun `자택 식별자 gap은 해당 scope만 pending으로 만들고 다음 entry를 계속 선택한다`() {
        val homeEntry = AutomationEntryEntity(15, account, AutomationType.HOME_QUEST, 0, true, now, now)
        val store = InMemoryConvergenceStore()
        val convergence = DefaultAutomationActionConvergenceModule(store, TimeProvider { now })
        val action = QuestAction.Accept("quest-1", "accept-1")
        val activeSelector = AutomationTargetSelector(
            typed = typed,
            work = work,
            loader = loader,
            lifecycle = lifecycle,
            timeProvider = TimeProvider { now },
            raidModule = defaultRaidModule,
            quest = fixedQuestRules(QuestDirective.Execute(action)),
            battle = AutomationHandler { HandlerEvaluation.Skipped },
            adventure = AutomationHandler { HandlerEvaluation.Skipped },
            union = AutomationHandler { HandlerEvaluation.Skipped },
            fishing = AutomationHandler { HandlerEvaluation.Skipped },
            homeQuest = AutomationHandler {
                HandlerEvaluation.ObservationGap(
                    actionKind = AutomationActionKind.HOME_ACCEPT,
                    scopeKind = AutomationIsolationScopeKind.HOME_TARGET,
                    scopeKey = "home-1",
                    baseline = "home|accept|home-1|missing",
                    nextRunAt = now.plusSeconds(10),
                    reasonCode = "HOME_ACTION_ID_MISSING",
                    message = "자택 action id가 없습니다.",
                    authoritative = true,
                )
            },
            convergenceSelectionFactory = StoredActionConvergenceSelectionFactory(),
            convergenceRollout = AutomationConvergenceRollout(
                AutomationConvergenceProperties(mode = AutomationConvergenceMode.ACTIVE),
            ),
            convergenceModule = convergence,
        )
        Mockito.`when`(work.findRunning(7)).thenReturn(null)
        Mockito.`when`(work.findWaiting(7)).thenReturn(emptyList())
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(homeEntry, questEntry))
        Mockito.`when`(loader.loadEntry(7, homeEntry.id, null, null)).thenReturn(
            AutomationEntrySnapshot(
                homeEntry.id,
                AutomationType.HOME_QUEST,
                homeQuest = HomeQuestAutomationSnapshot(7L, emptyList(), emptyList(), now),
            ),
        )
        Mockito.`when`(loader.loadEntry(7, questEntry.id, null, null)).thenReturn(questDecisionEntry())

        val selected = assertIs<AutomationCoordination.Runnable>(activeSelector.select(7L))

        assertEquals(questEntry.id, selected.entryId)
        assertEquals("HOME_ACTION_ID_MISSING", selected.trace.first().reasonCode)
        val diagnostic = jacksonObjectMapper().readTree(requireNotNull(selected.trace.first().diagnosticContext))
        assertEquals("ENTRY_EVALUATION", diagnostic["stage"].asString())
        assertEquals(0, diagnostic["snapshot"]["targetCount"].asInt())
        assertEquals(
            1,
            store.findActive(
                7L,
                AutomationIsolationScope(AutomationIsolationScopeKind.HOME_TARGET, "home-1"),
            )?.successfulObservationCount,
        )
    }

    @Test
    fun `자택 식별자 gap이 해소되면 observation scope를 닫고 최신 행동을 선택한다`() {
        val homeEntry = AutomationEntryEntity(15, account, AutomationType.HOME_QUEST, 0, true, now, now)
        val store = InMemoryConvergenceStore()
        val convergence = DefaultAutomationActionConvergenceModule(store, TimeProvider { now })
        val scope = AutomationIsolationScope(AutomationIsolationScopeKind.HOME_TARGET, "home-1")
        val runnable = HomeQuestAutomationAction(
            accountId = 7L,
            questId = "home-1",
            questName = "자택 퀘스트",
            actionId = "accept-1",
            action = HomeQuestAutomationActionType.ACCEPT,
        )
        var observation = 0
        val activeSelector = AutomationTargetSelector(
            typed = typed,
            work = work,
            loader = loader,
            lifecycle = lifecycle,
            timeProvider = TimeProvider { now },
            raidModule = defaultRaidModule,
            quest = Mockito.mock(QuestWorkCycleModule::class.java),
            battle = AutomationHandler { HandlerEvaluation.Skipped },
            adventure = AutomationHandler { HandlerEvaluation.Skipped },
            union = AutomationHandler { HandlerEvaluation.Skipped },
            fishing = AutomationHandler { HandlerEvaluation.Skipped },
            homeQuest = AutomationHandler {
                if (observation++ == 0) {
                    HandlerEvaluation.ObservationGap(
                        actionKind = AutomationActionKind.HOME_ACCEPT,
                        scopeKind = AutomationIsolationScopeKind.HOME_TARGET,
                        scopeKey = "home-1",
                        baseline = "home|accept|home-1|missing",
                        nextRunAt = now.plusSeconds(10),
                        reasonCode = "HOME_ACTION_ID_MISSING",
                        message = "자택 action id가 없습니다.",
                        authoritative = true,
                    )
                } else {
                    HandlerEvaluation.Runnable(runnable)
                }
            },
            convergenceGuard = StoreBackedAutomationConvergenceSelectionGuard(store),
            convergenceSelectionFactory = StoredActionConvergenceSelectionFactory(),
            convergenceRollout = AutomationConvergenceRollout(
                AutomationConvergenceProperties(mode = AutomationConvergenceMode.ACTIVE),
            ),
            convergenceModule = convergence,
        )
        Mockito.`when`(work.findRunning(7)).thenReturn(null)
        Mockito.`when`(work.findWaiting(7)).thenReturn(emptyList())
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(homeEntry))
        Mockito.`when`(loader.loadEntry(7, homeEntry.id, null, null)).thenReturn(
            AutomationEntrySnapshot(
                homeEntry.id,
                AutomationType.HOME_QUEST,
                homeQuest = HomeQuestAutomationSnapshot(7L, emptyList(), emptyList(), now),
            ),
        )

        assertIs<AutomationCoordination.Idle>(activeSelector.select(7L))
        val gapAttemptId = requireNotNull(store.findActive(7L, scope)).attemptId

        val selected = assertIs<AutomationCoordination.Runnable>(activeSelector.select(7L))

        assertEquals(runnable, selected.action)
        assertEquals(
            app.spammy.hof.automation.convergence.ActionConvergenceResult.SUPERSEDED,
            store.get(gapAttemptId)?.result,
        )
    }

    @Test
    fun `configuration warning does not block a lower priority runnable and is retained`() {
        val questSnapshot = questDecisionEntry()
        val battleSnapshot = battleDecisionEntry()
        val action = BattleMapAutomationAction(
            accountId = 7,
            progressDate = java.time.LocalDate.parse("2026-07-23"),
            categoryId = "battle_map",
            mapCode = "map-1",
            presetMode = PresetSelectionMode.PRIMARY,
            presetId = 3,
            battleCount = 1,
            executionIdentity = "after-warning",
        )
        Mockito.`when`(work.findRunning(7)).thenReturn(null)
        Mockito.`when`(work.findWaiting(7)).thenReturn(emptyList())
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(questEntry, battleEntry))
        Mockito.`when`(loader.loadEntry(7, questEntry.id, null, null)).thenReturn(questSnapshot)
        Mockito.`when`(loader.loadEntry(7, battleEntry.id, null, null)).thenReturn(battleSnapshot)
        questRules.returns(
            requireNotNull(questSnapshot.quest),
            QuestDirective.Hold("broken quest", "CONFIGURATION_WARNING"),
        )
        battleRules.returns(requireNotNull(battleSnapshot.battle), HandlerEvaluation.Runnable(action))

        val selected = assertIs<AutomationCoordination.Runnable>(selector.select(7))

        assertEquals(battleEntry.id, selected.entryId)
        assertEquals(listOf("broken quest"), selected.warnings)
        assertEquals(
            listOf(
                AutomationDecisionOutcome.CONFIGURATION_WARNING,
                AutomationDecisionOutcome.SELECTED,
            ),
            selected.trace.map(AutomationEvaluationTrace::outcome),
        )
    }

    @Test
    fun `cooling entries finish the round without waiting for their deadlines`() {
        val battleSnapshot = battleDecisionEntry()
        val adventureSnapshot = adventureDecisionEntry()
        val later = now.plusSeconds(600)
        val earlier = now.plusSeconds(300)
        Mockito.`when`(work.findRunning(7)).thenReturn(null)
        Mockito.`when`(work.findWaiting(7)).thenReturn(emptyList())
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(battleEntry, adventureEntry))
        Mockito.`when`(loader.loadEntry(7, battleEntry.id, null, null)).thenReturn(battleSnapshot)
        Mockito.`when`(loader.loadEntry(7, adventureEntry.id, null, null)).thenReturn(adventureSnapshot)
        battleRules.returns(requireNotNull(battleSnapshot.battle), HandlerEvaluation.Unavailable(later))
        adventureRules.returns(requireNotNull(adventureSnapshot.adventure), HandlerEvaluation.Unavailable(earlier))

        val unavailable = assertIs<AutomationCoordination.Idle>(selector.select(7))

        assertNull(unavailable.nextRunAt)
        assertEquals(listOf(later, earlier), unavailable.trace.map(AutomationEvaluationTrace::nextRunAt))
        assertEquals(listOf(0, 1), unavailable.trace.map(AutomationEvaluationTrace::sequence))
        assertEquals(listOf(AutomationDecisionOutcome.SKIPPED, AutomationDecisionOutcome.SKIPPED), unavailable.trace.map(AutomationEvaluationTrace::outcome))
    }

    @Test
    fun `all skipped entries finish the round without a five minute idle deadline`() {
        val battleSnapshot = battleDecisionEntry()
        val adventureSnapshot = adventureDecisionEntry()
        Mockito.`when`(work.findRunning(7)).thenReturn(null)
        Mockito.`when`(work.findWaiting(7)).thenReturn(emptyList())
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(battleEntry, adventureEntry))
        Mockito.`when`(loader.loadEntry(7, battleEntry.id, null, null)).thenReturn(battleSnapshot)
        Mockito.`when`(loader.loadEntry(7, adventureEntry.id, null, null)).thenReturn(adventureSnapshot)
        battleRules.returns(requireNotNull(battleSnapshot.battle), HandlerEvaluation.Skipped)
        adventureRules.returns(requireNotNull(adventureSnapshot.adventure), HandlerEvaluation.Skipped)

        val idleHeartbeat = assertIs<AutomationCoordination.Idle>(selector.select(7))

        assertNull(idleHeartbeat.nextRunAt)
        assertEquals(
            listOf(AutomationDecisionOutcome.SKIPPED, AutomationDecisionOutcome.SKIPPED),
            idleHeartbeat.trace.map(AutomationEvaluationTrace::outcome),
        )
    }

    @Test
    fun `candidate-level quest deadline is recorded as skipped while a lower entry remains runnable`() {
        val retryAt = now.plusSeconds(120)
        val questSnapshot = questDecisionEntry()
        val adventureSnapshot = adventureDecisionEntry()
        val adventureAction = AdventureMapAutomationAction(
            accountId = 7,
            categoryId = "adventure_map",
            mapCode = "map-1",
            presetMode = PresetSelectionMode.PRIMARY,
            presetId = 3,
            settingIdentity = 30,
            executionIdentity = "adventure-after-quest-candidate-wait",
        )
        Mockito.`when`(work.findRunning(7)).thenReturn(null)
        Mockito.`when`(work.findWaiting(7)).thenReturn(emptyList())
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(questEntry, adventureEntry))
        Mockito.`when`(loader.loadEntry(7, questEntry.id, null, null)).thenReturn(questSnapshot)
        Mockito.`when`(loader.loadEntry(7, adventureEntry.id, null, null)).thenReturn(adventureSnapshot)
        questRules.returns(
            requireNotNull(questSnapshot.quest),
            QuestDirective.WaitUntil(retryAt, "QUEST_BATTLE_COOLDOWN", "candidate cooldown"),
        )
        adventureRules.returns(
            requireNotNull(adventureSnapshot.adventure),
            HandlerEvaluation.Runnable(adventureAction),
        )

        val selected = assertIs<AutomationCoordination.Runnable>(selector.select(7))

        assertEquals(adventureAction, selected.action)
        assertEquals(AutomationDecisionOutcome.SKIPPED, selected.trace.first().outcome)
        assertEquals(retryAt, selected.trace.first().nextRunAt)
    }

    @Test
    fun `대기 작업을 재개하면 현재 판단을 끝내고 낮은 항목은 새 판단으로 넘긴다`() {
        val home = AutomationEntryEntity(101, account, AutomationType.HOME_QUEST, 0, true, now, now)
        val raid = AutomationEntryEntity(102, account, AutomationType.RAID, 1, true, now, now)
        val unionEntry = AutomationEntryEntity(103, account, AutomationType.UNION, 2, true, now, now)
        val fishingEntry = AutomationEntryEntity(104, account, AutomationType.FISHING, 3, true, now, now)
        val quest = AutomationEntryEntity(105, account, AutomationType.QUEST, 4, true, now, now)
        val adventure = AutomationEntryEntity(106, account, AutomationType.ADVENTURE_MAP, 5, true, now, now)
        val battle = AutomationEntryEntity(107, account, AutomationType.BATTLE_MAP, 6, true, now, now)
        val dueRaid = session(
            201,
            raid,
            AutomationWorkType.RAID,
            "RaidGoblin",
            AutomationWorkStatus.WAITING_COOLDOWN,
            nextCheckAt = now,
        )
        val dueQuest = session(
            202,
            quest,
            AutomationWorkType.QUEST,
            "quest-1",
            AutomationWorkStatus.WAITING_COOLDOWN,
            nextCheckAt = now,
        )
        val resumedRaid = dueRaid.copy(status = AutomationWorkStatus.RUNNING, nextCheckAt = null)
        val resumedQuest = dueQuest.copy(status = AutomationWorkStatus.RUNNING, nextCheckAt = null)
        val homeSnapshot = AutomationEntrySnapshot(
            home.id,
            AutomationType.HOME_QUEST,
            homeQuest = HomeQuestAutomationSnapshot(7, emptyList(), emptyList(), now),
        )
        val unionSnapshot = AutomationEntrySnapshot(
            unionEntry.id,
            AutomationType.UNION,
            union = UnionAutomationSnapshot(7, emptyList(), emptyList(), null, now),
        )
        val fishingSnapshot = fishingDecisionEntry(fishingEntry.id)
        val questSnapshot = questDecisionEntry(quest.id)
        val adventureSnapshot = adventureDecisionEntry(adventure.id)
        val adventureAction = AdventureMapAutomationAction(
            accountId = 7,
            categoryId = "adventure_map",
            mapCode = "map-1",
            presetMode = PresetSelectionMode.PRIMARY,
            presetId = 3,
            settingIdentity = 30,
            executionIdentity = "one-pass-adventure",
        )
        val raidModule = Mockito.mock(RaidCycleModule::class.java)
        val onePassSelector = selectorWithRaid(raidModule)
        val retryAt = now.plusSeconds(120)
        Mockito.`when`(work.findRunning(7)).thenReturn(null, resumedRaid, resumedQuest)
        Mockito.`when`(work.findWaiting(7)).thenReturn(
            listOf(dueRaid, dueQuest),
            listOf(dueQuest),
            emptyList(),
        )
        Mockito.`when`(typed.findEntries(7)).thenReturn(
            listOf(home, raid, unionEntry, fishingEntry, quest, adventure, battle),
        )
        Mockito.`when`(loader.loadEntry(7, home.id, null, null)).thenReturn(homeSnapshot)
        Mockito.`when`(loader.loadEntry(7, unionEntry.id, null, null)).thenReturn(unionSnapshot)
        Mockito.`when`(loader.loadEntry(7, fishingEntry.id, null, null)).thenReturn(fishingSnapshot)
        Mockito.`when`(loader.loadEntry(7, quest.id, dueQuest.targetKey, null)).thenReturn(questSnapshot)
        Mockito.`when`(loader.loadEntry(7, quest.id, null, null)).thenReturn(questSnapshot)
        Mockito.`when`(loader.loadEntry(7, adventure.id, null, null)).thenReturn(adventureSnapshot)
        Mockito.`when`(raidModule.decide(7)).thenReturn(
            RaidDecision(
                RaidDirective.WaitUntil(
                    at = retryAt,
                    reason = RaidWaitReason.BATTLE_COOLDOWN,
                    message = "레이드 전투 쿨다운",
                    entryId = raid.id,
                    raidId = dueRaid.targetKey,
                ),
            ),
        )
        homeQuestRules.returns(requireNotNull(homeSnapshot.homeQuest), HandlerEvaluation.Skipped)
        unionRules.returns(requireNotNull(unionSnapshot.union), HandlerEvaluation.Skipped)
        fishingRules.returns(requireNotNull(fishingSnapshot.fishing), HandlerEvaluation.Skipped)
        questRules.returns(
            requireNotNull(questSnapshot.quest).copy(
                workSessionId = resumedQuest.id,
                workSessionRevision = resumedQuest.revision,
            ),
            QuestDirective.Skip,
        )
        questRules.returns(requireNotNull(questSnapshot.quest), QuestDirective.Skip)
        adventureRules.returns(
            requireNotNull(adventureSnapshot.adventure),
            HandlerEvaluation.Runnable(adventureAction),
        )

        val boundary = assertIs<AutomationCoordination.CycleBoundary>(onePassSelector.select(7))

        assertEquals(
            listOf(home.id, raid.id),
            boundary.trace.map(AutomationEvaluationTrace::entryId),
        )
        Mockito.verify(loader, Mockito.times(1)).loadEntry(7, home.id, null, null)
        Mockito.verify(loader, Mockito.never()).loadEntry(7, unionEntry.id, null, null)
        Mockito.verify(loader, Mockito.never()).loadEntry(7, fishingEntry.id, null, null)
        Mockito.verify(loader, Mockito.never()).loadEntry(7, quest.id, dueQuest.targetKey, null)
        Mockito.verify(loader, Mockito.never()).loadEntry(7, adventure.id, null, null)
        Mockito.verify(loader, Mockito.never()).loadEntry(7, battle.id, null, null)
    }

    @Test
    fun `실행 중 작업은 설정 우선순위를 재평가하지 않고 먼저 진전시킨다`() {
        val home = AutomationEntryEntity(101, account, AutomationType.HOME_QUEST, 0, true, now, now)
        val runningQuestEntry = AutomationEntryEntity(105, account, AutomationType.QUEST, 4, true, now, now)
        val runningQuest = session(
            202,
            runningQuestEntry,
            AutomationWorkType.QUEST,
            "quest-1",
            AutomationWorkStatus.RUNNING,
        )
        val homeSnapshot = AutomationEntrySnapshot(
            home.id,
            AutomationType.HOME_QUEST,
            homeQuest = HomeQuestAutomationSnapshot(7, emptyList(), emptyList(), now),
        )
        val questSnapshot = questDecisionEntry(runningQuestEntry.id)
        val runningSnapshot = requireNotNull(questSnapshot.quest).copy(
            workSessionId = runningQuest.id,
            workSessionRevision = runningQuest.revision,
        )
        val action = QuestAction.Accept("quest-1", "accept-1")
        Mockito.`when`(work.findRunning(7)).thenReturn(runningQuest)
        Mockito.`when`(work.findWaiting(7)).thenReturn(emptyList())
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(home, runningQuestEntry))
        Mockito.`when`(loader.loadEntry(7, home.id, null, null)).thenReturn(homeSnapshot)
        Mockito.`when`(loader.loadEntry(7, runningQuestEntry.id, runningQuest.targetKey, null)).thenReturn(questSnapshot)
        homeQuestRules.returns(requireNotNull(homeSnapshot.homeQuest), HandlerEvaluation.Skipped)
        questRules.returns(runningSnapshot, QuestDirective.Execute(action))

        val selected = assertIs<AutomationCoordination.Runnable>(selector.select(7))

        assertEquals(runningQuestEntry.id, selected.entryId)
        assertEquals(action, selected.action)
        assertEquals(listOf(runningQuestEntry.id), selected.trace.map(AutomationEvaluationTrace::entryId))
        Mockito.verify(loader, Mockito.never()).loadEntry(7, home.id, null, null)
        Mockito.verify(loader, Mockito.times(1)).loadEntry(7, runningQuestEntry.id, runningQuest.targetKey, null)
    }

    @Test
    fun `fatal entry stops lower priority probes`() {
        val questSnapshot = questDecisionEntry()
        Mockito.`when`(work.findRunning(7)).thenReturn(null)
        Mockito.`when`(work.findWaiting(7)).thenReturn(emptyList())
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(questEntry, battleEntry))
        Mockito.`when`(loader.loadEntry(7, questEntry.id, null, null)).thenReturn(questSnapshot)
        questRules.returns(
            requireNotNull(questSnapshot.quest),
            QuestDirective.Fatal(AutomationStopReason.NETWORK, "fatal"),
        )

        val fatal = assertIs<AutomationCoordination.Fatal>(selector.select(7))

        assertEquals(AutomationStopReason.NETWORK, fatal.reason)
        Mockito.verify(loader, Mockito.never()).loadEntry(7, battleEntry.id, null, null)
    }

    @Test
    fun `stale running quest is parked and ends the current decision`() {
        val running = session(40, questEntry, AutomationWorkType.QUEST, "quest-1", AutomationWorkStatus.RUNNING)
        val questSnapshot = questDecisionEntry()
        val runningSnapshot = questSnapshot.copy(
            quest = requireNotNull(questSnapshot.quest).copy(
                workSessionId = running.id,
                workSessionRevision = running.revision,
            ),
        )
        val battleSnapshot = battleDecisionEntry()
        val battleAction = BattleMapAutomationAction(
            accountId = 7,
            progressDate = java.time.LocalDate.parse("2026-07-23"),
            categoryId = "battle_map",
            mapCode = "map-1",
            presetMode = PresetSelectionMode.PRIMARY,
            presetId = 3,
            battleCount = 1,
            executionIdentity = "after-stale-quest",
        )
        Mockito.`when`(work.findRunning(7)).thenReturn(running)
        Mockito.`when`(work.findWaiting(7)).thenReturn(emptyList())
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(questEntry, battleEntry))
        Mockito.`when`(loader.loadEntry(7, questEntry.id, "quest-1", null)).thenReturn(questSnapshot)
        Mockito.`when`(loader.loadEntry(7, battleEntry.id, null, null)).thenReturn(battleSnapshot)
        questRules.returns(
            requireNotNull(runningSnapshot.quest),
            QuestDirective.Recheck(now.plusSeconds(10), "QUEST_PROGRESS_STALE", "recheck"),
        )
        battleRules.returns(requireNotNull(battleSnapshot.battle), HandlerEvaluation.Runnable(battleAction))

        val boundary = assertIs<AutomationCoordination.CycleBoundary>(selector.select(7))

        Mockito.verify(lifecycle).waitForCooldown(7, running.id, now.plusSeconds(10))
        assertEquals(listOf(questEntry.id), boundary.trace.map(AutomationEvaluationTrace::entryId))
        assertEquals(1, questRules.evaluated.size)
        Mockito.verify(loader, Mockito.times(1)).loadEntry(7, questEntry.id, "quest-1", null)
        Mockito.verify(loader, Mockito.never()).loadEntry(7, battleEntry.id, null, null)
    }

    @Test
    fun `first quest stale is parked without reloading the same entry`() {
        val running = session(42, questEntry, AutomationWorkType.QUEST, "quest-1", AutomationWorkStatus.RUNNING)
        val questSnapshot = questDecisionEntry()
        val staleSnapshot = requireNotNull(questSnapshot.quest).copy(
            workSessionId = running.id,
            workSessionRevision = running.revision,
        )
        Mockito.`when`(work.findRunning(7)).thenReturn(running)
        Mockito.`when`(work.findWaiting(7)).thenReturn(emptyList())
        Mockito.`when`(typed.findEntries(7)).thenReturn(emptyList())
        Mockito.`when`(loader.loadEntry(7, questEntry.id, "quest-1", null)).thenReturn(questSnapshot)
        questRules.returns(
            staleSnapshot,
            QuestDirective.Recheck(now.plusSeconds(10), "QUEST_PROGRESS_STALE", "recheck"),
        )

        val selected = assertIs<AutomationCoordination.CycleBoundary>(selector.select(7))

        assertEquals(listOf(questEntry.id), selected.trace.map(AutomationEvaluationTrace::entryId))
        assertEquals(listOf(0L), questRules.evaluated.map { it.workSessionRevision })
        Mockito.verify(lifecycle).waitForCooldown(7, running.id, now.plusSeconds(10))
        Mockito.verify(work, Mockito.times(1)).findRunning(7)
    }

    @Test
    fun `due quest remains an entry candidate until a fresh action acquires ownership`() {
        val due = session(
            41,
            questEntry,
            AutomationWorkType.QUEST,
            "quest-1",
            AutomationWorkStatus.WAITING_COOLDOWN,
            nextCheckAt = now,
        ).copy(revision = 2)
        val questSnapshot = questDecisionEntry()
        val action = QuestAction.Accept("quest-1", "accept-1")
        val failingTelemetry = Mockito.mock(AutomationProgressTelemetry::class.java)
        Mockito.doThrow(IllegalStateException("registry unavailable"))
            .`when`(failingTelemetry)
            .recordDueSession(AutomationWorkType.QUEST, now)
        val guarded = AutomationTargetSelector(
            typed = typed,
            work = work,
            loader = loader,
            lifecycle = lifecycle,
            timeProvider = TimeProvider { now },
            raidModule = defaultRaidModule,
            quest = questRules,
            battle = battleRules,
            adventure = adventureRules,
            union = unionRules,
            fishing = fishingRules,
            homeQuest = homeQuestRules,
            progressTelemetry = failingTelemetry,
        )
        Mockito.`when`(work.findRunning(7)).thenReturn(null)
        Mockito.`when`(work.findWaiting(7)).thenReturn(listOf(due))
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(questEntry))
        Mockito.`when`(loader.loadEntry(7, questEntry.id, null, null)).thenReturn(questSnapshot)
        questRules.returns(requireNotNull(questSnapshot.quest), QuestDirective.Execute(action))

        val selected = assertIs<AutomationCoordination.Runnable>(guarded.select(7))

        assertEquals(action, selected.action)
        assertEquals(null, questRules.evaluated.single().workSessionRevision)
        Mockito.verify(lifecycle, Mockito.never()).resumeForCheck(7, due.id)
        Mockito.verify(failingTelemetry, Mockito.never()).recordDueSession(AutomationWorkType.QUEST, now)
    }

    @Test
    fun `yielded status without an explicit deadline does not preempt runnable work`() {
        val running = session(
            51,
            battleEntry,
            AutomationWorkType.BATTLE_MAP,
            "battle_map/map-1",
            AutomationWorkStatus.RUNNING,
        )
        val yielded = session(
            52,
            questEntry,
            AutomationWorkType.QUEST,
            "quest-1",
            AutomationWorkStatus.YIELDED_PRIORITY,
            nextCheckAt = null,
        )
        val snapshot = battleDecisionEntry()
        val action = BattleMapAutomationAction(
            accountId = 7,
            progressDate = java.time.LocalDate.parse("2026-07-23"),
            categoryId = "battle_map",
            mapCode = "map-1",
            presetMode = PresetSelectionMode.PRIMARY,
            presetId = 3,
            battleCount = 1,
            executionIdentity = "running-battle",
        )
        Mockito.`when`(work.findRunning(7)).thenReturn(running)
        Mockito.`when`(work.findWaiting(7)).thenReturn(listOf(yielded))
        Mockito.`when`(loader.loadEntry(7, battleEntry.id, running.targetKey, null)).thenReturn(snapshot)
        battleRules.returns(requireNotNull(snapshot.battle), HandlerEvaluation.Runnable(action))

        val selected = assertIs<AutomationCoordination.Runnable>(selector.select(7))

        assertEquals(battleEntry.id, selected.entryId)
        Mockito.verify(lifecycle, Mockito.never()).handoffForPriority(7, running.id, yielded.id)
        Mockito.verify(lifecycle, Mockito.never()).resumeForCheck(7, yielded.id)
    }

    @Test
    fun `exhausted fishing and cooling battle are skipped before selecting a lower priority adventure`() {
        val battleSnapshot = battleDecisionEntry()
        val adventureSnapshot = adventureDecisionEntry()
        val fishingSnapshot = fishingDecisionEntry()
        val retryAt = now.plusSeconds(501)
        val adventureAction = AdventureMapAutomationAction(
            accountId = 7,
            categoryId = "adventure_map",
            mapCode = "map-1",
            presetMode = PresetSelectionMode.PRIMARY,
            presetId = 3,
            settingIdentity = 30,
            executionIdentity = "adventure-1",
        )
        Mockito.`when`(work.findRunning(7)).thenReturn(null)
        Mockito.`when`(work.findWaiting(7)).thenReturn(emptyList())
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(fishingEntry, battleEntry, adventureEntry))
        Mockito.`when`(loader.loadEntry(7, fishingEntry.id, null, null)).thenReturn(fishingSnapshot)
        fishingRules.returns(
            requireNotNull(fishingSnapshot.fishing),
            HandlerEvaluation.Unavailable(now.plusSeconds(86400), "FISHING_DAILY_LIMIT", "오늘의 낚시 횟수를 모두 사용했습니다."),
        )
        Mockito.`when`(loader.loadEntry(7, 11, null, null)).thenReturn(battleSnapshot)
        battleRules.returns(
            requireNotNull(battleSnapshot.battle),
            HandlerEvaluation.Unavailable(retryAt),
        )
        Mockito.`when`(loader.loadEntry(7, 12, null, null)).thenReturn(adventureSnapshot)
        adventureRules.returns(
            requireNotNull(adventureSnapshot.adventure),
            HandlerEvaluation.Runnable(adventureAction),
        )

        val selected = assertIs<AutomationCoordination.Runnable>(selector.select(7))

        assertEquals(12, selected.entryId)
        assertEquals(
            listOf(AutomationDecisionOutcome.SKIPPED, AutomationDecisionOutcome.SKIPPED, AutomationDecisionOutcome.SELECTED),
            selected.trace.map(AutomationEvaluationTrace::outcome),
        )
        assertEquals(retryAt, selected.trace[1].nextRunAt)
        assertEquals(listOf(fishingEntry.id, battleEntry.id, adventureEntry.id), selected.trace.map(AutomationEvaluationTrace::entryId))
        assertEquals(null, selected.trace[0].actionKind)
    }

    @Test
    fun `fishing catch transition keeps ownership instead of checking higher or lower automation`() {
        val home = AutomationEntryEntity(13, account, AutomationType.HOME_QUEST, 0, true, now, now)
        val runningFishing = session(
            31,
            fishingEntry,
            AutomationWorkType.FISHING,
            FISHING_CYCLE_TARGET,
            AutomationWorkStatus.RUNNING,
        )
        val fishingSnapshot = fishingDecisionEntry()
        val retryAt = now.plusSeconds(5)
        Mockito.`when`(work.findRunning(7)).thenReturn(runningFishing)
        Mockito.`when`(work.findWaiting(7)).thenReturn(emptyList())
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(home, fishingEntry, battleEntry))
        Mockito.`when`(loader.loadEntry(7, 14, FISHING_CYCLE_TARGET, null)).thenReturn(fishingSnapshot)
        fishingRules.returns(
            requireNotNull(fishingSnapshot.fishing),
            HandlerEvaluation.Unavailable(
                retryAt,
                "FISHING_CATCH_TRANSITION_PENDING",
                "잡기 전환을 기다립니다.",
                AutomationWaitScope.HOLD_CURRENT_WORK,
            ),
        )

        val selected = assertIs<AutomationCoordination.Unavailable>(selector.select(7))

        assertEquals(retryAt, selected.nextRunAt)
        assertEquals(AutomationDecisionOutcome.WAITING, selected.trace.single().outcome)
        Mockito.verify(lifecycle, Mockito.never()).waitForCooldown(7, 31, retryAt)
        Mockito.verify(loader, Mockito.never()).loadEntry(7, home.id, null, null)
        Mockito.verify(loader, Mockito.never()).loadEntry(7, battleEntry.id, null, null)
    }

    @ParameterizedTest
    @EnumSource(RaidWaitReason::class)
    fun `raid wait classification on resumed work preserves its boundary`(reason: RaidWaitReason) {
        val runningRaid = session(30, raidEntry, AutomationWorkType.RAID, "RaidGoblin", AutomationWorkStatus.RUNNING)
        val retryAt = now.plusSeconds(120)
        val raidModule = Mockito.mock(RaidCycleModule::class.java)
        val moduleSelector = selectorWithRaid(raidModule)
        val battleSnapshot = battleDecisionEntry()
        val battleAction = BattleMapAutomationAction(
            accountId = 7,
            progressDate = java.time.LocalDate.parse("2026-07-23"),
            categoryId = "battle_map",
            mapCode = "map-1",
            presetMode = PresetSelectionMode.PRIMARY,
            presetId = 3,
            battleCount = 1,
            executionIdentity = "battle-after-raid-wait",
        )
        Mockito.`when`(work.findRunning(7)).thenReturn(runningRaid, null)
        Mockito.`when`(raidModule.decide(7)).thenReturn(
            RaidDecision(RaidDirective.WaitUntil(
                at = retryAt,
                reason = reason,
                message = "레이드 전투 쿨다운",
                entryId = raidEntry.id,
                raidId = "RaidGoblin",
                cooldownSource = RaidCooldownSource.LOCAL_FALLBACK,
                impactScope = AutomationImpactScope.RAID_ONLY,
                releaseCondition = "마감 뒤 최신 레이드 상태 재확인",
                reasonCode = "RAID_BATTLE_SAFETY_GATE",
            )),
        )
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(raidEntry, battleEntry))
        Mockito.`when`(work.findWaiting(7)).thenReturn(listOf(
            session(30, raidEntry, AutomationWorkType.RAID, "RaidGoblin", AutomationWorkStatus.WAITING_COOLDOWN, nextCheckAt = retryAt),
        ))
        Mockito.`when`(loader.loadEntry(7, 11, null, null)).thenReturn(battleSnapshot)
        battleRules.returns(requireNotNull(battleSnapshot.battle), HandlerEvaluation.Runnable(battleAction))

        val boundary = assertIs<AutomationCoordination.CycleBoundary>(moduleSelector.select(7))

        assertEquals(1, boundary.trace.size)
        assertEquals(AutomationType.RAID, boundary.trace.single().type)
        assertEquals(expectedRaidWaitOutcome(reason), boundary.trace.single().outcome)
        assertEquals("RAID_BATTLE_SAFETY_GATE", boundary.trace.single().reasonCode)
        assertEquals(retryAt, boundary.trace.single().nextRunAt)
        assertEquals("RaidGoblin", boundary.trace.single().targetKey)
        assertTrue(requireNotNull(boundary.trace.single().diagnosticContext).contains("RAID_EVALUATION"))
        assertEquals(RaidCooldownSource.LOCAL_FALLBACK, boundary.trace.single().cooldownSource)
        assertEquals(AutomationImpactScope.RAID_ONLY, boundary.trace.single().impactScope)
        assertEquals("마감 뒤 최신 레이드 상태 재확인", boundary.trace.single().releaseCondition)
        Mockito.verify(lifecycle).waitForCooldown(7, 30, retryAt)
        Mockito.verify(loader, Mockito.never()).loadEntry(7, 13, "RaidGoblin")
        Mockito.verify(loader, Mockito.never()).loadEntry(7, battleEntry.id, null, null)
        assertEquals(battleEntry.id, assertIs<AutomationCoordination.Runnable>(moduleSelector.select(7)).entryId)
    }

    @ParameterizedTest
    @EnumSource(RaidWaitReason::class)
    fun `raid wait before its first action is classified and releases the next automation entry`(reason: RaidWaitReason) {
        val retryAt = now.plusSeconds(120)
        val battleSnapshot = battleDecisionEntry()
        val battleAction = BattleMapAutomationAction(
            accountId = 7,
            progressDate = java.time.LocalDate.parse("2026-07-23"),
            categoryId = "battle_map",
            mapCode = "map-1",
            presetMode = PresetSelectionMode.PRIMARY,
            presetId = 3,
            battleCount = 1,
            executionIdentity = "battle-after-new-raid-wait",
        )
        Mockito.`when`(work.findRunning(7)).thenReturn(null)
        Mockito.`when`(work.findWaiting(7)).thenReturn(emptyList())
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(raidEntry, battleEntry))
        Mockito.`when`(defaultRaidModule.decide(7)).thenReturn(
            RaidDecision(RaidDirective.WaitUntil(
                at = retryAt,
                reason = reason,
                message = "레이드 등록 쿨타임",
                entryId = raidEntry.id,
                raidId = "RaidGoblin",
            )),
        )
        Mockito.`when`(loader.loadEntry(7, 11, null, null)).thenReturn(battleSnapshot)
        battleRules.returns(requireNotNull(battleSnapshot.battle), HandlerEvaluation.Runnable(battleAction))

        val selected = assertIs<AutomationCoordination.Runnable>(selector.select(7))

        assertEquals(11, selected.entryId)
        val raidTrace = selected.trace.first()
        assertEquals(expectedRaidWaitOutcome(reason), raidTrace.outcome)
        assertEquals(reason.name, raidTrace.reasonCode)
        assertEquals("RaidGoblin", raidTrace.targetKey)
        assertEquals(retryAt, raidTrace.nextRunAt)
        assertTrue(requireNotNull(raidTrace.diagnosticContext).contains("RAID_EVALUATION"))
        Mockito.verify(lifecycle).waitForRaid(7, raidEntry.id, "RaidGoblin", retryAt)
    }

    @Test
    fun `invalid raid preset is parked until configuration changes and releases other automation`() {
        val battleSnapshot = battleDecisionEntry()
        val battleAction = BattleMapAutomationAction(
            accountId = 7,
            progressDate = java.time.LocalDate.parse("2026-07-23"),
            categoryId = "battle_map",
            mapCode = "map-1",
            presetMode = PresetSelectionMode.PRIMARY,
            presetId = 3,
            battleCount = 1,
            executionIdentity = "battle-after-raid-preset-hold",
        )
        Mockito.`when`(work.findRunning(7)).thenReturn(null)
        Mockito.`when`(work.findWaiting(7)).thenReturn(emptyList())
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(raidEntry, battleEntry))
        Mockito.`when`(defaultRaidModule.decide(7)).thenReturn(
            RaidDecision(RaidDirective.Hold(
                reason = RaidHoldReason.INVALID_PRESET,
                message = "레이드 전투 프리셋 구성을 확인해 주세요.",
                entryId = raidEntry.id,
                raidId = "RaidGoblin",
                reasonCode = "RAID_BATTLE_RECOVERY_SUPERSEDED_BY_MAP",
            )),
        )
        Mockito.`when`(loader.loadEntry(7, 11, null, null)).thenReturn(battleSnapshot)
        battleRules.returns(requireNotNull(battleSnapshot.battle), HandlerEvaluation.Runnable(battleAction))

        val selected = assertIs<AutomationCoordination.Runnable>(selector.select(7))

        assertEquals(11, selected.entryId)
        assertEquals("RAID_BATTLE_RECOVERY_SUPERSEDED_BY_MAP", selected.trace.first().reasonCode)
        Mockito.verify(lifecycle).waitForRaid(
            7,
            raidEntry.id,
            "RaidGoblin",
            null,
            "레이드 전투 프리셋 구성을 확인해 주세요.",
        )
    }

    @Test
    fun `temporary incomplete raid observation is a normal scoped wait and not a user warning`() {
        val retryAt = now.plusSeconds(10)
        val battleSnapshot = battleDecisionEntry()
        val battleAction = BattleMapAutomationAction(
            accountId = 7,
            progressDate = java.time.LocalDate.parse("2026-07-23"),
            categoryId = "battle_map",
            mapCode = "map-1",
            presetMode = PresetSelectionMode.PRIMARY,
            presetId = 3,
            battleCount = 1,
            executionIdentity = "battle-after-incomplete-raid-observation",
        )
        Mockito.`when`(work.findRunning(7)).thenReturn(null)
        Mockito.`when`(work.findWaiting(7)).thenReturn(emptyList())
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(raidEntry, battleEntry))
        Mockito.`when`(defaultRaidModule.decide(7)).thenReturn(
            RaidDecision(RaidDirective.Hold(
                reason = RaidHoldReason.BATTLE_OBSERVATION_INCOMPLETE,
                message = "레이드 전투 상태를 다시 확인합니다.",
                recheckAt = retryAt,
                entryId = raidEntry.id,
                raidId = "RaidGoblin",
            )),
        )
        Mockito.`when`(loader.loadEntry(7, battleEntry.id, null, null)).thenReturn(battleSnapshot)
        battleRules.returns(requireNotNull(battleSnapshot.battle), HandlerEvaluation.Runnable(battleAction))

        val selected = assertIs<AutomationCoordination.Runnable>(selector.select(7))

        assertEquals(emptyList(), selected.warnings)
        assertEquals(AutomationDecisionOutcome.WAITING, selected.trace.first().outcome)
        Mockito.verify(lifecycle).waitForRaid(7, raidEntry.id, "RaidGoblin", retryAt, null)
    }

    @Test
    fun `다음 확인 시각 전인 레이드도 하위 항목 선택 이력에 스킵을 남긴다`() {
        val retryAt = now.plusSeconds(900)
        val lowerQuest = AutomationEntryEntity(15, account, AutomationType.QUEST, 1, true, now, now)
        val questSnapshot = questDecisionEntry(lowerQuest.id)
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(raidEntry, lowerQuest))
        Mockito.`when`(work.findWaiting(7)).thenReturn(listOf(
            session(33, raidEntry, AutomationWorkType.RAID, "RaidGoblin",
                AutomationWorkStatus.WAITING_COOLDOWN, nextCheckAt = retryAt),
        ))
        Mockito.`when`(loader.loadEntry(7, lowerQuest.id, null, null)).thenReturn(questSnapshot)
        questRules.returns(requireNotNull(questSnapshot.quest), QuestDirective.Execute(QuestAction.Accept("quest-1", "accept-1")))

        val selected = assertIs<AutomationCoordination.Runnable>(selector.select(7))

        assertEquals(lowerQuest.id, selected.entryId)
        assertEquals(listOf(raidEntry.id, lowerQuest.id), selected.trace.map { it.entryId })
        assertEquals(listOf(0, 1), selected.trace.map { it.sequence })
        assertEquals(AutomationDecisionOutcome.SKIPPED, selected.trace.first().outcome)
        assertEquals("WORK_RECHECK_NOT_DUE", selected.trace.first().reasonCode)
        assertEquals(retryAt, selected.trace.first().nextRunAt)
        assertEquals("RaidGoblin", selected.trace.first().targetKey)
        val diagnostic = jacksonObjectMapper().readTree(requireNotNull(selected.trace.first().diagnosticContext))
        assertEquals(33L, diagnostic.path("workSessionId").asLong())
        Mockito.verifyNoInteractions(defaultRaidModule, lifecycle)
    }

    @Test
    fun `재확인 시각 없는 레이드 보류도 유휴 판단 이력에서 사라지지 않는다`() {
        val holdMessage = "진행 중인 레이드 상태를 확인할 수 없습니다."
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(raidEntry))
        Mockito.`when`(work.findWaiting(7)).thenReturn(listOf(
            session(34, raidEntry, AutomationWorkType.RAID, "RaidGoblin",
                AutomationWorkStatus.WAITING_COOLDOWN, holdMessage = holdMessage),
        ))

        val result = assertIs<AutomationCoordination.Idle>(selector.select(7))

        assertEquals(raidEntry.id, result.trace.single().entryId)
        assertEquals(AutomationDecisionOutcome.SKIPPED, result.trace.single().outcome)
        assertEquals("WORK_RECHECK_UNSCHEDULED", result.trace.single().reasonCode)
        assertEquals(holdMessage, result.trace.single().message)
        assertEquals(listOf(holdMessage), result.warnings)
        assertNull(result.trace.single().nextRunAt)
        assertNull(result.nextRunAt)
        Mockito.verifyNoInteractions(defaultRaidModule, lifecycle, loader)
    }

    @Test
    fun `parked raid hold warning survives lower priority actions without an early raid recheck`() {
        val holdMessage = "사용자가 진행 중인 레이드가 끝날 때까지 레이드 자동화를 보류합니다."
        val battleSnapshot = battleDecisionEntry()
        val battleAction = BattleMapAutomationAction(
            accountId = 7,
            progressDate = java.time.LocalDate.parse("2026-07-23"),
            categoryId = "battle_map",
            mapCode = "map-1",
            presetMode = PresetSelectionMode.PRIMARY,
            presetId = 3,
            battleCount = 1,
            executionIdentity = "battle-while-manual-raid-is-active",
        )
        Mockito.`when`(work.findRunning(7)).thenReturn(null)
        Mockito.`when`(work.findWaiting(7)).thenReturn(listOf(
            session(
                33,
                raidEntry,
                AutomationWorkType.RAID,
                "RaidManual",
                AutomationWorkStatus.WAITING_COOLDOWN,
                nextCheckAt = now.plusSeconds(30),
                holdMessage = holdMessage,
            ),
        ))
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(raidEntry, battleEntry))
        Mockito.`when`(loader.loadEntry(7, battleEntry.id, null, null)).thenReturn(battleSnapshot)
        battleRules.returns(requireNotNull(battleSnapshot.battle), HandlerEvaluation.Runnable(battleAction))

        val selected = assertIs<AutomationCoordination.Runnable>(selector.select(7))

        assertEquals(listOf(holdMessage), selected.warnings)
        assertEquals(raidEntry.id, selected.trace.first().entryId)
        assertEquals(AutomationDecisionOutcome.SKIPPED, selected.trace.first().outcome)
        assertEquals(holdMessage, selected.trace.first().message)
        Mockito.verifyNoInteractions(defaultRaidModule)
    }

    @Test
    fun `raid completion directive is classified as a cycle history outcome`() {
        Mockito.`when`(work.findRunning(7)).thenReturn(null)
        Mockito.`when`(work.findWaiting(7)).thenReturn(emptyList())
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(raidEntry))
        Mockito.`when`(defaultRaidModule.decide(7)).thenReturn(
            RaidDecision(RaidDirective.Complete(
                RaidCycleOutcome(raidEntry.id, "RaidGoblin", RaidCycleOutcomeKind.ABORTED_CLOSED),
            )),
        )

        val decision = assertIs<AutomationCoordination.Idle>(selector.select(7))

        assertNull(decision.nextRunAt)
        assertEquals(AutomationDecisionOutcome.CYCLE_ABORTED, decision.trace.single().outcome)
        assertEquals(RaidCycleOutcomeKind.ABORTED_CLOSED.name, decision.trace.single().reasonCode)
    }

    @Test
    fun `due raid cooldown wakes the module for a fresh decision`() {
        val dueRaid = session(
            32, raidEntry, AutomationWorkType.RAID, "RaidGoblin",
            AutomationWorkStatus.WAITING_COOLDOWN, nextCheckAt = now,
        )
        val raidModule = Mockito.mock(RaidCycleModule::class.java)
        val moduleSelector = selectorWithRaid(raidModule)
        Mockito.`when`(work.findRunning(7)).thenReturn(
            null,
            dueRaid.copy(status = AutomationWorkStatus.RUNNING, nextCheckAt = null),
        )
        Mockito.`when`(work.findWaiting(7)).thenReturn(listOf(dueRaid))
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(raidEntry))
        Mockito.`when`(raidModule.decide(7)).thenReturn(
            RaidDecision(RaidDirective.Execute(
                RaidIntent.Town(13, "RaidGoblin", "고블린", RaidIntentKind.REFRESH, requestRaidId = null),
            )),
        )

        val selected = assertIs<AutomationCoordination.Runnable>(moduleSelector.select(7))

        assertEquals(13, selected.entryId)
        assertNull(selected.trace.single().actionKind)
        assertNull(selected.trace.single().targetKey)
        assertEquals("레이드 자동화 단계를 실행합니다.", selected.trace.single().message)
        Mockito.verify(lifecycle).resumeForCheck(7, 32)
        Mockito.verify(raidModule).decide(7)
        Mockito.verify(loader, Mockito.never()).loadEntry(7, 13, "RaidGoblin")
    }

    @Test
    fun `due higher priority raid reward waits until the running quest reaches a boundary`() {
        val lowerPriorityQuestEntry = AutomationEntryEntity(
            10,
            account,
            AutomationType.QUEST,
            4,
            true,
            now,
            now,
        )
        val higherPriorityRaidEntry = AutomationEntryEntity(
            13,
            account,
            AutomationType.RAID,
            1,
            true,
            now,
            now,
        )
        val runningQuest = session(
            21,
            lowerPriorityQuestEntry,
            AutomationWorkType.QUEST,
            "quest-1",
            AutomationWorkStatus.RUNNING,
        )
        val dueRaid = session(
            32,
            higherPriorityRaidEntry,
            AutomationWorkType.RAID,
            "RaidGoblin",
            AutomationWorkStatus.WAITING_COOLDOWN,
            nextCheckAt = now,
        )
        val questSnapshot = questDecisionEntry()
        val questAction = QuestAction.Battle(
            questKey = "quest-1",
            questCycle = "1",
            missionKey = "mission",
            missionType = app.spammy.hof.quest.model.QuestMissionType.MONSTER_KILL,
            categoryId = "battle_map",
            mapCode = "map",
            mapName = "Map",
            preset = QuestPresetSelection(app.spammy.hof.automation.entity.PresetSelectionMode.PRIMARY),
        )
        val runningSnapshot = questSnapshot.copy(
            quest = requireNotNull(questSnapshot.quest).copy(
                workSessionId = runningQuest.id,
                workSessionRevision = runningQuest.revision,
            ),
        )
        Mockito.`when`(work.findRunning(7)).thenReturn(
            runningQuest,
            dueRaid.copy(status = AutomationWorkStatus.RUNNING, nextCheckAt = null),
        )
        Mockito.`when`(work.findWaiting(7)).thenReturn(listOf(dueRaid))
        Mockito.`when`(
            lifecycle.handoffForPriority(7, runningQuest.id, dueRaid.id),
        ).thenReturn(true)
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(higherPriorityRaidEntry, lowerPriorityQuestEntry))
        Mockito.`when`(loader.loadEntry(7, lowerPriorityQuestEntry.id, "quest-1")).thenReturn(questSnapshot)
        questRules.returns(requireNotNull(runningSnapshot.quest), QuestDirective.Execute(questAction))
        Mockito.`when`(defaultRaidModule.decide(7)).thenReturn(
            RaidDecision(RaidDirective.Execute(
                RaidIntent.Town(
                    higherPriorityRaidEntry.id,
                    "RaidGoblin",
                    "고블린",
                    RaidIntentKind.REWARD,
                    requestRaidId = "RaidGoblin",
                ),
            )),
        )

        val selected = assertIs<AutomationCoordination.Runnable>(selector.select(7))

        assertEquals(lowerPriorityQuestEntry.id, selected.entryId)
        assertEquals(questAction, selected.action)
        Mockito.verify(lifecycle, Mockito.never()).handoffForPriority(7, runningQuest.id, dueRaid.id)
        Mockito.verify(lifecycle, Mockito.never()).resumeForCheck(7, dueRaid.id)
        Mockito.verifyNoInteractions(defaultRaidModule)
        Mockito.verify(loader).loadEntry(7, lowerPriorityQuestEntry.id, "quest-1")
    }

    @Test
    fun `due raid recovery selects a new linked execution and keeps a distinct retransmission warning`() {
        val dueRaid = session(
            33, raidEntry, AutomationWorkType.RAID, "RaidGoblin",
            AutomationWorkStatus.WAITING_COOLDOWN, nextCheckAt = now,
        )
        val party = ResolvedAutomationParty(
            listOf("101"),
            listOf(app.spammy.hof.battle.dto.BattlePatternLoadRequest("101", 1)),
        )
        val resumedRaid = dueRaid.copy(status = AutomationWorkStatus.RUNNING, nextCheckAt = null)
        Mockito.`when`(work.findRunning(7)).thenReturn(null, resumedRaid, resumedRaid)
        Mockito.`when`(work.findWaiting(7)).thenReturn(listOf(dueRaid))
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(raidEntry))
        Mockito.`when`(defaultRaidModule.decide(7)).thenReturn(
            RaidDecision(RaidDirective.Execute(
                RaidIntent.Battle(
                    entryId = raidEntry.id,
                    raidId = "RaidGoblin",
                    raidName = "고블린",
                    categoryId = "raid",
                    mapCode = "map-2",
                    presetMode = PresetSelectionMode.EXPLICIT,
                    presetId = 44L,
                    party = party,
                    recoveryChainId = "chain-1",
                    retransmissionCount = 3,
                ),
            )),
        )

        val selected = assertIs<AutomationCoordination.Runnable>(selector.select(7))
        val selectedAgain = assertIs<AutomationCoordination.Runnable>(selector.select(7))

        val action = assertIs<BattleMapAutomationAction>(selected.action)
        val nextAction = assertIs<BattleMapAutomationAction>(selectedAgain.action)
        assertEquals("chain-1", action.recoveryChainId)
        assertEquals(action.recoveryChainId, nextAction.recoveryChainId)
        assertNotEquals(action.executionIdentity, nextAction.executionIdentity)
        assertEquals(3, action.raidRetransmissionCount)
        assertEquals(44L, action.presetId)
        assertEquals(party, action.resolvedParty)
        assertEquals("RAID_BATTLE_RETRANSMIT", selected.trace.single().reasonCode)
        assertTrue(selected.warnings.single().contains("재전송 3회"))
    }

    @Test
    fun `running quest session stays sticky to its target`() {
        val session = session(21, questEntry, AutomationWorkType.QUEST, "quest-1", AutomationWorkStatus.RUNNING)
        val questSnapshot = questDecisionEntry()
        val action = QuestAction.Battle(
            questKey = "quest-1",
            questCycle = "1",
            missionKey = "mission",
            missionType = app.spammy.hof.quest.model.QuestMissionType.MONSTER_KILL,
            categoryId = "battle_map",
            mapCode = "map",
            mapName = "Map",
            preset = QuestPresetSelection(app.spammy.hof.automation.entity.PresetSelectionMode.PRIMARY),
        )
        val runningSnapshot = questSnapshot.copy(
            quest = requireNotNull(questSnapshot.quest).copy(
                workSessionId = session.id,
                workSessionRevision = session.revision,
            ),
        )
        Mockito.`when`(work.findRunning(7)).thenReturn(session)
        Mockito.`when`(work.findWaiting(7)).thenReturn(emptyList())
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(questEntry))
        Mockito.`when`(loader.loadEntry(7, 10, "quest-1")).thenReturn(questSnapshot)
        questRules.returns(requireNotNull(runningSnapshot.quest), QuestDirective.Execute(action))

        val selected = assertIs<AutomationCoordination.Runnable>(selector.select(7))

        assertEquals("quest-1", (selected.action as QuestAction.Battle).questKey)
        assertEquals(listOf(questEntry.id), selected.trace.map(AutomationEvaluationTrace::entryId))
        Mockito.verify(typed, Mockito.never()).findEntries(7)
        Mockito.verify(loader).loadEntry(7, 10, "quest-1")
    }

    @Test
    fun `accepted quest keeps ownership through its claim action`() {
        val running = session(44, questEntry, AutomationWorkType.QUEST, "quest-1", AutomationWorkStatus.RUNNING)
        val firstSnapshot = questDecisionEntry()
        val runningSnapshot = requireNotNull(firstSnapshot.quest).copy(
            workSessionId = running.id,
            workSessionRevision = running.revision,
        )
        val accept = QuestAction.Accept("quest-1", "accept-1")
        val claim = QuestAction.Claim("quest-1", "claim-1")
        Mockito.`when`(work.findRunning(7)).thenReturn(null, running)
        Mockito.`when`(work.findWaiting(7)).thenReturn(emptyList())
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(questEntry))
        Mockito.`when`(loader.loadEntry(7, questEntry.id, null, null)).thenReturn(firstSnapshot)
        Mockito.`when`(loader.loadEntry(7, questEntry.id, running.targetKey, null)).thenReturn(firstSnapshot)
        questRules.returns(requireNotNull(firstSnapshot.quest), QuestDirective.Execute(accept))
        questRules.returns(runningSnapshot, QuestDirective.Execute(claim))

        val accepted = assertIs<AutomationCoordination.Runnable>(selector.select(7))
        val claimed = assertIs<AutomationCoordination.Runnable>(selector.select(7))

        assertEquals(accept, accepted.action)
        assertEquals(claim, claimed.action)
        Mockito.verify(typed, Mockito.times(1)).findEntries(7)
    }

    @Test
    fun `incomplete material quest parks resource wait and releases lower priorities`() {
        val session = session(21, questEntry, AutomationWorkType.QUEST, "quest-1", AutomationWorkStatus.RUNNING)
        val quest = QuestSnapshot(
            questKey = "quest-1",
            name = "재료 수집",
            state = QuestState.ACTIVE,
            section = QuestSection.ACTIVE,
            sourceOrder = 0,
            missions = listOf(
                QuestMission(
                    key = "material",
                    type = QuestMissionType.ITEM_TURN_IN,
                    target = "  Steel   Ingot ",
                    progress = QuestProgress(current = 3, required = 5),
                    completable = false,
                ),
            ),
            actionNo = null,
        )
        val questContext = QuestAutomationSnapshot(
            accountId = 7,
            quests = listOf(quest),
            selections = emptyList(),
            mapStates = emptyList(),
            currentCycles = emptyMap(),
            counters = emptyMap(),
            mapIdentityCandidates = emptyList(),
            now = now,
        )
        val entrySnapshot = AutomationEntrySnapshot(10, AutomationType.QUEST, quest = questContext)
        val runningSnapshot = entrySnapshot.copy(quest = questContext.copy(
            workSessionId = session.id,
            workSessionRevision = session.revision,
        ))
        Mockito.`when`(work.findRunning(7)).thenReturn(session)
        Mockito.`when`(loader.loadEntry(7, 10, "quest-1")).thenReturn(entrySnapshot)
        questRules.returns(
            requireNotNull(runningSnapshot.quest),
            QuestDirective.WaitForResource("steel ingot", 2),
        )
        Mockito.`when`(typed.findEntries(7)).thenReturn(emptyList())

        assertIs<AutomationCoordination.CycleBoundary>(selector.select(7))

        Mockito.verify(lifecycle).applyTransition(
            7,
            21,
            AutomationWorkTransition.WaitForResource("steel ingot", 2),
        )
    }

    @Test
    fun `map clear below optimistic target still reloads authoritative quest progress`() {
        val session = session(
            id = 25,
            entry = questEntry,
            workType = AutomationWorkType.QUEST,
            targetKey = "quest-1",
            status = AutomationWorkStatus.RUNNING,
        )
        val liveQuest = QuestSnapshot(
            questKey = "quest-1",
            name = "quest-1",
            state = QuestState.ACTIVE,
            section = QuestSection.ACTIVE,
            sourceOrder = 0,
            missions = listOf(
                QuestMission(
                    key = "clear-map",
                    type = QuestMissionType.MAP_CLEAR,
                    target = null,
                    progress = QuestProgress(3, 5),
                    completable = false,
                ),
            ),
            actionNo = null,
        )
        val questContext = QuestAutomationSnapshot(
            accountId = 7,
            quests = listOf(liveQuest),
            selections = emptyList(),
            mapStates = emptyList(),
            currentCycles = emptyMap(),
            counters = emptyMap(),
            mapIdentityCandidates = emptyList(),
            now = now,
        )
        val entrySnapshot = AutomationEntrySnapshot(10, AutomationType.QUEST, quest = questContext)
        val enrichedEntry = entrySnapshot.copy(
            quest = questContext.copy(
                workSessionId = 25,
                workSessionRevision = session.revision,
            ),
        )
        val action = QuestAction.Battle(
            questKey = "quest-1",
            questCycle = "1",
            missionKey = "clear-map",
            missionType = QuestMissionType.MAP_CLEAR,
            categoryId = "battle_map",
            mapCode = "map",
            mapName = "Map",
            preset = QuestPresetSelection(app.spammy.hof.automation.entity.PresetSelectionMode.PRIMARY),
        )
        Mockito.`when`(work.findRunning(7)).thenReturn(session)
        Mockito.`when`(loader.loadEntry(7, 10, "quest-1")).thenReturn(entrySnapshot)
        questRules.returns(requireNotNull(enrichedEntry.quest), QuestDirective.Execute(action))

        assertIs<AutomationCoordination.Runnable>(selector.select(7))

        Mockito.verify(loader).loadEntry(7, 10, "quest-1")
    }

    @Test
    fun `authoritative map clear mismatch replaces optimistic progress before continuing`() {
        val session = session(
            id = 26,
            entry = questEntry,
            workType = AutomationWorkType.QUEST,
            targetKey = "quest-1",
            status = AutomationWorkStatus.RUNNING,
        )
        val liveQuest = QuestSnapshot(
            questKey = "quest-1",
            name = "quest-1",
            state = QuestState.ACTIVE,
            section = QuestSection.ACTIVE,
            sourceOrder = 0,
            missions = listOf(
                QuestMission(
                    key = "clear-map",
                    type = QuestMissionType.MAP_CLEAR,
                    target = null,
                    progress = QuestProgress(4, 5),
                    completable = false,
                ),
            ),
            actionNo = null,
        )
        val questContext = QuestAutomationSnapshot(
            accountId = 7,
            quests = listOf(liveQuest),
            selections = emptyList(),
            mapStates = emptyList(),
            currentCycles = emptyMap(),
            counters = emptyMap(),
            mapIdentityCandidates = emptyList(),
            now = now,
        )
        val entrySnapshot = AutomationEntrySnapshot(10, AutomationType.QUEST, quest = questContext)
        val enrichedEntry = entrySnapshot.copy(
            quest = questContext.copy(
                workSessionId = 26,
                workSessionRevision = session.revision,
            ),
        )
        val action = QuestAction.Battle(
            questKey = "quest-1",
            questCycle = "1",
            missionKey = "clear-map",
            missionType = QuestMissionType.MAP_CLEAR,
            categoryId = "battle_map",
            mapCode = "map",
            mapName = "Map",
            preset = QuestPresetSelection(app.spammy.hof.automation.entity.PresetSelectionMode.PRIMARY),
            missionCurrent = 4,
            missionRequired = 5,
        )
        Mockito.`when`(work.findRunning(7)).thenReturn(session)
        Mockito.`when`(loader.loadEntry(7, 10, "quest-1", null)).thenReturn(entrySnapshot)
        questRules.returns(requireNotNull(enrichedEntry.quest), QuestDirective.Execute(action))

        assertIs<AutomationCoordination.Runnable>(selector.select(7))

        assertTrue(requireNotNull(enrichedEntry.quest) in questRules.evaluated)
    }

    @Test
    fun `parked quest target does not block another runnable quest in the same entry`() {
        val waiting = session(
            id = 27,
            entry = questEntry,
            workType = AutomationWorkType.QUEST,
            targetKey = "quest-1",
            status = AutomationWorkStatus.WAITING_RESOURCE,
            nextCheckAt = now.plusSeconds(1800),
        )
        val questSnapshot = AutomationEntrySnapshot(
            10,
            AutomationType.QUEST,
            quest = QuestAutomationSnapshot(
                accountId = 7,
                quests = emptyList(),
                selections = listOf(selection("quest-1"), selection("quest-2")),
                mapStates = emptyList(),
                currentCycles = emptyMap(),
                counters = emptyMap(),
                mapIdentityCandidates = emptyList(),
                now = now,
            ),
        )
        val questAction = QuestAction.Accept("quest-2", "accept-2")
        Mockito.`when`(work.findRunning(7)).thenReturn(null)
        Mockito.`when`(work.findWaiting(7)).thenReturn(listOf(waiting))
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(questEntry))
        Mockito.`when`(loader.loadEntry(7, 10, null, null)).thenReturn(questSnapshot)
        questRules.returns(requireNotNull(questSnapshot.quest), QuestDirective.Execute(questAction))

        val selected = assertIs<AutomationCoordination.Runnable>(selector.select(7))

        assertEquals("quest-2", assertIs<QuestAction.Accept>(selected.action).questKey)
        Mockito.verify(loader).loadEntry(7, 10, null, null)
        assertTrue(requireNotNull(questSnapshot.quest) in questRules.evaluated)
    }

    @Test
    fun `parked home target does not block another runnable home cycle`() {
        val homeEntry = AutomationEntryEntity(16, account, AutomationType.HOME_QUEST, 0, true, now, now)
        val waiting = session(
            id = 26,
            entry = homeEntry,
            workType = AutomationWorkType.HOME_QUEST,
            targetKey = "home-1",
            status = AutomationWorkStatus.WAITING_COOLDOWN,
            nextCheckAt = now.plusSeconds(1_800),
        )
        val homeSnapshot = AutomationEntrySnapshot(
            homeEntry.id,
            AutomationType.HOME_QUEST,
            homeQuest = HomeQuestAutomationSnapshot(
                accountId = 7,
                quests = emptyList(),
                selections = listOf(
                    HomeQuestAutomationSelection("home-1", "home-1", true, 0),
                    HomeQuestAutomationSelection("home-2", "home-2", true, 1),
                ),
                now = now,
            ),
        )
        val action = HomeQuestAutomationAction(
            accountId = 7,
            questId = "home-2",
            questName = "home-2",
            actionId = "accept-2",
            action = HomeQuestAutomationActionType.ACCEPT,
        )
        Mockito.`when`(work.findRunning(7)).thenReturn(null)
        Mockito.`when`(work.findWaiting(7)).thenReturn(listOf(waiting))
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(homeEntry))
        Mockito.`when`(loader.loadEntry(7, homeEntry.id, null, null)).thenReturn(homeSnapshot)
        homeQuestRules.returns(requireNotNull(homeSnapshot.homeQuest), HandlerEvaluation.Runnable(action))

        val selected = assertIs<AutomationCoordination.Runnable>(selector.select(7))

        assertEquals(action, selected.action)
        Mockito.verify(lifecycle, Mockito.never()).resumeForCheck(7, waiting.id)
    }

    @Test
    fun `accepted home quest keeps ownership and claims on its next fresh observation`() {
        val homeEntry = AutomationEntryEntity(17, account, AutomationType.HOME_QUEST, 0, true, now, now)
        val selection = HomeQuestAutomationSelection("home-1", "home-1", true, 0)
        val available = AutomationEntrySnapshot(
            homeEntry.id,
            AutomationType.HOME_QUEST,
            homeQuest = HomeQuestAutomationSnapshot(
                accountId = 7,
                quests = listOf(app.spammy.hof.town.home.dto.HomeQuestResponse(
                    "home-1",
                    "home-1",
                    app.spammy.hof.town.home.model.HomeQuestState.AVAILABLE,
                    null,
                    null,
                    emptyList(),
                    "accept-1",
                )),
                selections = listOf(selection),
                now = now,
            ),
        )
        val claimable = available.copy(
            homeQuest = requireNotNull(available.homeQuest).copy(
                quests = listOf(app.spammy.hof.town.home.dto.HomeQuestResponse(
                    "home-1",
                    "home-1",
                    app.spammy.hof.town.home.model.HomeQuestState.CLAIMABLE,
                    null,
                    "Gold +10",
                    emptyList(),
                    "claim-1",
                )),
            ),
        )
        val running = session(
            id = 43,
            entry = homeEntry,
            workType = AutomationWorkType.HOME_QUEST,
            targetKey = "home-1",
            status = AutomationWorkStatus.RUNNING,
        )
        val directSelector = AutomationTargetSelector(
            typed = typed,
            work = work,
            loader = loader,
            lifecycle = lifecycle,
            timeProvider = TimeProvider { now },
            raidModule = defaultRaidModule,
            quest = questRules,
            battle = battleRules,
            adventure = adventureRules,
            union = unionRules,
            fishing = fishingRules,
            homeQuest = HomeQuestAutomationHandler(),
        )
        Mockito.`when`(work.findRunning(7)).thenReturn(null, running)
        Mockito.`when`(work.findWaiting(7)).thenReturn(emptyList())
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(homeEntry))
        Mockito.`when`(loader.loadEntry(7, homeEntry.id, null, null)).thenReturn(available)
        Mockito.`when`(loader.loadEntry(7, homeEntry.id, running.targetKey, null)).thenReturn(claimable)

        val accept = assertIs<AutomationCoordination.Runnable>(directSelector.select(7))
        val claim = assertIs<AutomationCoordination.Runnable>(directSelector.select(7))

        assertEquals(HomeQuestAutomationActionType.ACCEPT, assertIs<HomeQuestAutomationAction>(accept.action).action)
        assertEquals(HomeQuestAutomationActionType.CLAIM, assertIs<HomeQuestAutomationAction>(claim.action).action)
        assertEquals("home-1", assertIs<HomeQuestAutomationAction>(claim.action).questId)
    }

    @Test
    fun `due parked quest is arbitrated with every fresh quest candidate before ownership resumes`() {
        val due = session(
            id = 28,
            entry = questEntry,
            workType = AutomationWorkType.QUEST,
            targetKey = "quest-1",
            status = AutomationWorkStatus.WAITING_RESOURCE,
            nextCheckAt = now,
        )
        val resumed = due.copy(status = AutomationWorkStatus.RUNNING, nextCheckAt = null, revision = due.revision + 1)
        val fullSnapshot = AutomationEntrySnapshot(
            10,
            AutomationType.QUEST,
            quest = QuestAutomationSnapshot(
                accountId = 7,
                quests = emptyList(),
                selections = listOf(selection("quest-1"), selection("quest-2")),
                mapStates = emptyList(),
                currentCycles = emptyMap(),
                counters = emptyMap(),
                mapIdentityCandidates = emptyList(),
                now = now,
            ),
        )
        val resumedSnapshot = requireNotNull(fullSnapshot.quest).copy(
            workSessionId = resumed.id,
            workSessionRevision = resumed.revision,
        )
        val questAction = QuestAction.Accept("quest-2", "accept-2")
        Mockito.`when`(work.findRunning(7)).thenReturn(null, resumed)
        Mockito.`when`(work.findWaiting(7)).thenReturn(listOf(due))
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(questEntry))
        Mockito.`when`(loader.loadEntry(7, questEntry.id, null, null)).thenReturn(fullSnapshot)
        Mockito.`when`(loader.loadEntry(7, questEntry.id, due.targetKey, null)).thenReturn(fullSnapshot)
        questRules.returns(requireNotNull(fullSnapshot.quest), QuestDirective.Execute(questAction))
        questRules.returns(resumedSnapshot, QuestDirective.WaitForResource("material", 1))

        val selected = assertIs<AutomationCoordination.Runnable>(selector.select(7))

        assertEquals(questAction, selected.action)
        Mockito.verify(lifecycle, Mockito.never()).resumeForCheck(7, due.id)
        Mockito.verify(loader, Mockito.never()).loadEntry(7, questEntry.id, due.targetKey, null)
    }

    @Test
    fun `running quest cooldown does not reload another target from the same entry`() {
        val retryAt = now.plusSeconds(300)
        val running = session(30, questEntry, AutomationWorkType.QUEST, "quest-1", AutomationWorkStatus.RUNNING)
        val waiting = session(
            30,
            questEntry,
            AutomationWorkType.QUEST,
            "quest-1",
            AutomationWorkStatus.WAITING_COOLDOWN,
            nextCheckAt = retryAt,
        )
        val configured = AutomationEntrySnapshot(
            10,
            AutomationType.QUEST,
            quest = QuestAutomationSnapshot(
                accountId = 7,
                quests = emptyList(),
                selections = listOf(selection("quest-1"), selection("quest-2")),
                mapStates = emptyList(),
                currentCycles = emptyMap(),
                counters = emptyMap(),
                mapIdentityCandidates = emptyList(),
                now = now,
            ),
        )
        val active = configured.copy(
            quest = requireNotNull(configured.quest).copy(
                workSessionId = running.id,
                workSessionRevision = running.revision,
            ),
        )
        Mockito.`when`(work.findRunning(7)).thenReturn(running)
        Mockito.`when`(work.findWaiting(7)).thenReturn(listOf(waiting))
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(questEntry))
        Mockito.`when`(loader.loadEntry(7, 10, "quest-1")).thenReturn(configured)
        questRules.returns(
            requireNotNull(active.quest),
            QuestDirective.WaitUntil(retryAt, "COOLDOWN", "wait"),
        )

        val selected = assertIs<AutomationCoordination.CycleBoundary>(selector.select(7))

        assertEquals(listOf(questEntry.id), selected.trace.map(AutomationEvaluationTrace::entryId))
        Mockito.verify(lifecycle).waitForCooldown(7, running.id, retryAt)
        Mockito.verify(loader, Mockito.never()).loadEntry(7, 10, null, null)
    }

    @Test
    fun `running quest configuration hold is parked before a fresh automation decision`() {
        val running = session(31, questEntry, AutomationWorkType.QUEST, "quest-1", AutomationWorkStatus.RUNNING)
        val parked = session(
            31,
            questEntry,
            AutomationWorkType.QUEST,
            "quest-1",
            AutomationWorkStatus.WAITING_COOLDOWN,
            nextCheckAt = now.plusSeconds(1800),
            holdMessage = "bad preset",
        )
        val questSnapshot = AutomationEntrySnapshot(
            10,
            AutomationType.QUEST,
            quest = QuestAutomationSnapshot(
                accountId = 7,
                quests = emptyList(),
                selections = listOf(selection("quest-1")),
                mapStates = emptyList(),
                currentCycles = emptyMap(),
                counters = emptyMap(),
                mapIdentityCandidates = emptyList(),
                now = now,
            ),
        )
        val active = questSnapshot.copy(
            quest = requireNotNull(questSnapshot.quest).copy(
                workSessionId = running.id,
                workSessionRevision = running.revision,
            ),
        )
        val noRunnableQuest = questSnapshot.copy(
            quest = requireNotNull(questSnapshot.quest).copy(selections = emptyList()),
        )
        val battleSnapshot = battleDecisionEntry()
        val battleAction = BattleMapAutomationAction(
            accountId = 7,
            progressDate = java.time.LocalDate.parse("2026-07-23"),
            categoryId = "battle_map",
            mapCode = "map-1",
            presetMode = PresetSelectionMode.PRIMARY,
            presetId = 3,
            battleCount = 1,
            executionIdentity = "battle-after-quest-hold",
        )
        val transition = AutomationWorkTransition.WaitForConfiguration("bad preset")
        Mockito.`when`(work.findRunning(7)).thenReturn(running)
        Mockito.`when`(work.findWaiting(7)).thenReturn(listOf(parked))
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(questEntry, battleEntry))
        Mockito.`when`(loader.loadEntry(7, 10, "quest-1")).thenReturn(questSnapshot)
        Mockito.`when`(loader.loadEntry(7, 10, null, null)).thenReturn(questSnapshot)
        questRules.returns(
            requireNotNull(active.quest),
            QuestDirective.WaitForConfiguration("bad preset", "CONFIGURATION_WARNING"),
        )
        questRules.returns(requireNotNull(noRunnableQuest.quest), QuestDirective.Skip)
        Mockito.`when`(loader.loadEntry(7, 11, null, null)).thenReturn(battleSnapshot)
        battleRules.returns(requireNotNull(battleSnapshot.battle), HandlerEvaluation.Runnable(battleAction))

        val boundary = assertIs<AutomationCoordination.CycleBoundary>(selector.select(7))

        assertEquals(listOf("bad preset"), boundary.warnings)
        Mockito.verify(lifecycle).applyTransition(7, running.id, transition)
        Mockito.verify(loader, Mockito.never()).loadEntry(7, battleEntry.id, null, null)
    }

    @Test
    fun `parked quest target releases a lower priority entry when no other quest is runnable`() {
        val waiting = session(
            id = 29,
            entry = questEntry,
            workType = AutomationWorkType.QUEST,
            targetKey = "quest-1",
            status = AutomationWorkStatus.WAITING_COOLDOWN,
            nextCheckAt = now.plusSeconds(1800),
        )
        val questSnapshot = AutomationEntrySnapshot(
            10,
            AutomationType.QUEST,
            quest = QuestAutomationSnapshot(
                accountId = 7,
                quests = emptyList(),
                selections = listOf(selection("quest-1")),
                mapStates = emptyList(),
                currentCycles = emptyMap(),
                counters = emptyMap(),
                mapIdentityCandidates = emptyList(),
                now = now,
            ),
        )
        val battleSnapshot = battleDecisionEntry()
        val battleAction = BattleMapAutomationAction(
            accountId = 7,
            progressDate = java.time.LocalDate.parse("2026-07-23"),
            categoryId = "battle_map",
            mapCode = "map-1",
            presetMode = app.spammy.hof.automation.entity.PresetSelectionMode.PRIMARY,
            presetId = 3,
            battleCount = 1,
            executionIdentity = "battle-1",
        )
        Mockito.`when`(work.findRunning(7)).thenReturn(null)
        Mockito.`when`(work.findWaiting(7)).thenReturn(listOf(waiting))
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(questEntry, battleEntry))
        Mockito.`when`(loader.loadEntry(7, 10, null, null)).thenReturn(questSnapshot)
        questRules.returns(requireNotNull(questSnapshot.quest), QuestDirective.Skip)
        Mockito.`when`(loader.loadEntry(7, 11, null, null)).thenReturn(battleSnapshot)
        battleRules.returns(requireNotNull(battleSnapshot.battle), HandlerEvaluation.Runnable(battleAction))

        val selected = assertIs<AutomationCoordination.Runnable>(selector.select(7))

        assertEquals(11, selected.entryId)
        Mockito.verify(lifecycle, Mockito.never()).resumeForCheck(7, 29)
        assertTrue(requireNotNull(questSnapshot.quest) in questRules.evaluated)
        Mockito.verify(loader).loadEntry(7, 11, null, null)
    }

    @Test
    fun `repeat quest waiting after claim completes the current work cycle`() {
        val session = session(28, questEntry, AutomationWorkType.QUEST, "quest-1", AutomationWorkStatus.RUNNING)
        val entrySnapshot = AutomationEntrySnapshot(
            id = 10,
            type = AutomationType.QUEST,
            quest = QuestAutomationSnapshot(
                accountId = 7,
                quests = listOf(
                    QuestSnapshot(
                        questKey = "quest-1",
                        name = "quest-1",
                        state = QuestState.UNAVAILABLE,
                        section = QuestSection.WAITING,
                        sourceOrder = 0,
                        missions = emptyList(),
                        actionNo = null,
                    ),
                ),
                selections = emptyList(),
                mapStates = emptyList(),
                currentCycles = emptyMap(),
                counters = emptyMap(),
                mapIdentityCandidates = emptyList(),
                now = now,
            ),
        )
        val runningSnapshot = entrySnapshot.copy(
            quest = requireNotNull(entrySnapshot.quest).copy(
                workSessionId = session.id,
                workSessionRevision = session.revision,
            ),
        )
        Mockito.`when`(work.findRunning(7)).thenReturn(session)
        Mockito.`when`(loader.loadEntry(7, 10, "quest-1", null)).thenReturn(entrySnapshot)
        questRules.returns(
            requireNotNull(runningSnapshot.quest),
            QuestDirective.CompleteWork,
        )
        Mockito.`when`(work.findWaiting(7)).thenReturn(emptyList())
        Mockito.`when`(typed.findEntries(7)).thenReturn(emptyList())

        assertIs<AutomationCoordination.CycleBoundary>(selector.select(7))

        Mockito.verify(lifecycle).applyTransition(
            7,
            28,
            AutomationWorkTransition.Complete,
        )
    }

    private fun expectedRaidWaitOutcome(reason: RaidWaitReason) = when (reason) {
        RaidWaitReason.BATTLE_RECOVERY_RECHECK, RaidWaitReason.REWARD_CONFIRMATION, RaidWaitReason.POST_REWARD_CHECK ->
            AutomationDecisionOutcome.WAITING
        else -> AutomationDecisionOutcome.SKIPPED
    }

    private fun session(
        id: Long,
        entry: AutomationEntryEntity,
        workType: AutomationWorkType,
        targetKey: String,
        status: AutomationWorkStatus,
        materialName: String? = null,
        nextCheckAt: Instant? = null,
        holdMessage: String? = null,
    ) = AutomationWorkSessionView(
        id = id,
        accountId = account.id,
        entryId = entry.id,
        entryPriority = entry.priority,
        workType = workType,
        targetKey = targetKey,
        status = status,
        materialName = materialName,
        nextCheckAt = nextCheckAt,
        holdMessage = holdMessage,
    )

    private fun selection(questKey: String) = QuestAutomationSelection(
        questKey = questKey,
        enabled = true,
        maps = emptyList(),
    )

    private fun selectorWithRaid(raidModule: RaidCycleModule) = AutomationTargetSelector(
        typed,
        work,
        loader,
        lifecycle,
        TimeProvider { now },
        raidModule,
        questRules,
        battleRules,
        adventureRules,
        unionRules,
        fishingRules,
        homeQuestRules,
    )

    private fun questDecisionEntry(id: Long = 10) = AutomationEntrySnapshot(
        id,
        AutomationType.QUEST,
        quest = QuestAutomationSnapshot(
            accountId = 7,
            quests = emptyList(),
            selections = emptyList(),
            mapStates = emptyList(),
            currentCycles = emptyMap(),
            counters = emptyMap(),
            mapIdentityCandidates = emptyList(),
            now = now,
        ),
    )

    private fun battleDecisionEntry(id: Long = 11) = AutomationEntrySnapshot(
        id,
        AutomationType.BATTLE_MAP,
        battle = BattleMapAutomationSnapshot(
            accountId = 7,
            settings = emptyList(),
            mapStates = emptyList(),
            successfulRuns = emptyMap(),
            primaryPresetId = null,
            availablePresetIds = emptySet(),
            executionIdentity = "test-$id",
            evaluationInstant = now,
        ),
    )

    private fun adventureDecisionEntry(id: Long = 12) = AutomationEntrySnapshot(
        id,
        AutomationType.ADVENTURE_MAP,
        adventure = AdventureMapAutomationSnapshot(
            accountId = 7,
            settings = emptyList(),
            mapStates = emptyList(),
            presetResolutions = emptyMap(),
            executionIdentities = emptyMap(),
            evaluationInstant = now,
        ),
    )

    private fun fishingDecisionEntry(id: Long = 14) = AutomationEntrySnapshot(
        id,
        AutomationType.FISHING,
        fishing = FishingAutomationSnapshot(
            accountId = 7,
            state = FishingResponse(
                notice = null,
                remainingCasts = 9,
                waterStatus = null,
                baitCount = null,
                shiningBaitCount = null,
                escapeSeconds = null,
                combo = null,
                locationName = "낚시터",
                primaryAction = FishingPrimaryAction.START,
                availableActions = emptySet(),
                lastOutcome = null,
                blockedByBattle = false,
                battleTarget = null,
                catches = emptyList(),
                result = null,
            ),
            maps = emptyList(),
            primaryPreset = null,
            now = now,
        ),
    )

    private fun fixedQuestRules(directive: QuestDirective) = object : QuestWorkCycleModule {
        override fun decideNext(snapshot: QuestAutomationSnapshot): QuestDirective = directive

        override fun recordObservedResult(
            accountId: Long,
            attempt: QuestAttempt,
            observation: QuestResultObservation,
        ): QuestRecordResult = error("not used")
    }

    private class ScriptedQuestRules : QuestWorkCycleModule {
        private val directives = mutableMapOf<QuestAutomationSnapshot, QuestDirective>()
        val evaluated = mutableListOf<QuestAutomationSnapshot>()

        fun returns(snapshot: QuestAutomationSnapshot, directive: QuestDirective) {
            directives[snapshot] = directive
        }

        override fun decideNext(snapshot: QuestAutomationSnapshot): QuestDirective {
            evaluated += snapshot
            return directives[snapshot] ?: error("No quest directive scripted for $snapshot")
        }

        override fun recordObservedResult(
            accountId: Long,
            attempt: QuestAttempt,
            observation: QuestResultObservation,
        ): QuestRecordResult = error("not used")
    }

    private class ScriptedHandler<C> : AutomationHandler<C> {
        private val evaluations = mutableMapOf<C, HandlerEvaluation>()

        fun returns(context: C, evaluation: HandlerEvaluation) {
            evaluations[context] = evaluation
        }

        override fun evaluate(context: C): HandlerEvaluation =
            evaluations[context] ?: error("No handler evaluation scripted for $context")
    }

}
