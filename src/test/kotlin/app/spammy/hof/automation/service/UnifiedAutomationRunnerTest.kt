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
        val payload = payload(decision)
        Mockito.`when`(executor.prepare(7L, decision)).thenReturn(payload)
        Mockito.`when`(checkpoint.start(runnable, payload)).thenReturn(31L)
        Mockito.`when`(executor.execute(7L, payload)).thenReturn("{\"ok\":true}")

        runner.runOne(7L)

        Mockito.verify(executor).prepare(7L, decision)
        Mockito.verify(executor, Mockito.times(1)).execute(7L, payload)
        Mockito.verify(checkpoint).succeed(31L)
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
        val firstPayload = payload(first)
        val reorderedPayload = payload(reordered)
        Mockito.`when`(executor.prepare(7L, first)).thenReturn(firstPayload)
        Mockito.`when`(executor.prepare(7L, reordered)).thenReturn(reorderedPayload)
        Mockito.`when`(checkpoint.start(runnable, firstPayload)).thenReturn(31L)
        Mockito.`when`(checkpoint.start(runnable, reorderedPayload)).thenReturn(32L)
        Mockito.`when`(executor.execute(7L, firstPayload)).thenReturn("first-result")
        Mockito.`when`(executor.execute(7L, reorderedPayload)).thenReturn("second-result")

        runner.runOne(7L)
        runner.runOne(7L)

        val order = Mockito.inOrder(snapshotLoader, executor)
        order.verify(snapshotLoader).load(7L)
        order.verify(executor).execute(7L, firstPayload)
        order.verify(snapshotLoader).load(7L)
        order.verify(executor).execute(7L, reorderedPayload)
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
        val firstPayload = payload(first)
        val reorderedPayload = payload(reordered)
        Mockito.`when`(executor.prepare(7L, first)).thenReturn(firstPayload)
        Mockito.`when`(executor.prepare(7L, reordered)).thenReturn(reorderedPayload)
        Mockito.`when`(checkpoint.start(runnable, firstPayload)).thenReturn(31L)
        Mockito.`when`(checkpoint.start(runnable, reorderedPayload)).thenReturn(32L)
        Mockito.`when`(executor.execute(7L, firstPayload)).thenAnswer {
            entered.countDown()
            release.await(2, TimeUnit.SECONDS)
            "first-result"
        }
        Mockito.`when`(executor.execute(7L, reorderedPayload)).thenReturn("second-result")
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

        Mockito.verify(executor, Mockito.times(1)).execute(7L, firstPayload)
        Mockito.verify(executor, Mockito.times(1)).execute(7L, reorderedPayload)
    }

    @Test
    fun `stale module deleted before checkpoint start is not executed`() {
        val runnable = RunnableAutomationJob(11L, 7L, 0)
        val snapshot = Mockito.mock(AutomationSnapshot::class.java)
        val stale = battleDecision(404L, "deleted")
        Mockito.`when`(checkpoint.findRunnable(7L)).thenReturn(runnable)
        Mockito.`when`(snapshotLoader.load(7L)).thenReturn(snapshot)
        Mockito.`when`(decisionPolicy.decide(snapshot)).thenReturn(stale)
        val stalePayload = payload(stale)
        Mockito.`when`(executor.prepare(7L, stale)).thenReturn(stalePayload)
        Mockito.`when`(checkpoint.start(runnable, stalePayload)).thenReturn(null)

        runner.runOne(7L)

        Mockito.verify(executor, Mockito.never()).execute(Mockito.eq(7L), anyPayload())
        Mockito.verify(wakeup).wake(7L, "STALE_MODULE")
    }

    @Test
    fun `prepare configuration failure blocks the job before creating an action`() {
        val runnable = RunnableAutomationJob(11L, 7L, 0)
        val snapshot = Mockito.mock(AutomationSnapshot::class.java)
        val decision = battleDecision(101L, "missing-preset")
        Mockito.`when`(checkpoint.findRunnable(7L)).thenReturn(runnable)
        Mockito.`when`(snapshotLoader.load(7L)).thenReturn(snapshot)
        Mockito.`when`(decisionPolicy.decide(snapshot)).thenReturn(decision)
        Mockito.`when`(executor.prepare(7L, decision))
            .thenThrow(AutomationConfigurationException("파티 설정 필요"))

        runner.runOne(7L)

        Mockito.verify(checkpoint).findRunnable(7L)
        Mockito.verify(checkpoint).blockForConfig(11L, "파티 설정 필요")
        Mockito.verifyNoMoreInteractions(checkpoint)
        Mockito.verify(executor).prepare(7L, decision)
        Mockito.verifyNoMoreInteractions(executor)
    }

    @Test
    fun `retry executes the original persisted decision without loading a new snapshot`() {
        val original = battleDecision(505L, "original")
        val originalPayload = payload(original)
        val retry = RetryableAutomationAction(actionId = 91L, payload = originalPayload)
        val runnable = RunnableAutomationJob(11L, 7L, 4, retry)
        Mockito.`when`(checkpoint.findRunnable(7L)).thenReturn(runnable)
        Mockito.`when`(checkpoint.resumeRetry(runnable, retry)).thenReturn(true)
        Mockito.`when`(executor.execute(7L, originalPayload)).thenReturn("retry-result")

        runner.runOne(7L)

        Mockito.verifyNoInteractions(snapshotLoader, decisionPolicy)
        Mockito.verify(executor).execute(7L, originalPayload)
        Mockito.verify(checkpoint).succeed(91L)
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
        moduleRevision = Instant.parse("2026-07-14T00:00:00Z"),
        map = app.spammy.hof.automation.policy.AutomationMapCandidate(mapCode, mapCode, 0, 301L),
    )

    private fun payload(decision: AutomationDecision) = AutomationExecutionPayload(
        decision = decision,
        resolvedBattleRequest = app.spammy.hof.battle.dto.RunBattleRequest(
            categoryId = "battle_map",
            mapCode = decision.map?.mapCode ?: "map",
            characterIds = listOf("character-1"),
            patternLoads = listOf(app.spammy.hof.battle.dto.BattlePatternLoadRequest("character-1", 1)),
        ),
    )

    private fun anyPayload(): AutomationExecutionPayload =
        Mockito.any(AutomationExecutionPayload::class.java) ?: payload(battleDecision(999L, "any"))
}
