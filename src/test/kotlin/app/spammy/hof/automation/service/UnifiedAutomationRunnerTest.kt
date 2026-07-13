package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.AutomationModuleType
import app.spammy.hof.automation.policy.AutomationDecision
import app.spammy.hof.automation.policy.AutomationDecisionPolicy
import app.spammy.hof.automation.policy.AutomationDecisionType
import app.spammy.hof.automation.policy.AutomationSnapshot
import app.spammy.hof.automation.port.AutomationWakeupPort
import java.time.Instant
import kotlin.test.Test
import org.mockito.Mockito

class UnifiedAutomationRunnerTest {
    private val checkpoint = Mockito.mock(AutomationCheckpointService::class.java)
    private val snapshotLoader = Mockito.mock(AutomationSnapshotLoader::class.java)
    private val decisionPolicy = Mockito.mock(AutomationDecisionPolicy::class.java)
    private val executor = Mockito.mock(AutomationActionExecutor::class.java)
    private val wakeup = Mockito.mock(AutomationWakeupPort::class.java)
    private val runner = UnifiedAutomationRunner(checkpoint, snapshotLoader, decisionPolicy, executor, wakeup)

    @Test
    fun checkpointsExactlyOneActionThenWakesAFreshEvaluation() {
        val runnable = RunnableAutomationJob(jobId = 11L, accountId = 7L, currentStepIndex = 4)
        val snapshot = Mockito.mock(AutomationSnapshot::class.java)
        val decision = AutomationDecision(
            type = AutomationDecisionType.RUN_BATTLE,
            moduleType = AutomationModuleType.KEY_QUEST,
            questId = "0563",
        )
        Mockito.`when`(checkpoint.findRunnable(7L)).thenReturn(runnable)
        Mockito.`when`(snapshotLoader.load(7L)).thenReturn(snapshot)
        Mockito.`when`(decisionPolicy.decide(snapshot)).thenReturn(decision)
        Mockito.`when`(checkpoint.start(runnable, decision)).thenReturn(31L)
        Mockito.`when`(executor.execute(7L, decision)).thenReturn("{\"ok\":true}")

        runner.runOne(7L)

        Mockito.verify(executor, Mockito.times(1)).execute(7L, decision)
        Mockito.verify(checkpoint).succeed(31L, "{\"ok\":true}")
        Mockito.verify(wakeup).wake(7L, "ACTION_SUCCEEDED")
        Mockito.verifyNoMoreInteractions(executor)
    }

    @Test
    fun sleepOnlyPersistsTheNextWakeupWithoutCallingAnExternalExecutor() {
        val next = Instant.parse("2026-07-13T01:00:00Z")
        val runnable = RunnableAutomationJob(11L, 7L, 4)
        val snapshot = Mockito.mock(AutomationSnapshot::class.java)
        val decision = AutomationDecision(AutomationDecisionType.SLEEP, null, nextRunAt = next)
        Mockito.`when`(checkpoint.findRunnable(7L)).thenReturn(runnable)
        Mockito.`when`(snapshotLoader.load(7L)).thenReturn(snapshot)
        Mockito.`when`(decisionPolicy.decide(snapshot)).thenReturn(decision)

        runner.runOne(7L)

        Mockito.verify(checkpoint).sleep(11L, next)
        Mockito.verify(wakeup).schedule(7L, next, "SCHEDULED_RECHECK")
        Mockito.verifyNoInteractions(executor)
    }
}
