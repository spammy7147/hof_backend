package app.spammy.hof.automation.service

import app.spammy.hof.automation.raid.HofRaidObservationAdapter
import app.spammy.hof.automation.raid.RaidAttempt
import app.spammy.hof.automation.raid.RaidCycleModule
import app.spammy.hof.automation.raid.RaidCycleOutcome
import app.spammy.hof.automation.raid.RaidCycleOutcomeKind
import app.spammy.hof.automation.raid.RaidIntentKind
import app.spammy.hof.automation.raid.RaidObservation
import app.spammy.hof.automation.raid.RaidRecordResult
import app.spammy.hof.automation.raid.RaidResultObservation
import app.spammy.hof.account.service.HofAccountService
import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.battle.service.BattleRunService
import app.spammy.hof.town.raid.dto.RaidPubActionRequest
import app.spammy.hof.town.raid.dto.RaidPubResponse
import app.spammy.hof.town.raid.model.RaidAction
import app.spammy.hof.town.raid.service.RaidPubService
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.mockito.Mockito

class DefaultAutomationActionExecutorTest {
    private val accountService = Mockito.mock(HofAccountService::class.java)
    private val battleRun = Mockito.mock(BattleRunService::class.java)
    private val reconciler = Mockito.mock(BattleOutcomeReconciler::class.java)
    private val executionSignals = Mockito.mock(AutomationExecutionSignals::class.java)
    private val workLifecycle = Mockito.mock(AutomationWorkLifecycle::class.java)
    private val defaultRaidPub = Mockito.mock(RaidPubService::class.java)
    private val defaultRaidModule = Mockito.mock(RaidCycleModule::class.java)
    private val defaultRaidAdapter = Mockito.mock(HofRaidObservationAdapter::class.java)
    private val sessionRecovery = HofSessionRecoveryExecutor(HofSessionRecoveryService(accountService))
    private val battleSubmission = AutomationBattleSubmission(battleRun, sessionRecovery, reconciler)
    private val executor = DefaultAutomationActionExecutor(
        battleSubmission,
        executionSignals,
        workLifecycle,
        defaultRaidPub,
        defaultRaidModule,
        defaultRaidAdapter,
    )

    @Test
    fun `raid executor forwards the POST observation to the raid module instead of changing cycle state itself`() {
        val raidPub = Mockito.mock(RaidPubService::class.java)
        val raidModule = Mockito.mock(RaidCycleModule::class.java)
        val adapter = Mockito.mock(HofRaidObservationAdapter::class.java)
        val response = RaidPubResponse(emptyList(), false, false, null, null, emptySet(), null)
        val observation = RaidObservation(emptyList(), false, false)
        val raidExecutor = DefaultAutomationActionExecutor(
            battleSubmission,
            executionSignals,
            workLifecycle,
            raidPubService = raidPub,
            raidCycleModule = raidModule,
            raidObservationAdapter = adapter,
        )
        Mockito.`when`(raidPub.action(7L, RaidPubActionRequest(RaidAction.RESET, "RaidGoblin")))
            .thenReturn(response)
        Mockito.`when`(adapter.from(response)).thenReturn(observation)
        Mockito.`when`(raidModule.recordObservedResult(
            7L,
            RaidAttempt(13L, RaidIntentKind.RESET, "RaidGoblin"),
            RaidResultObservation.Page(observation),
        )).thenReturn(RaidRecordResult.Recorded())
        val action = StoredTypedAutomationAction(
            entryId = 13L,
            executionIdentity = "raid-reset-1",
            payload = StoredTypedActionPayload.RaidTown(RaidAction.RESET, "RaidGoblin"),
        )

        assertEquals(TypedAutomationExecution.Completed, raidExecutor.execute(7L, action))

        Mockito.verify(raidModule).recordObservedResult(
            7L,
            RaidAttempt(13L, RaidIntentKind.RESET, "RaidGoblin"),
            RaidResultObservation.Page(observation),
        )
        Mockito.verify(workLifecycle, Mockito.never()).completeRaidCycle(Mockito.anyLong(), Mockito.anyLong())
    }

    @Test
    fun `raid executor keeps an unproven POST result in reconciliation`() {
        val raidPub = Mockito.mock(RaidPubService::class.java)
        val raidModule = Mockito.mock(RaidCycleModule::class.java)
        val adapter = Mockito.mock(HofRaidObservationAdapter::class.java)
        val response = RaidPubResponse(emptyList(), false, false, null, null, emptySet(), null)
        val observation = RaidObservation(emptyList(), false, false)
        val raidExecutor = DefaultAutomationActionExecutor(
            battleSubmission,
            executionSignals,
            workLifecycle,
            raidPubService = raidPub,
            raidCycleModule = raidModule,
            raidObservationAdapter = adapter,
        )
        Mockito.`when`(raidPub.action(7L, RaidPubActionRequest(RaidAction.REGISTER, "RaidGoblin")))
            .thenReturn(response)
        Mockito.`when`(adapter.from(response)).thenReturn(observation)
        Mockito.`when`(raidModule.recordObservedResult(
            7L,
            RaidAttempt(13L, RaidIntentKind.REGISTER, "RaidGoblin"),
            RaidResultObservation.Page(observation),
        )).thenReturn(RaidRecordResult.NeedsRecheck(Instant.parse("2026-08-20T00:00:30Z"), "확인 필요"))
        val action = StoredTypedAutomationAction(
            entryId = 13L,
            executionIdentity = "raid-register-unproven",
            payload = StoredTypedActionPayload.RaidTown(RaidAction.REGISTER, "RaidGoblin"),
        )

        assertFailsWith<AmbiguousAutomationSubmissionException> {
            raidExecutor.execute(7L, action)
        }
    }

    @Test
    fun `raid executor closes the work session when the module records cycle completion`() {
        val raidPub = Mockito.mock(RaidPubService::class.java)
        val raidModule = Mockito.mock(RaidCycleModule::class.java)
        val adapter = Mockito.mock(HofRaidObservationAdapter::class.java)
        val response = RaidPubResponse(emptyList(), true, true, 10_000, null, emptySet(), null)
        val observation = RaidObservation(emptyList(), true, true, 10_000)
        val completion = RaidCycleOutcome(13L, "RaidGoblin", RaidCycleOutcomeKind.COMPLETED)
        val raidExecutor = DefaultAutomationActionExecutor(
            battleSubmission,
            executionSignals,
            workLifecycle,
            raidPubService = raidPub,
            raidCycleModule = raidModule,
            raidObservationAdapter = adapter,
        )
        Mockito.`when`(raidPub.action(7L, RaidPubActionRequest(RaidAction.REWARD, null))).thenReturn(response)
        Mockito.`when`(adapter.from(response)).thenReturn(observation)
        Mockito.`when`(raidModule.recordObservedResult(
            7L,
            RaidAttempt(13L, RaidIntentKind.REWARD, "RaidGoblin", null),
            RaidResultObservation.Page(observation),
        )).thenReturn(RaidRecordResult.Recorded(completion))
        val action = StoredTypedAutomationAction(
            entryId = 13L,
            executionIdentity = "raid-reward-complete",
            payload = StoredTypedActionPayload.RaidTown(RaidAction.REWARD, null, "RaidGoblin"),
        )

        assertEquals(TypedAutomationExecution.RaidCycleFinished(completion), raidExecutor.execute(7L, action))

        Mockito.verify(workLifecycle).completeRaidCycle(7L, 13L)
    }

}
