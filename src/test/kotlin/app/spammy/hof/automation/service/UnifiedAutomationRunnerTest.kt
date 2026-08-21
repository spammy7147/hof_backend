package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.AutomationWaitReason
import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.automation.history.AutomationActionTrace
import app.spammy.hof.automation.history.AutomationDecisionJournal
import app.spammy.hof.automation.history.AutomationHistoryEventKind
import app.spammy.hof.automation.port.AutomationWakeupPort
import app.spammy.hof.battle.dto.BattlePatternLoadRequest
import app.spammy.hof.external.client.HofAutomationDeferredException
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
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
    private val managed = Mockito.mock(ManagedAutomationAction::class.java)
    private val freshExecution = executionRight()
    private val defaultStored = defaultStoredAction()
    private val preparedExecution = executionRight(
        TypedRuntimeCheckpoint(defaultStored, TypedRuntimeCheckpointPhase.PREPARED, null, null),
    )
    private val runner = UnifiedAutomationRunner(
        preflight,
        runtime,
        decisions,
        wakeup,
        sharedCooldowns,
        lifecycle,
    )

    init {
        Mockito.`when`(runtime.isRunning(7)).thenReturn(true)
        Mockito.`when`(preflight.ensureReady(7)).thenReturn(AutomationDailyPreflight.Result.Ready)
        Mockito.`when`(runtime.acquire(7)).thenReturn(TypedRuntimeAcquisition.Acquired(freshExecution))
        Mockito.`when`(managed.storedAction).thenReturn(defaultStored)
        Mockito.`when`(managed.descriptor).thenReturn(defaultDescriptor())
        Mockito.`when`(managed.execute()).thenReturn(TypedAutomationExecution.Completed)
        Mockito.`when`(lifecycle.describe(anyPreparedAction())).thenReturn(defaultDescriptor())
        Mockito.`when`(
            lifecycle.prepare(Mockito.eq(7L), Mockito.anyLong(), anyPreparedAction()),
        ).thenReturn(managed)
        Mockito.`when`(
            runtime.persistPrepared(anyExecution(), anyStoredAction(), anyWarnings()),
        ).thenReturn(TypedRuntimePreparation.Ready(preparedExecution))
        Mockito.`when`(runtime.beginSubmission(preparedExecution))
            .thenReturn(TypedRuntimeSubmission.Started(Instant.EPOCH))
        Mockito.`when`(runtime.complete(anyExecution(), anyOutcome())).thenAnswer { invocation ->
            val outcome = invocation.arguments[1] as TypedRuntimeOutcome
            val next = when (outcome) {
                is TypedRuntimeOutcome.ScheduledWait -> outcome.nextRunAt
                is TypedRuntimeOutcome.ConfigurationWait -> CONFIG_RECHECK_AT
                is TypedRuntimeOutcome.SafeRetry,
                is TypedRuntimeOutcome.RetryableFailure,
                is TypedRuntimeOutcome.IntegrityFailure,
                -> RETRY_AT
                is TypedRuntimeOutcome.SubmissionDeferred -> outcome.retryAt
                is TypedRuntimeOutcome.ReconciliationDeferred -> outcome.retryAt
                else -> null
            }
            TypedRuntimeProjection(true, next)
        }
    }

    @Test
    fun `runnable action crosses one durable runtime lifecycle and records descriptor based history`() {
        val journal = Mockito.mock(AutomationDecisionJournal::class.java)
        val action = legacyBattleAction()
        val decision = AutomationCoordination.Runnable(12, action, listOf("parked warning"))
        Mockito.`when`(decisions.select(7)).thenReturn(decision)
        Mockito.`when`(journal.appendDecision(Mockito.eq(7L), anyCoordination())).thenReturn(41L)
        val scoped = UnifiedAutomationRunner(
            preflight,
            runtime,
            decisions,
            wakeup,
            sharedCooldowns,
            lifecycle,
            journal,
        )

        scoped.runOne(7)

        Mockito.verify(lifecycle).prepare(7, 12, action)
        Mockito.verify(runtime).persistPrepared(freshExecution, defaultStored, listOf("parked warning"))
        Mockito.verify(runtime).beginSubmission(preparedExecution)
        Mockito.verify(managed).execute()
        assertIs<TypedRuntimeOutcome.ActionSucceeded>(capturedOutcome())
        val traceCaptor = ArgumentCaptor.forClass(AutomationActionTrace::class.java)
        Mockito.verify(journal, Mockito.times(2)).appendActionResult(Mockito.eq(41L), captureTrace(traceCaptor))
        assertTrue(traceCaptor.allValues.all { it.actionKind == "QUEST_CLAIM" })
        assertTrue(traceCaptor.allValues.all { it.message.startsWith("퀘스트 보상 수령 · quest") })
    }

    @Test
    fun `verified reconciliation checkpoint resubmits without blind execution`() {
        val stored = StoredTypedAutomationAction(
            12,
            "execution-1",
            StoredTypedActionPayload.QuestAccept("quest", "accept-no"),
        )
        val reconciling = executionRight(
            TypedRuntimeCheckpoint(
                stored,
                TypedRuntimeCheckpointPhase.RECONCILING,
                Instant.EPOCH,
                "unknown result",
            ),
        )
        Mockito.`when`(runtime.acquire(7)).thenReturn(TypedRuntimeAcquisition.Acquired(reconciling))
        Mockito.`when`(lifecycle.restoreVerified(stored, 7)).thenReturn(managed)
        Mockito.`when`(managed.storedAction).thenReturn(stored)
        Mockito.`when`(managed.reconcile()).thenReturn(AmbiguousActionResolution.Resubmit)

        runner.runOne(7)

        Mockito.verify(lifecycle).restoreVerified(stored, 7)
        Mockito.verify(managed).reconcile()
        Mockito.verify(managed, Mockito.never()).execute()
        val outcome = assertIs<TypedRuntimeOutcome.ReconciliationResubmit>(capturedOutcome())
        assertEquals("TYPED_RECONCILED_RESUBMIT", outcome.wakeReason)
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
    fun `HOF deferral preserves the submitted checkpoint and schedules its retry`() {
        val retryAt = Instant.parse("2026-07-24T00:00:56Z")
        Mockito.`when`(decisions.select(7)).thenReturn(
            AutomationCoordination.Runnable(12, legacyBattleAction(), emptyList()),
        )
        Mockito.doThrow(HofAutomationDeferredException(retryAt, 1)).`when`(managed).execute()

        runner.runOne(7)

        val outcome = assertIs<TypedRuntimeOutcome.SubmissionDeferred>(capturedOutcome())
        assertEquals(retryAt, outcome.retryAt)
        Mockito.verify(wakeup).schedule(7, retryAt, "HOF_503_COOLDOWN")
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
    fun `idle configuration warnings become a bounded runtime recheck`() {
        Mockito.`when`(decisions.select(7)).thenReturn(
            AutomationCoordination.Idle(listOf("missing primary")),
        )

        runner.runOne(7)

        val outcome = assertIs<TypedRuntimeOutcome.ConfigurationWait>(capturedOutcome())
        assertEquals(listOf("missing primary"), outcome.warnings)
        Mockito.verify(wakeup).schedule(7, CONFIG_RECHECK_AT, "TYPED_CONFIG_RECHECK")
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
        Mockito.verify(wakeup).schedule(7, RETRY_AT, "TYPED_AUTOMATIC_RETRY")
    }

    @Test
    fun `preflight retry releases the acquired right with user visible wait state`() {
        val retryAt = Instant.parse("2026-07-23T00:00:30Z")
        Mockito.`when`(preflight.ensureReady(7))
            .thenReturn(AutomationDailyPreflight.Result.RetryScheduled(retryAt, 0))

        runner.runOne(7)

        val outcome = assertIs<TypedRuntimeOutcome.ScheduledWait>(capturedOutcome())
        assertEquals(AutomationWaitReason.HOF_CONNECTION, outcome.waitReason)
        Mockito.verify(wakeup).schedule(7, retryAt, "DAILY_PREFLIGHT_RETRY")
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
        Mockito.verify(wakeup).schedule(7, RETRY_AT, "TYPED_AUTOMATIC_RETRY")
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
        val scoped = UnifiedAutomationRunner(
            preflight,
            runtime,
            decisions,
            wakeup,
            sharedCooldowns,
            lifecycle,
            journal,
        )

        scoped.runOne(7)

        val outcome = assertIs<TypedRuntimeOutcome.ActionSucceeded>(capturedOutcome())
        assertEquals("RAID_BATTLE_APPLIED_TERMINAL_RESULT", outcome.wakeReason)
        assertEquals(emptyList(), outcome.warnings)
        val traceCaptor = ArgumentCaptor.forClass(AutomationActionTrace::class.java)
        Mockito.verify(journal, Mockito.times(2)).appendActionResult(Mockito.eq(41L), captureTrace(traceCaptor))
        assertEquals("RAID_BATTLE_APPLIED_TERMINAL_RESULT", traceCaptor.allValues.last().reasonCode)
    }

    private fun capturedOutcome(): TypedRuntimeOutcome {
        val captor = ArgumentCaptor.forClass(TypedRuntimeOutcome::class.java)
        Mockito.verify(runtime).complete(anyExecution(), captureOutcome(captor))
        return captor.value
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

    private fun anyOutcome(): TypedRuntimeOutcome =
        Mockito.any(TypedRuntimeOutcome::class.java) ?: TypedRuntimeOutcome.Idle

    private fun captureOutcome(captor: ArgumentCaptor<TypedRuntimeOutcome>): TypedRuntimeOutcome =
        captor.capture() ?: TypedRuntimeOutcome.Idle

    private fun anyWarnings(): List<String> = Mockito.anyList<String>() ?: emptyList()

    private fun anyStoredAction(): StoredTypedAutomationAction =
        Mockito.any(StoredTypedAutomationAction::class.java) ?: defaultStored

    private fun anyPreparedAction(): PreparedAutomationAction =
        Mockito.any(PreparedAutomationAction::class.java) ?: legacyBattleAction()

    private fun anyCoordination(): AutomationCoordination =
        Mockito.any(AutomationCoordination::class.java) ?: AutomationCoordination.Idle(emptyList())

    private fun captureTrace(captor: ArgumentCaptor<AutomationActionTrace>): AutomationActionTrace =
        captor.capture() ?: AutomationActionTrace(
            AutomationHistoryEventKind.ACTION_SUCCEEDED,
            "capture",
            "capture",
        )

    private companion object {
        val RETRY_AT: Instant = Instant.parse("2026-07-25T00:05:00Z")
        val CONFIG_RECHECK_AT: Instant = Instant.parse("2026-07-25T00:10:00Z")
    }
}
