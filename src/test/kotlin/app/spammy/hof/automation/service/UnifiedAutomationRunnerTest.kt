package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.AutomationModuleType
import app.spammy.hof.automation.entity.*
import app.spammy.hof.account.entity.HofAccountEntity
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
import tools.jackson.module.kotlin.jacksonObjectMapper

class UnifiedAutomationRunnerTest {
    private val checkpoint = Mockito.mock(AutomationCheckpointService::class.java)
    private val snapshotLoader = Mockito.mock(AutomationSnapshotLoader::class.java)
    private val decisionPolicy = Mockito.mock(AutomationDecisionPolicy::class.java)
    private val executor = Mockito.mock(AutomationActionExecutor::class.java)
    private val wakeup = Mockito.mock(AutomationWakeupPort::class.java)
    private val runner = UnifiedAutomationRunner(checkpoint, snapshotLoader, decisionPolicy, executor, wakeup)

    @Test
    fun `typed runner persists submits and checkpoints one action then wakes a fresh evaluation`() {
        val preflight = Mockito.mock(AutomationDailyPreflight::class.java)
        val runtime = Mockito.mock(TypedAutomationRuntimeService::class.java)
        val typedLoader = Mockito.mock(TypedAutomationSnapshotLoader::class.java)
        val typedCoordinator = Mockito.mock(AutomationCoordinator::class.java)
        val typedExecutor = Mockito.mock(TypedAutomationActionExecutor::class.java)
        val codec = StoredTypedAutomationActionCodec(jacksonObjectMapper())
        val engineSelector = Mockito.mock(TypedAutomationEngineSelector::class.java)
        val typedRunner = UnifiedAutomationRunner(
            checkpoint, snapshotLoader, decisionPolicy, executor, wakeup,
            preflight, runtime, typedLoader, typedCoordinator, typedExecutor, codec, engineSelector,
        )
        val snapshot = AutomationCoordinatorSnapshot(emptyList())
        val action = BattleMapAutomationAction(
            7, java.time.LocalDate.parse("2026-07-16"), "battle_map", "gb0",
            app.spammy.hof.automation.entity.PresetSelectionMode.PRIMARY, 301, 1, "execution-1",
            resolvedParty = ResolvedAutomationParty(
                listOf("character-1"),
                listOf(app.spammy.hof.battle.dto.BattlePatternLoadRequest("character-1", 1)),
            ),
        )
        val row = Mockito.mock(app.spammy.hof.automation.entity.TypedAutomationActionRunEntity::class.java)
        Mockito.`when`(row.id).thenReturn(88L)
        Mockito.`when`(engineSelector.usesTypedEngine(7)).thenReturn(true)
        Mockito.`when`(preflight.ensureReady(7)).thenReturn(AutomationDailyPreflight.Result.Ready)
        Mockito.`when`(runtime.claim(7)).thenReturn(TypedRuntimeClaim.Acquired("token"))
        Mockito.`when`(typedLoader.loadTyped(7)).thenReturn(snapshot)
        Mockito.`when`(typedCoordinator.coordinate(snapshot)).thenReturn(AutomationCoordination.Runnable(12, action, emptyList()))
        Mockito.`when`(runtime.prepare(Mockito.eq(7L), eqString("token"), anyStoredAction())).thenReturn(row)
        Mockito.`when`(runtime.markSubmitting(7, "token", 88L)).thenReturn(true)
        Mockito.`when`(runtime.succeedAndEnqueueWake(7, "token", 88L, "TYPED_ACTION_COMPLETED")).thenReturn(true)

        typedRunner.runOne(7)

        Mockito.verify(typedExecutor, Mockito.times(1)).execute(Mockito.eq(7L), anyStoredAction())
        Mockito.verify(runtime).recordWarnings(7, "token", emptyList())
        Mockito.verify(runtime).succeedAndEnqueueWake(7, "token", 88L, "TYPED_ACTION_COMPLETED")
        Mockito.verifyNoInteractions(checkpoint)
    }

    @Test
    fun `prepare and submitting races explicitly release each claimed token`() {
        val preflight = Mockito.mock(AutomationDailyPreflight::class.java)
        val runtime = Mockito.mock(TypedAutomationRuntimeService::class.java)
        val loader = Mockito.mock(TypedAutomationSnapshotLoader::class.java)
        val coordinator = Mockito.mock(AutomationCoordinator::class.java)
        val typedExecutor = Mockito.mock(TypedAutomationActionExecutor::class.java)
        val selector = Mockito.mock(TypedAutomationEngineSelector::class.java)
        val snapshot = AutomationCoordinatorSnapshot(emptyList())
        val action = QuestAction.Claim("quest", "claim")
        val row = Mockito.mock(TypedAutomationActionRunEntity::class.java)
        Mockito.`when`(row.id).thenReturn(88L)
        val typedRunner = UnifiedAutomationRunner(
            checkpoint, snapshotLoader, decisionPolicy, executor, wakeup, preflight, runtime, loader,
            coordinator, typedExecutor, StoredTypedAutomationActionCodec(jacksonObjectMapper()), selector,
        )
        Mockito.`when`(selector.usesTypedEngine(7)).thenReturn(true)
        Mockito.`when`(preflight.ensureReady(7)).thenReturn(AutomationDailyPreflight.Result.Ready)
        Mockito.`when`(runtime.claim(7)).thenReturn(TypedRuntimeClaim.Acquired("prepare-token"), TypedRuntimeClaim.Acquired("submit-token"))
        Mockito.`when`(loader.loadTyped(7)).thenReturn(snapshot)
        Mockito.`when`(coordinator.coordinate(snapshot)).thenReturn(AutomationCoordination.Runnable(12, action, listOf("warning")))
        Mockito.`when`(runtime.prepare(Mockito.eq(7L), eqString("prepare-token"), anyStoredAction())).thenReturn(null)
        Mockito.`when`(runtime.prepare(Mockito.eq(7L), eqString("submit-token"), anyStoredAction())).thenReturn(row)
        Mockito.`when`(runtime.markSubmitting(7, "submit-token", 88)).thenReturn(false)

        typedRunner.runOne(7)
        typedRunner.runOne(7)

        Mockito.verify(runtime).releaseAndEnqueueWake(7, "prepare-token", "TYPED_CONFIG_RELOAD")
        Mockito.verify(runtime).releaseAndEnqueueWake(7, "submit-token", "TYPED_CONFIG_RELOAD")
        Mockito.verify(typedExecutor, Mockito.never()).execute(Mockito.eq(7L), anyStoredAction())
    }

    @Test
    fun `configuration change releases lease and requests immediate durable reload`() {
        val preflight = Mockito.mock(AutomationDailyPreflight::class.java)
        val runtime = Mockito.mock(TypedAutomationRuntimeService::class.java)
        val loader = Mockito.mock(TypedAutomationSnapshotLoader::class.java)
        val selector = Mockito.mock(TypedAutomationEngineSelector::class.java)
        val typedRunner = UnifiedAutomationRunner(
            checkpoint, snapshotLoader, decisionPolicy, executor, wakeup, preflight, runtime, loader,
            Mockito.mock(AutomationCoordinator::class.java), Mockito.mock(TypedAutomationActionExecutor::class.java),
            StoredTypedAutomationActionCodec(jacksonObjectMapper()), selector,
        )
        Mockito.`when`(selector.usesTypedEngine(7)).thenReturn(true)
        Mockito.`when`(preflight.ensureReady(7)).thenReturn(AutomationDailyPreflight.Result.Ready)
        Mockito.`when`(runtime.claim(7)).thenReturn(TypedRuntimeClaim.Acquired("token"))
        Mockito.`when`(loader.loadTyped(7)).thenThrow(TypedAutomationConfigurationChangedException())

        typedRunner.runOne(7)

        Mockito.verify(runtime).releaseAndEnqueueWake(7, "token", "TYPED_CONFIG_RELOAD")
        Mockito.verifyNoInteractions(wakeup)
    }

    @Test
    fun `production dependencies still execute legacy runner for a legacy-only account`() {
        val preflight = Mockito.mock(AutomationDailyPreflight::class.java)
        val runtime = Mockito.mock(TypedAutomationRuntimeService::class.java)
        val typedLoader = Mockito.mock(TypedAutomationSnapshotLoader::class.java)
        val typedCoordinator = Mockito.mock(AutomationCoordinator::class.java)
        val typedExecutor = Mockito.mock(TypedAutomationActionExecutor::class.java)
        val engineSelector = Mockito.mock(TypedAutomationEngineSelector::class.java)
        val productionRunner = UnifiedAutomationRunner(
            checkpoint, snapshotLoader, decisionPolicy, executor, wakeup,
            preflight, runtime, typedLoader, typedCoordinator, typedExecutor,
            StoredTypedAutomationActionCodec(jacksonObjectMapper()), engineSelector,
        )
        val runnable = RunnableAutomationJob(11, 7, 0)
        val snapshot = Mockito.mock(AutomationSnapshot::class.java)
        val decision = AutomationDecision(AutomationDecisionType.SLEEP, AutomationModuleType.TIME_BURN, nextRunAt = null)
        Mockito.`when`(engineSelector.usesTypedEngine(7)).thenReturn(false)
        Mockito.`when`(checkpoint.findRunnable(7)).thenReturn(runnable)
        Mockito.`when`(snapshotLoader.load(7)).thenReturn(snapshot)
        Mockito.`when`(decisionPolicy.decide(snapshot)).thenReturn(decision)

        productionRunner.runOne(7)

        Mockito.verify(checkpoint).sleep(11, null)
        Mockito.verifyNoInteractions(preflight, runtime, typedLoader, typedCoordinator, typedExecutor)
    }

    @Test
    fun `typed configuration wins even before runtime is started and never falls through to legacy`() {
        val preflight = Mockito.mock(AutomationDailyPreflight::class.java)
        val runtime = Mockito.mock(TypedAutomationRuntimeService::class.java)
        val typedLoader = Mockito.mock(TypedAutomationSnapshotLoader::class.java)
        val typedCoordinator = Mockito.mock(AutomationCoordinator::class.java)
        val typedExecutor = Mockito.mock(TypedAutomationActionExecutor::class.java)
        val engineSelector = Mockito.mock(TypedAutomationEngineSelector::class.java)
        val productionRunner = UnifiedAutomationRunner(
            checkpoint, snapshotLoader, decisionPolicy, executor, wakeup,
            preflight, runtime, typedLoader, typedCoordinator, typedExecutor,
            StoredTypedAutomationActionCodec(jacksonObjectMapper()), engineSelector,
        )
        Mockito.`when`(engineSelector.usesTypedEngine(7)).thenReturn(true)
        Mockito.`when`(preflight.ensureReady(7)).thenReturn(AutomationDailyPreflight.Result.Ready)
        Mockito.`when`(runtime.claim(7)).thenReturn(TypedRuntimeClaim.Inactive)

        productionRunner.runOne(7)

        Mockito.verify(runtime).claim(7)
        Mockito.verifyNoInteractions(checkpoint, snapshotLoader, decisionPolicy, executor)
    }

    @Test
    fun `tampered stored envelopes stop fatally before submitting or posting`() {
        val codec = StoredTypedAutomationActionCodec(jacksonObjectMapper())
        val stored = StoredTypedAutomationActionV1(12, "execution-1", StoredTypedActionPayload.QuestClaim("quest", "claim"))
        val encoded = codec.encode(stored)
        data class Corruption(
            val name: String,
            val accountId: Long = 7,
            val entryId: Long = 12,
            val execution: String = "execution-1",
            val kind: String = "QUEST_CLAIM",
            val schema: Int = 1,
            val json: String = encoded.json,
            val fingerprint: String = encoded.fingerprint,
        )
        val changedPayload = codec.encode(stored.copy(payload = StoredTypedActionPayload.QuestClaim("tampered", "claim"))).json
        val whitespacePayload = "  ${encoded.json}\n"
        val reorderedPayload = "{\"executionIdentity\":\"execution-1\",\"entryId\":12,\"payload\":{\"kind\":\"QUEST_CLAIM\",\"questCode\":\"quest\",\"actionNo\":\"claim\"}}"
        val cases = listOf(
            Corruption("payload", json = changedPayload),
            Corruption("whitespace-bytes", json = whitespacePayload),
            Corruption("reordered-bytes", json = reorderedPayload),
            Corruption("fingerprint", fingerprint = "0".repeat(64)),
            Corruption("kind", kind = "QUEST_ACCEPT"),
            Corruption("account", accountId = 8),
            Corruption("entry", entryId = 13),
            Corruption("execution", execution = "different"),
            Corruption("schema", schema = 99),
        )
        cases.forEach { corruption ->
            val preflight = Mockito.mock(AutomationDailyPreflight::class.java)
            val runtime = Mockito.mock(TypedAutomationRuntimeService::class.java)
            val typedLoader = Mockito.mock(TypedAutomationSnapshotLoader::class.java)
            val typedCoordinator = Mockito.mock(AutomationCoordinator::class.java)
            val typedExecutor = Mockito.mock(TypedAutomationActionExecutor::class.java)
            val selector = Mockito.mock(TypedAutomationEngineSelector::class.java)
            val owner = HofAccountEntity(corruption.accountId, "login-${corruption.name}", "encrypted", Instant.EPOCH)
            val entry = AutomationEntryEntity(corruption.entryId, owner, AutomationType.QUEST, 0, true, Instant.EPOCH, Instant.EPOCH)
            val row = TypedAutomationActionRunEntity(
                88, owner, entry, corruption.execution, corruption.kind, corruption.schema, corruption.json,
                corruption.fingerprint, TypedAutomationActionStatus.PREPARED, leaseToken = "token",
                createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
            )
            val typedRunner = UnifiedAutomationRunner(
                checkpoint, snapshotLoader, decisionPolicy, executor, wakeup, preflight, runtime,
                typedLoader, typedCoordinator, typedExecutor, codec, selector,
            )
            Mockito.`when`(selector.usesTypedEngine(7)).thenReturn(true)
            Mockito.`when`(preflight.ensureReady(7)).thenReturn(AutomationDailyPreflight.Result.Ready)
            Mockito.`when`(runtime.claim(7)).thenReturn(TypedRuntimeClaim.Acquired("token", row))

            typedRunner.runOne(7)

            Mockito.verify(runtime).stop(7, "token", 88, AutomationStopReason.FATAL, "Stored typed action integrity check failed.")
            Mockito.verifyNoInteractions(typedExecutor)
        }
    }

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
        val token = AutomationExecutionToken(31L, 1)
        Mockito.`when`(checkpoint.start(runnable, payload)).thenReturn(token)
        Mockito.`when`(checkpoint.succeed(token)).thenReturn(true)
        Mockito.`when`(executor.execute(7L, payload)).thenReturn("{\"ok\":true}")

        runner.runOne(7L)

        Mockito.verify(executor).prepare(7L, decision)
        Mockito.verify(executor, Mockito.times(1)).execute(7L, payload)
        Mockito.verify(checkpoint).succeed(token)
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
        Mockito.`when`(checkpoint.start(runnable, firstPayload)).thenReturn(AutomationExecutionToken(31L, 1))
        Mockito.`when`(checkpoint.start(runnable, reorderedPayload)).thenReturn(AutomationExecutionToken(32L, 1))
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
        Mockito.`when`(checkpoint.start(runnable, firstPayload)).thenReturn(AutomationExecutionToken(31L, 1))
        Mockito.`when`(checkpoint.start(runnable, reorderedPayload)).thenReturn(AutomationExecutionToken(32L, 1))
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
        val token = AutomationExecutionToken(91L, 2)
        Mockito.`when`(checkpoint.resumeRetry(runnable, retry, originalPayload)).thenReturn(token)
        Mockito.`when`(executor.execute(7L, originalPayload)).thenReturn("retry-result")

        runner.runOne(7L)

        Mockito.verifyNoInteractions(snapshotLoader, decisionPolicy)
        Mockito.verify(executor).execute(7L, originalPayload)
        Mockito.verify(executor, Mockito.never()).prepare(Mockito.anyLong(), anyDecision())
        Mockito.verify(checkpoint).succeed(token)
    }

    @Test
    fun `legacy retry is prepared once upgraded transactionally and executed with the exact payload`() {
        val legacyDecision = battleDecision(505L, "legacy")
        val unresolved = AutomationExecutionPayload(legacyDecision)
        val prepared = payload(legacyDecision)
        val retry = RetryableAutomationAction(
            actionId = 91L,
            payload = unresolved,
            requiresPreparation = true,
        )
        val runnable = RunnableAutomationJob(11L, 7L, 4, retry)
        val token = AutomationExecutionToken(91L, 3)
        Mockito.`when`(checkpoint.findRunnable(7L)).thenReturn(runnable)
        Mockito.`when`(executor.prepare(7L, legacyDecision)).thenReturn(prepared)
        Mockito.`when`(checkpoint.resumeRetry(runnable, retry, prepared)).thenReturn(token)
        Mockito.`when`(executor.execute(7L, prepared)).thenReturn("legacy-retry-result")

        runner.runOne(7L)

        Mockito.verifyNoInteractions(snapshotLoader, decisionPolicy)
        Mockito.verify(executor).prepare(7L, legacyDecision)
        Mockito.verify(checkpoint).resumeRetry(runnable, retry, prepared)
        Mockito.verify(executor).execute(7L, prepared)
        Mockito.verify(checkpoint).succeed(token)
    }

    @Test
    fun `legacy retry configuration failure blocks without starting the external request`() {
        val legacyDecision = battleDecision(505L, "legacy-missing-preset")
        val retry = RetryableAutomationAction(
            actionId = 91L,
            payload = AutomationExecutionPayload(legacyDecision),
            requiresPreparation = true,
        )
        val runnable = RunnableAutomationJob(11L, 7L, 4, retry)
        Mockito.`when`(checkpoint.findRunnable(7L)).thenReturn(runnable)
        Mockito.`when`(executor.prepare(7L, legacyDecision))
            .thenThrow(AutomationConfigurationException("파티 설정을 확인해 주세요."))

        runner.runOne(7L)

        Mockito.verify(checkpoint).blockRetryForConfig(runnable, retry, "파티 설정을 확인해 주세요.")
        Mockito.verify(executor, Mockito.never()).execute(Mockito.anyLong(), anyPayload())
        Mockito.verify(checkpoint, Mockito.never()).resumeRetry(
            anyRunnable(),
            anyRetry(),
            anyPayload(),
        )
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

    private fun anyDecision(): AutomationDecision =
        Mockito.any(AutomationDecision::class.java) ?: battleDecision(999L, "any")

    private fun anyRunnable(): RunnableAutomationJob =
        Mockito.any(RunnableAutomationJob::class.java) ?: RunnableAutomationJob(999L, 999L, 0)

    private fun anyRetry(): RetryableAutomationAction =
        Mockito.any(RetryableAutomationAction::class.java)
            ?: RetryableAutomationAction(999L, payload(battleDecision(999L, "any")))

    private fun anyStoredAction(): StoredTypedAutomationActionV1 =
        Mockito.any(StoredTypedAutomationActionV1::class.java)
            ?: StoredTypedAutomationActionV1(1, "any", StoredTypedActionPayload.QuestClaim("q", "a"))

    private fun eqString(value: String): String = Mockito.eq(value) ?: value
}
