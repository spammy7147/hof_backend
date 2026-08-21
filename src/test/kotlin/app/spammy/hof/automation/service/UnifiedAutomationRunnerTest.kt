package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.automation.entity.AutomationEntryEntity
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.AutomationWaitReason
import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.automation.entity.TypedAutomationActionRunEntity
import app.spammy.hof.automation.entity.TypedAutomationActionStatus
import app.spammy.hof.automation.history.AutomationActionTrace
import app.spammy.hof.automation.history.AutomationDecisionJournal
import app.spammy.hof.automation.history.AutomationHistoryEventKind
import app.spammy.hof.automation.port.AutomationWakeupPort
import app.spammy.hof.automation.raid.RaidCycleOutcome
import app.spammy.hof.automation.raid.RaidCycleOutcomeKind
import app.spammy.hof.battle.dto.BattlePatternLoadRequest
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.external.client.HofAutomationDeferredException
import app.spammy.hof.town.raid.model.RaidAction
import java.time.Instant
import java.time.LocalDate
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.mockito.Mockito
import tools.jackson.module.kotlin.jacksonObjectMapper

class UnifiedAutomationRunnerTest {
    private val preflight = Mockito.mock(AutomationDailyPreflight::class.java)
    private val runtime = Mockito.mock(TypedAutomationRuntimeService::class.java)
    private val loader = Mockito.mock(TypedAutomationSnapshotLoader::class.java)
    private val coordinator = Mockito.mock(AutomationCoordinator::class.java)
    private val executor = Mockito.mock(TypedAutomationActionExecutor::class.java)
    private val wakeup = Mockito.mock(AutomationWakeupPort::class.java)
    private val sharedCooldowns = Mockito.mock(SharedBattleCooldownService::class.java)
    private val ambiguousReconciler = Mockito.mock(AutomationAmbiguousActionReconciler::class.java)
    private val actionLifecycleModule = Mockito.mock(AutomationActionLifecycleModule::class.java)
    private val codec = StoredTypedAutomationActionCodec(jacksonObjectMapper())
    private val runner = UnifiedAutomationRunner(
        preflight, runtime, loader, coordinator, executor, codec, wakeup, sharedCooldowns,
        ambiguousReconciler, actionLifecycleModule,
    )

    init {
        Mockito.`when`(runtime.isRunning(7)).thenReturn(true)
        Mockito.`when`(executor.execute(Mockito.eq(7L), anyStoredAction()))
            .thenReturn(TypedAutomationExecution.Completed)
    }

    @Test
    fun `자택 퀘스트는 새 행동 수명주기 module로 준비하고 한 번 실행한다`() {
        val decisions = Mockito.mock(AutomationDecisionSource::class.java)
        val workTracker = Mockito.mock(AutomationWorkTracker::class.java)
        val lifecycleModule = Mockito.mock(AutomationActionLifecycleModule::class.java)
        val managed = Mockito.mock(ManagedAutomationAction::class.java)
        val journal = Mockito.mock(AutomationDecisionJournal::class.java)
        val action = HomeQuestAutomationAction(
            7L,
            "home-1",
            "빗자루 제작",
            "accept-action",
            HomeQuestAutomationActionType.ACCEPT,
        )
        val stored = StoredTypedAutomationAction(
            10L,
            "home-execution-1",
            StoredTypedActionPayload.HomeQuest(
                "home-1",
                "accept-action",
                HomeQuestAutomationActionType.ACCEPT,
                StoredActionDisplay(questName = "빗자루 제작"),
            ),
        )
        val decision = AutomationCoordination.Runnable(
            10L,
            action,
            emptyList(),
            listOf(
                AutomationEvaluationTrace(
                    sequence = 0,
                    entryId = 10L,
                    type = AutomationType.HOME_QUEST,
                    outcome = AutomationDecisionOutcome.SELECTED,
                    reasonCode = "RUNNABLE",
                    message = "기존 선택 설명",
                    actionKind = "HOME_QUEST",
                    targetKey = "home-1",
                    targetName = "빗자루 제작",
                ),
            ),
        )
        val row = Mockito.mock(TypedAutomationActionRunEntity::class.java)
        Mockito.`when`(row.id).thenReturn(88L)
        Mockito.`when`(preflight.ensureReady(7L)).thenReturn(AutomationDailyPreflight.Result.Ready)
        Mockito.`when`(runtime.claim(7L)).thenReturn(TypedRuntimeClaim.Acquired("token"))
        Mockito.`when`(decisions.select(7L)).thenReturn(decision)
        Mockito.`when`(lifecycleModule.prepare(7L, 10L, action)).thenReturn(managed)
        Mockito.`when`(managed.storedAction).thenReturn(stored)
        val descriptor = AutomationActionDescriptor(
            AutomationType.HOME_QUEST,
            "HOME_QUEST",
            "HOME_ACCEPT",
            "자택 퀘스트",
            "수명주기 descriptor · 빗자루 제작",
            "home-1",
            "빗자루 제작",
            stored.payload.display,
        )
        Mockito.`when`(managed.descriptor).thenReturn(descriptor)
        Mockito.`when`(lifecycleModule.describe(action)).thenReturn(descriptor)
        Mockito.`when`(runtime.prepare(7L, "token", stored)).thenReturn(row)
        Mockito.`when`(lifecycleModule.restore(row, 7L)).thenReturn(managed)
        Mockito.`when`(runtime.markSubmitting(7L, "token", 88L)).thenReturn(true)
        Mockito.`when`(managed.execute()).thenReturn(TypedAutomationExecution.Completed)
        Mockito.`when`(journal.appendDecision(Mockito.eq(7L), anyCoordination()))
            .thenReturn(41L)
        val scopedRunner = UnifiedAutomationRunner(
            dailyPreflight = preflight,
            typedRuntime = runtime,
            decisionSource = decisions,
            workTracker = workTracker,
            typedActionExecutor = executor,
            typedCodec = codec,
            wakeupPort = wakeup,
            sharedBattleCooldowns = sharedCooldowns,
            ambiguousReconciler = ambiguousReconciler,
            decisionJournal = journal,
            actionLifecycleModule = lifecycleModule,
        )

        scopedRunner.runOne(7L)

        Mockito.verify(lifecycleModule).prepare(7L, 10L, action)
        Mockito.verify(lifecycleModule).restore(row, 7L)
        Mockito.verify(managed, Mockito.times(1)).execute()
        Mockito.verify(runtime).succeedAndEnqueueWake(7L, "token", 88L, "TYPED_ACTION_COMPLETED", emptyList())
        Mockito.verifyNoInteractions(workTracker, executor)
        val traceCaptor = org.mockito.ArgumentCaptor.forClass(AutomationActionTrace::class.java)
        Mockito.verify(journal, Mockito.times(2)).appendActionResult(Mockito.eq(41L), captureTrace(traceCaptor))
        assertTrue(traceCaptor.allValues.all { it.actionKind == "HOME_ACCEPT" })
        assertTrue(traceCaptor.allValues.all { it.message.startsWith("수명주기 descriptor · 빗자루 제작") })
        val decisionCaptor = org.mockito.ArgumentCaptor.forClass(AutomationCoordination::class.java)
        Mockito.verify(journal).appendDecision(Mockito.eq(7L), captureCoordination(decisionCaptor))
        val selectedTrace = assertIs<AutomationCoordination.Runnable>(decisionCaptor.value).trace.single()
        assertEquals("HOME_ACCEPT", selectedTrace.actionKind)
        assertEquals("수명주기 descriptor · 빗자루 제작", selectedTrace.message)
    }

    @Test
    fun `저장된 자택 퀘스트의 불명확 결과는 module로 복원해 blind replay 없이 조정한다`() {
        val lifecycleModule = Mockito.mock(AutomationActionLifecycleModule::class.java)
        val managed = Mockito.mock(ManagedAutomationAction::class.java)
        val row = Mockito.mock(TypedAutomationActionRunEntity::class.java)
        val stored = StoredTypedAutomationAction(
            10L,
            "home-execution-1",
            StoredTypedActionPayload.HomeQuest(
                "home-1",
                "accept-action",
                HomeQuestAutomationActionType.ACCEPT,
                StoredActionDisplay(questName = "빗자루 제작"),
            ),
        )
        Mockito.`when`(row.id).thenReturn(88L)
        Mockito.`when`(row.status).thenReturn(TypedAutomationActionStatus.RECONCILING)
        Mockito.`when`(preflight.ensureReady(7L)).thenReturn(AutomationDailyPreflight.Result.Ready)
        Mockito.`when`(runtime.claim(7L)).thenReturn(TypedRuntimeClaim.Acquired("token", row))
        Mockito.`when`(lifecycleModule.restore(row, 7L)).thenReturn(managed)
        Mockito.`when`(managed.storedAction).thenReturn(stored)
        Mockito.`when`(managed.descriptor).thenReturn(
            AutomationActionDescriptor(
                AutomationType.HOME_QUEST,
                "HOME_QUEST",
                "HOME_ACCEPT",
                "자택 퀘스트",
                "자택 퀘스트 수락 · 빗자루 제작",
            ),
        )
        Mockito.`when`(managed.reconcile()).thenReturn(AmbiguousActionResolution.Resubmit)
        val scopedRunner = UnifiedAutomationRunner(
            dailyPreflight = preflight,
            typedRuntime = runtime,
            decisionSource = Mockito.mock(AutomationDecisionSource::class.java),
            workTracker = Mockito.mock(AutomationWorkTracker::class.java),
            typedActionExecutor = executor,
            typedCodec = codec,
            wakeupPort = wakeup,
            sharedBattleCooldowns = sharedCooldowns,
            ambiguousReconciler = ambiguousReconciler,
            actionLifecycleModule = lifecycleModule,
        )

        scopedRunner.runOne(7L)

        Mockito.verify(lifecycleModule).restore(row, 7L)
        Mockito.verify(managed).reconcile()
        Mockito.verify(runtime).retryReconciledSubmission(7L, "token", 88L, "TYPED_RECONCILED_RESUBMIT")
        Mockito.verify(managed, Mockito.never()).execute()
        Mockito.verifyNoInteractions(executor, ambiguousReconciler)
    }

    @Test
    fun `production decision source replaces the global snapshot coordinator`() {
        val decisions = Mockito.mock(AutomationDecisionSource::class.java)
        val workTracker = Mockito.mock(AutomationWorkTracker::class.java)
        val action = QuestAction.Accept("quest-1", "accept-1")
        val row = Mockito.mock(TypedAutomationActionRunEntity::class.java)
        Mockito.`when`(row.id).thenReturn(88L)
        Mockito.`when`(preflight.ensureReady(7)).thenReturn(AutomationDailyPreflight.Result.Ready)
        Mockito.`when`(runtime.claim(7)).thenReturn(TypedRuntimeClaim.Acquired("token"))
        Mockito.`when`(decisions.select(7)).thenReturn(AutomationCoordination.Runnable(10, action, emptyList()))
        Mockito.`when`(runtime.prepare(Mockito.eq(7L), eqString("token"), anyStoredAction())).thenReturn(row)
        Mockito.`when`(runtime.markSubmitting(7, "token", 88L)).thenReturn(true)
        val scopedRunner = UnifiedAutomationRunner(
            preflight, runtime, decisions, workTracker, executor, codec, wakeup, sharedCooldowns,
            ambiguousReconciler, actionLifecycleModule,
        )

        scopedRunner.runOne(7)

        Mockito.verify(decisions).select(7)
        Mockito.verify(workTracker).ensureForAction(7, 10, action)
        Mockito.verify(loader, Mockito.never()).loadTyped(7)
    }

    @Test
    fun `prepare failure schedules retry before external execution`() {
        val decisions = Mockito.mock(AutomationDecisionSource::class.java)
        val workTracker = Mockito.mock(AutomationWorkTracker::class.java)
        val action = QuestAction.Accept("quest-1", "accept-1")
        Mockito.`when`(preflight.ensureReady(7)).thenReturn(AutomationDailyPreflight.Result.Ready)
        Mockito.`when`(runtime.claim(7)).thenReturn(TypedRuntimeClaim.Acquired("token"))
        Mockito.`when`(decisions.select(7)).thenReturn(AutomationCoordination.Runnable(10, action, emptyList()))
        Mockito.`when`(runtime.prepare(Mockito.eq(7L), eqString("token"), anyStoredAction()))
            .thenThrow(IllegalArgumentException("invalid stored action"))
        val retryAt = Instant.parse("2026-07-25T00:05:00Z")
        Mockito.`when`(runtime.scheduleAutomaticRetry(7, "token", null, AutomationStopReason.FATAL, "invalid stored action"))
            .thenReturn(retryAt)
        val scopedRunner = UnifiedAutomationRunner(
            preflight, runtime, decisions, workTracker, executor, codec, wakeup, sharedCooldowns,
            ambiguousReconciler, actionLifecycleModule,
        )

        scopedRunner.runOne(7)

        Mockito.verify(runtime).scheduleAutomaticRetry(
            Mockito.eq(7L),
            eqString("token"),
            Mockito.isNull(),
            eqValue(AutomationStopReason.FATAL),
            eqString("invalid stored action"),
        )
        Mockito.verify(wakeup).schedule(7, retryAt, "TYPED_AUTOMATIC_RETRY")
        Mockito.verifyNoInteractions(executor)
    }

    @Test
    fun `work tracking failure schedules retry before persistence or external execution`() {
        val decisions = Mockito.mock(AutomationDecisionSource::class.java)
        val workTracker = Mockito.mock(AutomationWorkTracker::class.java)
        val action = QuestAction.Accept("quest-1", "accept-1")
        Mockito.`when`(preflight.ensureReady(7)).thenReturn(AutomationDailyPreflight.Result.Ready)
        Mockito.`when`(runtime.claim(7)).thenReturn(TypedRuntimeClaim.Acquired("token"))
        Mockito.`when`(decisions.select(7)).thenReturn(AutomationCoordination.Runnable(10, action, emptyList()))
        Mockito.doThrow(IllegalStateException("failed to track work"))
            .`when`(workTracker).ensureForAction(7, 10, action)
        val retryAt = Instant.parse("2026-07-25T00:05:00Z")
        Mockito.`when`(runtime.scheduleAutomaticRetry(7, "token", null, AutomationStopReason.FATAL, "failed to track work"))
            .thenReturn(retryAt)
        val scopedRunner = UnifiedAutomationRunner(
            preflight, runtime, decisions, workTracker, executor, codec, wakeup, sharedCooldowns,
            ambiguousReconciler, actionLifecycleModule,
        )

        scopedRunner.runOne(7)

        Mockito.verify(runtime).scheduleAutomaticRetry(
            Mockito.eq(7L),
            eqString("token"),
            Mockito.isNull(),
            eqValue(AutomationStopReason.FATAL),
            eqString("failed to track work"),
        )
        Mockito.verify(wakeup).schedule(7, retryAt, "TYPED_AUTOMATIC_RETRY")
        Mockito.verify(runtime, Mockito.never())
            .prepare(Mockito.eq(7L), eqString("token"), anyStoredAction())
        Mockito.verifyNoInteractions(executor)
    }

    @Test
    fun `interrupted preparation restores the thread interrupt flag before returning`() {
        val decisions = Mockito.mock(AutomationDecisionSource::class.java)
        val workTracker = Mockito.mock(AutomationWorkTracker::class.java)
        val action = QuestAction.Accept("quest-1", "accept-1")
        Mockito.`when`(preflight.ensureReady(7)).thenReturn(AutomationDailyPreflight.Result.Ready)
        Mockito.`when`(runtime.claim(7)).thenReturn(TypedRuntimeClaim.Acquired("token"))
        Mockito.`when`(decisions.select(7)).thenReturn(AutomationCoordination.Runnable(10, action, emptyList()))
        Mockito.`when`(runtime.prepare(Mockito.eq(7L), eqString("token"), anyStoredAction())).thenThrow(
            IllegalStateException("interrupted preparation", InterruptedException("interrupted")),
        )
        val retryAt = Instant.parse("2026-07-25T00:05:00Z")
        Mockito.`when`(runtime.scheduleAutomaticRetry(7, "token", null, AutomationStopReason.FATAL, "interrupted preparation"))
            .thenReturn(retryAt)
        val scopedRunner = UnifiedAutomationRunner(
            preflight, runtime, decisions, workTracker, executor, codec, wakeup, sharedCooldowns,
            ambiguousReconciler, actionLifecycleModule,
        )

        try {
            scopedRunner.runOne(7)

            assertTrue(Thread.currentThread().isInterrupted)
            Mockito.verify(runtime).scheduleAutomaticRetry(
                Mockito.eq(7L),
                eqString("token"),
                Mockito.isNull(),
                eqValue(AutomationStopReason.FATAL),
                eqString("interrupted preparation"),
            )
            Mockito.verify(wakeup).schedule(7, retryAt, "TYPED_AUTOMATIC_RETRY")
            Mockito.verifyNoInteractions(executor)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `runner persists submits and checkpoints one action then wakes a fresh evaluation`() {
        val snapshot = AutomationCoordinatorSnapshot(emptyList())
        val action = BattleMapAutomationAction(
            7,
            LocalDate.parse("2026-07-16"),
            "battle_map",
            "gb0",
            PresetSelectionMode.PRIMARY,
            301,
            1,
            "execution-1",
            mapName = "거대 보스",
            resolvedParty = ResolvedAutomationParty(
                listOf("character-1"),
                listOf(BattlePatternLoadRequest("character-1", 1)),
            ),
        )
        val row = Mockito.mock(TypedAutomationActionRunEntity::class.java)
        Mockito.`when`(row.id).thenReturn(88L)
        Mockito.`when`(preflight.ensureReady(7)).thenReturn(AutomationDailyPreflight.Result.Ready)
        Mockito.`when`(runtime.claim(7)).thenReturn(TypedRuntimeClaim.Acquired("token"))
        Mockito.`when`(loader.loadTyped(7)).thenReturn(snapshot)
        Mockito.`when`(coordinator.coordinate(snapshot)).thenReturn(AutomationCoordination.Runnable(12, action, emptyList()))
        Mockito.`when`(runtime.prepare(Mockito.eq(7L), eqString("token"), anyStoredAction())).thenReturn(row)
        Mockito.`when`(runtime.markSubmitting(7, "token", 88L)).thenReturn(true)
        Mockito.`when`(executor.execute(Mockito.eq(7L), anyStoredAction()))
            .thenReturn(TypedAutomationExecution.BattleCompleted("battle_map", "gb0"))

        runner.runOne(7)

        val storedCaptor = org.mockito.ArgumentCaptor.forClass(StoredTypedAutomationAction::class.java)
        Mockito.verify(executor).execute(Mockito.eq(7L), capture(storedCaptor))
        assertEquals(StoredActionDisplay(mapName = "거대 보스"), storedCaptor.value.payload.display)
        Mockito.verify(runtime).recordWarnings(7, "token", emptyList())
        Mockito.verify(sharedCooldowns).applyAfterSuccessfulBattle(7, "battle_map", "gb0")
        Mockito.verify(runtime).succeedAndEnqueueWake(7, "token", 88L, "TYPED_ACTION_COMPLETED", emptyList())
    }

    @Test
    fun `raid completion is recorded with its stable outcome instead of generic action success`() {
        val decisions = Mockito.mock(AutomationDecisionSource::class.java)
        val workTracker = Mockito.mock(AutomationWorkTracker::class.java)
        val journal = Mockito.mock(AutomationDecisionJournal::class.java)
        val action = RaidTownAutomationAction(7, RaidAction.REWARD, null, "RaidGoblin")
        val outcome = RaidCycleOutcome(13, "RaidGoblin", RaidCycleOutcomeKind.COMPLETED)
        val decision = AutomationCoordination.Runnable(13, action, emptyList())
        val row = Mockito.mock(TypedAutomationActionRunEntity::class.java)
        Mockito.`when`(row.id).thenReturn(88L)
        Mockito.`when`(preflight.ensureReady(7)).thenReturn(AutomationDailyPreflight.Result.Ready)
        Mockito.`when`(runtime.claim(7)).thenReturn(TypedRuntimeClaim.Acquired("token"))
        Mockito.`when`(decisions.select(7)).thenReturn(decision)
        Mockito.`when`(runtime.prepare(Mockito.eq(7L), eqString("token"), anyStoredAction())).thenReturn(row)
        Mockito.`when`(runtime.markSubmitting(7, "token", 88L)).thenReturn(true)
        Mockito.`when`(executor.execute(Mockito.eq(7L), anyStoredAction()))
            .thenReturn(TypedAutomationExecution.RaidCycleFinished(outcome))
        Mockito.`when`(journal.appendDecision(7L, decision)).thenReturn(41L)
        val scopedRunner = UnifiedAutomationRunner(
            preflight, runtime, decisions, workTracker, executor, codec, wakeup, sharedCooldowns,
            ambiguousReconciler, actionLifecycleModule, journal,
        )

        scopedRunner.runOne(7)

        val traceCaptor = org.mockito.ArgumentCaptor.forClass(AutomationActionTrace::class.java)
        Mockito.verify(journal, Mockito.times(2)).appendActionResult(Mockito.eq(41L), captureTrace(traceCaptor))
        val result = traceCaptor.allValues.last()
        assertEquals(AutomationHistoryEventKind.CYCLE_COMPLETED, result.kind)
        assertEquals(RaidCycleOutcomeKind.COMPLETED.name, result.reasonCode)
        assertEquals("RaidGoblin", result.targetKey)
    }

    @Test
    fun `shared cooldown learns map completes prepared action and wakes fresh evaluation`() {
        val retryAt = Instant.parse("2026-07-24T00:00:56Z")
        val stored = StoredTypedAutomationAction(
            12,
            "execution-1",
            StoredTypedActionPayload.BattleMap(
                LocalDate.parse("2026-07-24"),
                "raid",
                "castle",
                PresetSelectionMode.PRIMARY,
                301L,
                1,
                app.spammy.hof.battle.dto.RunBattleRequest(
                    "raid", "castle", listOf("character-1"),
                    listOf(BattlePatternLoadRequest("character-1", 1)), 1,
                ),
            ),
        )
        val encoded = codec.encode(stored)
        val owner = HofAccountEntity(7, "login", "encrypted", Instant.EPOCH)
        val entry = AutomationEntryEntity(12, owner, AutomationType.BATTLE_MAP, 0, true, Instant.EPOCH, Instant.EPOCH)
        val row = TypedAutomationActionRunEntity(
            88, owner, entry, stored.executionIdentity, stored.payload.kind(),
            encoded.json,
            encoded.fingerprint, TypedAutomationActionStatus.PREPARED, leaseToken = "token",
            createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
        )
        Mockito.`when`(preflight.ensureReady(7)).thenReturn(AutomationDailyPreflight.Result.Ready)
        Mockito.`when`(runtime.claim(7)).thenReturn(TypedRuntimeClaim.Acquired("token", row))
        Mockito.`when`(runtime.markSubmitting(7, "token", 88)).thenReturn(true)
        Mockito.`when`(executor.execute(Mockito.eq(7L), anyStoredAction())).thenReturn(
            TypedAutomationExecution.SharedCooldown("raid", "castle", retryAt),
        )

        runner.runOne(7)

        Mockito.verify(sharedCooldowns).learnAndApply(7, "raid", "castle", retryAt)
        Mockito.verify(runtime).succeedAndEnqueueWake(7, "token", 88, "TYPED_SHARED_COOLDOWN_SKIPPED", null)
        assertTrue(Mockito.mockingDetails(runtime).invocations.none { it.method.name == "stop" })
        Mockito.verifyNoInteractions(wakeup)
    }

    @Test
    fun `runner snapshots quest battle labels and progress`() {
        val snapshot = AutomationCoordinatorSnapshot(emptyList())
        val action = QuestAction.Battle(
            "quest", "1", "mission", app.spammy.hof.quest.model.QuestMissionType.MONSTER_KILL,
            "battle_map", "gb0", "푸른 초원", QuestPresetSelection(
                PresetSelectionMode.PRIMARY,
                resolvedPresetId = 301,
                resolutionChecked = true,
                resolvedParty = ResolvedAutomationParty(
                    listOf("character-1"), listOf(BattlePatternLoadRequest("character-1", 1)),
                ),
            ),
            questName = "초보자 임무",
            missionLabel = "몬스터 처치 · 슬라임",
            missionCurrent = 2,
            missionRequired = 5,
            battleCount = 3,
        )
        val row = Mockito.mock(TypedAutomationActionRunEntity::class.java)
        Mockito.`when`(row.id).thenReturn(88L)
        Mockito.`when`(preflight.ensureReady(7)).thenReturn(AutomationDailyPreflight.Result.Ready)
        Mockito.`when`(runtime.claim(7)).thenReturn(TypedRuntimeClaim.Acquired("token"))
        Mockito.`when`(loader.loadTyped(7)).thenReturn(snapshot)
        Mockito.`when`(coordinator.coordinate(snapshot)).thenReturn(AutomationCoordination.Runnable(12, action, emptyList()))
        Mockito.`when`(runtime.prepare(Mockito.eq(7L), eqString("token"), anyStoredAction())).thenReturn(row)
        Mockito.`when`(runtime.markSubmitting(7, "token", 88L)).thenReturn(true)

        runner.runOne(7)

        val storedCaptor = org.mockito.ArgumentCaptor.forClass(StoredTypedAutomationAction::class.java)
        Mockito.verify(executor).execute(Mockito.eq(7L), capture(storedCaptor))
        assertEquals(
            StoredActionDisplay("초보자 임무", "몬스터 처치 · 슬라임", 2, 5, "푸른 초원"),
            storedCaptor.value.payload.display,
        )
        assertEquals(3, (storedCaptor.value.payload as StoredTypedActionPayload.QuestBattle).battleCount)
    }

    @Test
    fun `runner snapshots quest mutation names and adventure map name`() {
        val snapshot = AutomationCoordinatorSnapshot(emptyList())
        val party = ResolvedAutomationParty(
            listOf("character-1"), listOf(BattlePatternLoadRequest("character-1", 1)),
        )
        val actions = listOf<PreparedAutomationAction>(
            QuestAction.Claim("claim", "claim-no", "받을 퀘스트"),
            QuestAction.Accept("accept", "accept-no", "시작할 퀘스트"),
            AdventureMapAutomationAction(
                7, "adventure", "a1", PresetSelectionMode.EXPLICIT, 301, 1, 99, "adventure-execution",
                resolvedParty = party, mapName = "모험의 숲",
            ),
        )
        val row = Mockito.mock(TypedAutomationActionRunEntity::class.java)
        Mockito.`when`(row.id).thenReturn(88L)
        Mockito.`when`(preflight.ensureReady(7)).thenReturn(AutomationDailyPreflight.Result.Ready)
        Mockito.`when`(runtime.claim(7)).thenReturn(
            TypedRuntimeClaim.Acquired("claim-token"),
            TypedRuntimeClaim.Acquired("accept-token"),
            TypedRuntimeClaim.Acquired("adventure-token"),
        )
        Mockito.`when`(loader.loadTyped(7)).thenReturn(snapshot)
        Mockito.`when`(coordinator.coordinate(snapshot)).thenReturn(
            AutomationCoordination.Runnable(12, actions[0], emptyList()),
            AutomationCoordination.Runnable(12, actions[1], emptyList()),
            AutomationCoordination.Runnable(12, actions[2], emptyList()),
        )
        actions.indices.forEach { index ->
            Mockito.`when`(
                runtime.prepare(
                    Mockito.eq(7L),
                    eqString(listOf("claim-token", "accept-token", "adventure-token")[index]),
                    anyStoredAction(),
                ),
            ).thenReturn(row)
        }
        Mockito.`when`(runtime.markSubmitting(Mockito.eq(7L), Mockito.anyString() ?: "", Mockito.eq(88L))).thenReturn(true)

        repeat(actions.size) { runner.runOne(7) }

        val storedCaptor = org.mockito.ArgumentCaptor.forClass(StoredTypedAutomationAction::class.java)
        Mockito.verify(executor, Mockito.times(3)).execute(Mockito.eq(7L), capture(storedCaptor))
        assertEquals(
            listOf(
                StoredActionDisplay(questName = "받을 퀘스트"),
                StoredActionDisplay(questName = "시작할 퀘스트"),
                StoredActionDisplay(mapName = "모험의 숲"),
            ),
            storedCaptor.allValues.map { it.payload.display },
        )
    }

    @Test
    fun `prepare and submitting races explicitly release each claimed token`() {
        val snapshot = AutomationCoordinatorSnapshot(emptyList())
        val action = QuestAction.Claim("quest", "claim")
        val row = Mockito.mock(TypedAutomationActionRunEntity::class.java)
        Mockito.`when`(row.id).thenReturn(88L)
        Mockito.`when`(preflight.ensureReady(7)).thenReturn(AutomationDailyPreflight.Result.Ready)
        Mockito.`when`(runtime.claim(7)).thenReturn(
            TypedRuntimeClaim.Acquired("prepare-token"),
            TypedRuntimeClaim.Acquired("submit-token"),
        )
        Mockito.`when`(loader.loadTyped(7)).thenReturn(snapshot)
        Mockito.`when`(coordinator.coordinate(snapshot)).thenReturn(AutomationCoordination.Runnable(12, action, listOf("warning")))
        Mockito.`when`(runtime.prepare(Mockito.eq(7L), eqString("prepare-token"), anyStoredAction())).thenReturn(null)
        Mockito.`when`(runtime.prepare(Mockito.eq(7L), eqString("submit-token"), anyStoredAction())).thenReturn(row)
        Mockito.`when`(runtime.markSubmitting(7, "submit-token", 88)).thenReturn(false)

        runner.runOne(7)
        runner.runOne(7)

        Mockito.verify(runtime).releaseAndEnqueueWake(7, "prepare-token", "TYPED_CONFIG_RELOAD")
        Mockito.verify(runtime).releaseAndEnqueueWake(7, "submit-token", "TYPED_CONFIG_RELOAD")
        Mockito.verifyNoInteractions(executor)
    }

    @Test
    fun `configuration change releases lease and requests immediate durable reload`() {
        Mockito.`when`(preflight.ensureReady(7)).thenReturn(AutomationDailyPreflight.Result.Ready)
        Mockito.`when`(runtime.claim(7)).thenReturn(TypedRuntimeClaim.Acquired("token"))
        Mockito.`when`(loader.loadTyped(7)).thenThrow(TypedAutomationConfigurationChangedException())

        runner.runOne(7)

        Mockito.verify(runtime).releaseAndEnqueueWake(7, "token", "TYPED_CONFIG_RELOAD")
        Mockito.verifyNoInteractions(wakeup, executor)
    }

    @Test
    fun `inactive typed runtime returns before preflight or any HOF work`() {
        Mockito.`when`(runtime.isRunning(7)).thenReturn(false)

        runner.runOne(7)

        Mockito.verify(runtime).isRunning(7)
        Mockito.verifyNoInteractions(preflight, loader, coordinator, executor, wakeup)
        Mockito.verify(runtime, Mockito.never()).claim(7)
    }

    @Test
    fun `preflight failure remains running and schedules another attempt`() {
        val retryAt = Instant.parse("2026-07-23T00:05:00Z")
        Mockito.`when`(runtime.isRunning(7)).thenReturn(true)
        Mockito.`when`(preflight.ensureReady(7))
            .thenReturn(AutomationDailyPreflight.Result.Stopped(AutomationDailyPreflight.StopReason.NETWORK))
        Mockito.`when`(runtime.scheduleAutomaticRetry(7, AutomationStopReason.NETWORK, "Daily preflight failed: NETWORK"))
            .thenReturn(retryAt)

        runner.runOne(7)

        Mockito.verify(preflight, Mockito.times(1)).ensureReady(7)
        Mockito.verify(preflight).resume(7)
        Mockito.verify(runtime).scheduleAutomaticRetry(7, AutomationStopReason.NETWORK, "Daily preflight failed: NETWORK")
        Mockito.verify(wakeup).schedule(7, retryAt, "TYPED_AUTOMATIC_RETRY")
        Mockito.verify(runtime, Mockito.never()).claim(7)
        Mockito.verifyNoInteractions(loader, coordinator, executor)
    }

    @Test
    fun `preflight retry is persisted on the running runtime for user-visible waiting state`() {
        val retryAt = Instant.parse("2026-07-23T00:00:30Z")
        Mockito.`when`(preflight.ensureReady(7))
            .thenReturn(AutomationDailyPreflight.Result.RetryScheduled(retryAt, 0))
        Mockito.`when`(
            runtime.deferUntil(7, retryAt, AutomationWaitReason.HOF_CONNECTION),
        ).thenReturn(true)

        runner.runOne(7)

        Mockito.verify(runtime).deferUntil(7, retryAt, AutomationWaitReason.HOF_CONNECTION)
        Mockito.verify(wakeup).schedule(7, retryAt, "DAILY_PREFLIGHT_RETRY")
        Mockito.verify(runtime, Mockito.never()).claim(7)
    }

    @Test
    fun `session login failure keeps typed runtime alive for authentication retry`() {
        preparedActionRetry(
            IllegalStateException("wrapped login failure", AutomationLoginRequiredException()),
            AutomationStopReason.AUTHENTICATION,
        )
    }

    @Test
    fun `captcha response keeps typed runtime alive for captcha retry`() {
        preparedActionRetry(
            IllegalStateException(
                "wrapped captcha",
                ApiException(ErrorCode.CAPTCHA_REQUIRED, "captcha"),
            ),
            AutomationStopReason.CAPTCHA,
        )
    }

    @Test
    fun `captcha live snapshot schedules retry before preparing any action`() {
        liveSnapshotRetry(
            ApiException(ErrorCode.CAPTCHA_REQUIRED, "captcha"),
            AutomationStopReason.CAPTCHA,
        )
    }

    @Test
    fun `failed live snapshot login recovery schedules another authentication attempt`() {
        liveSnapshotRetry(
            AutomationLoginRequiredException(),
            AutomationStopReason.AUTHENTICATION,
        )
    }

    @Test
    fun `ambiguous submission enters reconciliation without stopping runtime`() {
        ambiguousActionFailure(
            IllegalStateException(
                "wrapped ambiguous outcome",
                AmbiguousAutomationSubmissionException("unknown outcome"),
            ),
        )
    }

    @Test
    fun `ambiguous quest side effect is checkpointed for automatic verification`() {
        ambiguousActionFailure(
            AmbiguousAutomationSubmissionException(
                "Quest side-effect request outcome is not provable; it will not be resent.",
                IOException("connection reset"),
            ),
        )
    }

    @Test
    fun `reconciling applied action succeeds without external resubmission`() {
        val (stored, row) = reconcilingQuestAction()
        Mockito.`when`(preflight.ensureReady(7)).thenReturn(AutomationDailyPreflight.Result.Ready)
        Mockito.`when`(runtime.claim(7)).thenReturn(TypedRuntimeClaim.Acquired("token", row))
        Mockito.`when`(ambiguousReconciler.reconcile(7, stored))
            .thenReturn(AmbiguousActionResolution.Applied())

        runner.runOne(7)

        Mockito.verify(ambiguousReconciler).reconcile(7, stored)
        Mockito.verify(runtime).succeedReconciliation(7, "token", 88, "TYPED_ACTION_COMPLETED")
        Mockito.verifyNoInteractions(executor)
    }

    @Test
    fun `pause drain resumes only the current stored action without starting daily preflight`() {
        val (stored, row) = reconcilingQuestAction()
        Mockito.`when`(runtime.isCompletingCurrentAction(7)).thenReturn(true)
        Mockito.`when`(runtime.claim(7)).thenReturn(TypedRuntimeClaim.Acquired("token", row))
        Mockito.`when`(ambiguousReconciler.reconcile(7, stored))
            .thenReturn(AmbiguousActionResolution.Applied())

        runner.runOne(7)

        Mockito.verify(preflight, Mockito.never()).ensureReady(7)
        Mockito.verify(ambiguousReconciler).reconcile(7, stored)
        Mockito.verify(runtime).succeedReconciliation(7, "token", 88, "TYPED_ACTION_COMPLETED")
    }

    @Test
    fun `503 while reconciling preserves action and schedules authoritative recheck`() {
        val retryAt = Instant.parse("2026-07-25T00:00:30Z")
        val (stored, row) = reconcilingQuestAction()
        Mockito.`when`(preflight.ensureReady(7)).thenReturn(AutomationDailyPreflight.Result.Ready)
        Mockito.`when`(runtime.claim(7)).thenReturn(TypedRuntimeClaim.Acquired("token", row))
        Mockito.`when`(ambiguousReconciler.reconcile(7, stored))
            .thenThrow(HofAutomationDeferredException(retryAt, 1))
        Mockito.`when`(
            runtime.deferReconciliation(7, "token", 88, retryAt, "HOF automation requests are deferred until $retryAt"),
        ).thenReturn(true)

        runner.runOne(7)

        Mockito.verify(runtime).deferReconciliation(
            7,
            "token",
            88,
            retryAt,
            "HOF automation requests are deferred until $retryAt",
        )
        Mockito.verify(wakeup).schedule(7, retryAt, "HOF_503_COOLDOWN")
        assertTrue(Mockito.mockingDetails(runtime).invocations.none { it.method.name == "stop" })
    }

    @Test
    fun `captcha while reconciling preserves action and schedules another verification`() {
        val (stored, row) = reconcilingQuestAction()
        Mockito.`when`(preflight.ensureReady(7)).thenReturn(AutomationDailyPreflight.Result.Ready)
        Mockito.`when`(runtime.claim(7)).thenReturn(TypedRuntimeClaim.Acquired("token", row))
        Mockito.`when`(ambiguousReconciler.reconcile(7, stored))
            .thenThrow(ApiException(ErrorCode.CAPTCHA_REQUIRED, "captcha"))
        val retryAt = Instant.parse("2026-07-25T00:05:00Z")
        Mockito.`when`(runtime.scheduleAutomaticRetry(7, "token", 88, AutomationStopReason.CAPTCHA, "captcha"))
            .thenReturn(retryAt)

        runner.runOne(7)

        Mockito.verify(runtime).scheduleAutomaticRetry(7, "token", 88, AutomationStopReason.CAPTCHA, "captcha")
        Mockito.verify(wakeup).schedule(7, retryAt, "TYPED_AUTOMATIC_RETRY")
        Mockito.verifyNoInteractions(executor)
    }

    @Test
    fun `503 while loading snapshot releases runtime and schedules its global retry`() {
        val retryAt = Instant.parse("2026-07-23T00:00:30Z")
        Mockito.`when`(preflight.ensureReady(7)).thenReturn(AutomationDailyPreflight.Result.Ready)
        Mockito.`when`(runtime.claim(7)).thenReturn(TypedRuntimeClaim.Acquired("token"))
        Mockito.`when`(loader.loadTyped(7)).thenThrow(HofAutomationDeferredException(retryAt, 1))
        Mockito.`when`(
            runtime.release(7, "token", retryAt, AutomationWaitReason.HOF_CONNECTION),
        ).thenReturn(true)

        runner.runOne(7)

        Mockito.verify(runtime).release(7, "token", retryAt, AutomationWaitReason.HOF_CONNECTION)
        Mockito.verify(wakeup).schedule(7, retryAt, "HOF_503_COOLDOWN")
        assertTrue(Mockito.mockingDetails(runtime).invocations.none { it.method.name == "stop" })
    }

    @Test
    fun `503 after submission returns action to prepared and schedules exact retry`() {
        val retryAt = Instant.parse("2026-07-23T00:03:00Z")
        val stored = StoredTypedAutomationAction(
            12, "execution-1", StoredTypedActionPayload.QuestClaim("quest", "claim"),
        )
        val encoded = codec.encode(stored)
        val owner = HofAccountEntity(7, "login", "encrypted", Instant.EPOCH)
        val entry = AutomationEntryEntity(12, owner, AutomationType.QUEST, 0, true, Instant.EPOCH, Instant.EPOCH)
        val row = TypedAutomationActionRunEntity(
            88, owner, entry, stored.executionIdentity, stored.payload.kind(),
            encoded.json,
            encoded.fingerprint, TypedAutomationActionStatus.PREPARED, leaseToken = "token",
            createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
        )
        Mockito.`when`(preflight.ensureReady(7)).thenReturn(AutomationDailyPreflight.Result.Ready)
        Mockito.`when`(runtime.claim(7)).thenReturn(TypedRuntimeClaim.Acquired("token", row))
        Mockito.`when`(runtime.markSubmitting(7, "token", 88)).thenReturn(true)
        Mockito.doThrow(HofAutomationDeferredException(retryAt, 3))
            .`when`(executor).execute(Mockito.eq(7L), anyStoredAction())
        val message = "HOF automation requests are deferred until $retryAt"
        Mockito.`when`(runtime.deferSubmittedAction(7, "token", 88, retryAt, message)).thenReturn(true)

        runner.runOne(7)

        Mockito.verify(runtime).deferSubmittedAction(7, "token", 88, retryAt, message)
        Mockito.verify(wakeup).schedule(7, retryAt, "HOF_503_COOLDOWN")
        assertTrue(Mockito.mockingDetails(runtime).invocations.none { it.method.name == "stop" })
    }

    @Test
    fun `tampered stored envelopes are isolated and retried without submitting or posting`() {
        val stored = StoredTypedAutomationAction(
            12,
            "execution-1",
            StoredTypedActionPayload.QuestClaim("quest", "claim"),
        )
        val encoded = codec.encode(stored)
        data class Corruption(
            val name: String,
            val accountId: Long = 7,
            val entryId: Long = 12,
            val execution: String = "execution-1",
            val kind: String = "QUEST_CLAIM",
            val json: String = encoded.json,
            val fingerprint: String = encoded.fingerprint,
        )
        val changedPayload = codec.encode(
            stored.copy(payload = StoredTypedActionPayload.QuestClaim("tampered", "claim")),
        ).json
        val cases = listOf(
            Corruption("payload", json = changedPayload),
            Corruption("whitespace-bytes", json = "  ${encoded.json}\n"),
            Corruption(
                "reordered-bytes",
                json = "{\"executionIdentity\":\"execution-1\",\"entryId\":12,\"payload\":{\"kind\":\"QUEST_CLAIM\",\"questKey\":\"quest\",\"actionNo\":\"claim\"}}",
            ),
            Corruption("fingerprint", fingerprint = "0".repeat(64)),
            Corruption("kind", kind = "QUEST_ACCEPT"),
            Corruption("account", accountId = 8),
            Corruption("entry", entryId = 13),
            Corruption("execution", execution = "different"),
        )
        cases.forEach { corruption ->
            val casePreflight = Mockito.mock(AutomationDailyPreflight::class.java)
            val caseRuntime = Mockito.mock(TypedAutomationRuntimeService::class.java)
            val caseExecutor = Mockito.mock(TypedAutomationActionExecutor::class.java)
            val owner = HofAccountEntity(corruption.accountId, "login-${corruption.name}", "encrypted", Instant.EPOCH)
            val entry = AutomationEntryEntity(
                corruption.entryId,
                owner,
                AutomationType.QUEST,
                0,
                true,
                Instant.EPOCH,
                Instant.EPOCH,
            )
            val row = TypedAutomationActionRunEntity(
                88,
                owner,
                entry,
                corruption.execution,
                corruption.kind,
                corruption.json,
                corruption.fingerprint,
                TypedAutomationActionStatus.PREPARED,
                leaseToken = "token",
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            )
            val caseRunner = UnifiedAutomationRunner(
                casePreflight,
                caseRuntime,
                Mockito.mock(TypedAutomationSnapshotLoader::class.java),
                Mockito.mock(AutomationCoordinator::class.java),
                caseExecutor,
                codec,
                wakeup,
                sharedCooldowns,
                ambiguousReconciler,
                actionLifecycleModule,
            )
            Mockito.`when`(casePreflight.ensureReady(7)).thenReturn(AutomationDailyPreflight.Result.Ready)
            Mockito.`when`(caseRuntime.isRunning(7)).thenReturn(true)
            Mockito.`when`(caseRuntime.claim(7)).thenReturn(TypedRuntimeClaim.Acquired("token", row))
            val retryAt = Instant.parse("2026-07-25T00:05:00Z")
            Mockito.`when`(
                caseRuntime.isolateIntegrityFailureForRetry(
                    7, "token", 88, "Stored typed action integrity check failed.",
                ),
            ).thenReturn(retryAt)

            caseRunner.runOne(7)

            Mockito.verify(caseRuntime).isolateIntegrityFailureForRetry(
                7,
                "token",
                88,
                "Stored typed action integrity check failed.",
            )
            Mockito.verify(wakeup).schedule(7, retryAt, "TYPED_AUTOMATIC_RETRY")
            Mockito.clearInvocations(wakeup)
            Mockito.verifyNoInteractions(caseExecutor)
        }
    }

    private fun anyStoredAction(): StoredTypedAutomationAction =
        Mockito.any(StoredTypedAutomationAction::class.java)
            ?: StoredTypedAutomationAction(1, "any", StoredTypedActionPayload.QuestClaim("q", "a"))

    private fun anyCoordination(): AutomationCoordination =
        Mockito.any(AutomationCoordination::class.java)
            ?: AutomationCoordination.Idle(emptyList())

    private fun capture(captor: org.mockito.ArgumentCaptor<StoredTypedAutomationAction>): StoredTypedAutomationAction =
        captor.capture() ?: StoredTypedAutomationAction(1, "capture", StoredTypedActionPayload.QuestClaim("q", "a"))

    private fun captureTrace(captor: org.mockito.ArgumentCaptor<AutomationActionTrace>): AutomationActionTrace =
        captor.capture() ?: AutomationActionTrace(
            AutomationHistoryEventKind.ACTION_SUCCEEDED,
            "capture",
            "capture",
        )

    private fun captureCoordination(
        captor: org.mockito.ArgumentCaptor<AutomationCoordination>,
    ): AutomationCoordination = captor.capture() ?: AutomationCoordination.Idle(emptyList())

    private fun eqString(value: String): String = Mockito.eq(value) ?: value

    private fun reconcilingQuestAction(): Pair<StoredTypedAutomationAction, TypedAutomationActionRunEntity> {
        val stored = StoredTypedAutomationAction(
            12,
            "execution-1",
            StoredTypedActionPayload.QuestAccept("quest", "accept-no"),
        )
        val encoded = codec.encode(stored)
        val owner = HofAccountEntity(7, "login", "encrypted", Instant.EPOCH)
        val entry = AutomationEntryEntity(12, owner, AutomationType.QUEST, 0, true, Instant.EPOCH, Instant.EPOCH)
        return stored to TypedAutomationActionRunEntity(
            88, owner, entry, stored.executionIdentity, stored.payload.kind(),
            encoded.json,
            encoded.fingerprint, TypedAutomationActionStatus.RECONCILING, leaseToken = "token",
            createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
        )
    }

    private fun preparedActionRetry(error: Throwable, expectedReason: AutomationStopReason) {
        val stored = StoredTypedAutomationAction(
            12,
            "execution-1",
            StoredTypedActionPayload.QuestClaim("quest", "claim"),
        )
        val encoded = codec.encode(stored)
        val owner = HofAccountEntity(7, "login", "encrypted", Instant.EPOCH)
        val entry = AutomationEntryEntity(12, owner, AutomationType.QUEST, 0, true, Instant.EPOCH, Instant.EPOCH)
        val row = TypedAutomationActionRunEntity(
            88,
            owner,
            entry,
            stored.executionIdentity,
            stored.payload.kind(),
            encoded.json,
            encoded.fingerprint,
            TypedAutomationActionStatus.PREPARED,
            leaseToken = "token",
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )
        Mockito.`when`(preflight.ensureReady(7)).thenReturn(AutomationDailyPreflight.Result.Ready)
        Mockito.`when`(runtime.claim(7)).thenReturn(TypedRuntimeClaim.Acquired("token", row))
        Mockito.`when`(runtime.markSubmitting(7, "token", 88)).thenReturn(true)
        Mockito.doThrow(error).`when`(executor).execute(Mockito.eq(7L), anyStoredAction())
        val retryAt = Instant.parse("2026-07-25T00:05:00Z")
        Mockito.`when`(runtime.scheduleAutomaticRetry(Mockito.eq(7L), eqString("token"), Mockito.eq(88L), eqValue(expectedReason), anyStringValue()))
            .thenReturn(retryAt)

        runner.runOne(7)

        Mockito.verify(runtime).scheduleAutomaticRetry(
            Mockito.eq(7L),
            eqString("token"),
            Mockito.eq(88L),
            eqValue(expectedReason),
            anyStringValue(),
        )
        Mockito.verify(wakeup).schedule(7, retryAt, "TYPED_AUTOMATIC_RETRY")
    }

    private fun ambiguousActionFailure(error: Throwable) {
        val stored = StoredTypedAutomationAction(
            12,
            "execution-ambiguous",
            StoredTypedActionPayload.QuestClaim("quest", "claim"),
        )
        val encoded = codec.encode(stored)
        val owner = HofAccountEntity(7, "login-ambiguous", "encrypted", Instant.EPOCH)
        val entry = AutomationEntryEntity(12, owner, AutomationType.QUEST, 0, true, Instant.EPOCH, Instant.EPOCH)
        val row = TypedAutomationActionRunEntity(
            89, owner, entry, stored.executionIdentity, stored.payload.kind(),
            encoded.json,
            encoded.fingerprint, TypedAutomationActionStatus.PREPARED, leaseToken = "token",
            createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
        )
        Mockito.`when`(preflight.ensureReady(7)).thenReturn(AutomationDailyPreflight.Result.Ready)
        Mockito.`when`(runtime.claim(7)).thenReturn(TypedRuntimeClaim.Acquired("token", row))
        Mockito.`when`(runtime.markSubmitting(7, "token", row.id)).thenReturn(true)
        Mockito.doThrow(error).`when`(executor).execute(7, stored)

        runner.runOne(7)

        Mockito.verify(runtime).markReconcilingAndEnqueueWake(
            Mockito.eq(7L),
            eqString("token"),
            Mockito.eq(row.id),
            anyStringValue(),
        )
        assertTrue(Mockito.mockingDetails(runtime).invocations.none { it.method.name == "stop" })
    }

    private fun <T> eqValue(value: T): T = Mockito.eq(value) ?: value
    private fun anyStringValue(): String = Mockito.anyString() ?: ""

    private fun liveSnapshotRetry(error: Throwable, expectedReason: AutomationStopReason) {
        Mockito.`when`(preflight.ensureReady(7)).thenReturn(AutomationDailyPreflight.Result.Ready)
        Mockito.`when`(runtime.claim(7)).thenReturn(TypedRuntimeClaim.Acquired("token"))
        Mockito.`when`(loader.loadTyped(7)).thenThrow(error)
        val retryAt = Instant.parse("2026-07-25T00:05:00Z")
        Mockito.`when`(runtime.scheduleAutomaticRetry(Mockito.eq(7L), eqString("token"), Mockito.isNull(), eqValue(expectedReason), anyStringValue()))
            .thenReturn(retryAt)

        runner.runOne(7)

        Mockito.verify(runtime).scheduleAutomaticRetry(
            Mockito.eq(7L),
            eqString("token"),
            Mockito.isNull(),
            eqValue(expectedReason),
            anyStringValue(),
        )
        Mockito.verify(wakeup).schedule(7, retryAt, "TYPED_AUTOMATIC_RETRY")
        Mockito.verifyNoInteractions(coordinator, executor)
    }
}
