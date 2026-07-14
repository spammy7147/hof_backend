package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.AutomationModuleType
import app.spammy.hof.automation.policy.AutomationDecision
import app.spammy.hof.automation.policy.AutomationDecisionPolicy
import app.spammy.hof.automation.policy.AutomationDecisionType
import app.spammy.hof.automation.policy.AutomationSnapshot
import app.spammy.hof.automation.port.AutomationWakeupPort
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue
import org.mockito.Mockito

class UnifiedAutomationRunnerTest {
    private val checkpoint = Mockito.mock(AutomationCheckpointService::class.java)
    private val snapshotLoader = Mockito.mock(AutomationSnapshotLoader::class.java)
    private val decisionPolicy = Mockito.mock(AutomationDecisionPolicy::class.java)
    private val executor = Mockito.mock(AutomationActionExecutor::class.java)
    private val wakeup = Mockito.mock(AutomationWakeupPort::class.java)
    private val runner = UnifiedAutomationRunner(checkpoint, snapshotLoader, decisionPolicy, executor, wakeup)

    @Test
    fun `checkpoints exactly one action then wakes a fresh evaluation`() {
        val runnable = RunnableAutomationJob(jobId = 11L, accountId = 7L, currentStepIndex = 4)
        val snapshot = Mockito.mock(AutomationSnapshot::class.java)
        val decision = battleDecision(moduleId = 101L, mapCode = "first")
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
    fun `loads a fresh snapshot on each run so reordered modules apply to the next action`() {
        val runnable = RunnableAutomationJob(11L, 7L, 0)
        val firstSnapshot = Mockito.mock(AutomationSnapshot::class.java)
        val reorderedSnapshot = Mockito.mock(AutomationSnapshot::class.java)
        val first = battleDecision(101L, "first")
        val reordered = battleDecision(202L, "reordered")
        Mockito.`when`(checkpoint.findRunnable(7L)).thenReturn(runnable)
        Mockito.`when`(snapshotLoader.load(7L)).thenReturn(firstSnapshot, reorderedSnapshot)
        Mockito.`when`(decisionPolicy.decide(firstSnapshot)).thenReturn(first)
        Mockito.`when`(decisionPolicy.decide(reorderedSnapshot)).thenReturn(reordered)
        Mockito.`when`(checkpoint.start(runnable, first)).thenReturn(31L)
        Mockito.`when`(checkpoint.start(runnable, reordered)).thenReturn(32L)
        Mockito.`when`(executor.execute(7L, first)).thenReturn("first-result")
        Mockito.`when`(executor.execute(7L, reordered)).thenReturn("second-result")

        runner.runOne(7L)
        runner.runOne(7L)

        val order = Mockito.inOrder(snapshotLoader, executor)
        order.verify(snapshotLoader).load(7L)
        order.verify(executor).execute(7L, first)
        order.verify(snapshotLoader).load(7L)
        order.verify(executor).execute(7L, reordered)
    }

    @Test
    fun `reorder during a blocking executor does not replace the current decision`() {
        val runnable = RunnableAutomationJob(11L, 7L, 0)
        val firstSnapshot = Mockito.mock(AutomationSnapshot::class.java)
        val reorderedSnapshot = Mockito.mock(AutomationSnapshot::class.java)
        val first = battleDecision(101L, "first")
        val reordered = battleDecision(202L, "reordered")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Mockito.`when`(checkpoint.findRunnable(7L)).thenReturn(runnable)
        Mockito.`when`(snapshotLoader.load(7L)).thenReturn(firstSnapshot, reorderedSnapshot)
        Mockito.`when`(decisionPolicy.decide(firstSnapshot)).thenReturn(first)
        Mockito.`when`(decisionPolicy.decide(reorderedSnapshot)).thenReturn(reordered)
        Mockito.`when`(checkpoint.start(runnable, first)).thenReturn(31L)
        Mockito.`when`(checkpoint.start(runnable, reordered)).thenReturn(32L)
        Mockito.`when`(executor.execute(7L, first)).thenAnswer {
            entered.countDown()
            release.await(2, TimeUnit.SECONDS)
            "first-result"
        }
        Mockito.`when`(executor.execute(7L, reordered)).thenReturn("second-result")
        val pool = Executors.newSingleThreadExecutor()

        try {
            val firstRun = pool.submit { runner.runOne(7L) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            release.countDown()
            firstRun.get(2, TimeUnit.SECONDS)
            runner.runOne(7L)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }

        Mockito.verify(executor, Mockito.times(1)).execute(7L, first)
        Mockito.verify(executor, Mockito.times(1)).execute(7L, reordered)
    }

    @Test
    fun `stale module deleted before checkpoint start is not executed`() {
        val runnable = RunnableAutomationJob(11L, 7L, 0)
        val snapshot = Mockito.mock(AutomationSnapshot::class.java)
        val stale = battleDecision(404L, "deleted")
        Mockito.`when`(checkpoint.findRunnable(7L)).thenReturn(runnable)
        Mockito.`when`(snapshotLoader.load(7L)).thenReturn(snapshot)
        Mockito.`when`(decisionPolicy.decide(snapshot)).thenReturn(stale)
        Mockito.`when`(checkpoint.start(runnable, stale)).thenReturn(null)

        runner.runOne(7L)

        Mockito.verifyNoInteractions(executor)
        Mockito.verify(wakeup).wake(7L, "STALE_MODULE")
    }

    @Test
    fun `retry executes the original persisted decision without loading a new snapshot`() {
        val original = battleDecision(505L, "original")
        val retry = RetryableAutomationAction(actionId = 91L, decision = original)
        val runnable = RunnableAutomationJob(11L, 7L, 4, retry)
        Mockito.`when`(checkpoint.findRunnable(7L)).thenReturn(runnable)
        Mockito.`when`(checkpoint.resumeRetry(runnable, retry)).thenReturn(true)
        Mockito.`when`(executor.execute(7L, original)).thenReturn("retry-result")

        runner.runOne(7L)

        Mockito.verifyNoInteractions(snapshotLoader, decisionPolicy)
        Mockito.verify(executor).execute(7L, original)
        Mockito.verify(checkpoint).succeed(91L, "retry-result")
    }

    @Test
    fun `sleep only persists the next wakeup without calling an external executor`() {
        val next = Instant.parse("2026-07-13T01:00:00Z")
        val runnable = RunnableAutomationJob(11L, 7L, 4)
        val snapshot = Mockito.mock(AutomationSnapshot::class.java)
        val decision = AutomationDecision(AutomationDecisionType.SLEEP, null, moduleConfigId = null, nextRunAt = next)
        Mockito.`when`(checkpoint.findRunnable(7L)).thenReturn(runnable)
        Mockito.`when`(snapshotLoader.load(7L)).thenReturn(snapshot)
        Mockito.`when`(decisionPolicy.decide(snapshot)).thenReturn(decision)

        runner.runOne(7L)

        Mockito.verify(checkpoint).sleep(11L, next)
        Mockito.verify(wakeup).schedule(7L, next, "SCHEDULED_RECHECK")
        Mockito.verifyNoInteractions(executor)
    }

    private fun battleDecision(moduleId: Long, mapCode: String) = AutomationDecision(
        type = AutomationDecisionType.RUN_BATTLE,
        moduleType = AutomationModuleType.TIME_BURN,
        moduleConfigId = moduleId,
        map = app.spammy.hof.automation.policy.AutomationMapCandidate(mapCode, mapCode, 0, 301L),
    )
}
