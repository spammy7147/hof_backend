package app.spammy.hof.automation.service

import app.spammy.hof.automation.policy.AutomationDecisionPolicy
import app.spammy.hof.automation.policy.AutomationDecisionType
import app.spammy.hof.automation.port.AutomationWakeupPort
import app.spammy.hof.automation.repository.TypedAutomationQueryRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

@Service
class TypedAutomationEngineSelector(
    private val queryRepository: TypedAutomationQueryRepository,
) {
    /** Mixed legacy/typed persistence intentionally resolves to typed; bean availability is irrelevant. */
    fun usesTypedEngine(accountId: Long): Boolean = queryRepository.hasTypedAutomation(accountId)
}

/** 최신 스냅샷 결정 또는 저장된 prepared payload 중 action 하나만 실행하는 통합 자동화 루프다. */
@Service
class UnifiedAutomationRunner(
    private val checkpointService: AutomationCheckpointService,
    private val snapshotLoader: AutomationSnapshotLoader,
    private val decisionPolicy: AutomationDecisionPolicy,
    private val actionExecutor: AutomationActionExecutor,
    private val wakeupPort: AutomationWakeupPort,
    private val dailyPreflight: AutomationDailyPreflight? = null,
    private val typedRuntime: TypedAutomationRuntimeService? = null,
    private val typedSnapshotLoader: TypedAutomationSnapshotLoader? = null,
    private val coordinator: AutomationCoordinator? = null,
    private val typedActionExecutor: TypedAutomationActionExecutor? = null,
    private val afterCommitWakeups: AutomationAfterCommitWakeupService? = null,
    private val typedCodec: StoredTypedAutomationActionCodec? = null,
    private val typedEngineSelector: TypedAutomationEngineSelector? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 한 wakeup에서 최대 action 하나만 실행하고 후속 판단은 새 wakeup과 새 스냅샷에 맡긴다. */
    fun runOne(accountId: Long) {
        if (typedEngineSelector?.usesTypedEngine(accountId) == true) {
            check(listOf(dailyPreflight, typedRuntime, typedSnapshotLoader, coordinator, typedActionExecutor, afterCommitWakeups, typedCodec).all { it != null }) {
                "Typed automation dependencies are incomplete."
            }
            runTyped(accountId)
            return
        }
        val runnable = checkpointService.findRunnable(accountId) ?: return
        runnable.retryAction?.let { retry ->
            val payload = if (retry.requiresPreparation) {
                try {
                    actionExecutor.prepare(runnable.accountId, retry.payload.decision)
                } catch (error: AutomationConfigurationException) {
                    checkpointService.blockRetryForConfig(runnable, retry, error.message)
                    return
                }
            } else {
                retry.payload
            }
            checkpointService.resumeRetry(runnable, retry, payload)?.let { token ->
                executeOne(token, runnable, payload)
            }
            return
        }
        val decision = decisionPolicy.decide(snapshotLoader.load(accountId))
        when (decision.type) {
            AutomationDecisionType.SLEEP -> {
                checkpointService.sleep(runnable.jobId, decision.nextRunAt)
                decision.nextRunAt?.let { wakeupPort.schedule(accountId, it, "SCHEDULED_RECHECK") }
            }
            AutomationDecisionType.WAITING_CONFIG ->
                checkpointService.blockForConfig(
                    runnable.jobId,
                    decision.message ?: "전투에 사용할 파티를 선택해 주세요.",
                )
            else -> {
                val payload = try {
                    actionExecutor.prepare(runnable.accountId, decision)
                } catch (error: AutomationConfigurationException) {
                    checkpointService.blockForConfig(
                        runnable.jobId,
                        error.message,
                    )
                    return
                }
                val token = checkpointService.start(runnable, payload)
                if (token == null) {
                    wakeupPort.wake(accountId, "STALE_MODULE")
                } else {
                    executeOne(token, runnable, payload)
                }
            }
        }
    }

    private fun runTyped(accountId: Long) {
        val runtime = typedRuntime!!
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "Typed automation runner must not be called with an active transaction."
        }
        when (val preflight = dailyPreflight!!.ensureReady(accountId)) {
            AutomationDailyPreflight.Result.Ready -> Unit
            is AutomationDailyPreflight.Result.Busy -> { wakeupPort.schedule(accountId, preflight.retryAt, "DAILY_PREFLIGHT_BUSY"); return }
            is AutomationDailyPreflight.Result.RetryScheduled -> { wakeupPort.schedule(accountId, preflight.nextAttemptAt, "DAILY_PREFLIGHT_RETRY"); return }
            is AutomationDailyPreflight.Result.Stopped -> {
                runtime.stop(
                    accountId,
                    when (preflight.reason) {
                        AutomationDailyPreflight.StopReason.NETWORK -> AutomationStopReason.NETWORK
                        AutomationDailyPreflight.StopReason.FATAL -> AutomationStopReason.FATAL
                    },
                )
                return
            }
        }
        val claim = runtime.claim(accountId)
        if (claim !is TypedRuntimeClaim.Acquired) return
        val token = claim.token
        val stored = claim.preparedAction?.let {
            runCatching { typedCodec!!.verifyPersisted(it, accountId) }.getOrElse { error ->
                log.warn("Stored typed action integrity failure accountId={} actionId={} errorType={}", accountId, it.id, error.javaClass.name)
                runtime.stop(accountId, token, it.id, AutomationStopReason.FATAL, "Stored typed action integrity check failed.")
                return
            }
        } ?: run {
            val decision = try {
                coordinator!!.coordinate(typedSnapshotLoader!!.loadTyped(accountId))
            } catch (_: TypedAutomationConfigurationChangedException) {
                runtime.release(accountId, token)
                wakeupPort.wake(accountId, "TYPED_CONFIG_RELOAD")
                return
            } catch (error: SafeRetryableAutomationException) {
                runtime.scheduleSafeRetry(accountId, token, error.message ?: "Safe snapshot retry")?.let {
                    wakeupPort.schedule(accountId, it, "TYPED_SAFE_RETRY")
                }
                return
            } catch (error: FatalAutomationException) {
                runtime.stop(accountId, token, null, AutomationStopReason.FATAL, error.message ?: "Fatal live snapshot failure")
                return
            }
            when (decision) {
                is AutomationCoordination.Runnable -> {
                    runtime.recordWarnings(accountId, token, decision.warnings)
                    toStored(decision.entryId, decision.action)
                }
                is AutomationCoordination.Fatal -> {
                    runtime.recordWarnings(accountId, token, decision.warnings)
                    runtime.stop(accountId, token, null, decision.reason, decision.message)
                    return
                }
                is AutomationCoordination.Unavailable -> {
                    runtime.releaseWithDiagnostics(accountId, token, decision.nextRunAt, decision.warnings)
                    wakeupPort.schedule(accountId, decision.nextRunAt, "TYPED_UNAVAILABLE")
                    return
                }
                is AutomationCoordination.Idle -> {
                    if (decision.warnings.isEmpty()) {
                        runtime.releaseWithDiagnostics(accountId, token, null, emptyList())
                    } else {
                        runtime.deferForConfiguration(accountId, token, decision.warnings)?.let {
                            wakeupPort.schedule(accountId, it, "TYPED_CONFIG_RECHECK")
                        }
                    }
                    return
                }
            }
        }
        val row = claim.preparedAction ?: runtime.prepare(accountId, token, stored) ?: run {
            runtime.release(accountId, token)
            wakeupPort.wake(accountId, "TYPED_CONFIG_RELOAD")
            return
        }
        if (!runtime.markSubmitting(accountId, token, row.id)) {
            runtime.release(accountId, token)
            return
        }
        try {
            typedActionExecutor!!.execute(accountId, stored)
            if (runtime.succeed(accountId, token, row.id)) afterCommitWakeups!!.wake(accountId, "TYPED_ACTION_COMPLETED")
        } catch (error: Throwable) {
            log.warn("Typed automation action stopped accountId={} actionId={} errorType={}", accountId, row.id, error.javaClass.name)
            runtime.stop(accountId, token, row.id, AutomationStopReason.NETWORK, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun toStored(entryId: Long, action: PreparedAutomationAction): StoredTypedAutomationActionV1 {
        val executionId = when (action) {
            is BattleMapAutomationAction -> action.executionIdentity
            is AdventureMapAutomationAction -> action.executionIdentity
            else -> UUID.randomUUID().toString()
        }
        val payload = when (action) {
            is QuestAction.Claim -> StoredTypedActionPayload.QuestClaim(action.questCode, action.actionNo)
            is QuestAction.Accept -> StoredTypedActionPayload.QuestAccept(action.questCode, action.actionNo)
            is QuestAction.Battle -> StoredTypedActionPayload.QuestBattle(
                action.questCode, action.questCycle, action.missionKey, action.missionType,
                action.categoryId, action.mapCode, action.preset.mode,
                action.preset.resolvedPresetId ?: action.preset.presetId ?: throw AutomationConfigurationException(), action.battleCount,
                action.resolvedParty.toRequest(action.categoryId, action.mapCode, action.battleCount),
            )
            is BattleMapAutomationAction -> StoredTypedActionPayload.BattleMap(
                action.progressDate, action.categoryId, action.mapCode, action.presetMode,
                action.presetId ?: throw AutomationConfigurationException(), action.battleCount,
                action.resolvedParty.toRequest(action.categoryId, action.mapCode, action.battleCount),
            )
            is AdventureMapAutomationAction -> StoredTypedActionPayload.AdventureMap(
                action.categoryId, action.mapCode, action.presetMode, action.presetId,
                action.battleCount, action.settingIdentity,
                action.resolvedParty.toRequest(action.categoryId, action.mapCode, action.battleCount),
            )
        }
        return StoredTypedAutomationActionV1(entryId, executionId, payload)
    }

    private fun ResolvedAutomationParty?.toRequest(categoryId: String, mapCode: String, battleCount: Int) =
        this?.let { app.spammy.hof.battle.dto.RunBattleRequest(categoryId, mapCode, it.characterIds, it.patternLoads, battleCount) }
            ?: throw AutomationConfigurationException("The prepared party is missing.")

    /** checkpoint가 저장한 단일 prepared payload만 실행하고 다음 판단은 별도 wakeup에 맡긴다. */
    private fun executeOne(
        token: AutomationExecutionToken,
        runnable: RunnableAutomationJob,
        payload: AutomationExecutionPayload,
    ) {
        runCatching {
            AutomationActionContext.withToken(token) {
                actionExecutor.execute(runnable.accountId, payload)
            }
        }
            .onSuccess {
                if (checkpointService.succeed(token)) {
                    wakeupPort.wake(runnable.accountId, "ACTION_SUCCEEDED")
                }
            }
            .onFailure { error ->
                log.warn(
                    "Automation action failed accountId={} jobId={} actionId={} type={} errorType={}",
                    runnable.accountId,
                    runnable.jobId,
                    token.actionId,
                    payload.decision.type,
                    error.javaClass.name,
                )
                checkpointService.fail(token, error)?.let { next ->
                    wakeupPort.schedule(runnable.accountId, next, "ACTION_RETRY")
                }
            }
    }
}
