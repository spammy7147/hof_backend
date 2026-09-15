package app.spammy.hof.automation.service

import app.spammy.hof.automation.convergence.AutomationActionConvergenceModule
import app.spammy.hof.automation.convergence.ActionConvergenceResult
import app.spammy.hof.automation.convergence.AutomationActionEvidence
import app.spammy.hof.automation.convergence.AutomationActionKind
import app.spammy.hof.automation.convergence.AutomationConvergenceMode
import app.spammy.hof.automation.convergence.AutomationConvergenceProperties
import app.spammy.hof.automation.convergence.AutomationConvergenceRollout
import app.spammy.hof.automation.convergence.AutomationConvergenceShadowEvaluator
import app.spammy.hof.automation.convergence.AutomationIsolationScope
import app.spammy.hof.automation.convergence.AutomationIsolationScopeKind
import app.spammy.hof.automation.convergence.ConvergenceDirective
import app.spammy.hof.automation.convergence.DefaultAutomationActionConvergenceModule
import app.spammy.hof.automation.convergence.DefaultActionEvidencePolicies
import app.spammy.hof.automation.convergence.InMemoryConvergenceStore
import app.spammy.hof.automation.convergence.LegacyConvergenceDecision
import app.spammy.hof.automation.convergence.ProductionActionEvidenceInterpreter
import app.spammy.hof.automation.convergence.ProductionEvidenceShapes
import app.spammy.hof.automation.convergence.FishingObservedState
import app.spammy.hof.automation.convergence.QuestObservedState
import app.spammy.hof.automation.convergence.SelectedAutomationAction
import app.spammy.hof.automation.convergence.RestoredActionCheckpoint
import app.spammy.hof.automation.convergence.StoredActionConvergenceSelectionFactory
import app.spammy.hof.automation.convergence.StoredConvergenceActionLoader
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.AutomationEntryEntity
import app.spammy.hof.automation.entity.AutomationWorkStatus
import app.spammy.hof.automation.entity.AutomationWorkType
import app.spammy.hof.automation.entity.AutomationWaitReason
import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.automation.history.AutomationActionTrace
import app.spammy.hof.automation.history.AutomationDecisionJournal
import app.spammy.hof.automation.history.AutomationHistoryEventKind
import app.spammy.hof.automation.port.AutomationWakeupPort
import app.spammy.hof.automation.raid.RaidCycleOutcome
import app.spammy.hof.automation.raid.RaidCycleOutcomeKind
import app.spammy.hof.automation.raid.RaidCycleModule
import app.spammy.hof.automation.raid.RaidDecision
import app.spammy.hof.automation.raid.RaidDirective
import app.spammy.hof.automation.raid.RaidIntent
import app.spammy.hof.automation.raid.RaidIntentKind
import app.spammy.hof.automation.repository.AutomationWorkSessionQueryRepository
import app.spammy.hof.automation.repository.AutomationWorkSessionView
import app.spammy.hof.automation.repository.TypedAutomationQueryRepository
import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.battle.dto.BattlePatternLoadRequest
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.auth.service.AccountExecutionSubmissionGate
import app.spammy.hof.external.client.HofAutomationDeferredException
import app.spammy.hof.quest.model.QuestState
import app.spammy.hof.town.raid.model.RaidAction
import app.spammy.hof.town.fishing.dto.FishingBattleTargetResponse
import app.spammy.hof.town.fishing.service.FishingService
import app.spammy.hof.town.fishing.service.FishingAutomationObservation
import app.spammy.hof.town.fishing.service.FishingOneCastRemoteResult
import app.spammy.hof.town.common.service.TownSubmissionBoundary
import app.spammy.hof.town.fishing.dto.FishingResponse
import app.spammy.hof.town.fishing.model.FishingAction
import app.spammy.hof.town.fishing.model.FishingOutcome
import app.spammy.hof.town.fishing.model.FishingPrimaryAction
import app.spammy.hof.town.common.service.AccountHofObservationInvalidatedException
import app.spammy.hof.town.common.service.ObservedTownActionPreconditionChangedException
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.mockito.ArgumentCaptor
import org.mockito.Mockito

class UnifiedAutomationRunnerTest {
    private val preflight = Mockito.mock(AutomationDailyPreflight::class.java)
    private val runtime = Mockito.mock(TypedAutomationRuntimeService::class.java)
    private val decisions = Mockito.mock(AutomationDecisionSource::class.java)
    private val wakeup = Mockito.mock(AutomationWakeupPort::class.java)
    private val sharedCooldowns = Mockito.mock(SharedBattleCooldownService::class.java)
    private val lifecycle = Mockito.mock(AutomationActionLifecycleModule::class.java)
    private val submissionGate = Mockito.mock(AccountExecutionSubmissionGate::class.java)
    private val managed = Mockito.mock(ManagedAutomationAction::class.java)
    private val freshExecution = executionRight()
    private val defaultStored = defaultStoredAction()
    private val productionEvidenceInterpreter = ProductionActionEvidenceInterpreter(DefaultActionEvidencePolicies())
    private val preparedExecution = executionRight(
        TypedRuntimeCheckpoint(defaultStored, TypedRuntimeCheckpointPhase.PREPARED, null, null),
    )
    private val runner = buildRunner(
        preflight,
        runtime,
        decisions,
        wakeup,
        sharedCooldowns,
        lifecycle,
        submissionGate,
    )

    init {
        Mockito.`when`(submissionGate.executeIfAuthorized(Mockito.anyLong(), anyRunnable()))
            .thenAnswer { invocation ->
                (invocation.arguments[1] as Runnable).run()
                true
            }
        Mockito.doAnswer { invocation ->
            (invocation.arguments[1] as Runnable).run()
            null
        }.`when`(submissionGate).executeLogout(Mockito.anyLong(), anyRunnable())
        Mockito.`when`(runtime.isRunning(7)).thenReturn(true)
        Mockito.`when`(preflight.ensureReady(7)).thenReturn(AutomationDailyPreflight.Result.Ready)
        Mockito.`when`(runtime.acquire(7)).thenReturn(TypedRuntimeAcquisition.Acquired(freshExecution))
        Mockito.`when`(managed.storedAction).thenReturn(defaultStored)
        Mockito.`when`(managed.descriptor).thenReturn(defaultDescriptor())
        Mockito.`when`(managed.execute()).thenReturn(TypedAutomationExecution.Completed)
        Mockito.`when`(managed.applyLegacyExecution(anyTypedExecution())).thenAnswer { it.arguments[0] }
        Mockito.`when`(managed.applyPolicyAcceptedExecution(anyTypedExecution())).thenAnswer { it.arguments[0] }
        Mockito.`when`(lifecycle.describe(anyPreparedAction())).thenReturn(defaultDescriptor())
        Mockito.`when`(
            lifecycle.prepare(Mockito.eq(7L), Mockito.anyLong(), anyPreparedAction()),
        ).thenReturn(managed)
        Mockito.`when`(
            runtime.persistPrepared(anyExecution(), anyStoredAction(), anyWarnings()),
        ).thenReturn(TypedRuntimePreparation.Ready(preparedExecution))
        Mockito.`when`(runtime.beginSubmission(preparedExecution))
            .thenReturn(TypedRuntimeSubmission.Started(Instant.EPOCH))
        Mockito.`when`(runtime.complete(anyExecution(), anyOutcome(), Mockito.nullable(Instant::class.java)))
            .thenReturn(TypedRuntimeProjection(true))
        Mockito.doAnswer { invocation ->
            invocation.getArgument<() -> Unit>(2).invoke()
            runtime.complete(invocation.getArgument(0), invocation.getArgument(1))
        }.`when`(runtime).complete(anyExecution(), anyOutcome(), Mockito.any<() -> Unit>() ?: {})
        Mockito.doAnswer { invocation ->
            invocation.getArgument<() -> Unit>(3).invoke()
            runtime.complete(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument<Instant?>(2))
        }.`when`(runtime).completeReconciliation(anyExecution(), anyOutcome(), Mockito.any(), Mockito.any<() -> Unit>() ?: {})
        Mockito.doAnswer { invocation ->
            invocation.getArgument<() -> Unit>(2).invoke()
            null
        }.`when`(runtime).persistReconciliationHistory(anyExecution(), anyOutcome(), Mockito.any<() -> Unit>() ?: {})
        Mockito.doAnswer { invocation ->
            val preparation = runtime.advanceAppliedActionToPreparedFollowup(invocation.getArgument(0), invocation.getArgument(1))
            invocation.getArgument<() -> Unit>(3).invoke()
            preparation
        }.`when`(runtime).advanceAppliedActionToPreparedFollowup(anyExecution(), anyStoredAction(),
            Mockito.any(TypedRuntimeOutcome.ActionSucceeded::class.java) ?: TypedRuntimeOutcome.ActionSucceeded("test"),
            Mockito.any<() -> Unit>() ?: {})
    }

    @Test
    fun `미지원 정책의 직접 공유 쿨다운 응답은 작업 진행에 반영하지 않는다`() {
        val now = Instant.parse("2026-09-10T08:00:00Z")
        val store = InMemoryConvergenceStore()
        val selected = SelectedAutomationAction(12L, "unknown-cooldown", AutomationActionKind.MAP_BATTLE,
            AutomationIsolationScope(AutomationIsolationScopeKind.BATTLE_COOLDOWN_SCOPE, "battle"),
            "unsupported-fixture-version", "baseline")
        val attempt = store.createOrGet(7L, selected, now)
        val convergence = DefaultAutomationActionConvergenceModule(store, TimeProvider { now })
        val coordinator = AutomationResultCoordinator(lifecycle, sharedCooldowns, convergence, timeProvider = TimeProvider { now })
        val execution = TypedAutomationExecution.SharedCooldown("battle", "map", now.plusSeconds(30))
        val evidence = productionEvidenceInterpreter.fromExecution(selected, execution, now)
        val result = coordinator.applyDirect(managed, execution, evidence, attempt.attemptId)
        assertIs<AutomationResultCoordinator.DirectResult.Unapplied>(result)
        assertEquals(ActionConvergenceResult.HELD, store.get(attempt.attemptId)?.result)
        Mockito.verifyNoInteractions(managed, sharedCooldowns)
    }

    @Test
    fun `미지원 저장 정책의 probe는 lifecycle 복원과 작업 진전 전에 보류한다`() {
        val now = Instant.parse("2026-09-10T08:00:00Z")
        val store = InMemoryConvergenceStore()
        val selected = SelectedAutomationAction(12L, defaultStored.executionIdentity, AutomationActionKind.QUEST_CLAIM,
            AutomationIsolationScope(AutomationIsolationScopeKind.QUEST_TARGET, "quest"),
            "unsupported-fixture-version", "baseline")
        val attempt = store.createOrGet(7L, selected, now)
        val convergence = DefaultAutomationActionConvergenceModule(store, TimeProvider { now })
        Mockito.`when`(lifecycle.restoreVerified(defaultStored, 7L)).thenReturn(managed)
        Mockito.`when`(managed.reconcile()).thenReturn(AmbiguousActionResolution.Applied())
        val coordinator = AutomationResultCoordinator(lifecycle, sharedCooldowns, convergence,
            storedConvergenceActionLoader = StoredConvergenceActionLoader { _, _ -> defaultStored },
            timeProvider = TimeProvider { now }, evidenceInterpreter = productionEvidenceInterpreter)
        assertIs<ConvergenceDirective.ContinueSelection>(coordinator.probe(7L, ConvergenceDirective.Probe(attempt.attemptId, selected)))
        assertEquals(ActionConvergenceResult.HELD, store.get(attempt.attemptId)?.result)
        Mockito.verifyNoInteractions(lifecycle, managed, sharedCooldowns)
    }

    @Test
    fun `runnable action crosses one durable runtime lifecycle and records descriptor based history`() {
        val journal = Mockito.mock(AutomationDecisionJournal::class.java)
        val action = legacyBattleAction()
        val decision = AutomationCoordination.Runnable(12, action, listOf("parked warning"))
        Mockito.`when`(decisions.select(7)).thenReturn(decision)
        Mockito.`when`(journal.appendDecision(Mockito.eq(7L), anyCoordination())).thenReturn(41L)
        val scoped = buildRunner(
            preflight,
            runtime,
            decisions,
            wakeup,
            sharedCooldowns,
            lifecycle,
            submissionGate,
            journal,
        )

        scoped.runOne(7)

        Mockito.verify(lifecycle).prepare(7, 12, action)
        Mockito.verify(runtime).persistPrepared(freshExecution, defaultStored, listOf("parked warning"))
        Mockito.verify(runtime).beginSubmission(preparedExecution)
        Mockito.verify(managed).execute()
        assertIs<TypedRuntimeOutcome.ActionSucceeded>(capturedOutcome())
        val traceCaptor = ArgumentCaptor.forClass(AutomationActionTrace::class.java)
        Mockito.verify(journal).appendActionResult(Mockito.eq(41L), captureTrace(traceCaptor))
        Mockito.verify(journal).deferActionResult(Mockito.eq(41L), Mockito.eq(defaultStored.executionIdentity) ?: defaultStored.executionIdentity, captureTrace(traceCaptor))
        assertTrue(traceCaptor.allValues.all { it.actionKind == "QUEST_CLAIM" })
        assertTrue(traceCaptor.allValues.all { it.message.startsWith("퀘스트 보상 수령 · quest") })
    }

    @Test
    fun `logout authorization boundary discards a prepared action without starting its remote submission`() {
        Mockito.`when`(decisions.select(7)).thenReturn(
            AutomationCoordination.Runnable(12, legacyBattleAction(), emptyList()),
        )
        Mockito.`when`(
            submissionGate.executeIfAuthorized(Mockito.eq(7L), anyRunnable()),
        ).thenReturn(false)

        runner.runOne(7)

        Mockito.verify(runtime).beginSubmission(preparedExecution)
        Mockito.verify(managed, Mockito.never()).execute()
        val outcome = assertIs<TypedRuntimeOutcome.SubmissionDeferred>(capturedOutcome())
        assertTrue(outcome.message.contains("로그아웃"))
    }

    @Test
    fun `낚시 START 선택은 같은 실행에서 CATCH checkpoint로 전이해 완료한다`() {
        val fishing = Mockito.mock(FishingService::class.java)
        val startManaged = Mockito.mock(ManagedFishingAutomationAction::class.java)
        val catchManaged = Mockito.mock(ManagedFishingAutomationAction::class.java)
        val startStored = StoredTypedAutomationAction(
            15L,
            "start-1",
            StoredTypedActionPayload.FishingTown(
                FishingAction.START,
                FishingPrimaryAction.START,
                18,
                progressDate = LocalDate.parse("2026-08-24"),
            ),
        )
        val startPrepared = executionRight(
            TypedRuntimeCheckpoint(startStored, TypedRuntimeCheckpointPhase.PREPARED, null, null),
        )
        val catchPrepared = executionRight()
        val startResponse = fishingResponse(FishingPrimaryAction.CATCH, 17, FishingOutcome.STARTED)
        val catchResponse = fishingResponse(FishingPrimaryAction.START, 16, FishingOutcome.CAUGHT)
        val action = FishingTownAutomationAction(
            7L,
            FishingAction.START,
            FishingPrimaryAction.START,
            18,
            LocalDate.parse("2026-08-24"),
        )
        val descriptor = AutomationActionDescriptor(
            AutomationType.FISHING,
            "FISHING_TOWN",
            "START",
            "낚시",
            "낚시 사이클 시작",
        )
        Mockito.`when`(decisions.select(7L)).thenReturn(AutomationCoordination.Runnable(15L, action, emptyList()))
        Mockito.`when`(lifecycle.describe(action)).thenReturn(descriptor)
        Mockito.`when`(lifecycle.prepare(7L, 15L, action)).thenReturn(startManaged)
        Mockito.`when`(startManaged.storedAction).thenReturn(startStored)
        Mockito.`when`(startManaged.descriptor).thenReturn(descriptor)
        Mockito.`when`(catchManaged.descriptor).thenReturn(descriptor.copy(actionKind = "CATCH"))
        Mockito.`when`(runtime.persistPrepared(freshExecution, startStored, emptyList()))
            .thenReturn(TypedRuntimePreparation.Ready(startPrepared))
        Mockito.`when`(runtime.beginSubmission(startPrepared)).thenReturn(TypedRuntimeSubmission.Started(Instant.EPOCH))
        Mockito.`when`(
            runtime.advanceAppliedActionToPreparedFollowup(anyExecution(), anyStoredAction()),
        ).thenReturn(TypedRuntimePreparation.Ready(catchPrepared))
        Mockito.`when`(runtime.beginSubmission(catchPrepared)).thenReturn(TypedRuntimeSubmission.Started(Instant.EPOCH))
        Mockito.`when`(lifecycle.restoreVerified(anyStoredAction(), Mockito.eq(7L))).thenReturn(catchManaged)
        Mockito.`when`(startManaged.observeDirectResponse(startResponse)).thenReturn(
            Mockito.mock(TypedAutomationExecution.ActionCompleted::class.java),
        )
        Mockito.`when`(catchManaged.observeDirectResponse(catchResponse)).thenReturn(
            Mockito.mock(TypedAutomationExecution.ActionCompleted::class.java),
        )
        Mockito.`when`(startManaged.applyLegacyExecution(anyTypedExecution())).thenAnswer { it.arguments[0] }
        Mockito.`when`(catchManaged.applyLegacyExecution(anyTypedExecution())).thenAnswer { it.arguments[0] }
        Mockito.`when`(
            fishing.executeOneCastForAutomation(Mockito.eq(7L), anyFishingObservation(), anyTownBoundary(), anyBeforeCatch()),
        ).thenAnswer { invocation ->
            invocation.getArgument<(FishingResponse) -> Unit>(3).invoke(startResponse)
            FishingOneCastRemoteResult.Completed(startResponse, catchResponse)
        }
        val scoped = buildRunner(
            preflight,
            runtime,
            decisions,
            wakeup,
            sharedCooldowns,
            lifecycle,
            submissionGate,
            fishingService = fishing,
        )

        scoped.runOne(7L)

        Mockito.verify(fishing).executeOneCastForAutomation(Mockito.eq(7L), anyFishingObservation(), anyTownBoundary(), anyBeforeCatch())
        Mockito.verify(runtime).advanceAppliedActionToPreparedFollowup(anyExecution(), anyStoredAction())
        Mockito.verify(runtime).beginSubmission(catchPrepared)
        val outcome = ArgumentCaptor.forClass(TypedRuntimeOutcome::class.java)
        Mockito.verify(runtime).complete(anyExecution(), captureOutcome(outcome), Mockito.nullable(Instant::class.java))
        assertIs<TypedRuntimeOutcome.ActionSucceeded>(outcome.value)
        Mockito.verify(startManaged, Mockito.never()).execute()
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(AutomationConvergenceMode::class)
    fun `낚시 CATCH 응답의 방해 전투는 실행하지 않고 한 번 낚시를 완료한다`(mode: AutomationConvergenceMode) {
        val fishing = Mockito.mock(FishingService::class.java)
        val convergence = Mockito.mock(AutomationActionConvergenceModule::class.java)
        val shadow = Mockito.mock(AutomationConvergenceShadowEvaluator::class.java)
        val startManaged = Mockito.mock(ManagedFishingAutomationAction::class.java)
        val catchManaged = Mockito.mock(ManagedFishingAutomationAction::class.java)
        val startStored = fishingStartStored()
        val startPrepared = executionRight(
            TypedRuntimeCheckpoint(startStored, TypedRuntimeCheckpointPhase.PREPARED, null, null),
        )
        val catchPrepared = executionRight()
        val startResponse = fishingResponse(FishingPrimaryAction.CATCH, 17, FishingOutcome.STARTED)
        val catchResponse = fishingResponse(FishingPrimaryAction.NONE, 16, FishingOutcome.CAUGHT).copy(
            availableActions = emptySet(),
            blockedByBattle = true,
            battleTarget = FishingBattleTargetResponse("battle_map", "fish-monster", "낚시터 괴물"),
        )
        val action = fishingStartAction()
        Mockito.`when`(decisions.select(7L)).thenReturn(AutomationCoordination.Runnable(15L, action, emptyList()))
        Mockito.`when`(lifecycle.describe(action)).thenReturn(fishingDescriptor("START"))
        Mockito.`when`(lifecycle.prepare(7L, 15L, action)).thenReturn(startManaged)
        Mockito.`when`(startManaged.storedAction).thenReturn(startStored)
        Mockito.`when`(startManaged.descriptor).thenReturn(fishingDescriptor("START"))
        Mockito.`when`(catchManaged.descriptor).thenReturn(fishingDescriptor("CATCH"))
        Mockito.`when`(runtime.persistPrepared(freshExecution, startStored, emptyList()))
            .thenReturn(TypedRuntimePreparation.Ready(startPrepared))
        Mockito.`when`(runtime.beginSubmission(startPrepared)).thenReturn(TypedRuntimeSubmission.Started(Instant.EPOCH))
        Mockito.`when`(runtime.advanceAppliedActionToPreparedFollowup(anyExecution(), anyStoredAction()))
            .thenReturn(TypedRuntimePreparation.Ready(catchPrepared))
        Mockito.`when`(runtime.beginSubmission(catchPrepared)).thenReturn(TypedRuntimeSubmission.Started(Instant.EPOCH))
        Mockito.`when`(lifecycle.restoreVerified(anyStoredAction(), Mockito.eq(7L))).thenReturn(catchManaged)
        Mockito.`when`(startManaged.observeDirectResponse(startResponse)).thenReturn(fishingExecution(startResponse))
        Mockito.`when`(catchManaged.observeDirectResponse(catchResponse)).thenReturn(fishingExecution(catchResponse))
        Mockito.`when`(startManaged.applyLegacyExecution(anyTypedExecution())).thenAnswer { it.arguments[0] }
        Mockito.`when`(catchManaged.applyLegacyExecution(anyTypedExecution())).thenAnswer { it.arguments[0] }
        Mockito.`when`(startManaged.applyPolicyAcceptedExecution(anyTypedExecution())).thenAnswer { it.arguments[0] }
        Mockito.`when`(catchManaged.applyPolicyAcceptedExecution(anyTypedExecution())).thenAnswer { it.arguments[0] }
        Mockito.`when`(convergence.prepare(Mockito.eq(7L), anyConvergenceSelection()))
            .thenAnswer { ConvergenceDirective.Submit(201L, it.getArgument(1)) }
            .thenAnswer { ConvergenceDirective.Submit(202L, it.getArgument(1)) }
        Mockito.`when`(convergence.record(Mockito.anyLong(), anyConvergenceEvidence()))
            .thenReturn(ConvergenceDirective.ContinueSelection)
        Mockito.`when`(fishing.executeOneCastForAutomation(Mockito.eq(7L), anyFishingObservation(), anyTownBoundary(), anyBeforeCatch()))
            .thenAnswer { invocation ->
                invocation.getArgument<(FishingResponse) -> Unit>(3).invoke(startResponse)
                FishingOneCastRemoteResult.Completed(startResponse, catchResponse)
            }
        val scoped = buildRunner(
            preflight,
            runtime,
            decisions,
            wakeup,
            sharedCooldowns,
            lifecycle,
            submissionGate,
            convergenceModule = convergence,
            convergenceSelectionFactory = StoredActionConvergenceSelectionFactory(),
            evidenceInterpreter = productionEvidenceInterpreter,
            rollout = AutomationConvergenceRollout(
                AutomationConvergenceProperties(mode = mode),
            ),
            fishingService = fishing,
            shadowEvaluator = shadow,
        )

        scoped.runOne(7L)

        Mockito.verify(decisions, Mockito.times(1)).select(7L)
        Mockito.verify(runtime, Mockito.times(1))
            .advanceAppliedActionToPreparedFollowup(anyExecution(), anyStoredAction())
        Mockito.verify(runtime, Mockito.times(2)).beginSubmission(anyExecution())
        Mockito.verify(lifecycle, Mockito.times(1)).prepare(Mockito.eq(7L), Mockito.eq(15L), anyPreparedAction())
        if (mode == AutomationConvergenceMode.ACTIVE) {
            Mockito.verify(convergence, Mockito.times(2)).prepare(Mockito.eq(7L), anyConvergenceSelection())
            val projectionOrder = Mockito.inOrder(startManaged, catchManaged, convergence)
            projectionOrder.verify(startManaged).applyPolicyAcceptedExecution(anyTypedExecution())
            projectionOrder.verify(convergence).record(Mockito.eq(201L), anyConvergenceEvidence())
            projectionOrder.verify(catchManaged).applyPolicyAcceptedExecution(anyTypedExecution())
            projectionOrder.verify(convergence).record(Mockito.eq(202L), anyConvergenceEvidence())
            Mockito.verify(startManaged, Mockito.never()).applyLegacyExecution(anyTypedExecution())
            Mockito.verify(catchManaged, Mockito.never()).applyLegacyExecution(anyTypedExecution())
        } else {
            Mockito.verify(startManaged).applyLegacyExecution(anyTypedExecution())
            Mockito.verify(catchManaged).applyLegacyExecution(anyTypedExecution())
            Mockito.verify(startManaged, Mockito.never()).applyPolicyAcceptedExecution(anyTypedExecution())
            Mockito.verify(catchManaged, Mockito.never()).applyPolicyAcceptedExecution(anyTypedExecution())
            // 기존 미관측 결과의 늦은 귀속은 허용하지만 새 ACTIVE 선택은 만들지 않는다.
            Mockito.verify(convergence, Mockito.never()).prepare(Mockito.eq(7L), anyConvergenceSelection())
            Mockito.verify(convergence, Mockito.never()).record(Mockito.anyLong(), anyConvergenceEvidence())
        }
        if (mode == AutomationConvergenceMode.SHADOW) {
            Mockito.verify(shadow, Mockito.times(2)).observe(
                Mockito.eq(7L), Mockito.anyString() ?: "", anyConvergenceEvidence(),
                Mockito.eq(LegacyConvergenceDecision.APPLIED) ?: LegacyConvergenceDecision.APPLIED,
            )
        } else {
            Mockito.verifyNoInteractions(shadow)
        }
        assertIs<TypedRuntimeOutcome.ActionSucceeded>(capturedOutcome())
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(AutomationConvergenceMode::class)
    fun `START에서 발견한 방해 전투는 시작 성공으로 귀속하지 않고 새 판단으로 넘긴다`(mode: AutomationConvergenceMode) {
        val fishing = Mockito.mock(FishingService::class.java)
        val convergence = Mockito.mock(AutomationActionConvergenceModule::class.java)
        val shadow = Mockito.mock(AutomationConvergenceShadowEvaluator::class.java)
        val startManaged = Mockito.mock(ManagedFishingAutomationAction::class.java)
        val startStored = fishingStartStored()
        val startPrepared = executionRight(
            TypedRuntimeCheckpoint(startStored, TypedRuntimeCheckpointPhase.PREPARED, null, null),
        )
        val response = fishingResponse(FishingPrimaryAction.NONE, 17, FishingOutcome.CAUGHT).copy(
            availableActions = emptySet(), blockedByBattle = true,
            battleTarget = FishingBattleTargetResponse("battle_map", "fish-monster", "낚시터 괴물"),
        )
        val direct = fishingExecution(response)
        val action = fishingStartAction()
        Mockito.`when`(decisions.select(7L)).thenReturn(AutomationCoordination.Runnable(15L, action, emptyList()))
        Mockito.`when`(lifecycle.describe(action)).thenReturn(fishingDescriptor("START"))
        Mockito.`when`(lifecycle.prepare(7L, 15L, action)).thenReturn(startManaged)
        Mockito.`when`(startManaged.storedAction).thenReturn(startStored)
        Mockito.`when`(startManaged.descriptor).thenReturn(fishingDescriptor("START"))
        Mockito.`when`(startManaged.observeDirectResponse(response)).thenReturn(direct)
        Mockito.`when`(runtime.persistPrepared(freshExecution, startStored, emptyList()))
            .thenReturn(TypedRuntimePreparation.Ready(startPrepared))
        Mockito.`when`(runtime.beginSubmission(startPrepared)).thenReturn(TypedRuntimeSubmission.Started(Instant.EPOCH))
        Mockito.`when`(convergence.prepare(Mockito.eq(7L), anyConvergenceSelection())).thenAnswer { ConvergenceDirective.Submit(211L, it.getArgument(1)) }
        Mockito.`when`(convergence.record(Mockito.anyLong(), anyConvergenceEvidence())).thenReturn(ConvergenceDirective.ContinueSelection)
        Mockito.`when`(fishing.executeOneCastForAutomation(Mockito.eq(7L), anyFishingObservation(), anyTownBoundary(), anyBeforeCatch())).thenAnswer { invocation ->
            FishingOneCastRemoteResult.WaitingForCatch(response)
        }
        val scoped = buildRunner(
            preflight, runtime, decisions, wakeup, sharedCooldowns, lifecycle, submissionGate,
            convergenceModule = convergence,
            convergenceSelectionFactory = StoredActionConvergenceSelectionFactory(),
            evidenceInterpreter = productionEvidenceInterpreter,
            rollout = AutomationConvergenceRollout(AutomationConvergenceProperties(mode = mode)),
            fishingService = fishing, shadowEvaluator = shadow,
        )

        scoped.runOne(7L)

        Mockito.verify(decisions).select(7L)
        Mockito.verify(runtime, Mockito.never()).advanceAppliedActionToPreparedFollowup(anyExecution(), anyStoredAction())
        Mockito.verify(startManaged, Mockito.never()).applyPolicyAcceptedExecution(anyTypedExecution())
        Mockito.verify(startManaged, Mockito.never()).applyLegacyExecution(anyTypedExecution())
        val order = Mockito.inOrder(startManaged, convergence, runtime)
        order.verify(startManaged).applyPolicyResolvedExecution(Mockito.eq(direct) ?: direct, anyConvergenceEvidence())
        if (mode == AutomationConvergenceMode.ACTIVE) {
            order.verify(convergence).record(Mockito.eq(211L), anyConvergenceEvidence())
        } else {
            Mockito.verifyNoInteractions(convergence)
        }
        val outcome = assertIs<TypedRuntimeOutcome.ActionSuperseded>(capturedOutcome())
        assertEquals("ACTION_SUPERSEDED_BY_FRESH_STATE", outcome.wakeReason)
        if (mode == AutomationConvergenceMode.SHADOW) {
            val (evidence, legacy) = shadowObservation(shadow, startStored.executionIdentity)
            assertIs<AutomationActionEvidence.StateAdvanced>(evidence)
            assertEquals(LegacyConvergenceDecision.SUPERSEDED, legacy)
        } else {
            Mockito.verifyNoInteractions(shadow)
        }
    }

    @Test
    fun `다음 판단의 CATCH는 선택 GET 관측을 재사용해 generic 사전 GET 없이 제출한다`() {
        val observation = Mockito.mock(app.spammy.hof.town.fishing.service.FishingAutomationObservation::class.java)
        val catchManaged = Mockito.mock(ManagedFishingAutomationAction::class.java)
        val catchStored = StoredTypedAutomationAction(
            15L,
            "catch-observed-1",
            StoredTypedActionPayload.FishingTown(
                FishingAction.CATCH,
                FishingPrimaryAction.CATCH,
                17,
                progressDate = LocalDate.parse("2026-08-24"),
            ),
        )
        val catchPrepared = executionRight(
            TypedRuntimeCheckpoint(catchStored, TypedRuntimeCheckpointPhase.PREPARED, null, null),
        )
        val response = fishingResponse(FishingPrimaryAction.START, 16, FishingOutcome.CAUGHT)
        val evidence = Mockito.mock(TypedAutomationExecution.ActionCompleted::class.java)
        val action = FishingTownAutomationAction(
            7L,
            FishingAction.CATCH,
            FishingPrimaryAction.CATCH,
            17,
            LocalDate.parse("2026-08-24"),
            observation,
        )
        Mockito.`when`(decisions.select(7L)).thenReturn(AutomationCoordination.Runnable(15L, action, emptyList()))
        Mockito.`when`(lifecycle.describe(action)).thenReturn(fishingDescriptor("CATCH"))
        Mockito.`when`(lifecycle.prepare(7L, 15L, action)).thenReturn(catchManaged)
        Mockito.`when`(catchManaged.storedAction).thenReturn(catchStored)
        Mockito.`when`(catchManaged.descriptor).thenReturn(fishingDescriptor("CATCH"))
        Mockito.`when`(catchManaged.cycleObservation).thenReturn(observation)
        Mockito.`when`(catchManaged.executeObservedResponse()).thenReturn(FishingDirectExecution(response, evidence))
        Mockito.`when`(catchManaged.applyLegacyExecution(evidence)).thenReturn(evidence)
        Mockito.`when`(runtime.persistPrepared(freshExecution, catchStored, emptyList()))
            .thenReturn(TypedRuntimePreparation.Ready(catchPrepared))
        Mockito.`when`(runtime.beginSubmission(catchPrepared)).thenReturn(TypedRuntimeSubmission.Started(Instant.EPOCH))
        val scoped = buildRunner(
            preflight,
            runtime,
            decisions,
            wakeup,
            sharedCooldowns,
            lifecycle,
            submissionGate,
        )

        scoped.runOne(7L)

        Mockito.verify(catchManaged).executeObservedResponse()
        Mockito.verify(catchManaged, Mockito.never()).validateBeforeSubmission()
        Mockito.verify(catchManaged, Mockito.never()).execute()
        assertIs<TypedRuntimeOutcome.ActionSucceeded>(capturedOutcome())
    }

    @Test
    fun `ACTIVE 낚시 CATCH의 로컬 projection 실패는 적용 evidence를 먼저 종결하지 않는다`() {
        val observation = Mockito.mock(app.spammy.hof.town.fishing.service.FishingAutomationObservation::class.java)
        val convergence = Mockito.mock(AutomationActionConvergenceModule::class.java)
        val catchManaged = Mockito.mock(ManagedFishingAutomationAction::class.java)
        val catchStored = StoredTypedAutomationAction(
            15L,
            "catch-projection-failure",
            StoredTypedActionPayload.FishingTown(
                FishingAction.CATCH,
                FishingPrimaryAction.CATCH,
                17,
                progressDate = LocalDate.parse("2026-08-24"),
            ),
        )
        val catchPrepared = executionRight(
            TypedRuntimeCheckpoint(catchStored, TypedRuntimeCheckpointPhase.PREPARED, null, null),
        )
        val response = fishingResponse(FishingPrimaryAction.START, 16, FishingOutcome.CAUGHT)
        val direct = fishingExecution(response)
        val action = FishingTownAutomationAction(
            7L,
            FishingAction.CATCH,
            FishingPrimaryAction.CATCH,
            17,
            LocalDate.parse("2026-08-24"),
            observation,
        )
        Mockito.`when`(decisions.select(7L)).thenReturn(AutomationCoordination.Runnable(15L, action, emptyList()))
        Mockito.`when`(lifecycle.describe(action)).thenReturn(fishingDescriptor("CATCH"))
        Mockito.`when`(lifecycle.prepare(7L, 15L, action)).thenReturn(catchManaged)
        Mockito.`when`(catchManaged.storedAction).thenReturn(catchStored)
        Mockito.`when`(catchManaged.descriptor).thenReturn(fishingDescriptor("CATCH"))
        Mockito.`when`(catchManaged.cycleObservation).thenReturn(observation)
        Mockito.`when`(catchManaged.executeObservedResponse()).thenReturn(FishingDirectExecution(response, direct))
        Mockito.doThrow(IllegalStateException("local projection failed"))
            .`when`(catchManaged)
            .applyPolicyAcceptedExecution(direct)
        Mockito.`when`(runtime.persistPrepared(freshExecution, catchStored, emptyList()))
            .thenReturn(TypedRuntimePreparation.Ready(catchPrepared))
        Mockito.`when`(runtime.beginSubmission(catchPrepared)).thenReturn(TypedRuntimeSubmission.Started(Instant.EPOCH))
        Mockito.`when`(convergence.prepare(Mockito.eq(7L), anyConvergenceSelection()))
            .thenAnswer { ConvergenceDirective.Submit(205L, it.getArgument(1)) }
        Mockito.`when`(convergence.record(Mockito.eq(205L), anyConvergenceEvidence()))
            .thenReturn(ConvergenceDirective.ContinueSelection)
        val scoped = buildRunner(
            preflight,
            runtime,
            decisions,
            wakeup,
            sharedCooldowns,
            lifecycle,
            submissionGate,
            convergenceModule = convergence,
            convergenceSelectionFactory = StoredActionConvergenceSelectionFactory(),
            timeProvider = TimeProvider { Instant.EPOCH },
            evidenceInterpreter = productionEvidenceInterpreter,
        )

        scoped.runOne(7L)

        assertIs<AutomationActionEvidence.IncompleteObservation>(convergenceEvidence(convergence, 205L))
        assertIs<TypedRuntimeOutcome.SubmissionAmbiguous>(capturedOutcome())
    }

    @Test
    fun `프로세스 재개로 관측 form을 잃은 저장 CATCH는 POST하지 않고 최신 판단으로 넘긴다`() {
        val convergence = Mockito.mock(AutomationActionConvergenceModule::class.java)
        val convergenceFactory = StoredActionConvergenceSelectionFactory()
        val catchManaged = Mockito.mock(ManagedFishingAutomationAction::class.java)
        val stored = StoredTypedAutomationAction(
            15L,
            "catch-restored",
            StoredTypedActionPayload.FishingTown(FishingAction.CATCH, FishingPrimaryAction.CATCH, 17),
        ).withRecordedPolicy()
        val prepared = executionRight(
            TypedRuntimeCheckpoint(stored, TypedRuntimeCheckpointPhase.PREPARED, null, null),
        )
        Mockito.`when`(runtime.acquire(7L)).thenReturn(TypedRuntimeAcquisition.Acquired(prepared))
        Mockito.`when`(lifecycle.restoreVerified(stored, 7L)).thenReturn(catchManaged)
        Mockito.`when`(catchManaged.storedAction).thenReturn(stored)
        Mockito.`when`(catchManaged.descriptor).thenReturn(fishingDescriptor("CATCH"))
        Mockito.`when`(catchManaged.cycleObservation).thenReturn(null)
        val scoped = buildRunner(
            preflight,
            runtime,
            decisions,
            wakeup,
            sharedCooldowns,
            lifecycle,
            submissionGate,
            convergenceModule = convergence,
            convergenceSelectionFactory = convergenceFactory,
            timeProvider = TimeProvider { Instant.EPOCH },
            rollout = AutomationConvergenceRollout(
                AutomationConvergenceProperties(mode = AutomationConvergenceMode.ACTIVE),
            ),
        )

        scoped.runOne(7L)

        assertIs<TypedRuntimeOutcome.ActionSuperseded>(capturedOutcome())
        Mockito.verify(convergence).discardUnsubmitted(
            7L,
            convergenceFactory.create(stored),
            Instant.EPOCH,
            "FISHING_OBSERVATION_LOST_BEFORE_SUBMISSION",
        )
        Mockito.verify(catchManaged, Mockito.never()).validateBeforeSubmission()
        Mockito.verify(catchManaged, Mockito.never()).execute()
        Mockito.verify(catchManaged, Mockito.never()).executeObservedResponse()
        Mockito.verifyNoInteractions(decisions)
    }

    @Test
    fun `프로세스 재개로 관측 form을 잃은 저장 START는 fallback GET 없이 최신 판단으로 넘긴다`() {
        val fishing = Mockito.mock(FishingService::class.java)
        val startManaged = Mockito.mock(ManagedFishingAutomationAction::class.java)
        val stored = fishingStartStored()
        val prepared = executionRight(
            TypedRuntimeCheckpoint(stored, TypedRuntimeCheckpointPhase.PREPARED, null, null),
        )
        Mockito.`when`(runtime.acquire(7L)).thenReturn(TypedRuntimeAcquisition.Acquired(prepared))
        Mockito.`when`(lifecycle.restoreVerified(stored, 7L)).thenReturn(startManaged)
        Mockito.`when`(startManaged.storedAction).thenReturn(stored)
        Mockito.`when`(startManaged.descriptor).thenReturn(fishingDescriptor("START"))
        Mockito.`when`(startManaged.cycleObservation).thenReturn(null)
        val scoped = buildRunner(
            preflight,
            runtime,
            decisions,
            wakeup,
            sharedCooldowns,
            lifecycle,
            submissionGate,
            fishingService = fishing,
        )

        scoped.runOne(7L)

        assertIs<TypedRuntimeOutcome.ActionSuperseded>(capturedOutcome())
        Mockito.verifyNoInteractions(fishing, decisions)
    }

    @Test
    fun `관측된 CATCH POST의 503은 같은 identity를 예약 재실행하지 않고 불명확 처리한다`() {
        val observation = Mockito.mock(app.spammy.hof.town.fishing.service.FishingAutomationObservation::class.java)
        val catchManaged = Mockito.mock(ManagedFishingAutomationAction::class.java)
        val catchStored = StoredTypedAutomationAction(
            15L,
            "catch-observed-503",
            StoredTypedActionPayload.FishingTown(FishingAction.CATCH, FishingPrimaryAction.CATCH, 17),
        )
        val catchPrepared = executionRight(
            TypedRuntimeCheckpoint(catchStored, TypedRuntimeCheckpointPhase.PREPARED, null, null),
        )
        val action = FishingTownAutomationAction(
            7L,
            FishingAction.CATCH,
            FishingPrimaryAction.CATCH,
            17,
            observation = observation,
        )
        Mockito.`when`(decisions.select(7L)).thenReturn(AutomationCoordination.Runnable(15L, action, emptyList()))
        Mockito.`when`(lifecycle.describe(action)).thenReturn(fishingDescriptor("CATCH"))
        Mockito.`when`(lifecycle.prepare(7L, 15L, action)).thenReturn(catchManaged)
        Mockito.`when`(catchManaged.storedAction).thenReturn(catchStored)
        Mockito.`when`(catchManaged.descriptor).thenReturn(fishingDescriptor("CATCH"))
        Mockito.`when`(catchManaged.cycleObservation).thenReturn(observation)
        Mockito.`when`(catchManaged.executeObservedResponse()).thenThrow(
            HofAutomationDeferredException(Instant.EPOCH.plusSeconds(5), 1, requestAttempted = true),
        )
        Mockito.`when`(runtime.persistPrepared(freshExecution, catchStored, emptyList()))
            .thenReturn(TypedRuntimePreparation.Ready(catchPrepared))
        Mockito.`when`(runtime.beginSubmission(catchPrepared)).thenReturn(TypedRuntimeSubmission.Started(Instant.EPOCH))
        val scoped = buildRunner(
            preflight,
            runtime,
            decisions,
            wakeup,
            sharedCooldowns,
            lifecycle,
            submissionGate,
        )

        scoped.runOne(7L)

        Mockito.verify(catchManaged, Mockito.times(1)).executeObservedResponse()
        assertIs<TypedRuntimeOutcome.SubmissionAmbiguous>(capturedOutcome())
    }

    @Test
    fun `ACTIVE 관측 CATCH가 중간 mutation으로 무효화되면 수렴 시도를 종료하고 새 판단으로 넘긴다`() {
        val observation = Mockito.mock(app.spammy.hof.town.fishing.service.FishingAutomationObservation::class.java)
        val convergence = Mockito.mock(AutomationActionConvergenceModule::class.java)
        val catchManaged = Mockito.mock(ManagedFishingAutomationAction::class.java)
        val stored = StoredTypedAutomationAction(
            15L,
            "catch-invalidated",
            StoredTypedActionPayload.FishingTown(FishingAction.CATCH, FishingPrimaryAction.CATCH, 17),
        )
        val prepared = executionRight(
            TypedRuntimeCheckpoint(stored, TypedRuntimeCheckpointPhase.PREPARED, null, null),
        )
        val action = FishingTownAutomationAction(
            7L,
            FishingAction.CATCH,
            FishingPrimaryAction.CATCH,
            17,
            observation = observation,
        )
        Mockito.`when`(decisions.select(7L)).thenReturn(AutomationCoordination.Runnable(15L, action, emptyList()))
        Mockito.`when`(lifecycle.describe(action)).thenReturn(fishingDescriptor("CATCH"))
        Mockito.`when`(lifecycle.prepare(7L, 15L, action)).thenReturn(catchManaged)
        Mockito.`when`(catchManaged.storedAction).thenReturn(stored)
        Mockito.`when`(catchManaged.descriptor).thenReturn(fishingDescriptor("CATCH"))
        Mockito.`when`(catchManaged.cycleObservation).thenReturn(observation)
        Mockito.`when`(catchManaged.executeObservedResponse()).thenThrow(AccountHofObservationInvalidatedException())
        Mockito.`when`(runtime.persistPrepared(freshExecution, stored, emptyList()))
            .thenReturn(TypedRuntimePreparation.Ready(prepared))
        Mockito.`when`(runtime.beginSubmission(prepared)).thenReturn(TypedRuntimeSubmission.Started(Instant.EPOCH))
        Mockito.`when`(convergence.prepare(Mockito.eq(7L), anyConvergenceSelection()))
            .thenAnswer { ConvergenceDirective.Submit(205L, it.getArgument(1)) }
        Mockito.`when`(convergence.record(Mockito.eq(205L), anyConvergenceEvidence()))
            .thenReturn(ConvergenceDirective.ContinueSelection)
        val scoped = buildRunner(
            preflight,
            runtime,
            decisions,
            wakeup,
            sharedCooldowns,
            lifecycle,
            submissionGate,
            convergenceModule = convergence,
            convergenceSelectionFactory = StoredActionConvergenceSelectionFactory(),
            rollout = AutomationConvergenceRollout(
                AutomationConvergenceProperties(mode = AutomationConvergenceMode.ACTIVE),
            ),
        )

        scoped.runOne(7L)

        assertIs<TypedRuntimeOutcome.ActionSuperseded>(capturedOutcome())
        assertIs<AutomationActionEvidence.StateAdvanced>(convergenceEvidence(convergence, 205L))
        Mockito.verify(catchManaged, Mockito.times(1)).executeObservedResponse()
    }

    @Test
    fun `낚시 START의 503 응답은 같은 POST를 재시도하지 않고 불명확 checkpoint로 남긴다`() {
        val retryAt = Instant.parse("2026-08-24T00:00:05Z")
        val fishing = Mockito.mock(FishingService::class.java)
        val startManaged = Mockito.mock(ManagedFishingAutomationAction::class.java)
        val startStored = fishingStartStored()
        val startPrepared = executionRight(
            TypedRuntimeCheckpoint(startStored, TypedRuntimeCheckpointPhase.PREPARED, null, null),
        )
        val action = fishingStartAction()
        Mockito.`when`(decisions.select(7L)).thenReturn(AutomationCoordination.Runnable(15L, action, emptyList()))
        Mockito.`when`(lifecycle.describe(action)).thenReturn(fishingDescriptor("START"))
        Mockito.`when`(lifecycle.prepare(7L, 15L, action)).thenReturn(startManaged)
        Mockito.`when`(startManaged.storedAction).thenReturn(startStored)
        Mockito.`when`(startManaged.descriptor).thenReturn(fishingDescriptor("START"))
        Mockito.`when`(runtime.persistPrepared(freshExecution, startStored, emptyList()))
            .thenReturn(TypedRuntimePreparation.Ready(startPrepared))
        Mockito.`when`(runtime.beginSubmission(startPrepared)).thenReturn(TypedRuntimeSubmission.Started(Instant.EPOCH))
        Mockito.`when`(fishing.executeOneCastForAutomation(Mockito.eq(7L), anyFishingObservation(), anyTownBoundary(), anyBeforeCatch()))
            .thenThrow(HofAutomationDeferredException(retryAt, 1, requestAttempted = true))
        val scoped = buildRunner(
            preflight,
            runtime,
            decisions,
            wakeup,
            sharedCooldowns,
            lifecycle,
            submissionGate,
            timeProvider = TimeProvider { Instant.EPOCH.plusSeconds(1) },
            fishingService = fishing,
        )

        scoped.runOne(7L)

        Mockito.verify(fishing, Mockito.times(1)).executeOneCastForAutomation(Mockito.eq(7L), anyFishingObservation(), anyTownBoundary(), anyBeforeCatch())
        assertIs<TypedRuntimeOutcome.SubmissionAmbiguous>(capturedOutcome())
        Mockito.verify(runtime, Mockito.never()).advanceAppliedActionToPreparedFollowup(anyExecution(), anyStoredAction())
    }

    @Test
    fun `낚시 START form 불일치는 결과 불명이 아니라 새 판단으로 넘긴다`() {
        val fishing = Mockito.mock(FishingService::class.java)
        val startManaged = Mockito.mock(ManagedFishingAutomationAction::class.java)
        val startStored = fishingStartStored()
        val startPrepared = executionRight(
            TypedRuntimeCheckpoint(startStored, TypedRuntimeCheckpointPhase.PREPARED, null, null),
        )
        val action = fishingStartAction()
        Mockito.`when`(decisions.select(7L)).thenReturn(AutomationCoordination.Runnable(15L, action, emptyList()))
        Mockito.`when`(lifecycle.describe(action)).thenReturn(fishingDescriptor("START"))
        Mockito.`when`(lifecycle.prepare(7L, 15L, action)).thenReturn(startManaged)
        Mockito.`when`(startManaged.storedAction).thenReturn(startStored)
        Mockito.`when`(startManaged.descriptor).thenReturn(fishingDescriptor("START"))
        Mockito.`when`(runtime.persistPrepared(freshExecution, startStored, emptyList()))
            .thenReturn(TypedRuntimePreparation.Ready(startPrepared))
        Mockito.`when`(runtime.beginSubmission(startPrepared)).thenReturn(TypedRuntimeSubmission.Started(Instant.EPOCH))
        Mockito.`when`(fishing.executeOneCastForAutomation(Mockito.eq(7L), anyFishingObservation(), anyTownBoundary(), anyBeforeCatch()))
            .thenThrow(ObservedTownActionPreconditionChangedException("낚시 START form 변경"))
        val scoped = buildRunner(
            preflight,
            runtime,
            decisions,
            wakeup,
            sharedCooldowns,
            lifecycle,
            submissionGate,
            fishingService = fishing,
        )

        scoped.runOne(7L)

        assertIs<TypedRuntimeOutcome.ActionSuperseded>(capturedOutcome())
        Mockito.verify(fishing).executeOneCastForAutomation(Mockito.eq(7L), anyFishingObservation(), anyTownBoundary(), anyBeforeCatch())
        Mockito.verify(runtime, Mockito.never()).advanceAppliedActionToPreparedFollowup(anyExecution(), anyStoredAction())
    }

    @Test
    fun `낚시 START 요청 전 cooldown 거절은 불명확 처리하지 않고 같은 prepared 행동을 예약한다`() {
        val retryAt = Instant.parse("2026-08-24T00:00:05Z")
        val fishing = Mockito.mock(FishingService::class.java)
        val convergence = Mockito.mock(AutomationActionConvergenceModule::class.java)
        val startManaged = Mockito.mock(ManagedFishingAutomationAction::class.java)
        val startStored = fishingStartStored()
        val startPrepared = executionRight(
            TypedRuntimeCheckpoint(startStored, TypedRuntimeCheckpointPhase.PREPARED, null, null),
        )
        val action = fishingStartAction()
        Mockito.`when`(decisions.select(7L)).thenReturn(AutomationCoordination.Runnable(15L, action, emptyList()))
        Mockito.`when`(lifecycle.describe(action)).thenReturn(fishingDescriptor("START"))
        Mockito.`when`(lifecycle.prepare(7L, 15L, action)).thenReturn(startManaged)
        Mockito.`when`(startManaged.storedAction).thenReturn(startStored)
        Mockito.`when`(startManaged.descriptor).thenReturn(fishingDescriptor("START"))
        Mockito.`when`(runtime.persistPrepared(freshExecution, startStored, emptyList()))
            .thenReturn(TypedRuntimePreparation.Ready(startPrepared))
        Mockito.`when`(runtime.beginSubmission(startPrepared)).thenReturn(TypedRuntimeSubmission.Started(Instant.EPOCH))
        Mockito.`when`(fishing.executeOneCastForAutomation(Mockito.eq(7L), anyFishingObservation(), anyTownBoundary(), anyBeforeCatch()))
            .thenThrow(HofAutomationDeferredException(retryAt, 1, requestAttempted = false))
        Mockito.`when`(convergence.prepare(Mockito.eq(7L), anyConvergenceSelection()))
            .thenAnswer { ConvergenceDirective.Submit(204L, it.getArgument(1)) }
        val convergenceFactory = StoredActionConvergenceSelectionFactory()
        val scoped = buildRunner(
            preflight,
            runtime,
            decisions,
            wakeup,
            sharedCooldowns,
            lifecycle,
            submissionGate,
            convergenceModule = convergence,
            convergenceSelectionFactory = convergenceFactory,
            timeProvider = TimeProvider { Instant.EPOCH },
            rollout = AutomationConvergenceRollout(
                AutomationConvergenceProperties(mode = AutomationConvergenceMode.ACTIVE),
            ),
            fishingService = fishing,
        )

        scoped.runOne(7L)

        assertIs<TypedRuntimeOutcome.SubmissionDeferred>(capturedOutcome())
        Mockito.verify(fishing, Mockito.times(1)).executeOneCastForAutomation(Mockito.eq(7L), anyFishingObservation(), anyTownBoundary(), anyBeforeCatch())
        Mockito.verify(convergence).discardUnsubmitted(
            7L,
            convergenceFactory.create(startStored),
            Instant.EPOCH,
            "SUBMISSION_NOT_ATTEMPTED",
        )
    }

    @Test
    fun `불명확한 낚시 START 재개는 저장 POST를 보내지 않고 최신 상태만 확인한다`() {
        val fishing = Mockito.mock(FishingService::class.java)
        val startManaged = Mockito.mock(ManagedFishingAutomationAction::class.java)
        val startStored = fishingStartStored()
        val reconciling = executionRight(
            TypedRuntimeCheckpoint(
                startStored,
                TypedRuntimeCheckpointPhase.RECONCILING,
                submittedAt = Instant.EPOCH,
                diagnostic = "503 after START",
            ),
        )
        Mockito.`when`(runtime.acquire(7L)).thenReturn(TypedRuntimeAcquisition.Acquired(reconciling))
        Mockito.`when`(lifecycle.restoreVerified(startStored, 7L)).thenReturn(startManaged)
        Mockito.`when`(startManaged.storedAction).thenReturn(startStored)
        Mockito.`when`(startManaged.descriptor).thenReturn(fishingDescriptor("START"))
        Mockito.`when`(startManaged.reconcile()).thenReturn(
            AmbiguousActionResolution.Held("아직 START 화면이라 낚시만 보류합니다."),
        )
        val scoped = buildRunner(
            preflight,
            runtime,
            decisions,
            wakeup,
            sharedCooldowns,
            lifecycle,
            submissionGate,
            timeProvider = TimeProvider { Instant.EPOCH.plusSeconds(1) },
            fishingService = fishing,
        )

        scoped.runOne(7L)

        Mockito.verify(startManaged).reconcile()
        Mockito.verify(fishing, Mockito.never()).executeOneCastForAutomation(Mockito.eq(7L), anyFishingObservation(), anyTownBoundary(), anyBeforeCatch())
        assertIs<TypedRuntimeOutcome.AmbiguousHandoff>(capturedOutcome())
    }

    @Test
    fun `실제 selector는 실행 중 quest가 작업 경계에 도달하기 전에 높은 raid로 건너뛰지 않는다`() {
        val observedAt = Instant.parse("2026-08-24T00:00:00Z")
        var currentTime = observedAt
        val account = HofAccountEntity(7L, "runner-progress", "encrypted", observedAt)
        val raidEntry = AutomationEntryEntity(21L, account, AutomationType.RAID, 1, true, observedAt, observedAt)
        val questEntry = AutomationEntryEntity(22L, account, AutomationType.QUEST, 4, true, observedAt, observedAt)
        val adventureEntry = AutomationEntryEntity(
            23L,
            account,
            AutomationType.ADVENTURE_MAP,
            5,
            true,
            observedAt,
            observedAt,
        )
        val sessions = linkedMapOf(
            31L to AutomationWorkSessionView(
                id = 31L,
                accountId = 7L,
                entryId = questEntry.id,
                entryPriority = questEntry.priority,
                workType = AutomationWorkType.QUEST,
                targetKey = "quest-1",
                status = AutomationWorkStatus.RUNNING,
                materialName = null,
                nextCheckAt = null,
                revision = 7,
            ),
            32L to AutomationWorkSessionView(
                id = 32L,
                accountId = 7L,
                entryId = raidEntry.id,
                entryPriority = raidEntry.priority,
                workType = AutomationWorkType.RAID,
                targetKey = "RaidGoblin",
                status = AutomationWorkStatus.WAITING_COOLDOWN,
                materialName = null,
                nextCheckAt = observedAt,
                revision = 3,
            ),
        )
        val workQueries = Mockito.mock(AutomationWorkSessionQueryRepository::class.java)
        Mockito.`when`(workQueries.findRunning(7L)).thenAnswer {
            sessions.values.singleOrNull { it.status == AutomationWorkStatus.RUNNING }
        }
        Mockito.`when`(workQueries.findWaiting(7L)).thenAnswer {
            sessions.values.filter { it.status != AutomationWorkStatus.RUNNING }
        }
        val workLifecycle = Mockito.mock(AutomationWorkLifecycle::class.java)
        Mockito.`when`(workLifecycle.handoffForPriority(7L, 31L, 32L)).thenAnswer {
            sessions[31L] = requireNotNull(sessions[31L]).copy(
                status = AutomationWorkStatus.YIELDED_PRIORITY,
                nextCheckAt = observedAt.plusSeconds(10),
                revision = 8,
            )
            sessions[32L] = requireNotNull(sessions[32L]).copy(
                status = AutomationWorkStatus.RUNNING,
                nextCheckAt = null,
                revision = 4,
            )
            true
        }
        Mockito.doAnswer { invocation ->
            val sessionId = invocation.arguments[1] as Long
            val nextCheckAt = invocation.arguments[2] as Instant
            sessions[sessionId] = requireNotNull(sessions[sessionId]).copy(
                status = AutomationWorkStatus.WAITING_COOLDOWN,
                nextCheckAt = nextCheckAt,
                revision = requireNotNull(sessions[sessionId]).revision + 1,
            )
            null
        }.`when`(workLifecycle).waitForCooldown(Mockito.eq(7L), Mockito.anyLong(), anyInstantValue())
        Mockito.doAnswer { invocation ->
            val sessionId = invocation.arguments[1] as Long
            sessions[sessionId] = requireNotNull(sessions[sessionId]).copy(
                status = AutomationWorkStatus.RUNNING,
                nextCheckAt = null,
                revision = requireNotNull(sessions[sessionId]).revision + 1,
            )
            null
        }.`when`(workLifecycle).resumeForCheck(7L, 31L)

        val typedQueries = Mockito.mock(TypedAutomationQueryRepository::class.java)
        Mockito.`when`(typedQueries.findEntries(7L)).thenReturn(listOf(raidEntry, questEntry, adventureEntry))
        val snapshotLoader = Mockito.mock(TypedAutomationSnapshotLoader::class.java)
        val questSnapshot = AutomationEntrySnapshot(
            questEntry.id,
            AutomationType.QUEST,
            quest = QuestAutomationSnapshot(
                accountId = 7L,
                quests = emptyList(),
                selections = emptyList(),
                mapStates = emptyList(),
                currentCycles = emptyMap(),
                counters = emptyMap(),
                mapIdentityCandidates = emptyList(),
                now = observedAt,
            ),
        )
        Mockito.`when`(snapshotLoader.loadEntry(7L, questEntry.id, null, null)).thenReturn(questSnapshot)
        Mockito.`when`(snapshotLoader.loadEntry(7L, questEntry.id, "quest-1", null)).thenReturn(questSnapshot)
        Mockito.`when`(snapshotLoader.loadEntry(7L, adventureEntry.id, null, null)).thenReturn(
            AutomationEntrySnapshot(
                adventureEntry.id,
                AutomationType.ADVENTURE_MAP,
                adventure = AdventureMapAutomationSnapshot(
                    accountId = 7L,
                    settings = emptyList(),
                    mapStates = emptyList(),
                    presetResolutions = emptyMap(),
                    executionIdentities = emptyMap(),
                    evaluationInstant = observedAt,
                ),
            ),
        )
        val questRules = Mockito.mock(QuestWorkCycleModule::class.java, Mockito.CALLS_REAL_METHODS)
        val evaluatedQuestRevisions = mutableListOf<Long?>()
        val resumedQuestAction = QuestAction.Accept("quest-1", "accept-1")
        Mockito.`when`(questRules.decideNext(anyQuestSnapshotValue())).thenAnswer { invocation ->
            val snapshot = invocation.arguments[0] as QuestAutomationSnapshot
            evaluatedQuestRevisions += snapshot.workSessionRevision
            if (snapshot.workSessionId == 31L) QuestDirective.Execute(resumedQuestAction) else QuestDirective.Skip
        }
        val adventureAction = AdventureMapAutomationAction(
            accountId = 7L,
            categoryId = "adventure_map",
            mapCode = "adventure-1",
            presetMode = PresetSelectionMode.PRIMARY,
            presetId = 301L,
            settingIdentity = 41L,
            executionIdentity = "runner-adventure-1",
        )
        val raidIntent = RaidIntent.Town(
            entryId = raidEntry.id,
            raidId = "RaidGoblin",
            raidName = "고블린 레이드",
            kind = RaidIntentKind.REGISTER,
        )
        val raidRules = Mockito.mock(RaidCycleModule::class.java)
        Mockito.`when`(raidRules.decide(7L)).thenReturn(RaidDecision(RaidDirective.Execute(raidIntent)))
        val convergenceFactory = StoredActionConvergenceSelectionFactory()
        val raidPrepared = RaidTownAutomationAction(
            accountId = 7L,
            action = RaidAction.REGISTER,
            raidId = "RaidGoblin",
            targetRaidId = "RaidGoblin",
            raidName = "고블린 레이드",
        )
        val raidPreview = convergenceFactory.preview(raidEntry.id, raidPrepared)
        val heldConvergence = DefaultAutomationActionConvergenceModule(InMemoryConvergenceStore(), TimeProvider { currentTime })
        val heldAttempt = assertIs<ConvergenceDirective.Submit>(heldConvergence.prepare(7,
            SelectedAutomationAction(raidEntry.id, "held-selection", raidPreview.actionKind, raidPreview.scope,
                StoredActionConvergenceSelectionFactory.POLICY_VERSION, requireNotNull(raidPreview.baselineFingerprint)),
        )).attemptId
        heldConvergence.record(heldAttempt, AutomationActionEvidence.ResultUnobserved(currentTime, "response lost"))
        val selector = AutomationTargetSelector(
            typed = typedQueries,
            work = workQueries,
            loader = snapshotLoader,
            lifecycle = workLifecycle,
            timeProvider = TimeProvider { currentTime },
            raidModule = raidRules,
            quest = questRules,
            battle = AutomationHandler { HandlerEvaluation.Skipped },
            adventure = AutomationHandler { HandlerEvaluation.Runnable(adventureAction) },
            union = AutomationHandler { HandlerEvaluation.Skipped },
            fishing = AutomationHandler { HandlerEvaluation.Skipped },
            homeQuest = AutomationHandler { HandlerEvaluation.Skipped },
            convergenceModule = heldConvergence,
            convergenceRollout = AutomationConvergenceRollout(
                AutomationConvergenceProperties(mode = AutomationConvergenceMode.SHADOW),
            ),
        )
        val scoped = buildRunner(
            preflight,
            runtime,
            selector,
            wakeup,
            sharedCooldowns,
            lifecycle,
            submissionGate,
        )

        scoped.runOne(7L)

        Mockito.verify(workLifecycle, Mockito.never()).handoffForPriority(7L, 31L, 32L)
        Mockito.verify(workLifecycle, Mockito.never()).waitForCooldown(7L, 32L, observedAt.plusSeconds(30))
        Mockito.verify(lifecycle).prepare(7L, questEntry.id, resumedQuestAction)
        Mockito.verify(lifecycle, Mockito.never()).prepare(7L, adventureEntry.id, adventureAction)
        Mockito.verify(managed).execute()
        assertEquals(AutomationWorkStatus.RUNNING, sessions.getValue(31L).status)
        assertEquals(AutomationWorkStatus.WAITING_COOLDOWN, sessions.getValue(32L).status)
        assertTrue(7L in evaluatedQuestRevisions)
    }

    @Test
    fun `SHADOW 장애는 production row와 실제 성공 결과에 영향을 주지 않는다`() {
        val convergence = Mockito.mock(AutomationActionConvergenceModule::class.java)
        val shadow = Mockito.mock(AutomationConvergenceShadowEvaluator::class.java)
        val factory = StoredActionConvergenceSelectionFactory()
        Mockito.`when`(decisions.select(7L)).thenReturn(
            AutomationCoordination.Runnable(12L, QuestAction.Claim("quest", "claim"), emptyList()),
        )
        Mockito.doThrow(IllegalStateException("shadow selected failed"))
            .`when`(shadow)
            .selected(Mockito.eq(7L), anyConvergenceSelection())
        Mockito.doThrow(IllegalStateException("shadow observe failed"))
            .`when`(shadow)
            .observe(
                Mockito.eq(7L),
                Mockito.anyString(),
                anyConvergenceEvidence(),
                anyLegacyConvergenceDecision(),
            )
        val scoped = buildRunner(
            preflight,
            runtime,
            decisions,
            wakeup,
            sharedCooldowns,
            lifecycle,
            submissionGate,
            convergenceModule = convergence,
            convergenceSelectionFactory = factory,
            rollout = AutomationConvergenceRollout(
                AutomationConvergenceProperties(mode = AutomationConvergenceMode.SHADOW),
            ),
            shadowEvaluator = shadow,
        )

        scoped.runOne(7L)

        Mockito.verify(convergence, Mockito.never()).prepare(Mockito.anyLong(), anyConvergenceSelection())
        assertIs<TypedRuntimeOutcome.ActionSucceeded>(capturedOutcome())
    }

    @Test
    fun `SHADOW는 legacy projection 뒤 실제 재조정 판정과 policy evidence를 한 번 기록한다`() {
        val convergence = Mockito.mock(AutomationActionConvergenceModule::class.java)
        val shadow = Mockito.mock(AutomationConvergenceShadowEvaluator::class.java)
        Mockito.`when`(decisions.select(7L)).thenReturn(
            AutomationCoordination.Runnable(12L, QuestAction.Claim("quest", "claim"), emptyList()),
        )
        Mockito.`when`(managed.execute()).thenReturn(
            TypedAutomationExecution.ActionCompleted(
                observedState = QuestObservedState(
                    fingerprint = "empty-quest-response",
                    present = false,
                    state = null,
                    actionNo = null,
                ),
                responseShapeMaterial = "${ProductionEvidenceShapes.QUEST_RESPONSE}|variant=UNCLASSIFIED",
                sanitizedSnippet = "QuestResponse|targetMultiplicity=NONE|targetPresent=false",
            ),
        )
        Mockito.doThrow(AmbiguousAutomationSubmissionException("legacy result requires reconciliation"))
            .`when`(managed)
            .applyLegacyExecution(anyTypedExecution())
        val scoped = buildRunner(
            preflight,
            runtime,
            decisions,
            wakeup,
            sharedCooldowns,
            lifecycle,
            submissionGate,
            convergenceModule = convergence,
            convergenceSelectionFactory = StoredActionConvergenceSelectionFactory(),
            rollout = AutomationConvergenceRollout(
                AutomationConvergenceProperties(mode = AutomationConvergenceMode.SHADOW),
            ),
            shadowEvaluator = shadow,
            evidenceInterpreter = productionEvidenceInterpreter,
        )

        scoped.runOne(7L)

        val evidenceCaptor = ArgumentCaptor.forClass(AutomationActionEvidence::class.java)
        val decisionCaptor = ArgumentCaptor.forClass(LegacyConvergenceDecision::class.java)
        Mockito.verify(shadow).observe(
            Mockito.eq(7L),
            Mockito.eq(defaultStored.executionIdentity) ?: defaultStored.executionIdentity,
            captureConvergenceEvidence(evidenceCaptor),
            captureLegacyDecision(decisionCaptor),
        )
        assertIs<AutomationActionEvidence.IncompleteObservation>(evidenceCaptor.value)
        assertEquals(LegacyConvergenceDecision.RECONCILING, decisionCaptor.value)
        assertIs<TypedRuntimeOutcome.SubmissionAmbiguous>(capturedOutcome())
        Mockito.verify(managed).applyLegacyExecution(anyTypedExecution())
        Mockito.verify(managed, Mockito.never()).applyPolicyAcceptedExecution(anyTypedExecution())
    }

    @Test
    fun `SHADOW는 HOF deferral을 network reconciling 표본으로 남긴다`() {
        val shadow = Mockito.mock(AutomationConvergenceShadowEvaluator::class.java)
        val retryAt = Instant.parse("2026-07-25T00:01:00Z")
        Mockito.`when`(decisions.select(7L)).thenReturn(
            AutomationCoordination.Runnable(12L, QuestAction.Claim("quest", "claim"), emptyList()),
        )
        Mockito.doThrow(HofAutomationDeferredException(retryAt, 1, requestAttempted = true))
            .`when`(managed).execute()

        shadowRunner(shadow).runOne(7L)

        val (evidence, decision) = shadowObservation(shadow, defaultStored.executionIdentity)
        assertIs<AutomationActionEvidence.NetworkFailure>(evidence)
        assertEquals(LegacyConvergenceDecision.RECONCILING, decision)
        assertIs<TypedRuntimeOutcome.SubmissionAmbiguous>(capturedOutcome())
    }

    @Test
    fun `SHADOW는 미분류 제출 실패를 result unobserved 표본으로 남긴다`() {
        val shadow = Mockito.mock(AutomationConvergenceShadowEvaluator::class.java)
        Mockito.`when`(decisions.select(7L)).thenReturn(
            AutomationCoordination.Runnable(12L, QuestAction.Claim("quest", "claim"), emptyList()),
        )
        Mockito.doThrow(IllegalStateException("unexpected adapter failure")).`when`(managed).execute()

        shadowRunner(shadow).runOne(7L)

        val (evidence, decision) = shadowObservation(shadow, defaultStored.executionIdentity)
        assertIs<AutomationActionEvidence.ResultUnobserved>(evidence)
        assertEquals(LegacyConvergenceDecision.RESULT_UNOBSERVED, decision)
        assertIs<TypedRuntimeOutcome.RetryableFailure>(capturedOutcome())
    }

    @Test
    fun `SHADOW는 레이드 handoff를 incomplete reconciling 표본으로 남긴다`() {
        val shadow = Mockito.mock(AutomationConvergenceShadowEvaluator::class.java)
        val stored = raidStoredAction()
        val prepared = executionRight(
            TypedRuntimeCheckpoint(stored, TypedRuntimeCheckpointPhase.PREPARED, null, null),
        )
        Mockito.`when`(decisions.select(7L)).thenReturn(
            AutomationCoordination.Runnable(12L, legacyBattleAction(), emptyList()),
        )
        Mockito.`when`(managed.storedAction).thenReturn(stored)
        Mockito.`when`(runtime.persistPrepared(anyExecution(), anyStoredAction(), anyWarnings()))
            .thenReturn(TypedRuntimePreparation.Ready(prepared))
        Mockito.`when`(runtime.beginSubmission(prepared))
            .thenReturn(TypedRuntimeSubmission.Started(Instant.EPOCH))
        Mockito.`when`(
            managed.handoffAmbiguousSubmission(Instant.EPOCH, "전투 응답 시간 초과"),
        ).thenReturn(
            AmbiguousActionResolution.HandedOff(
                Instant.EPOCH.plusSeconds(300),
                "레이드 전투 결과 미확정",
            ),
        )
        Mockito.doThrow(AmbiguousAutomationSubmissionException("전투 응답 시간 초과"))
            .`when`(managed).execute()

        shadowRunner(shadow).runOne(7L)

        val (evidence, decision) = shadowObservation(shadow, stored.executionIdentity)
        assertIs<AutomationActionEvidence.IncompleteObservation>(evidence)
        assertEquals(LegacyConvergenceDecision.RECONCILING, decision)
        assertIs<TypedRuntimeOutcome.AmbiguousHandoff>(capturedOutcome())
    }

    @Test
    fun `unchanged reconciliation waits without replaying the stored payload`() {
        val observedAt = Instant.parse("2026-08-23T10:00:00Z")
        val stored = StoredTypedAutomationAction(
            12,
            "execution-1",
            StoredTypedActionPayload.QuestAccept("quest", "accept-no"),
        )
        val reconciling = executionRight(
            TypedRuntimeCheckpoint(
                stored,
                TypedRuntimeCheckpointPhase.RECONCILING,
                observedAt.minusSeconds(30),
                "unknown result",
                successfulObservationCount = 1,
                firstPendingAt = observedAt.minusSeconds(30),
            ),
        )
        Mockito.`when`(runtime.acquire(7)).thenReturn(TypedRuntimeAcquisition.Acquired(reconciling))
        Mockito.`when`(lifecycle.restoreVerified(stored, 7)).thenReturn(managed)
        Mockito.`when`(managed.storedAction).thenReturn(stored)
        Mockito.`when`(managed.reconcile()).thenReturn(AmbiguousActionResolution.Resubmit)

        val scoped = buildRunner(
            preflight,
            runtime,
            decisions,
            wakeup,
            sharedCooldowns,
            lifecycle,
            submissionGate,
            timeProvider = TimeProvider { observedAt },
        )

        scoped.runOne(7)

        Mockito.verify(lifecycle).restoreVerified(stored, 7)
        Mockito.verify(managed).reconcile()
        Mockito.verify(managed, Mockito.never()).execute()
        val outcome = assertIs<TypedRuntimeOutcome.ReconciliationDeferred>(capturedOutcome())
        assertEquals(observedAt.plusSeconds(10), outcome.retryAt)
        assertEquals(true, outcome.successfulObservation)
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(AutomationConvergenceMode::class, names = ["LEGACY", "SHADOW"])
    fun `외부 레이드 복구는 경고 없이 대기 이력과 대체 판정을 보존한다`(mode: AutomationConvergenceMode) {
        val stored = StoredTypedAutomationAction(12L, "external-raid-refresh",
            StoredTypedActionPayload.RaidTown(RaidAction.REFRESH, "RaidGoblin")).withRecordedPolicy()
        val reconciling = executionRight(TypedRuntimeCheckpoint(stored,
            TypedRuntimeCheckpointPhase.RECONCILING, Instant.EPOCH, "응답 유실"))
        val wait = TypedAutomationExecution.RaidWaiting(Instant.EPOCH.plusSeconds(600), "RaidGoblin",
            "RAID_EXTERNAL_CONFIGURED_ACTIVE", "다른 사용자 레이드를 다시 확인합니다.", "최신 상태 재확인")
        val journal = Mockito.mock(AutomationDecisionJournal::class.java)
        Mockito.`when`(runtime.acquire(7L)).thenReturn(TypedRuntimeAcquisition.Acquired(reconciling))
        Mockito.`when`(lifecycle.restoreVerified(stored, 7L)).thenReturn(managed)
        Mockito.`when`(managed.storedAction).thenReturn(stored)
        Mockito.`when`(managed.descriptor).thenReturn(defaultDescriptor().copy(
            source = AutomationType.RAID, storageKind = "RAID_TOWN", actionKind = "REFRESH",
            actionLabel = "상태 갱신", context = "상태 갱신 단계", targetKey = "RaidGoblin"))
        Mockito.`when`(managed.reconcile()).thenReturn(AmbiguousActionResolution.Superseded(
            wait.message, raidWait = wait, warning = null))
        val scoped = buildRunner(preflight, runtime, decisions, wakeup, sharedCooldowns, lifecycle, submissionGate,
            decisionJournal = journal, timeProvider = TimeProvider { Instant.EPOCH },
            rollout = AutomationConvergenceRollout(AutomationConvergenceProperties(mode = mode)))

        scoped.runOne(7L)

        assertNull(assertIs<TypedRuntimeOutcome.ActionSuperseded>(capturedOutcome()).warning)
        val trace = ArgumentCaptor.forClass(AutomationActionTrace::class.java)
        Mockito.verify(journal).appendActionResult(Mockito.anyLong(), trace.capture() ?: AutomationActionTrace(
            AutomationHistoryEventKind.SKIPPED, "", ""))
        assertEquals(AutomationHistoryEventKind.SKIPPED, trace.value.kind)
        assertEquals(AutomationType.RAID, trace.value.type)
        assertEquals(wait.raidId, trace.value.targetKey)
        assertEquals(wait.reasonCode, trace.value.reasonCode)
        assertEquals(wait.retryAt, trace.value.nextRunAt)
        assertEquals(wait.releaseCondition, trace.value.releaseCondition)
        Mockito.verify(managed, Mockito.never()).execute()
    }

    @Test
    fun `legacy reconciliation stops after the two minute observation budget`() {
        val observedAt = Instant.parse("2026-08-23T10:00:00Z")
        val stored = StoredTypedAutomationAction(
            12,
            "execution-budget",
            StoredTypedActionPayload.QuestAccept("quest", "accept-no"),
        )
        val reconciling = executionRight(
            TypedRuntimeCheckpoint(
                stored,
                TypedRuntimeCheckpointPhase.RECONCILING,
                observedAt.minusSeconds(121),
                "unknown result",
            ),
        )
        Mockito.`when`(runtime.acquire(7)).thenReturn(TypedRuntimeAcquisition.Acquired(reconciling))
        Mockito.`when`(lifecycle.restoreVerified(
            stored.withRecordedPolicy(StoredActionConvergenceSelectionFactory().unrecorded(stored)), 7,
        )).thenReturn(managed)
        Mockito.`when`(managed.storedAction).thenReturn(stored)
        Mockito.`when`(managed.reconcile()).thenReturn(
            AmbiguousActionResolution.VerifyLater(observedAt.plusSeconds(10), "still unchanged"),
        )
        val store = InMemoryConvergenceStore()
        val factory = StoredActionConvergenceSelectionFactory()
        val scoped = buildRunner(
            preflight,
            runtime,
            decisions,
            wakeup,
            sharedCooldowns,
            lifecycle,
            submissionGate,
            convergenceModule = DefaultAutomationActionConvergenceModule(store, TimeProvider { observedAt }),
            convergenceSelectionFactory = factory,
            timeProvider = TimeProvider { observedAt },
            rollout = AutomationConvergenceRollout(
                AutomationConvergenceProperties(mode = AutomationConvergenceMode.SHADOW),
            ),
        )

        scoped.runOne(7)

        val outcome = assertIs<TypedRuntimeOutcome.AmbiguousHandoff>(capturedOutcome())
        assertEquals("TYPED_RECONCILIATION_BUDGET_EXHAUSTED", outcome.wakeReason)
        val selection = factory.create(stored)
        assertEquals(
            setOf(selection.baselineFingerprint),
            store.findSuppressedBaselines(7L)[selection.scope],
        )
        Mockito.verify(managed, Mockito.never()).execute()
    }

    @Test
    fun `legacy reconciliation network failures stop when the two minute time budget expires`() {
        val observedAt = Instant.parse("2026-08-23T10:00:00Z")
        val retryAt = observedAt.plusSeconds(10)
        val stored = StoredTypedAutomationAction(
            12,
            "execution-network-budget",
            StoredTypedActionPayload.QuestAccept("quest", "accept-no"),
        )
        val reconciling = executionRight(
            TypedRuntimeCheckpoint(
                storedAction = stored,
                phase = TypedRuntimeCheckpointPhase.RECONCILING,
                submittedAt = observedAt.minusSeconds(121),
                diagnostic = "unknown result",
                successfulObservationCount = 0,
                firstPendingAt = observedAt.minusSeconds(121),
            ),
        )
        Mockito.`when`(runtime.acquire(7)).thenReturn(TypedRuntimeAcquisition.Acquired(reconciling))
        Mockito.`when`(lifecycle.restoreVerified(
            stored.withRecordedPolicy(StoredActionConvergenceSelectionFactory().unrecorded(stored)), 7,
        )).thenReturn(managed)
        Mockito.`when`(managed.storedAction).thenReturn(stored)
        Mockito.`when`(managed.reconcile()).thenThrow(HofAutomationDeferredException(retryAt, 1))
        val store = InMemoryConvergenceStore()
        val factory = StoredActionConvergenceSelectionFactory()
        val scoped = buildRunner(
            preflight,
            runtime,
            decisions,
            wakeup,
            sharedCooldowns,
            lifecycle,
            submissionGate,
            convergenceModule = DefaultAutomationActionConvergenceModule(store, TimeProvider { observedAt }),
            convergenceSelectionFactory = factory,
            timeProvider = TimeProvider { observedAt },
            rollout = AutomationConvergenceRollout(
                AutomationConvergenceProperties(mode = AutomationConvergenceMode.SHADOW),
            ),
        )

        scoped.runOne(7)

        val outcome = assertIs<TypedRuntimeOutcome.AmbiguousHandoff>(capturedOutcome())
        assertEquals("TYPED_RECONCILIATION_BUDGET_EXHAUSTED", outcome.wakeReason)
        val selection = factory.create(stored)
        assertEquals(
            setOf(selection.baselineFingerprint),
            store.findSuppressedBaselines(7L)[selection.scope],
        )
        Mockito.verify(managed, Mockito.never()).execute()
    }

    @Test
    fun `legacy reconciliation generic network failures stop when the two minute time budget expires`() {
        val observedAt = Instant.parse("2026-08-23T10:00:00Z")
        val stored = StoredTypedAutomationAction(
            12,
            "execution-generic-network-budget",
            StoredTypedActionPayload.QuestAccept("quest", "accept-no"),
        )
        val reconciling = executionRight(
            TypedRuntimeCheckpoint(
                storedAction = stored,
                phase = TypedRuntimeCheckpointPhase.RECONCILING,
                submittedAt = observedAt.minusSeconds(121),
                diagnostic = "unknown result",
                successfulObservationCount = 0,
                firstPendingAt = observedAt.minusSeconds(121),
            ),
        )
        Mockito.`when`(runtime.acquire(7)).thenReturn(TypedRuntimeAcquisition.Acquired(reconciling))
        Mockito.`when`(lifecycle.restoreVerified(stored, 7)).thenReturn(managed)
        Mockito.`when`(managed.storedAction).thenReturn(stored)
        Mockito.`when`(managed.reconcile()).thenThrow(
            AmbiguousAutomationSubmissionException("HOF request failed while reconciling"),
        )
        val scoped = buildRunner(
            preflight,
            runtime,
            decisions,
            wakeup,
            sharedCooldowns,
            lifecycle,
            submissionGate,
            timeProvider = TimeProvider { observedAt },
        )

        scoped.runOne(7)

        val outcome = assertIs<TypedRuntimeOutcome.AmbiguousHandoff>(capturedOutcome())
        assertEquals("TYPED_RECONCILIATION_BUDGET_EXHAUSTED", outcome.wakeReason)
        Mockito.verify(managed, Mockito.never()).execute()
    }

    @Test
    fun `legacy reconciliation stops on the fifth successful observation`() {
        val observedAt = Instant.parse("2026-08-23T10:00:00Z")
        val stored = StoredTypedAutomationAction(
            12,
            "execution-observation-budget",
            StoredTypedActionPayload.QuestAccept("quest", "accept-no"),
        )
        val reconciling = executionRight(
            TypedRuntimeCheckpoint(
                storedAction = stored,
                phase = TypedRuntimeCheckpointPhase.RECONCILING,
                submittedAt = observedAt.minusSeconds(30),
                diagnostic = "unknown result",
                successfulObservationCount = 4,
                firstPendingAt = observedAt.minusSeconds(30),
            ),
        )
        Mockito.`when`(runtime.acquire(7)).thenReturn(TypedRuntimeAcquisition.Acquired(reconciling))
        Mockito.`when`(lifecycle.restoreVerified(stored, 7)).thenReturn(managed)
        Mockito.`when`(managed.storedAction).thenReturn(stored)
        Mockito.`when`(managed.reconcile()).thenReturn(
            AmbiguousActionResolution.VerifyLater(observedAt.plusSeconds(10), "still unchanged"),
        )
        val scoped = buildRunner(
            preflight,
            runtime,
            decisions,
            wakeup,
            sharedCooldowns,
            lifecycle,
            submissionGate,
            timeProvider = TimeProvider { observedAt },
        )

        scoped.runOne(7)

        assertIs<TypedRuntimeOutcome.AmbiguousHandoff>(capturedOutcome())
        Mockito.verify(managed, Mockito.never()).execute()
    }

    @Test
    fun `legacy reconciliation defers a successful observation while budget remains`() {
        val observedAt = Instant.parse("2026-08-23T10:00:00Z")
        val retryAt = observedAt.plusSeconds(10)
        val stored = StoredTypedAutomationAction(
            12,
            "execution-with-budget",
            StoredTypedActionPayload.QuestAccept("quest", "accept-no"),
        )
        val reconciling = executionRight(
            TypedRuntimeCheckpoint(
                storedAction = stored,
                phase = TypedRuntimeCheckpointPhase.RECONCILING,
                submittedAt = observedAt.minusSeconds(30),
                diagnostic = "unknown result",
                successfulObservationCount = 3,
                firstPendingAt = observedAt.minusSeconds(30),
            ),
        )
        Mockito.`when`(runtime.acquire(7)).thenReturn(TypedRuntimeAcquisition.Acquired(reconciling))
        Mockito.`when`(lifecycle.restoreVerified(stored, 7)).thenReturn(managed)
        Mockito.`when`(managed.storedAction).thenReturn(stored)
        Mockito.`when`(managed.reconcile()).thenReturn(
            AmbiguousActionResolution.VerifyLater(retryAt, "still unchanged"),
        )
        val scoped = buildRunner(
            preflight,
            runtime,
            decisions,
            wakeup,
            sharedCooldowns,
            lifecycle,
            submissionGate,
            timeProvider = TimeProvider { observedAt },
        )

        scoped.runOne(7)

        val outcome = assertIs<TypedRuntimeOutcome.ReconciliationDeferred>(capturedOutcome())
        assertEquals(retryAt, outcome.retryAt)
        assertEquals(true, outcome.successfulObservation)
    }

    @Test
    fun `raid reconciliation handoff becomes one runtime outcome`() {
        val submittedAt = Instant.parse("2026-08-21T00:00:00Z")
        val retryAt = submittedAt.plusSeconds(300)
        val stored = raidStoredAction(recoveryChainId = "chain-1")
        val reconciling = executionRight(
            TypedRuntimeCheckpoint(
                stored,
                TypedRuntimeCheckpointPhase.RECONCILING,
                submittedAt,
                "전투 응답 시간 초과",
            ),
        )
        Mockito.`when`(runtime.acquire(7)).thenReturn(TypedRuntimeAcquisition.Acquired(reconciling))
        Mockito.`when`(lifecycle.restoreVerified(stored, 7)).thenReturn(managed)
        Mockito.`when`(managed.storedAction).thenReturn(stored)
        Mockito.`when`(
            managed.handoffAmbiguousSubmission(submittedAt, "전투 응답 시간 초과"),
        ).thenReturn(AmbiguousActionResolution.HandedOff(retryAt, "레이드 전투 결과 미확정"))

        runner.runOne(7)

        Mockito.verify(managed, Mockito.never()).reconcile()
        val outcome = assertIs<TypedRuntimeOutcome.AmbiguousHandoff>(capturedOutcome())
        assertEquals("레이드 전투 결과 미확정", outcome.warning)
        assertEquals("RAID_BATTLE_RECOVERY_STARTED", outcome.wakeReason)
    }

    @Test
    fun `new ambiguous submission is handed to verification without immediate replay`() {
        val stored = raidStoredAction()
        val prepared = executionRight(
            TypedRuntimeCheckpoint(stored, TypedRuntimeCheckpointPhase.PREPARED, null, null),
        )
        Mockito.`when`(managed.storedAction).thenReturn(stored)
        Mockito.`when`(decisions.select(7)).thenReturn(
            AutomationCoordination.Runnable(12, legacyBattleAction(), emptyList()),
        )
        Mockito.`when`(runtime.persistPrepared(anyExecution(), anyStoredAction(), anyWarnings()))
            .thenReturn(TypedRuntimePreparation.Ready(prepared))
        Mockito.`when`(runtime.beginSubmission(prepared))
            .thenReturn(TypedRuntimeSubmission.Started(Instant.EPOCH))
        Mockito.doThrow(AmbiguousAutomationSubmissionException("전투 응답 시간 초과"))
            .`when`(managed).execute()

        runner.runOne(7)

        val outcome = assertIs<TypedRuntimeOutcome.SubmissionAmbiguous>(capturedOutcome())
        assertEquals("전투 응답 시간 초과", outcome.message)
    }

    @Test
    fun `shared cooldown stays a domain result while runtime owns completion`() {
        val retryAt = Instant.parse("2026-07-24T00:00:56Z")
        val stored = raidStoredAction()
        val prepared = executionRight(
            TypedRuntimeCheckpoint(stored, TypedRuntimeCheckpointPhase.PREPARED, null, null),
        )
        Mockito.`when`(managed.storedAction).thenReturn(stored)
        Mockito.`when`(decisions.select(7)).thenReturn(
            AutomationCoordination.Runnable(12, legacyBattleAction(), emptyList()),
        )
        Mockito.`when`(runtime.persistPrepared(anyExecution(), anyStoredAction(), anyWarnings()))
            .thenReturn(TypedRuntimePreparation.Ready(prepared))
        Mockito.`when`(runtime.beginSubmission(prepared))
            .thenReturn(TypedRuntimeSubmission.Started(Instant.EPOCH))
        Mockito.`when`(managed.execute()).thenReturn(
            TypedAutomationExecution.SharedCooldown("raid", "castle", retryAt),
        )

        runner.runOne(7)

        Mockito.verify(sharedCooldowns).learnAndApply(7, "raid", "castle", retryAt)
        assertIs<TypedRuntimeOutcome.SharedCooldownHandled>(capturedOutcome())
        Mockito.verifyNoInteractions(wakeup)
    }

    @Test
    fun `ACTIVE shared cooldown은 수렴을 종료하면서 계정 전투 상태에도 반영한다`() {
        val convergence = Mockito.mock(AutomationActionConvergenceModule::class.java)
        val factory = StoredActionConvergenceSelectionFactory()
        val stored = raidStoredAction()
        val selection = factory.create(stored)
        val prepared = executionRight(
            TypedRuntimeCheckpoint(stored, TypedRuntimeCheckpointPhase.PREPARED, null, null),
        )
        val retryAt = Instant.parse("2026-07-24T00:00:56Z")
        Mockito.`when`(convergence.resumeDue(7L)).thenReturn(ConvergenceDirective.ContinueSelection)
        Mockito.`when`(decisions.select(7L)).thenReturn(
            AutomationCoordination.Runnable(12L, legacyBattleAction(), emptyList()),
        )
        Mockito.`when`(managed.storedAction).thenReturn(stored)
        Mockito.`when`(runtime.persistPrepared(anyExecution(), anyStoredAction(), anyWarnings()))
            .thenReturn(TypedRuntimePreparation.Ready(prepared))
        Mockito.`when`(runtime.beginSubmission(prepared))
            .thenReturn(TypedRuntimeSubmission.Started(Instant.EPOCH))
        Mockito.`when`(convergence.prepare(7L, selection)).thenAnswer { ConvergenceDirective.Submit(113L, it.getArgument(1)) }
        Mockito.`when`(convergence.record(Mockito.eq(113L), anyConvergenceEvidence()))
            .thenReturn(ConvergenceDirective.ContinueSelection)
        Mockito.`when`(managed.execute()).thenReturn(
            TypedAutomationExecution.SharedCooldown("raid", "castle", retryAt),
        )
        val scoped = buildRunner(
            preflight,
            runtime,
            decisions,
            wakeup,
            sharedCooldowns,
            lifecycle,
            submissionGate,
            convergenceModule = convergence,
            convergenceSelectionFactory = factory,
            evidenceInterpreter = productionEvidenceInterpreter,
        )

        scoped.runOne(7L)

        assertIs<AutomationActionEvidence.StateAdvanced>(convergenceEvidence(convergence, 113L))
        Mockito.verify(sharedCooldowns).learnAndApply(7L, "raid", "castle", retryAt)
        assertIs<TypedRuntimeOutcome.SharedCooldownHandled>(capturedOutcome())
    }

    @Test
    fun `SHADOW shared cooldown은 적용 성공이 아닌 shared-state 대체로 비교한다`() {
        val shadow = Mockito.mock(AutomationConvergenceShadowEvaluator::class.java)
        val stored = raidStoredAction()
        val prepared = executionRight(
            TypedRuntimeCheckpoint(stored, TypedRuntimeCheckpointPhase.PREPARED, null, null),
        )
        val retryAt = Instant.parse("2026-07-24T00:00:56Z")
        Mockito.`when`(decisions.select(7L)).thenReturn(
            AutomationCoordination.Runnable(12L, legacyBattleAction(), emptyList()),
        )
        Mockito.`when`(managed.storedAction).thenReturn(stored)
        Mockito.`when`(runtime.persistPrepared(anyExecution(), anyStoredAction(), anyWarnings()))
            .thenReturn(TypedRuntimePreparation.Ready(prepared))
        Mockito.`when`(runtime.beginSubmission(prepared))
            .thenReturn(TypedRuntimeSubmission.Started(Instant.EPOCH))
        Mockito.`when`(managed.execute()).thenReturn(
            TypedAutomationExecution.SharedCooldown("raid", "castle", retryAt),
        )

        shadowRunner(shadow).runOne(7L)

        val (evidence, decision) = shadowObservation(shadow, stored.executionIdentity)
        assertIs<AutomationActionEvidence.StateAdvanced>(evidence)
        assertEquals(LegacyConvergenceDecision.SUPERSEDED, decision)
        Mockito.verify(sharedCooldowns).learnAndApply(7L, "raid", "castle", retryAt)
        assertIs<TypedRuntimeOutcome.SharedCooldownHandled>(capturedOutcome())
    }

    @Test
    fun `HOF deferral preserves the submitted checkpoint and schedules its retry`() {
        val retryAt = Instant.parse("2026-07-24T00:00:56Z")
        Mockito.`when`(decisions.select(7)).thenReturn(
            AutomationCoordination.Runnable(12, legacyBattleAction(), emptyList()),
        )
        Mockito.doThrow(HofAutomationDeferredException(retryAt, 1, requestAttempted = false))
            .`when`(managed).execute()

        runner.runOne(7)

        val outcome = assertIs<TypedRuntimeOutcome.SubmissionDeferred>(capturedOutcome())
        assertEquals(retryAt, outcome.retryAt)
    }

    @Test
    fun `미전송 재시도의 직접 응답은 저장 시도의 기준으로 같은 상태를 판정한다`() {
        val clock = TimeProvider { Instant.EPOCH }
        val store = InMemoryConvergenceStore()
        val convergence = DefaultAutomationActionConvergenceModule(store, clock)
        val factory = StoredActionConvergenceSelectionFactory()
        val selection = factory.create(defaultStored, legacySuppressionEpoch = "created-before-cutover")
        val attempt = store.createOrGet(7L, selection, Instant.EPOCH)
        assertTrue(convergence.discardUnsubmitted(7L, selection, Instant.EPOCH, "HOF_CONNECTION"))
        val resumed = executionRight(TypedRuntimeCheckpoint(defaultStored,
            TypedRuntimeCheckpointPhase.PREPARED, null, "HOF_CONNECTION", deferredSubmissionRetry = true))
        Mockito.`when`(runtime.acquire(7L)).thenReturn(TypedRuntimeAcquisition.Acquired(resumed))
        Mockito.`when`(runtime.beginSubmission(resumed)).thenReturn(TypedRuntimeSubmission.Started(Instant.EPOCH))
        Mockito.`when`(lifecycle.restoreVerified(defaultStored.withRecordedPolicy(selection), 7L)).thenReturn(managed)
        Mockito.`when`(managed.execute()).thenReturn(questClaimExecution().copy(observedState = QuestObservedState(
            fingerprint = selection.baselineFingerprint, present = true, state = QuestState.CLAIMABLE, actionNo = "claim")))
        val scoped = buildRunner(
            preflight, runtime, decisions, wakeup, sharedCooldowns, lifecycle, submissionGate,
            convergenceModule = convergence, convergenceSelectionFactory = factory,
            timeProvider = clock, evidenceInterpreter = productionEvidenceInterpreter,
        )

        scoped.runOne(7L)

        val result = requireNotNull(store.get(attempt.attemptId))
        assertEquals(app.spammy.hof.automation.convergence.ActionConvergenceResult.PENDING, result.result)
        assertEquals("AUTHORITATIVE_STATE_UNCHANGED", result.reasonCode)
        assertEquals(selection, result.selection)
        assertEquals(Instant.EPOCH.plusSeconds(10), result.nextProbeAt)
        Mockito.verify(managed).execute()
        assertIs<TypedRuntimeOutcome.AmbiguousHandoff>(capturedOutcome())
    }

    @Test
    fun `전투 패턴 POST의 503은 ACTIVE 재개에서 성공 패턴을 생략하고 전투까지 이어간다`() {
        val retryAt = Instant.parse("2026-07-24T00:00:56Z")
        val convergence = Mockito.mock(AutomationActionConvergenceModule::class.java)
        val factory = StoredActionConvergenceSelectionFactory()
        val stored = raidStoredAction()
        val selection = factory.create(stored)
        val boundStored = stored.withRecordedPolicy(selection)
        val firstPrepared = executionRight(
            TypedRuntimeCheckpoint(boundStored, TypedRuntimeCheckpointPhase.PREPARED, null, null),
        )
        val resumedPrepared = executionRight(
            TypedRuntimeCheckpoint(
                storedAction = boundStored,
                phase = TypedRuntimeCheckpointPhase.PREPARED,
                submittedAt = null,
                diagnostic = "BATTLE_PATTERN_PRELOAD_DEFERRED",
                deferredSubmissionRetry = true,
            ),
        )
        val remoteRequests = mutableListOf<String>()
        var executionCount = 0
        Mockito.`when`(runtime.acquire(7L)).thenReturn(
            TypedRuntimeAcquisition.Acquired(freshExecution),
            TypedRuntimeAcquisition.Acquired(resumedPrepared),
        )
        Mockito.`when`(convergence.resumeDue(7L)).thenReturn(ConvergenceDirective.ContinueSelection)
        Mockito.`when`(decisions.select(7L)).thenReturn(
            AutomationCoordination.Runnable(12L, legacyBattleAction(), emptyList()),
        )
        Mockito.`when`(managed.storedAction).thenReturn(stored)
        Mockito.`when`(managed.descriptor).thenReturn(defaultDescriptor())
        Mockito.`when`(lifecycle.restoreVerified(boundStored, 7L)).thenReturn(managed)
        Mockito.`when`(runtime.persistPrepared(freshExecution, boundStored, emptyList()))
            .thenReturn(TypedRuntimePreparation.Ready(firstPrepared))
        Mockito.`when`(runtime.beginSubmission(firstPrepared)).thenReturn(TypedRuntimeSubmission.Started(Instant.EPOCH))
        Mockito.`when`(runtime.beginSubmission(resumedPrepared))
            .thenReturn(TypedRuntimeSubmission.Started(Instant.EPOCH.plusSeconds(1)))
        Mockito.`when`(convergence.prepare(7L, selection)).thenAnswer { ConvergenceDirective.Submit(114L, it.getArgument(1)) }
        Mockito.`when`(convergence.retryUnsubmitted(7L, selection, Instant.EPOCH))
            .thenAnswer { ConvergenceDirective.Submit(114L, it.getArgument(1)) }
        Mockito.`when`(convergence.record(Mockito.eq(114L), anyConvergenceEvidence()))
            .thenReturn(ConvergenceDirective.ContinueSelection)
        Mockito.`when`(convergence.discardUnsubmitted(7L, selection, Instant.EPOCH, "BATTLE_PATTERN_PRELOAD_DEFERRED"))
            .thenReturn(true)
        Mockito.doAnswer {
            if (executionCount++ == 0) {
                remoteRequests += listOf("pattern-1", "pattern-2")
                throw HofAutomationDeferredException(
                    retryAt = retryAt,
                    consecutiveFailures = 1,
                    requestAttempted = true,
                    actionSubmissionAttempted = false,
                    reasonCode = "BATTLE_PATTERN_PRELOAD_DEFERRED",
                )
            }
            remoteRequests += listOf("pattern-2", "pattern-3", "battle")
            TypedAutomationExecution.BattleCompleted("raid", "castle", listOf("VICTORY"))
        }.`when`(managed).execute()
        val scoped = buildRunner(
            preflight,
            runtime,
            decisions,
            wakeup,
            sharedCooldowns,
            lifecycle,
            submissionGate,
            convergenceModule = convergence,
            convergenceSelectionFactory = factory,
            timeProvider = TimeProvider { Instant.EPOCH },
            evidenceInterpreter = productionEvidenceInterpreter,
        )

        scoped.runOne(7L)
        scoped.runOne(7L)

        val outcomeCaptor = ArgumentCaptor.forClass(TypedRuntimeOutcome::class.java)
        Mockito.verify(runtime, Mockito.times(2)).complete(anyExecution(), captureOutcome(outcomeCaptor), Mockito.nullable(Instant::class.java))
        assertIs<TypedRuntimeOutcome.SubmissionDeferred>(outcomeCaptor.allValues[0])
        assertIs<TypedRuntimeOutcome.ActionSucceeded>(outcomeCaptor.allValues[1])
        assertEquals(
            listOf("pattern-1", "pattern-2", "pattern-2", "pattern-3", "battle"),
            remoteRequests,
        )
        Mockito.verify(convergence).retryUnsubmitted(7L, selection, Instant.EPOCH)
        assertIs<AutomationActionEvidence.DirectApplied>(convergenceEvidence(convergence, 114L))
        assertEquals(retryAt, assertIs<TypedRuntimeOutcome.SubmissionDeferred>(outcomeCaptor.allValues[0]).retryAt)
    }

    @Test
    fun `전투 POST가 시도된 뒤 503이면 PREPARED로 되돌리지 않고 불명확 결과로 수렴한다`() {
        val retryAt = Instant.parse("2026-07-24T00:00:56Z")
        val stored = raidStoredAction()
        val prepared = executionRight(
            TypedRuntimeCheckpoint(stored, TypedRuntimeCheckpointPhase.PREPARED, null, null),
        )
        Mockito.`when`(decisions.select(7L)).thenReturn(
            AutomationCoordination.Runnable(12L, legacyBattleAction(), emptyList()),
        )
        Mockito.`when`(managed.storedAction).thenReturn(stored)
        Mockito.`when`(runtime.persistPrepared(freshExecution, stored, emptyList()))
            .thenReturn(TypedRuntimePreparation.Ready(prepared))
        Mockito.`when`(runtime.beginSubmission(prepared)).thenReturn(TypedRuntimeSubmission.Started(Instant.EPOCH))
        Mockito.doThrow(HofAutomationDeferredException(retryAt, 1, requestAttempted = true))
            .`when`(managed).execute()

        runner.runOne(7L)

        assertIs<TypedRuntimeOutcome.SubmissionAmbiguous>(capturedOutcome())
    }

    @Test
    fun `configuration change releases through the semantic reload outcome`() {
        Mockito.`when`(decisions.select(7)).thenThrow(TypedAutomationConfigurationChangedException())

        runner.runOne(7)

        val outcome = assertIs<TypedRuntimeOutcome.SelectionChanged>(capturedOutcome())
        assertEquals("TYPED_CONFIG_RELOAD", outcome.wakeReason)
        Mockito.verifyNoInteractions(wakeup)
        Mockito.verify(managed, Mockito.never()).execute()
    }

    @Test
    fun `work cycle boundary releases the decision and immediately wakes a fresh global scan`() {
        Mockito.`when`(decisions.select(7)).thenReturn(
            AutomationCoordination.CycleBoundary(
                warnings = listOf("cycle parked"),
                trace = emptyList(),
            ),
        )

        runner.runOne(7)

        val outcome = assertIs<TypedRuntimeOutcome.SelectionChanged>(capturedOutcome())
        assertEquals("WORK_CYCLE_BOUNDARY", outcome.wakeReason)
        Mockito.verify(lifecycle, Mockito.never()).prepare(Mockito.anyLong(), Mockito.anyLong(), anyPreparedAction())
        Mockito.verify(managed, Mockito.never()).execute()
    }

    @Test
    fun `an exhausted round preserves warnings and continues to the next round`() {
        Mockito.`when`(decisions.select(7)).thenReturn(
            AutomationCoordination.Idle(listOf("missing primary")),
        )

        runner.runOne(7)

        val outcome = assertIs<TypedRuntimeOutcome.RoundCompleted>(capturedOutcome())
        assertEquals(listOf("missing primary"), outcome.warnings)
        Mockito.verifyNoInteractions(wakeup)
    }

    @Test
    fun `fatal decision is projected once with warnings and retry schedule`() {
        Mockito.`when`(decisions.select(7)).thenReturn(
            AutomationCoordination.Fatal(
                AutomationStopReason.AUTHENTICATION,
                "login failed",
                listOf("credential warning"),
            ),
        )

        runner.runOne(7)

        val outcome = assertIs<TypedRuntimeOutcome.RetryableFailure>(capturedOutcome())
        assertEquals(AutomationStopReason.AUTHENTICATION, outcome.reason)
        assertEquals(listOf("credential warning"), outcome.warnings)
    }

    @Test
    fun `preflight retry releases the acquired right with user visible wait state`() {
        val retryAt = Instant.parse("2026-07-23T00:00:30Z")
        Mockito.`when`(preflight.ensureReady(7))
            .thenReturn(AutomationDailyPreflight.Result.RetryScheduled(retryAt, 0))

        runner.runOne(7)

        val outcome = assertIs<TypedRuntimeOutcome.ScheduledWait>(capturedOutcome())
        assertEquals(AutomationWaitReason.HOF_CONNECTION, outcome.waitReason)
        assertEquals(retryAt, outcome.nextRunAt)
        assertEquals("DAILY_PREFLIGHT_RETRY", outcome.wakeReason)
        Mockito.verifyNoInteractions(decisions)
    }

    @Test
    fun `draining runtime skips preflight and completes its current checkpoint`() {
        Mockito.`when`(runtime.isCompletingCurrentAction(7)).thenReturn(true)
        Mockito.`when`(runtime.acquire(7)).thenReturn(TypedRuntimeAcquisition.Acquired(preparedExecution))
        Mockito.`when`(lifecycle.restoreVerified(defaultStored, 7)).thenReturn(managed)

        runner.runOne(7)

        Mockito.verifyNoInteractions(preflight)
        Mockito.verify(managed).execute()
        assertIs<TypedRuntimeOutcome.ActionSucceeded>(capturedOutcome())
    }

    @Test
    fun `invalid verified checkpoint becomes an isolated integrity outcome`() {
        val reconciling = executionRight(
            TypedRuntimeCheckpoint(
                defaultStored,
                TypedRuntimeCheckpointPhase.RECONCILING,
                Instant.EPOCH,
                "unknown",
            ),
        )
        Mockito.`when`(runtime.acquire(7)).thenReturn(TypedRuntimeAcquisition.Acquired(reconciling))
        Mockito.`when`(lifecycle.restoreVerified(defaultStored, 7))
            .thenThrow(IllegalArgumentException("unsupported"))

        runner.runOne(7)

        assertIs<TypedRuntimeOutcome.IntegrityFailure>(capturedOutcome())
        Mockito.verify(managed, Mockito.never()).execute()
    }

    @Test
    fun `inactive runtime returns before acquisition or HOF work`() {
        Mockito.`when`(runtime.isRunning(7)).thenReturn(false)

        runner.runOne(7)

        Mockito.verify(runtime, Mockito.never()).acquire(7)
        Mockito.verifyNoInteractions(preflight, decisions, wakeup)
    }

    @Test
    fun `post kill switch keeps selection and reads available but never begins submission`() {
        val action = QuestAction.Claim("quest", "claim")
        Mockito.`when`(decisions.select(7)).thenReturn(
            AutomationCoordination.Runnable(12, action, emptyList()),
        )
        val rollout = AutomationConvergenceRollout(
            AutomationConvergenceProperties(
                mode = AutomationConvergenceMode.ACTIVE,
                automationPostsEnabled = false,
            ),
        )
        val scoped = buildRunner(
            preflight,
            runtime,
            decisions,
            wakeup,
            sharedCooldowns,
            lifecycle,
            submissionGate,
            rollout = rollout,
            timeProvider = app.spammy.hof.common.time.TimeProvider { Instant.EPOCH },
        )

        scoped.runOne(7)

        Mockito.verify(runtime, Mockito.never()).beginSubmission(anyExecution())
        Mockito.verify(managed, Mockito.never()).execute()
        val wait = assertIs<TypedRuntimeOutcome.ScheduledWait>(capturedOutcome())
        assertEquals(Instant.EPOCH.plusSeconds(30), wait.nextRunAt)
    }

    @Test
    fun `raid terminal battle result clears recovery warning and records stable reason`() {
        val journal = Mockito.mock(AutomationDecisionJournal::class.java)
        val action = legacyBattleAction(recoveryChainId = "chain-1")
        val stored = raidStoredAction(recoveryChainId = "chain-1")
        val prepared = executionRight(
            TypedRuntimeCheckpoint(stored, TypedRuntimeCheckpointPhase.PREPARED, null, null),
        )
        Mockito.`when`(decisions.select(7)).thenReturn(
            AutomationCoordination.Runnable(12, action, listOf("레이드 전투 결과 미확정 · 확인 필요")),
        )
        Mockito.`when`(managed.storedAction).thenReturn(stored)
        Mockito.`when`(runtime.persistPrepared(anyExecution(), anyStoredAction(), anyWarnings()))
            .thenReturn(TypedRuntimePreparation.Ready(prepared))
        Mockito.`when`(runtime.beginSubmission(prepared))
            .thenReturn(TypedRuntimeSubmission.Started(Instant.EPOCH))
        Mockito.`when`(managed.execute())
            .thenReturn(TypedAutomationExecution.BattleCompleted("raid", "castle"))
        Mockito.`when`(journal.appendDecision(Mockito.eq(7L), anyCoordination())).thenReturn(41L)
        val scoped = buildRunner(
            preflight,
            runtime,
            decisions,
            wakeup,
            sharedCooldowns,
            lifecycle,
            submissionGate,
            journal,
        )

        scoped.runOne(7)

        val outcome = assertIs<TypedRuntimeOutcome.ActionSucceeded>(capturedOutcome())
        assertEquals("RAID_BATTLE_APPLIED_TERMINAL_RESULT", outcome.wakeReason)
        assertEquals(emptyList(), outcome.warnings)
        val traceCaptor = ArgumentCaptor.forClass(AutomationActionTrace::class.java)
        Mockito.verify(journal).appendActionResult(Mockito.eq(41L), captureTrace(traceCaptor))
        Mockito.verify(journal).deferActionResult(Mockito.eq(41L), Mockito.eq(stored.executionIdentity) ?: stored.executionIdentity, captureTrace(traceCaptor))
        assertEquals("RAID_BATTLE_APPLIED_TERMINAL_RESULT", traceCaptor.allValues.last().reasonCode)
    }

    @Test
    fun `raid cycle result keeps lifecycle descriptor as its semantic source`() {
        val journal = Mockito.mock(AutomationDecisionJournal::class.java)
        val action = legacyBattleAction()
        val stored = raidStoredAction()
        val prepared = executionRight(
            TypedRuntimeCheckpoint(stored, TypedRuntimeCheckpointPhase.PREPARED, null, null),
        )
        val descriptor = AutomationActionDescriptor(
            source = AutomationType.RAID,
            storageKind = "BATTLE_MAP",
            actionKind = "RAID_BATTLE",
            actionLabel = "레이드 전투",
            context = "레이드 전투 · RaidGoblin",
            targetKey = "RaidGoblin",
            targetName = "고블린",
            presetId = 301,
        )
        Mockito.`when`(decisions.select(7)).thenReturn(
            AutomationCoordination.Runnable(12, action, emptyList()),
        )
        Mockito.`when`(lifecycle.describe(action)).thenReturn(descriptor)
        Mockito.`when`(managed.descriptor).thenReturn(descriptor)
        Mockito.`when`(managed.storedAction).thenReturn(stored)
        Mockito.`when`(runtime.persistPrepared(anyExecution(), anyStoredAction(), anyWarnings()))
            .thenReturn(TypedRuntimePreparation.Ready(prepared))
        Mockito.`when`(runtime.beginSubmission(prepared))
            .thenReturn(TypedRuntimeSubmission.Started(Instant.EPOCH))
        Mockito.`when`(managed.execute()).thenReturn(
            TypedAutomationExecution.RaidCycleFinished(
                RaidCycleOutcome(12, "RaidGoblin", RaidCycleOutcomeKind.COMPLETED),
            ),
        )
        Mockito.`when`(journal.appendDecision(Mockito.eq(7L), anyCoordination())).thenReturn(41L)
        val scoped = buildRunner(
            preflight,
            runtime,
            decisions,
            wakeup,
            sharedCooldowns,
            lifecycle,
            submissionGate,
            journal,
        )

        scoped.runOne(7)

        val traceCaptor = ArgumentCaptor.forClass(AutomationActionTrace::class.java)
        Mockito.verify(journal).appendActionResult(Mockito.eq(41L), captureTrace(traceCaptor))
        Mockito.verify(journal).deferActionResult(Mockito.eq(41L), Mockito.eq(stored.executionIdentity) ?: stored.executionIdentity, captureTrace(traceCaptor))
        val result = traceCaptor.allValues.last()
        assertEquals("RAID_BATTLE", result.actionKind)
        assertEquals("RaidGoblin", result.targetKey)
        assertEquals("고블린", result.targetName)
        assertTrue(result.message.startsWith("레이드 전투 · RaidGoblin · "))
    }

    @Test
    fun `raid entry wait records owner classification deadline scope and release condition`() {
        val journal = Mockito.mock(AutomationDecisionJournal::class.java)
        val action = legacyBattleAction()
        val stored = raidStoredAction()
        val prepared = executionRight(
            TypedRuntimeCheckpoint(stored, TypedRuntimeCheckpointPhase.PREPARED, null, null),
        )
        val retryAt = Instant.parse("2026-07-24T00:10:00Z")
        val descriptor = AutomationActionDescriptor(
            source = AutomationType.RAID,
            storageKind = "RAID_TOWN",
            actionKind = "REFRESH",
            actionLabel = "레이드 상태 갱신",
            context = "레이드 상태 갱신 · 자동화 대상",
            targetKey = "RaidAuto",
            targetName = "자동화 대상",
        )
        Mockito.`when`(decisions.select(7)).thenReturn(
            AutomationCoordination.Runnable(12, action, emptyList()),
        )
        Mockito.`when`(lifecycle.describe(action)).thenReturn(descriptor)
        Mockito.`when`(managed.descriptor).thenReturn(descriptor)
        Mockito.`when`(managed.storedAction).thenReturn(stored)
        Mockito.`when`(runtime.persistPrepared(anyExecution(), anyStoredAction(), anyWarnings()))
            .thenReturn(TypedRuntimePreparation.Ready(prepared))
        Mockito.`when`(runtime.beginSubmission(prepared))
            .thenReturn(TypedRuntimeSubmission.Started(Instant.EPOCH))
        Mockito.`when`(managed.execute()).thenReturn(
            TypedAutomationExecution.RaidWaiting(
                retryAt = retryAt,
                raidId = "RaidManual",
                reasonCode = "RAID_MANUAL_UNCONFIGURED_ACTIVE",
                message = "설정 밖 수동 레이드를 기다립니다.",
                releaseCondition = "수동 레이드 종료 뒤 상태 갱신",
            ),
        )
        Mockito.`when`(journal.appendDecision(Mockito.eq(7L), anyCoordination())).thenReturn(41L)
        val scoped = buildRunner(
            preflight,
            runtime,
            decisions,
            wakeup,
            sharedCooldowns,
            lifecycle,
            submissionGate,
            journal,
        )

        scoped.runOne(7)

        val outcome = assertIs<TypedRuntimeOutcome.ActionSucceeded>(capturedOutcome())
        assertEquals("TYPED_RAID_WAITING", outcome.wakeReason)
        val traceCaptor = ArgumentCaptor.forClass(AutomationActionTrace::class.java)
        Mockito.verify(journal).appendActionResult(Mockito.eq(41L), captureTrace(traceCaptor))
        Mockito.verify(journal).deferActionResult(Mockito.eq(41L), Mockito.eq(stored.executionIdentity) ?: stored.executionIdentity, captureTrace(traceCaptor))
        val result = traceCaptor.allValues.last()
        assertEquals(AutomationHistoryEventKind.WAITING, result.kind)
        assertEquals("RAID_MANUAL_UNCONFIGURED_ACTIVE", result.reasonCode)
        assertEquals("RaidManual", result.targetKey)
        assertEquals(retryAt, result.nextRunAt)
        assertEquals(AutomationImpactScope.RAID_ONLY, result.impactScope)
        assertEquals("수동 레이드 종료 뒤 상태 갱신", result.releaseCondition)
    }

    @Test
    fun `새 행동은 격리 범위 시도를 만들고 직접 성공 증거로 종료한다`() {
        val convergence = Mockito.mock(AutomationActionConvergenceModule::class.java)
        val factory = StoredActionConvergenceSelectionFactory()
        val selection = factory.create(defaultStored)
        Mockito.`when`(convergence.resumeDue(7L)).thenReturn(ConvergenceDirective.ContinueSelection)
        Mockito.`when`(decisions.select(7)).thenReturn(
            AutomationCoordination.Runnable(12, QuestAction.Claim("quest", "claim"), emptyList()),
        )
        Mockito.`when`(convergence.prepare(7L, selection)).thenAnswer { ConvergenceDirective.Submit(99L, it.getArgument(1)) }
        Mockito.`when`(convergence.record(Mockito.eq(99L), anyConvergenceEvidence()))
            .thenReturn(ConvergenceDirective.ContinueSelection)
        Mockito.`when`(managed.execute()).thenReturn(questClaimExecution())
        val scoped = buildRunner(
            preflight,
            runtime,
            decisions,
            wakeup,
            sharedCooldowns,
            lifecycle,
            submissionGate,
            convergenceModule = convergence,
            convergenceSelectionFactory = factory,
            evidenceInterpreter = productionEvidenceInterpreter,
        )

        scoped.runOne(7)

        Mockito.verify(convergence).prepare(7L, selection)
        val evidence = convergenceEvidence(convergence, 99L)
        assertIs<AutomationActionEvidence.DirectApplied>(evidence)
        assertIs<TypedRuntimeOutcome.ActionSucceeded>(capturedOutcome())
    }

    @Test
    fun `active에서 poststate 없는 응답은 성공 완료하지 않고 재관측으로 넘긴다`() {
        val convergence = Mockito.mock(AutomationActionConvergenceModule::class.java)
        val factory = StoredActionConvergenceSelectionFactory()
        val selection = factory.create(defaultStored)
        val probeAt = Instant.parse("2026-07-25T00:00:10Z")
        Mockito.`when`(convergence.resumeDue(7L)).thenReturn(ConvergenceDirective.ContinueSelection)
        Mockito.`when`(decisions.select(7)).thenReturn(
            AutomationCoordination.Runnable(12, QuestAction.Claim("quest", "claim"), emptyList()),
        )
        Mockito.`when`(convergence.prepare(7L, selection)).thenAnswer { ConvergenceDirective.Submit(111L, it.getArgument(1)) }
        Mockito.`when`(convergence.recordDirectResponse(Mockito.eq(111L), anyConvergenceEvidence())).thenReturn(
            ConvergenceDirective.WaitUntil(probeAt, selection.scope),
        )
        val scoped = buildRunner(
            preflight,
            runtime,
            decisions,
            wakeup,
            sharedCooldowns,
            lifecycle,
            submissionGate,
            convergenceModule = convergence,
            convergenceSelectionFactory = factory,
            evidenceInterpreter = productionEvidenceInterpreter,
        )

        scoped.runOne(7L)

        assertIs<AutomationActionEvidence.IncompleteObservation>(convergenceEvidence(convergence, 111L, directResponse = true))
        assertIs<TypedRuntimeOutcome.AmbiguousHandoff>(capturedOutcome())
        Mockito.verify(runtime).complete(anyExecution(), anyOutcome(), Mockito.eq(probeAt))
    }

    @Test
    fun `active에서 명시적 거절 응답은 미적용으로 닫으면서 행동별 상태 projection을 허용한다`() {
        val convergence = Mockito.mock(AutomationActionConvergenceModule::class.java)
        val factory = StoredActionConvergenceSelectionFactory()
        val selection = factory.create(defaultStored)
        val rejected = questClaimExecution().copy(
            explicitRejected = true,
            rejectionReason = "QUEST_ACTION_REJECTED",
        )
        Mockito.`when`(convergence.resumeDue(7L)).thenReturn(ConvergenceDirective.ContinueSelection)
        Mockito.`when`(decisions.select(7)).thenReturn(
            AutomationCoordination.Runnable(12, QuestAction.Claim("quest", "claim"), emptyList()),
        )
        Mockito.`when`(convergence.prepare(7L, selection)).thenAnswer { ConvergenceDirective.Submit(112L, it.getArgument(1)) }
        Mockito.`when`(convergence.recordDirectResponse(Mockito.eq(112L), anyConvergenceEvidence()))
            .thenReturn(ConvergenceDirective.ContinueSelection)
        Mockito.`when`(managed.execute()).thenReturn(rejected)
        val scoped = buildRunner(
            preflight,
            runtime,
            decisions,
            wakeup,
            sharedCooldowns,
            lifecycle,
            submissionGate,
            convergenceModule = convergence,
            convergenceSelectionFactory = factory,
            evidenceInterpreter = productionEvidenceInterpreter,
        )

        scoped.runOne(7L)

        val evidence = assertIs<AutomationActionEvidence.DirectRejected>(convergenceEvidence(convergence, 112L, directResponse = true))
        Mockito.verify(managed).applyPolicyResolvedExecution(rejected, evidence)
        assertIs<TypedRuntimeOutcome.ActionSuperseded>(capturedOutcome())
    }

    @Test
    fun `prepared payload 저장 실패는 convergence attempt를 만들지 않는다`() {
        val convergence = Mockito.mock(AutomationActionConvergenceModule::class.java)
        val factory = StoredActionConvergenceSelectionFactory()
        Mockito.`when`(convergence.resumeDue(7L)).thenReturn(ConvergenceDirective.ContinueSelection)
        Mockito.`when`(decisions.select(7L)).thenReturn(
            AutomationCoordination.Runnable(12L, QuestAction.Claim("quest", "claim"), emptyList()),
        )
        Mockito.`when`(runtime.persistPrepared(anyExecution(), anyStoredAction(), anyWarnings()))
            .thenThrow(IllegalStateException("checkpoint write failed"))
        val scoped = buildRunner(
            preflight,
            runtime,
            decisions,
            wakeup,
            sharedCooldowns,
            lifecycle,
            submissionGate,
            convergenceModule = convergence,
            convergenceSelectionFactory = factory,
        )

        scoped.runOne(7L)

        Mockito.verify(convergence, Mockito.never()).prepare(Mockito.eq(7L), anyConvergenceSelection())
        assertIs<TypedRuntimeOutcome.RetryableFailure>(capturedOutcome())
    }

    @Test
    fun `제출 시작 뒤 분류되지 않은 예외도 convergence attempt를 terminal 처리한다`() {
        val convergence = Mockito.mock(AutomationActionConvergenceModule::class.java)
        val factory = StoredActionConvergenceSelectionFactory()
        val selection = factory.create(defaultStored)
        Mockito.`when`(convergence.resumeDue(7L)).thenReturn(ConvergenceDirective.ContinueSelection)
        Mockito.`when`(decisions.select(7L)).thenReturn(
            AutomationCoordination.Runnable(12L, QuestAction.Claim("quest", "claim"), emptyList()),
        )
        Mockito.`when`(convergence.prepare(7L, selection)).thenAnswer { ConvergenceDirective.Submit(106L, it.getArgument(1)) }
        Mockito.`when`(convergence.record(Mockito.eq(106L), anyConvergenceEvidence()))
            .thenReturn(ConvergenceDirective.ContinueSelection)
        Mockito.doThrow(IllegalStateException("unexpected adapter failure")).`when`(managed).execute()
        val scoped = buildRunner(
            preflight,
            runtime,
            decisions,
            wakeup,
            sharedCooldowns,
            lifecycle,
            submissionGate,
            convergenceModule = convergence,
            convergenceSelectionFactory = factory,
        )

        scoped.runOne(7L)

        assertIs<AutomationActionEvidence.ResultUnobserved>(convergenceEvidence(convergence, 106L))
        assertIs<TypedRuntimeOutcome.RetryableFailure>(capturedOutcome())
    }

    @Test
    fun `HOF deferral은 convergence 관측 횟수를 소비하지 않는 network pending으로 기록한다`() {
        val convergence = Mockito.mock(AutomationActionConvergenceModule::class.java)
        val factory = StoredActionConvergenceSelectionFactory()
        val selection = factory.create(defaultStored)
        val retryAt = Instant.parse("2026-07-25T00:01:00Z")
        Mockito.`when`(convergence.resumeDue(7L)).thenReturn(ConvergenceDirective.ContinueSelection)
        Mockito.`when`(decisions.select(7L)).thenReturn(
            AutomationCoordination.Runnable(12L, QuestAction.Claim("quest", "claim"), emptyList()),
        )
        Mockito.`when`(convergence.prepare(7L, selection)).thenAnswer { ConvergenceDirective.Submit(107L, it.getArgument(1)) }
        Mockito.`when`(convergence.record(Mockito.eq(107L), anyConvergenceEvidence())).thenReturn(
            ConvergenceDirective.WaitUntil(retryAt, selection.scope),
        )
        Mockito.doThrow(HofAutomationDeferredException(retryAt, 1, requestAttempted = true))
            .`when`(managed).execute()
        val scoped = buildRunner(
            preflight,
            runtime,
            decisions,
            wakeup,
            sharedCooldowns,
            lifecycle,
            submissionGate,
            convergenceModule = convergence,
            convergenceSelectionFactory = factory,
        )

        scoped.runOne(7L)

        assertIs<AutomationActionEvidence.NetworkFailure>(convergenceEvidence(convergence, 107L))
        assertIs<TypedRuntimeOutcome.SubmissionAmbiguous>(capturedOutcome())
    }

    @Test
    fun `제출 직전 권위 상태 변경은 저장 행동과 convergence attempt를 종료하고 fresh 판단으로 넘긴다`() {
        val convergence = Mockito.mock(AutomationActionConvergenceModule::class.java)
        val factory = StoredActionConvergenceSelectionFactory()
        val selection = factory.create(defaultStored)
        Mockito.`when`(convergence.resumeDue(7L)).thenReturn(ConvergenceDirective.ContinueSelection)
        Mockito.`when`(decisions.select(7L)).thenReturn(
            AutomationCoordination.Runnable(12L, QuestAction.Claim("quest", "claim"), emptyList()),
        )
        Mockito.`when`(convergence.prepare(7L, selection)).thenAnswer { ConvergenceDirective.Submit(105L, it.getArgument(1)) }
        Mockito.`when`(convergence.record(Mockito.eq(105L), anyConvergenceEvidence()))
            .thenReturn(ConvergenceDirective.ContinueSelection)
        Mockito.doThrow(
            AutomationActionPreconditionChangedException("최신 상태에서 저장 행동의 사전조건이 사라졌습니다."),
        ).`when`(managed).execute()
        val scoped = buildRunner(
            preflight,
            runtime,
            decisions,
            wakeup,
            sharedCooldowns,
            lifecycle,
            submissionGate,
            convergenceModule = convergence,
            convergenceSelectionFactory = factory,
        )

        scoped.runOne(7L)

        assertIs<TypedRuntimeOutcome.ActionSuperseded>(capturedOutcome())
        assertIs<AutomationActionEvidence.StateAdvanced>(convergenceEvidence(convergence, 105L))
    }

    @Test
    fun `제출 직전 불완전 관측은 POST 없이 convergence 재관측만 예약한다`() {
        val convergence = Mockito.mock(AutomationActionConvergenceModule::class.java)
        val factory = StoredActionConvergenceSelectionFactory()
        val selection = factory.create(defaultStored)
        val retryAt = Instant.parse("2026-07-25T00:00:10Z")
        Mockito.`when`(convergence.resumeDue(7L)).thenReturn(ConvergenceDirective.ContinueSelection)
        Mockito.`when`(decisions.select(7L)).thenReturn(
            AutomationCoordination.Runnable(12L, QuestAction.Claim("quest", "claim"), emptyList()),
        )
        Mockito.`when`(convergence.prepare(7L, selection)).thenAnswer { ConvergenceDirective.Submit(108L, it.getArgument(1)) }
        Mockito.`when`(convergence.record(Mockito.eq(108L), anyConvergenceEvidence())).thenReturn(
            ConvergenceDirective.WaitUntil(retryAt, selection.scope),
        )
        Mockito.doThrow(
            AutomationPreSubmitObservationIncompleteException("최신 전투 맵을 완전하게 관측하지 못했습니다."),
        ).`when`(managed).validateBeforeSubmission()
        val scoped = buildRunner(
            preflight,
            runtime,
            decisions,
            wakeup,
            sharedCooldowns,
            lifecycle,
            submissionGate,
            convergenceModule = convergence,
            convergenceSelectionFactory = factory,
        )

        scoped.runOne(7L)

        assertIs<AutomationActionEvidence.IncompleteObservation>(convergenceEvidence(convergence, 108L))
        assertIs<TypedRuntimeOutcome.PreparedDiscarded>(capturedOutcome())
        Mockito.verify(managed, Mockito.never()).execute()
    }

    @Test
    fun `불명확 제출은 기존 action을 terminal 처리하고 수렴 확인만 예약한다`() {
        val convergence = Mockito.mock(AutomationActionConvergenceModule::class.java)
        val factory = StoredActionConvergenceSelectionFactory()
        val selection = factory.create(defaultStored)
        val probeAt = Instant.parse("2026-07-25T00:00:10Z")
        Mockito.`when`(convergence.resumeDue(7L)).thenReturn(ConvergenceDirective.ContinueSelection)
        Mockito.`when`(decisions.select(7)).thenReturn(
            AutomationCoordination.Runnable(12, QuestAction.Claim("quest", "claim"), emptyList()),
        )
        Mockito.`when`(convergence.prepare(7L, selection)).thenAnswer { ConvergenceDirective.Submit(99L, it.getArgument(1)) }
        Mockito.`when`(convergence.record(Mockito.eq(99L), anyConvergenceEvidence())).thenReturn(
            ConvergenceDirective.WaitUntil(probeAt, selection.scope),
        )
        Mockito.doThrow(
            AmbiguousAutomationSubmissionException(
                "result unknown",
                responseShapeFingerprint = "b".repeat(64),
                sanitizedSnippet = "BattleHttpResponse|status=200|rounds=1|outcomes=UNKNOWN",
            ),
        ).`when`(managed).execute()
        val scoped = buildRunner(
            preflight,
            runtime,
            decisions,
            wakeup,
            sharedCooldowns,
            lifecycle,
            submissionGate,
            convergenceModule = convergence,
            convergenceSelectionFactory = factory,
        )

        scoped.runOne(7)

        val evidence = assertIs<AutomationActionEvidence.IncompleteObservation>(
            convergenceEvidence(convergence, 99L),
        )
        assertEquals("b".repeat(64), evidence.responseShapeFingerprint)
        assertEquals("BattleHttpResponse|status=200|rounds=1|outcomes=UNKNOWN", evidence.sanitizedSnippet)
        assertIs<TypedRuntimeOutcome.AmbiguousHandoff>(capturedOutcome())
        Mockito.verify(runtime).complete(anyExecution(), anyOutcome(), Mockito.eq(probeAt))
    }

    @Test
    fun `레이드 전용 handoff도 기존 convergence attempt의 유한 재관측으로 넘긴다`() {
        val convergence = Mockito.mock(AutomationActionConvergenceModule::class.java)
        val factory = StoredActionConvergenceSelectionFactory()
        val stored = raidStoredAction()
        val selection = factory.create(stored)
        val prepared = executionRight(
            TypedRuntimeCheckpoint(stored, TypedRuntimeCheckpointPhase.PREPARED, null, null),
        )
        Mockito.`when`(convergence.resumeDue(7L)).thenReturn(ConvergenceDirective.ContinueSelection)
        Mockito.`when`(decisions.select(7)).thenReturn(
            AutomationCoordination.Runnable(12, legacyBattleAction(), emptyList()),
        )
        Mockito.`when`(managed.storedAction).thenReturn(stored)
        Mockito.`when`(runtime.persistPrepared(anyExecution(), anyStoredAction(), anyWarnings()))
            .thenReturn(TypedRuntimePreparation.Ready(prepared))
        Mockito.`when`(runtime.beginSubmission(prepared))
            .thenReturn(TypedRuntimeSubmission.Started(Instant.EPOCH))
        Mockito.`when`(convergence.prepare(7L, selection)).thenAnswer { ConvergenceDirective.Submit(102L, it.getArgument(1)) }
        Mockito.`when`(convergence.record(Mockito.eq(102L), anyConvergenceEvidence()))
            .thenReturn(ConvergenceDirective.WaitUntil(Instant.EPOCH.plusSeconds(10), selection.scope))
        Mockito.`when`(
            managed.handoffAmbiguousSubmission(Instant.EPOCH, "전투 응답 시간 초과"),
        ).thenReturn(
            AmbiguousActionResolution.HandedOff(
                Instant.EPOCH.plusSeconds(300),
                "레이드 전투 결과 미확정",
            ),
        )
        Mockito.doThrow(AmbiguousAutomationSubmissionException("전투 응답 시간 초과"))
            .`when`(managed).execute()
        val scoped = buildRunner(
            preflight,
            runtime,
            decisions,
            wakeup,
            sharedCooldowns,
            lifecycle,
            submissionGate,
            convergenceModule = convergence,
            convergenceSelectionFactory = factory,
        )

        scoped.runOne(7)

        assertIs<AutomationActionEvidence.IncompleteObservation>(convergenceEvidence(convergence, 102L))
        assertIs<TypedRuntimeOutcome.AmbiguousHandoff>(capturedOutcome())
        Mockito.verify(runtime).complete(anyExecution(), anyOutcome(), Mockito.eq(Instant.EPOCH.plusSeconds(10)))
    }

    @Test
    fun `기한이 된 수렴 확인은 저장 시도의 기준을 유지하고 payload를 재제출하지 않는다`() {
        val store = InMemoryConvergenceStore()
        val evidenceCases = mutableListOf<AutomationActionEvidence>()
        val clock = TimeProvider { Instant.EPOCH }
        val convergence = DefaultAutomationActionConvergenceModule(store, clock,
            app.spammy.hof.automation.convergence.EvidenceCaseRecorder { _, evidence, _ -> evidenceCases += evidence })
        val loader = Mockito.mock(StoredConvergenceActionLoader::class.java)
        val factory = StoredActionConvergenceSelectionFactory()
        val selection = factory.create(defaultStored, legacySuppressionEpoch = "created-before-cutover")
        val attempt = store.createOrGet(7L, selection, Instant.EPOCH.minusSeconds(10))
        convergence.record(attempt.attemptId,
            AutomationActionEvidence.NetworkFailure(Instant.EPOCH.minusSeconds(10), "direct response lost"))
        Mockito.`when`(loader.load(7L, defaultStored.executionIdentity)).thenReturn(defaultStored)
        Mockito.`when`(lifecycle.restoreVerified(defaultStored, 7L)).thenReturn(managed)
        Mockito.`when`(managed.reconcile()).thenReturn(AmbiguousActionResolution.Resubmit)
        Mockito.`when`(decisions.select(7L)).thenReturn(AutomationCoordination.Idle(emptyList()))
        val scoped = buildRunner(
            preflight, runtime, decisions, wakeup, sharedCooldowns, lifecycle, submissionGate,
            convergenceModule = convergence,
            convergenceSelectionFactory = factory,
            storedConvergenceActionLoader = loader,
            timeProvider = clock,
            evidenceInterpreter = productionEvidenceInterpreter,
        )

        scoped.runOne(7L)

        val evidence = assertIs<AutomationActionEvidence.SameState>(evidenceCases.last())
        assertEquals(selection.baselineFingerprint, evidence.stateFingerprint)
        assertEquals(selection, store.get(attempt.attemptId)?.selection)
        assertEquals(Instant.EPOCH.plusSeconds(10), store.get(attempt.attemptId)?.nextProbeAt)
        Mockito.verify(managed).reconcile()
        Mockito.verify(managed, Mockito.never()).execute()
        Mockito.verify(decisions).select(7L)
        assertIs<TypedRuntimeOutcome.SelectionChanged>(capturedOutcome())
    }

    @Test
    fun `기한이 된 수렴 확인은 관련 없는 실행 가능 행동에 한 번 양보한다`() {
        val convergence = Mockito.mock(AutomationActionConvergenceModule::class.java)
        val factory = StoredActionConvergenceSelectionFactory()
        val otherStored = defaultStored.copy(
            executionIdentity = "execution-other",
            payload = StoredTypedActionPayload.QuestClaim("other-quest", "other-claim"),
        )
        val otherSelection = factory.create(otherStored)
        Mockito.`when`(convergence.resumeDue(7L)).thenReturn(
            ConvergenceDirective.Probe(99L, factory.create(defaultStored)),
        )
        Mockito.`when`(decisions.select(7L)).thenReturn(
            AutomationCoordination.Runnable(
                13L,
                QuestAction.Claim("other-quest", "other-claim"),
                emptyList(),
            ),
        )
        Mockito.`when`(managed.storedAction).thenReturn(otherStored)
        Mockito.`when`(convergence.prepare(7L, otherSelection)).thenAnswer { ConvergenceDirective.Submit(100L, it.getArgument(1)) }
        Mockito.`when`(convergence.record(Mockito.eq(100L), anyConvergenceEvidence()))
            .thenReturn(ConvergenceDirective.ContinueSelection)
        Mockito.`when`(managed.execute()).thenReturn(questClaimExecution())
        val scoped = buildRunner(
            preflight,
            runtime,
            decisions,
            wakeup,
            sharedCooldowns,
            lifecycle,
            submissionGate,
            convergenceModule = convergence,
            convergenceSelectionFactory = factory,
            evidenceInterpreter = productionEvidenceInterpreter,
        )

        scoped.runOne(7L)

        Mockito.verify(managed).execute()
        Mockito.verify(managed, Mockito.never()).reconcile()
        Mockito.verify(convergence, Mockito.never()).record(Mockito.eq(99L), anyConvergenceEvidence())
        assertIs<AutomationActionEvidence.DirectApplied>(convergenceEvidence(convergence, 100L))
    }

    @Test
    fun `due probe entry가 fresh entry보다 앞서면 판단 주기에서 probe 하나만 실행한다`() {
        val convergence = Mockito.mock(AutomationActionConvergenceModule::class.java)
        val loader = Mockito.mock(StoredConvergenceActionLoader::class.java)
        Mockito.`when`(convergence.resumeDue(7L)).thenReturn(
            ConvergenceDirective.Probe(109L, StoredActionConvergenceSelectionFactory().create(defaultStored)),
        )
        Mockito.`when`(decisions.select(7L)).thenReturn(
            AutomationCoordination.Runnable(
                13L,
                QuestAction.Claim("other-quest", "other-claim"),
                emptyList(),
            ),
        )
        Mockito.`when`(loader.load(7L, defaultStored.executionIdentity)).thenReturn(defaultStored)
        Mockito.`when`(lifecycle.restoreVerified(defaultStored, 7L)).thenReturn(managed)
        Mockito.`when`(managed.reconcile()).thenReturn(AmbiguousActionResolution.Resubmit)
        Mockito.`when`(convergence.record(Mockito.eq(109L), anyConvergenceEvidence()))
            .thenReturn(ConvergenceDirective.ContinueSelection)
        val scoped = buildRunner(
            preflight,
            runtime,
            decisions,
            wakeup,
            sharedCooldowns,
            lifecycle,
            submissionGate,
            convergenceModule = convergence,
            convergenceSelectionFactory = StoredActionConvergenceSelectionFactory(),
            storedConvergenceActionLoader = loader,
            convergenceWorkPriority = AutomationConvergenceWorkPriority { _, probeEntryId, freshEntryId ->
                probeEntryId < freshEntryId
            },
        )

        scoped.runOne(7L)

        Mockito.verify(managed).reconcile()
        Mockito.verify(managed, Mockito.never()).execute()
        assertIs<AutomationActionEvidence.SameState>(convergenceEvidence(convergence, 109L))
    }

    @Test
    fun `active 전환은 원래 시도의 동일 상태를 pending으로 넘기고 재제출하지 않는다`() {
        val convergence = Mockito.mock(AutomationActionConvergenceModule::class.java)
        val factory = StoredActionConvergenceSelectionFactory()
        val runtimeEnrichedStored = defaultStored.copy(
            payload = assertIs<StoredTypedActionPayload.QuestClaim>(defaultStored.payload)
                .copy(questCycle = "4"),
        )
        val selection = factory.create(runtimeEnrichedStored, legacySuppressionEpoch = "4")
        val legacyExecution = executionRight(
            TypedRuntimeCheckpoint(
                runtimeEnrichedStored,
                TypedRuntimeCheckpointPhase.RECONCILING,
                Instant.EPOCH,
                "legacy ambiguous",
                legacySuppressionEpoch = "4",
            ),
        )
        val probeAt = Instant.parse("2026-07-25T00:00:10Z")
        Mockito.`when`(runtime.acquire(7)).thenReturn(TypedRuntimeAcquisition.Acquired(legacyExecution))
        val boundStored = runtimeEnrichedStored.copy(policyContext = StoredActionPolicyContext(
            selection.policyVersion, selection.actionKind, selection.scope, selection.baselineFingerprint))
        Mockito.`when`(convergence.storedSelection(7L, runtimeEnrichedStored.executionIdentity)).thenReturn(selection)
        Mockito.`when`(lifecycle.restoreVerified(boundStored, 7L)).thenReturn(managed)
        Mockito.`when`(managed.reconcile()).thenReturn(AmbiguousActionResolution.Resubmit)
        Mockito.`when`(convergence.restoreCheckpoint(7L, selection, RestoredActionCheckpoint(Instant.EPOCH, 0, null)))
            .thenAnswer { ConvergenceDirective.Probe(101L, it.getArgument(1)) }
        Mockito.`when`(convergence.record(Mockito.eq(101L), anyConvergenceEvidence())).thenReturn(
            ConvergenceDirective.WaitUntil(probeAt, selection.scope),
        )
        val scoped = buildRunner(
            preflight,
            runtime,
            decisions,
            wakeup,
            sharedCooldowns,
            lifecycle,
            submissionGate,
            convergenceModule = convergence,
            convergenceSelectionFactory = factory,
            rollout = AutomationConvergenceRollout(
                AutomationConvergenceProperties(mode = AutomationConvergenceMode.ACTIVE),
            ),
            evidenceInterpreter = productionEvidenceInterpreter,
        )

        scoped.runOne(7)

        assertIs<AutomationActionEvidence.SameState>(convergenceEvidence(convergence, 101L))
        Mockito.verify(managed).reconcile()
        Mockito.verify(managed, Mockito.never()).execute()
        assertIs<TypedRuntimeOutcome.AmbiguousHandoff>(capturedOutcome())
        Mockito.verify(runtime).complete(anyExecution(), anyOutcome(), Mockito.eq(probeAt))
    }

    @Test
    fun `전투 중 캡차는 계정 전투 gate만 열고 자동화 runtime을 중단하지 않는다`() {
        val convergence = Mockito.mock(AutomationActionConvergenceModule::class.java)
        val factory = StoredActionConvergenceSelectionFactory()
        val stored = raidStoredAction()
        val selection = factory.create(stored)
        val prepared = executionRight(
            TypedRuntimeCheckpoint(stored, TypedRuntimeCheckpointPhase.PREPARED, null, null),
        )
        Mockito.`when`(convergence.resumeDue(7L)).thenReturn(ConvergenceDirective.ContinueSelection)
        Mockito.`when`(decisions.select(7)).thenReturn(
            AutomationCoordination.Runnable(12, legacyBattleAction(), emptyList()),
        )
        Mockito.`when`(managed.storedAction).thenReturn(stored)
        Mockito.`when`(runtime.persistPrepared(anyExecution(), anyStoredAction(), anyWarnings()))
            .thenReturn(TypedRuntimePreparation.Ready(prepared))
        Mockito.`when`(runtime.beginSubmission(prepared))
            .thenReturn(TypedRuntimeSubmission.Started(Instant.EPOCH))
        Mockito.`when`(convergence.prepare(7L, selection)).thenAnswer { ConvergenceDirective.Submit(99L, it.getArgument(1)) }
        Mockito.`when`(convergence.record(Mockito.eq(99L), anyConvergenceEvidence())).thenReturn(
            ConvergenceDirective.BattleGateWait(Instant.EPOCH, "CAPTCHA_REQUIRED"),
        )
        Mockito.doThrow(ApiException(ErrorCode.CAPTCHA_REQUIRED, "captcha")).`when`(managed).execute()
        val scoped = buildRunner(
            preflight,
            runtime,
            decisions,
            wakeup,
            sharedCooldowns,
            lifecycle,
            submissionGate,
            convergenceModule = convergence,
            convergenceSelectionFactory = factory,
        )

        scoped.runOne(7)

        assertIs<AutomationActionEvidence.BattleGateRequired>(convergenceEvidence(convergence, 99L))
        assertIs<TypedRuntimeOutcome.BattleGateBlocked>(capturedOutcome())
        Mockito.verifyNoInteractions(wakeup)
    }

    @Test
    fun `SHADOW 전투 캡차도 저장 행동을 종료하고 계정 전투 gate를 연다`() {
        val convergence = Mockito.mock(AutomationActionConvergenceModule::class.java)
        val shadow = Mockito.mock(AutomationConvergenceShadowEvaluator::class.java)
        val factory = StoredActionConvergenceSelectionFactory()
        val stored = raidStoredAction()
        val prepared = executionRight(
            TypedRuntimeCheckpoint(stored, TypedRuntimeCheckpointPhase.PREPARED, null, null),
        )
        Mockito.`when`(decisions.select(7L)).thenReturn(
            AutomationCoordination.Runnable(12L, legacyBattleAction(), emptyList()),
        )
        Mockito.`when`(managed.storedAction).thenReturn(stored)
        Mockito.`when`(runtime.persistPrepared(anyExecution(), anyStoredAction(), anyWarnings()))
            .thenReturn(TypedRuntimePreparation.Ready(prepared))
        Mockito.`when`(runtime.beginSubmission(prepared))
            .thenReturn(TypedRuntimeSubmission.Started(Instant.EPOCH))
        Mockito.`when`(
            convergence.requireBattleGate(7L, null, ErrorCode.CAPTCHA_REQUIRED.name, Instant.EPOCH),
        ).thenReturn(ConvergenceDirective.BattleGateWait(Instant.EPOCH, ErrorCode.CAPTCHA_REQUIRED.name))
        Mockito.doThrow(ApiException(ErrorCode.CAPTCHA_REQUIRED, "captcha")).`when`(managed).execute()
        val scoped = buildRunner(
            preflight,
            runtime,
            decisions,
            wakeup,
            sharedCooldowns,
            lifecycle,
            submissionGate,
            convergenceModule = convergence,
            convergenceSelectionFactory = factory,
            timeProvider = TimeProvider { Instant.EPOCH },
            rollout = AutomationConvergenceRollout(
                AutomationConvergenceProperties(mode = AutomationConvergenceMode.SHADOW),
            ),
            shadowEvaluator = shadow,
            evidenceInterpreter = productionEvidenceInterpreter,
        )

        scoped.runOne(7L)

        Mockito.verify(convergence).requireBattleGate(
            7L,
            null,
            ErrorCode.CAPTCHA_REQUIRED.name,
            Instant.EPOCH,
        )
        val (evidence, decision) = shadowObservation(shadow, stored.executionIdentity)
        assertIs<AutomationActionEvidence.BattleGateRequired>(evidence)
        assertEquals(LegacyConvergenceDecision.HELD, decision)
        assertIs<TypedRuntimeOutcome.BattleGateBlocked>(capturedOutcome())
    }

    @Test
    fun `LEGACY 전투 캡차도 저장 행동을 종료하고 계정 전투 gate를 연다`() {
        val convergence = Mockito.mock(AutomationActionConvergenceModule::class.java)
        val factory = StoredActionConvergenceSelectionFactory()
        val stored = raidStoredAction()
        val prepared = executionRight(
            TypedRuntimeCheckpoint(stored, TypedRuntimeCheckpointPhase.PREPARED, null, null),
        )
        Mockito.`when`(decisions.select(7L)).thenReturn(
            AutomationCoordination.Runnable(12L, legacyBattleAction(), emptyList()),
        )
        Mockito.`when`(managed.storedAction).thenReturn(stored)
        Mockito.`when`(runtime.persistPrepared(anyExecution(), anyStoredAction(), anyWarnings()))
            .thenReturn(TypedRuntimePreparation.Ready(prepared))
        Mockito.`when`(runtime.beginSubmission(prepared))
            .thenReturn(TypedRuntimeSubmission.Started(Instant.EPOCH))
        Mockito.`when`(
            convergence.requireBattleGate(7L, null, ErrorCode.CAPTCHA_REQUIRED.name, Instant.EPOCH),
        ).thenReturn(ConvergenceDirective.BattleGateWait(Instant.EPOCH, ErrorCode.CAPTCHA_REQUIRED.name))
        Mockito.doThrow(ApiException(ErrorCode.CAPTCHA_REQUIRED, "captcha")).`when`(managed).execute()
        val scoped = buildRunner(
            preflight,
            runtime,
            decisions,
            wakeup,
            sharedCooldowns,
            lifecycle,
            submissionGate,
            convergenceModule = convergence,
            convergenceSelectionFactory = factory,
            timeProvider = TimeProvider { Instant.EPOCH },
            rollout = AutomationConvergenceRollout(
                AutomationConvergenceProperties(mode = AutomationConvergenceMode.LEGACY),
            ),
        )

        scoped.runOne(7L)

        Mockito.verify(convergence).requireBattleGate(
            7L,
            null,
            ErrorCode.CAPTCHA_REQUIRED.name,
            Instant.EPOCH,
        )
        assertIs<TypedRuntimeOutcome.BattleGateBlocked>(capturedOutcome())
    }

    @Test
    fun `전투 직전 상태 확인에서 캡차가 발생해도 battle gate를 열고 POST하지 않는다`() {
        val convergence = Mockito.mock(AutomationActionConvergenceModule::class.java)
        val factory = StoredActionConvergenceSelectionFactory()
        val stored = raidStoredAction()
        val selection = factory.create(stored)
        val prepared = executionRight(
            TypedRuntimeCheckpoint(stored, TypedRuntimeCheckpointPhase.PREPARED, null, null),
        )
        Mockito.`when`(convergence.resumeDue(7L)).thenReturn(ConvergenceDirective.ContinueSelection)
        Mockito.`when`(decisions.select(7)).thenReturn(
            AutomationCoordination.Runnable(12, legacyBattleAction(), emptyList()),
        )
        Mockito.`when`(managed.storedAction).thenReturn(stored)
        Mockito.`when`(runtime.persistPrepared(anyExecution(), anyStoredAction(), anyWarnings()))
            .thenReturn(TypedRuntimePreparation.Ready(prepared))
        Mockito.`when`(convergence.prepare(7L, selection)).thenAnswer { ConvergenceDirective.Submit(110L, it.getArgument(1)) }
        Mockito.`when`(convergence.record(Mockito.eq(110L), anyConvergenceEvidence())).thenReturn(
            ConvergenceDirective.BattleGateWait(Instant.EPOCH, "CAPTCHA_REQUIRED"),
        )
        Mockito.doThrow(ApiException(ErrorCode.CAPTCHA_REQUIRED, "captcha"))
            .`when`(managed)
            .validateBeforeSubmission()
        val scoped = buildRunner(
            preflight,
            runtime,
            decisions,
            wakeup,
            sharedCooldowns,
            lifecycle,
            submissionGate,
            convergenceModule = convergence,
            convergenceSelectionFactory = factory,
        )

        scoped.runOne(7L)

        assertIs<AutomationActionEvidence.BattleGateRequired>(convergenceEvidence(convergence, 110L))
        assertIs<TypedRuntimeOutcome.BattleGateBlocked>(capturedOutcome())
        Mockito.verify(managed, Mockito.never()).execute()
        Mockito.verify(runtime, Mockito.never()).beginSubmission(anyExecution())
    }

    private fun buildRunner(
        dailyPreflight: AutomationDailyPreflight,
        typedRuntime: TypedAutomationRuntimeService,
        decisionSource: AutomationDecisionSource,
        wakeupPort: AutomationWakeupPort,
        sharedBattleCooldowns: SharedBattleCooldownService,
        actionLifecycleModule: AutomationActionLifecycleModule,
        submissionGate: AccountExecutionSubmissionGate,
        decisionJournal: AutomationDecisionJournal? = null,
        convergenceModule: AutomationActionConvergenceModule? = null,
        convergenceSelectionFactory: StoredActionConvergenceSelectionFactory? = null,
        storedConvergenceActionLoader: StoredConvergenceActionLoader? = null,
        timeProvider: TimeProvider? = null,
        rollout: AutomationConvergenceRollout? = null,
        shadowEvaluator: AutomationConvergenceShadowEvaluator? = null,
        convergenceWorkPriority: AutomationConvergenceWorkPriority? = null,
        evidenceInterpreter: ProductionActionEvidenceInterpreter? = null,
        fishingService: FishingService = Mockito.mock(FishingService::class.java),
    ) = AutomationResultCoordinator(
            actionLifecycleModule, sharedBattleCooldowns, convergenceModule,
            convergenceSelectionFactory, storedConvergenceActionLoader, timeProvider,
            rollout, shadowEvaluator, evidenceInterpreter,
        ).let { resultCoordinator ->
        UnifiedAutomationRunner(
            dailyPreflight = dailyPreflight,
            typedRuntime = typedRuntime,
            decisionSource = decisionSource,
            wakeupPort = wakeupPort,
            sharedBattleCooldowns = sharedBattleCooldowns,
            actionLifecycleModule = actionLifecycleModule,
            submissionGate = submissionGate,
            decisionJournal = decisionJournal,
            timeProvider = timeProvider,
            convergenceWorkPriority = convergenceWorkPriority,
            fishingCycleModule = FishingCycleModule(fishingService, submissionGate, typedRuntime,
                actionLifecycleModule, resultCoordinator, decisionJournal, timeProvider),
            results = resultCoordinator,
        )
    }.also {
        convergenceModule?.takeIf { Mockito.mockingDetails(it).isMock }?.let { module ->
            Mockito.doAnswer { invocation ->
                val next = module.record(invocation.getArgument(0), invocation.getArgument(1))
                invocation.getArgument<(ConvergenceDirective) -> Unit>(2).invoke(next)
                next
            }.`when`(module).record(Mockito.anyLong(), anyConvergenceEvidence(),
                Mockito.any<(ConvergenceDirective) -> Unit>() ?: {})
        }
    }

    private fun capturedOutcome(): TypedRuntimeOutcome {
        val captor = ArgumentCaptor.forClass(TypedRuntimeOutcome::class.java)
        Mockito.verify(runtime).complete(anyExecution(), captureOutcome(captor), Mockito.nullable(Instant::class.java))
        return captor.value
    }

    private fun shadowRunner(shadow: AutomationConvergenceShadowEvaluator) = buildRunner(
        preflight,
        runtime,
        decisions,
        wakeup,
        sharedCooldowns,
        lifecycle,
        submissionGate,
        convergenceModule = Mockito.mock(AutomationActionConvergenceModule::class.java),
        convergenceSelectionFactory = StoredActionConvergenceSelectionFactory(),
        rollout = AutomationConvergenceRollout(
            AutomationConvergenceProperties(mode = AutomationConvergenceMode.SHADOW),
        ),
        shadowEvaluator = shadow,
        evidenceInterpreter = productionEvidenceInterpreter,
    )

    private fun shadowObservation(
        shadow: AutomationConvergenceShadowEvaluator,
        executionIdentity: String,
    ): Pair<AutomationActionEvidence, LegacyConvergenceDecision> {
        val evidenceCaptor = ArgumentCaptor.forClass(AutomationActionEvidence::class.java)
        val decisionCaptor = ArgumentCaptor.forClass(LegacyConvergenceDecision::class.java)
        Mockito.verify(shadow).observe(
            Mockito.eq(7L),
            Mockito.eq(executionIdentity) ?: executionIdentity,
            captureConvergenceEvidence(evidenceCaptor),
            captureLegacyDecision(decisionCaptor),
        )
        return evidenceCaptor.value to decisionCaptor.value
    }

    private fun executionRight(checkpoint: TypedRuntimeCheckpoint? = null): TypedRuntimeExecutionRight =
        Mockito.mock(TypedRuntimeExecutionRight::class.java).also {
            Mockito.`when`(it.checkpoint).thenReturn(checkpoint)
        }

    private fun defaultStoredAction() = StoredTypedAutomationAction(
        12,
        "default-execution",
        StoredTypedActionPayload.QuestClaim("quest", "claim"),
    )

    private fun defaultDescriptor() = AutomationActionDescriptor(
        source = AutomationType.QUEST,
        storageKind = "QUEST_CLAIM",
        actionKind = "QUEST_CLAIM",
        actionLabel = "퀘스트 완료",
        context = "퀘스트 보상 수령 · quest",
        targetKey = "quest",
    )

    private fun fishingStartStored() = StoredTypedAutomationAction(
        15L,
        "start-1",
        StoredTypedActionPayload.FishingTown(
            FishingAction.START,
            FishingPrimaryAction.START,
            18,
            progressDate = LocalDate.parse("2026-08-24"),
        ),
    )

    private fun fishingStartAction() = FishingTownAutomationAction(
        7L,
        FishingAction.START,
        FishingPrimaryAction.START,
        18,
        LocalDate.parse("2026-08-24"),
    )

    private fun fishingDescriptor(actionKind: String) = AutomationActionDescriptor(
        AutomationType.FISHING,
        "FISHING_TOWN",
        actionKind,
        "낚시",
        "낚시 사이클 $actionKind",
    )

    private fun fishingResponse(
        primaryAction: FishingPrimaryAction,
        remainingCasts: Int,
        outcome: FishingOutcome,
    ) = FishingResponse(
        notice = null,
        remainingCasts = remainingCasts,
        waterStatus = null,
        baitCount = null,
        shiningBaitCount = null,
        escapeSeconds = 30,
        combo = null,
        locationName = "일반 낚시터",
        primaryAction = primaryAction,
        availableActions = if (primaryAction == FishingPrimaryAction.CATCH) {
            setOf(FishingAction.CATCH)
        } else {
            setOf(FishingAction.START)
        },
        lastOutcome = outcome,
        blockedByBattle = false,
        battleTarget = null,
        catches = emptyList(),
        result = null,
    )

    private fun fishingExecution(response: FishingResponse) = TypedAutomationExecution.ActionCompleted(
        observedState = FishingObservedState(
            fingerprint = ProductionEvidenceShapes.fingerprint(
                "fishing|${response.primaryAction}|${response.remainingCasts}|${response.blockedByBattle}",
            ),
            primaryAction = response.primaryAction,
            remainingCasts = response.remainingCasts,
            lastOutcome = response.lastOutcome,
            blockedByBattle = response.blockedByBattle,
        ),
        responseShapeMaterial = ProductionEvidenceShapes.FISHING_RESPONSE,
        sanitizedSnippet = "FishingResponse|blockedByBattle=${response.blockedByBattle}",
    )

    private fun legacyBattleAction(recoveryChainId: String? = null) = BattleMapAutomationAction(
        accountId = 7,
        progressDate = LocalDate.parse("2026-07-25"),
        categoryId = "raid",
        mapCode = "castle",
        presetMode = PresetSelectionMode.PRIMARY,
        presetId = 301,
        battleCount = 1,
        executionIdentity = "legacy-battle",
        source = BattleAutomationActionSource.RAID_AUTOMATION,
        resolvedParty = ResolvedAutomationParty(
            listOf("character-1"),
            listOf(BattlePatternLoadRequest("character-1", 1)),
        ),
        sourceTargetKey = "RaidGoblin",
        recoveryChainId = recoveryChainId,
        raidSubmittedFromRunnable = true,
    )

    private fun raidStoredAction(recoveryChainId: String? = null) = StoredTypedAutomationAction(
        entryId = 12,
        executionIdentity = "raid-execution",
        payload = StoredTypedActionPayload.BattleMap(
            progressDate = LocalDate.parse("2026-07-25"),
            categoryId = "raid",
            mapCode = "castle",
            presetMode = PresetSelectionMode.PRIMARY,
            presetId = 301,
            battleCount = 1,
            battleRequest = app.spammy.hof.battle.dto.RunBattleRequest(
                "raid",
                "castle",
                listOf("character-1"),
                listOf(BattlePatternLoadRequest("character-1", 1)),
                1,
            ),
            source = BattleAutomationActionSource.RAID_AUTOMATION,
            sourceTargetKey = "RaidGoblin",
            recoveryChainId = recoveryChainId,
            raidSubmittedFromRunnable = true,
        ),
    )

    private fun anyExecution(): TypedRuntimeExecutionRight =
        Mockito.any(TypedRuntimeExecutionRight::class.java) ?: freshExecution

    private fun anyRunnable(): Runnable = Mockito.any(Runnable::class.java) ?: Runnable {}

    private fun anyOutcome(): TypedRuntimeOutcome =
        Mockito.any(TypedRuntimeOutcome::class.java) ?: TypedRuntimeOutcome.Idle

    private fun anyTypedExecution(): TypedAutomationExecution =
        Mockito.any(TypedAutomationExecution::class.java) ?: TypedAutomationExecution.Completed

    private fun captureOutcome(captor: ArgumentCaptor<TypedRuntimeOutcome>): TypedRuntimeOutcome =
        captor.capture() ?: TypedRuntimeOutcome.Idle

    private fun anyWarnings(): List<String> = Mockito.anyList<String>() ?: emptyList()

    private fun StoredTypedAutomationAction.withRecordedPolicy(
        selection: SelectedAutomationAction = StoredActionConvergenceSelectionFactory().create(this),
    ) = copy(policyContext = StoredActionPolicyContext(
        selection.policyVersion, selection.actionKind, selection.scope, selection.baselineFingerprint,
    ))

    private fun anyStoredAction(): StoredTypedAutomationAction =
        Mockito.any(StoredTypedAutomationAction::class.java) ?: defaultStored

    private fun anyPreparedAction(): PreparedAutomationAction =
        Mockito.any(PreparedAutomationAction::class.java) ?: legacyBattleAction()

    private fun anyFishingObservation(): FishingAutomationObservation? =
        Mockito.nullable(FishingAutomationObservation::class.java)

    private fun anyTownBoundary(): TownSubmissionBoundary =
        Mockito.any(TownSubmissionBoundary::class.java) ?: TownSubmissionBoundary { it() }

    private fun anyBeforeCatch(): (FishingResponse) -> Unit =
        Mockito.any<(FishingResponse) -> Unit>() ?: {}

    private fun anyCoordination(): AutomationCoordination =
        Mockito.any(AutomationCoordination::class.java) ?: AutomationCoordination.Idle(emptyList())

    private fun captureTrace(captor: ArgumentCaptor<AutomationActionTrace>): AutomationActionTrace =
        captor.capture() ?: AutomationActionTrace(
            AutomationHistoryEventKind.ACTION_SUCCEEDED,
            "capture",
            "capture",
        )

    private fun anyConvergenceEvidence(): AutomationActionEvidence =
        Mockito.any(AutomationActionEvidence::class.java)
            ?: AutomationActionEvidence.ResultUnobserved(Instant.EPOCH, "matcher")

    private fun anyConvergenceSelection(): SelectedAutomationAction =
        Mockito.any(SelectedAutomationAction::class.java) ?: SelectedAutomationAction(
            entryId = 1L,
            executionIdentity = "matcher",
            actionKind = AutomationActionKind.QUEST_CLAIM,
            scope = AutomationIsolationScope(AutomationIsolationScopeKind.QUEST_TARGET, "matcher"),
            policyVersion = "matcher",
            baselineFingerprint = "matcher",
        )

    private fun anyLegacyConvergenceDecision(): LegacyConvergenceDecision =
        Mockito.any(LegacyConvergenceDecision::class.java) ?: LegacyConvergenceDecision.APPLIED

    private fun anyInstantValue(): Instant = Mockito.any(Instant::class.java) ?: Instant.EPOCH

    private fun anyQuestSnapshotValue(): QuestAutomationSnapshot =
        Mockito.any(QuestAutomationSnapshot::class.java) ?: QuestAutomationSnapshot(
            accountId = 0,
            quests = emptyList(),
            selections = emptyList(),
            mapStates = emptyList(),
            currentCycles = emptyMap(),
            counters = emptyMap(),
            mapIdentityCandidates = emptyList(),
            now = Instant.EPOCH,
        )

    private fun captureLegacyDecision(
        captor: ArgumentCaptor<LegacyConvergenceDecision>,
    ): LegacyConvergenceDecision = captor.capture() ?: LegacyConvergenceDecision.APPLIED

    private fun convergenceEvidence(
        convergence: AutomationActionConvergenceModule,
        attemptId: Long,
        directResponse: Boolean = false,
    ): AutomationActionEvidence {
        val captor = ArgumentCaptor.forClass(AutomationActionEvidence::class.java)
        if (directResponse) Mockito.verify(convergence).recordDirectResponse(Mockito.eq(attemptId), captureConvergenceEvidence(captor))
        else Mockito.verify(convergence).record(Mockito.eq(attemptId), captureConvergenceEvidence(captor))
        return captor.value
    }

    private fun captureConvergenceEvidence(
        captor: ArgumentCaptor<AutomationActionEvidence>,
    ): AutomationActionEvidence = captor.capture()
            ?: AutomationActionEvidence.ResultUnobserved(Instant.EPOCH, "capture")

    private fun questClaimExecution() = TypedAutomationExecution.ActionCompleted(
        observedState = QuestObservedState(
            fingerprint = "quest-claim-response",
            present = true,
            state = QuestState.UNAVAILABLE,
            actionNo = null,
        ),
        responseShapeMaterial = ProductionEvidenceShapes.QUEST_RESPONSE,
        sanitizedSnippet = "QuestResponse|targetPresent=true|state=UNAVAILABLE|actionNoPresent=false|progressPresent=false",
    )

    private companion object {
    }
}
