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
import org.mockito.Mockito

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
    )

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
        Mockito.verify(loader, Mockito.never()).loadEntry(7, 11, null)
        Mockito.verify(loader, Mockito.never()).loadEntry(7, 12, null)
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

        assertIs<AutomationCoordination.Unavailable>(guarded.select(7L))
        assertEquals(emptyMap(), store.findSuppressedBaselines(7L))

        val selected = assertIs<AutomationCoordination.Runnable>(guarded.select(7L))

        assertEquals(registerAction, selected.action)
    }

    @Test
    fun `a decision cycle does not resume a convergence blocked work session twice`() {
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

        val selected = assertIs<AutomationCoordination.Runnable>(guarded.select(7L))

        assertEquals(battleEntry.id, selected.entryId)
        assertEquals(battleAction, selected.action)
        Mockito.verify(lifecycle).resumeForCheck(7, due.id)
        Mockito.verify(lifecycle, Mockito.never()).resumeForCheck(7, running.id)
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

        assertIs<AutomationCoordination.Unavailable>(activeSelector.select(7L))
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
    fun `earliest unavailable is returned after every configured entry is evaluated`() {
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

        val unavailable = assertIs<AutomationCoordination.Unavailable>(selector.select(7))

        assertEquals(earlier, unavailable.nextRunAt)
        assertEquals(listOf(0, 1), unavailable.trace.map(AutomationEvaluationTrace::sequence))
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
    fun `stale running quest parks only itself and selects a lower runnable entry`() {
        val running = session(40, questEntry, AutomationWorkType.QUEST, "quest-1", AutomationWorkStatus.RUNNING)
        val refreshed = running.copy(revision = running.revision + 1)
        val questSnapshot = questDecisionEntry()
        val runningSnapshot = questSnapshot.copy(
            quest = requireNotNull(questSnapshot.quest).copy(
                workSessionId = running.id,
                workSessionRevision = running.revision,
            ),
        )
        val refreshedSnapshot = questSnapshot.copy(
            quest = requireNotNull(questSnapshot.quest).copy(
                workSessionId = refreshed.id,
                workSessionRevision = refreshed.revision,
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
        Mockito.`when`(work.findRunning(7)).thenReturn(running, refreshed)
        Mockito.`when`(work.findWaiting(7)).thenReturn(emptyList())
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(questEntry, battleEntry))
        Mockito.`when`(loader.loadEntry(7, questEntry.id, "quest-1", null)).thenReturn(questSnapshot)
        Mockito.`when`(loader.loadEntry(7, questEntry.id, null, null)).thenReturn(questSnapshot)
        Mockito.`when`(loader.loadEntry(7, battleEntry.id, null, null)).thenReturn(battleSnapshot)
        questRules.returns(
            requireNotNull(runningSnapshot.quest),
            QuestDirective.Recheck(now.plusSeconds(10), "QUEST_PROGRESS_STALE", "recheck"),
        )
        questRules.returns(
            requireNotNull(refreshedSnapshot.quest),
            QuestDirective.Recheck(now.plusSeconds(10), "QUEST_PROGRESS_STALE", "recheck again"),
        )
        questRules.returns(requireNotNull(questSnapshot.quest), QuestDirective.Skip)
        battleRules.returns(requireNotNull(battleSnapshot.battle), HandlerEvaluation.Runnable(battleAction))

        val selected = assertIs<AutomationCoordination.Runnable>(selector.select(7))

        assertEquals(battleEntry.id, selected.entryId)
        assertEquals(battleAction, selected.action)
        Mockito.verify(lifecycle).waitForCooldown(7, running.id, now.plusSeconds(10))
        assertTrue(selected.trace.any { it.reasonCode == "QUEST_PROGRESS_STALE" })
    }

    @Test
    fun `first quest stale reloads fresh owner once before parking`() {
        val running = session(42, questEntry, AutomationWorkType.QUEST, "quest-1", AutomationWorkStatus.RUNNING)
        val refreshed = running.copy(revision = running.revision + 1)
        val questSnapshot = questDecisionEntry()
        val staleSnapshot = requireNotNull(questSnapshot.quest).copy(
            workSessionId = running.id,
            workSessionRevision = running.revision,
        )
        val freshSnapshot = requireNotNull(questSnapshot.quest).copy(
            workSessionId = refreshed.id,
            workSessionRevision = refreshed.revision,
        )
        val action = QuestAction.Accept("quest-1", "accept-1")
        Mockito.`when`(work.findRunning(7)).thenReturn(running, refreshed)
        Mockito.`when`(work.findWaiting(7)).thenReturn(emptyList())
        Mockito.`when`(loader.loadEntry(7, questEntry.id, "quest-1", null)).thenReturn(questSnapshot)
        questRules.returns(
            staleSnapshot,
            QuestDirective.Recheck(now.plusSeconds(10), "QUEST_PROGRESS_STALE", "recheck"),
        )
        questRules.returns(freshSnapshot, QuestDirective.Execute(action))

        val selected = assertIs<AutomationCoordination.Runnable>(selector.select(7))

        assertEquals(action, selected.action)
        assertEquals(listOf(0L, 1L), questRules.evaluated.map { it.workSessionRevision })
        Mockito.verify(lifecycle, Mockito.never()).waitForCooldown(7, running.id, now.plusSeconds(10))
    }

    @Test
    fun `due quest is evaluated with the fresh revision produced by resume`() {
        val due = session(
            41,
            questEntry,
            AutomationWorkType.QUEST,
            "quest-1",
            AutomationWorkStatus.WAITING_COOLDOWN,
            nextCheckAt = now,
        ).copy(revision = 2)
        val resumed = due.copy(
            status = AutomationWorkStatus.RUNNING,
            nextCheckAt = null,
            revision = 3,
        )
        val questSnapshot = questDecisionEntry()
        val resumedQuest = requireNotNull(questSnapshot.quest).copy(
            workSessionId = resumed.id,
            workSessionRevision = resumed.revision,
        )
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
        Mockito.`when`(work.findRunning(7)).thenReturn(null, resumed)
        Mockito.`when`(work.findWaiting(7)).thenReturn(listOf(due))
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(questEntry))
        Mockito.`when`(loader.loadEntry(7, questEntry.id, "quest-1", null)).thenReturn(questSnapshot)
        questRules.returns(resumedQuest, QuestDirective.Execute(action))

        val selected = assertIs<AutomationCoordination.Runnable>(guarded.select(7))

        assertEquals(action, selected.action)
        assertEquals(3, questRules.evaluated.single().workSessionRevision)
        Mockito.verify(lifecycle).resumeForCheck(7, due.id)
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
    fun `unavailable battle map does not block a lower priority adventure entry`() {
        val battleSnapshot = battleDecisionEntry()
        val adventureSnapshot = adventureDecisionEntry()
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
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(battleEntry, adventureEntry))
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
    }

    @Test
    fun `fishing catch transition reserves the short deadline instead of starting another automation`() {
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
        Mockito.verify(lifecycle, Mockito.never()).waitForCooldown(7, 31, retryAt)
        Mockito.verify(typed, Mockito.never()).findEntries(7)
    }

    @Test
    fun `raid cooldown parks its cycle and releases the next automation entry`() {
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
        Mockito.`when`(work.findRunning(7)).thenReturn(runningRaid)
        Mockito.`when`(raidModule.decide(7)).thenReturn(
            RaidDecision(RaidDirective.WaitUntil(
                at = retryAt,
                reason = RaidWaitReason.BATTLE_COOLDOWN,
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

        val selected = assertIs<AutomationCoordination.Runnable>(moduleSelector.select(7))

        assertEquals(11, selected.entryId)
        assertEquals(2, selected.trace.size)
        assertEquals(AutomationType.RAID, selected.trace.first().type)
        assertEquals(AutomationDecisionOutcome.WAITING, selected.trace.first().outcome)
        assertEquals("RAID_BATTLE_SAFETY_GATE", selected.trace.first().reasonCode)
        assertEquals(AutomationDiagnosticKind.RAID_LOCAL_SAFETY_GATE, selected.trace.first().diagnosticKind)
        assertEquals(RaidCooldownSource.LOCAL_FALLBACK, selected.trace.first().cooldownSource)
        assertEquals(AutomationImpactScope.RAID_ONLY, selected.trace.first().impactScope)
        assertEquals("마감 뒤 최신 레이드 상태 재확인", selected.trace.first().releaseCondition)
        assertEquals(AutomationDecisionOutcome.SELECTED, selected.trace.last().outcome)
        Mockito.verify(lifecycle).waitForCooldown(7, 30, retryAt)
        Mockito.verify(loader, Mockito.never()).loadEntry(7, 13, "RaidGoblin")
    }

    @Test
    fun `raid wait before its first action is persisted and releases the next automation entry`() {
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
                reason = RaidWaitReason.REGISTRATION_COOLDOWN,
                message = "레이드 등록 쿨타임",
                entryId = raidEntry.id,
                raidId = "RaidGoblin",
            )),
        )
        Mockito.`when`(loader.loadEntry(7, 11, null, null)).thenReturn(battleSnapshot)
        battleRules.returns(requireNotNull(battleSnapshot.battle), HandlerEvaluation.Runnable(battleAction))

        val selected = assertIs<AutomationCoordination.Runnable>(selector.select(7))

        assertEquals(11, selected.entryId)
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
    fun `due higher priority raid reward preempts a running quest after its current action`() {
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

        assertEquals(higherPriorityRaidEntry.id, selected.entryId)
        Mockito.verify(lifecycle).handoffForPriority(7, runningQuest.id, dueRaid.id)
        Mockito.verify(lifecycle, Mockito.never()).resumeForCheck(7, dueRaid.id)
        Mockito.verify(defaultRaidModule).decide(7)
        Mockito.verify(loader, Mockito.never()).loadEntry(7, lowerPriorityQuestEntry.id, "quest-1")
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
        Mockito.`when`(loader.loadEntry(7, 10, "quest-1")).thenReturn(questSnapshot)
        questRules.returns(requireNotNull(runningSnapshot.quest), QuestDirective.Execute(action))

        val selected = assertIs<AutomationCoordination.Runnable>(selector.select(7))

        assertEquals("quest-1", (selected.action as QuestAction.Battle).questKey)
        Mockito.verify(typed, Mockito.never()).findEntries(7)
        Mockito.verify(loader).loadEntry(7, 10, "quest-1")
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

        assertIs<AutomationCoordination.Idle>(selector.select(7))

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
        val runnableSnapshot = questSnapshot.copy(
            quest = requireNotNull(questSnapshot.quest).copy(selections = listOf(selection("quest-2"))),
        )
        val questAction = QuestAction.Accept("quest-2", "accept-2")
        Mockito.`when`(work.findRunning(7)).thenReturn(null)
        Mockito.`when`(work.findWaiting(7)).thenReturn(listOf(waiting))
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(questEntry))
        Mockito.`when`(loader.loadEntry(7, 10, null, null)).thenReturn(questSnapshot)
        questRules.returns(requireNotNull(runnableSnapshot.quest), QuestDirective.Execute(questAction))

        val selected = assertIs<AutomationCoordination.Runnable>(selector.select(7))

        assertEquals("quest-2", assertIs<QuestAction.Accept>(selected.action).questKey)
        Mockito.verify(loader).loadEntry(7, 10, null, null)
        assertTrue(requireNotNull(runnableSnapshot.quest) in questRules.evaluated)
    }

    @Test
    fun `running quest cooldown parks only that quest and releases another quest immediately`() {
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
        val otherQuest = configured.copy(
            quest = requireNotNull(configured.quest).copy(selections = listOf(selection("quest-2"))),
        )
        val action = QuestAction.Accept("quest-2", "accept-2")
        Mockito.`when`(work.findRunning(7)).thenReturn(running)
        Mockito.`when`(work.findWaiting(7)).thenReturn(listOf(waiting))
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(questEntry))
        Mockito.`when`(loader.loadEntry(7, 10, "quest-1")).thenReturn(configured)
        Mockito.`when`(loader.loadEntry(7, 10, null, null)).thenReturn(configured)
        questRules.returns(
            requireNotNull(active.quest),
            QuestDirective.WaitUntil(retryAt, "COOLDOWN", "wait"),
        )
        questRules.returns(requireNotNull(otherQuest.quest), QuestDirective.Execute(action))

        val selected = assertIs<AutomationCoordination.Runnable>(selector.select(7))

        assertEquals("quest-2", assertIs<QuestAction.Accept>(selected.action).questKey)
        Mockito.verify(lifecycle).waitForCooldown(7, running.id, retryAt)
        assertTrue(requireNotNull(otherQuest.quest) in questRules.evaluated)
    }

    @Test
    fun `running quest configuration hold is parked before selecting another automation`() {
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

        val selected = assertIs<AutomationCoordination.Runnable>(selector.select(7))

        assertEquals(11, selected.entryId)
        assertEquals(listOf("bad preset"), selected.warnings)
        Mockito.verify(lifecycle).applyTransition(7, running.id, transition)
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
        val idleQuestSnapshot = questSnapshot.copy(
            quest = requireNotNull(questSnapshot.quest).copy(selections = emptyList()),
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
        questRules.returns(requireNotNull(idleQuestSnapshot.quest), QuestDirective.Skip)
        Mockito.`when`(loader.loadEntry(7, 11, null, null)).thenReturn(battleSnapshot)
        battleRules.returns(requireNotNull(battleSnapshot.battle), HandlerEvaluation.Runnable(battleAction))

        val selected = assertIs<AutomationCoordination.Runnable>(selector.select(7))

        assertEquals(11, selected.entryId)
        Mockito.verify(lifecycle, Mockito.never()).resumeForCheck(7, 29)
        assertTrue(requireNotNull(idleQuestSnapshot.quest) in questRules.evaluated)
        Mockito.verify(loader).loadEntry(7, 11, null, null)
    }

    @Test
    fun `repeat quest waiting after completion is parked instead of completed`() {
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
            QuestDirective.WaitForUnknownCooldown,
        )
        Mockito.`when`(work.findWaiting(7)).thenReturn(emptyList())
        Mockito.`when`(typed.findEntries(7)).thenReturn(emptyList())

        assertIs<AutomationCoordination.Idle>(selector.select(7))

        Mockito.verify(lifecycle).applyTransition(
            7,
            28,
            AutomationWorkTransition.WaitForUnknownCooldown,
        )
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
