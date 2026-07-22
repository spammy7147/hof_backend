package app.spammy.hof.automation.service

import app.spammy.hof.automation.port.AutomationWakeupPort
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.external.client.HofAutomationDeferredException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

/** 최신 타입별 스냅샷 결정 또는 저장된 prepared payload 중 action 하나만 실행하는 자동화 루프다. */
@Service
class UnifiedAutomationRunner(
    private val dailyPreflight: AutomationDailyPreflight,
    private val typedRuntime: TypedAutomationRuntimeService,
    private val typedSnapshotLoader: TypedAutomationSnapshotLoader,
    private val coordinator: AutomationCoordinator,
    private val typedActionExecutor: TypedAutomationActionExecutor,
    private val typedCodec: StoredTypedAutomationActionCodec,
    private val wakeupPort: AutomationWakeupPort,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 한 wakeup에서 최대 action 하나만 실행하고 후속 판단은 새 wakeup과 새 스냅샷에 맡긴다. */
    fun runOne(accountId: Long) {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "Typed automation runner must not be called with an active transaction."
        }
        if (!typedRuntime.isRunning(accountId)) return
        when (val preflight = dailyPreflight.ensureReady(accountId)) {
            AutomationDailyPreflight.Result.Ready -> Unit
            is AutomationDailyPreflight.Result.Busy -> { wakeupPort.schedule(accountId, preflight.retryAt, "DAILY_PREFLIGHT_BUSY"); return }
            is AutomationDailyPreflight.Result.RetryScheduled -> {
                if (typedRuntime.deferUntil(accountId, preflight.nextAttemptAt)) {
                    wakeupPort.schedule(accountId, preflight.nextAttemptAt, "DAILY_PREFLIGHT_RETRY")
                }
                return
            }
            is AutomationDailyPreflight.Result.Stopped -> {
                typedRuntime.stop(
                    accountId,
                    when (preflight.reason) {
                        AutomationDailyPreflight.StopReason.AUTHENTICATION -> AutomationStopReason.AUTHENTICATION
                        AutomationDailyPreflight.StopReason.CAPTCHA -> AutomationStopReason.CAPTCHA
                        AutomationDailyPreflight.StopReason.NETWORK -> AutomationStopReason.NETWORK
                        AutomationDailyPreflight.StopReason.FATAL -> AutomationStopReason.FATAL
                    },
                )
                return
            }
        }
        val claim = typedRuntime.claim(accountId)
        if (claim !is TypedRuntimeClaim.Acquired) return
        val token = claim.token
        val stored = claim.preparedAction?.let {
            runCatching { typedCodec.verifyPersisted(it, accountId) }.getOrElse { error ->
                log.warn("Stored typed action integrity failure accountId={} actionId={} errorType={}", accountId, it.id, error.javaClass.name)
                typedRuntime.stop(accountId, token, it.id, AutomationStopReason.FATAL, "Stored typed action integrity check failed.")
                return
            }
        } ?: run {
            val decision = try {
                coordinator.coordinate(typedSnapshotLoader.loadTyped(accountId))
            } catch (_: TypedAutomationConfigurationChangedException) {
                typedRuntime.releaseAndEnqueueWake(accountId, token, "TYPED_CONFIG_RELOAD")
                return
            } catch (error: HofAutomationDeferredException) {
                if (typedRuntime.release(accountId, token, error.retryAt)) {
                    wakeupPort.schedule(accountId, error.retryAt, HOF_COOLDOWN_WAKE_REASON)
                }
                return
            } catch (error: SafeRetryableAutomationException) {
                typedRuntime.scheduleSafeRetry(accountId, token, error.message ?: "Safe snapshot retry")?.let {
                    wakeupPort.schedule(accountId, it, "TYPED_SAFE_RETRY")
                }
                return
            } catch (error: AutomationLoginRequiredException) {
                typedRuntime.stop(
                    accountId,
                    token,
                    null,
                    AutomationStopReason.AUTHENTICATION,
                    error.message,
                )
                return
            } catch (error: ApiException) {
                val reason = when (error.errorCode) {
                    ErrorCode.CAPTCHA_REQUIRED -> AutomationStopReason.CAPTCHA
                    ErrorCode.HOF_LOGIN_FAILED, ErrorCode.HOF_SESSION_EXPIRED -> AutomationStopReason.AUTHENTICATION
                    else -> AutomationStopReason.FATAL
                }
                typedRuntime.stop(accountId, token, null, reason, error.message)
                return
            } catch (error: FatalAutomationException) {
                typedRuntime.stop(accountId, token, null, AutomationStopReason.FATAL, error.message ?: "Fatal live snapshot failure")
                return
            }
            when (decision) {
                is AutomationCoordination.Runnable -> {
                    typedRuntime.recordWarnings(accountId, token, decision.warnings)
                    toStored(decision.entryId, decision.action)
                }
                is AutomationCoordination.Fatal -> {
                    typedRuntime.recordWarnings(accountId, token, decision.warnings)
                    typedRuntime.stop(accountId, token, null, decision.reason, decision.message)
                    return
                }
                is AutomationCoordination.Unavailable -> {
                    typedRuntime.releaseWithDiagnostics(accountId, token, decision.nextRunAt, decision.warnings)
                    wakeupPort.schedule(accountId, decision.nextRunAt, "TYPED_UNAVAILABLE")
                    return
                }
                is AutomationCoordination.Idle -> {
                    if (decision.warnings.isEmpty()) {
                        typedRuntime.releaseWithDiagnostics(accountId, token, null, emptyList())
                    } else {
                        typedRuntime.deferForConfiguration(accountId, token, decision.warnings)?.let {
                            wakeupPort.schedule(accountId, it, "TYPED_CONFIG_RECHECK")
                        }
                    }
                    return
                }
            }
        }
        val row = claim.preparedAction ?: typedRuntime.prepare(accountId, token, stored) ?: run {
            typedRuntime.releaseAndEnqueueWake(accountId, token, "TYPED_CONFIG_RELOAD")
            return
        }
        if (!typedRuntime.markSubmitting(accountId, token, row.id)) {
            typedRuntime.releaseAndEnqueueWake(accountId, token, "TYPED_CONFIG_RELOAD")
            return
        }
        try {
            typedActionExecutor.execute(accountId, stored)
            typedRuntime.succeedAndEnqueueWake(accountId, token, row.id, "TYPED_ACTION_COMPLETED")
        } catch (error: Throwable) {
            error.findHofAutomationDeferral()?.let { deferred ->
                if (typedRuntime.deferSubmittedAction(
                        accountId,
                        token,
                        row.id,
                        deferred.retryAt,
                        deferred.message ?: "HOF server returned 503.",
                    )
                ) {
                    wakeupPort.schedule(accountId, deferred.retryAt, HOF_COOLDOWN_WAKE_REASON)
                }
                return
            }
            log.warn("Typed automation action stopped accountId={} actionId={} errorType={}", accountId, row.id, error.javaClass.name)
            typedRuntime.stop(
                accountId,
                token,
                row.id,
                classifyActionStop(error),
                error.message ?: error.javaClass.simpleName,
            )
        }
    }

    private fun classifyActionStop(error: Throwable): AutomationStopReason {
        val causes = generateSequence(error) { it.cause }.toList()
        if (causes.any { it is AutomationLoginRequiredException }) return AutomationStopReason.AUTHENTICATION
        causes.filterIsInstance<ApiException>().firstOrNull()?.let { api ->
            return when (api.errorCode) {
                ErrorCode.CAPTCHA_REQUIRED -> AutomationStopReason.CAPTCHA
                ErrorCode.HOF_LOGIN_FAILED, ErrorCode.HOF_SESSION_EXPIRED -> AutomationStopReason.AUTHENTICATION
                else -> AutomationStopReason.FATAL
            }
        }
        return if (causes.any { it is AmbiguousAutomationSubmissionException }) {
            AutomationStopReason.NETWORK
        } else {
            AutomationStopReason.FATAL
        }
    }

    private fun Throwable.findHofAutomationDeferral(): HofAutomationDeferredException? =
        generateSequence(this) { it.cause }.filterIsInstance<HofAutomationDeferredException>().firstOrNull()

    private fun toStored(entryId: Long, action: PreparedAutomationAction): StoredTypedAutomationActionV1 {
        val executionId = when (action) {
            is BattleMapAutomationAction -> action.executionIdentity
            is AdventureMapAutomationAction -> action.executionIdentity
            else -> UUID.randomUUID().toString()
        }
        val payload = when (action) {
            is QuestAction.Claim -> StoredTypedActionPayload.QuestClaim(
                action.questCode, action.actionNo, StoredActionDisplay(questName = action.questName),
            )
            is QuestAction.Accept -> StoredTypedActionPayload.QuestAccept(
                action.questCode, action.actionNo, StoredActionDisplay(questName = action.questName),
            )
            is QuestAction.Battle -> StoredTypedActionPayload.QuestBattle(
                action.questCode, action.questCycle, action.missionKey, action.missionType,
                action.categoryId, action.mapCode, action.preset.mode,
                action.preset.resolvedPresetId ?: action.preset.presetId ?: throw AutomationConfigurationException(), action.battleCount,
                action.resolvedParty.toRequest(action.categoryId, action.mapCode, action.battleCount),
                StoredActionDisplay(
                    action.questName,
                    action.missionLabel,
                    action.missionCurrent,
                    action.missionRequired,
                    action.mapName,
                ),
            )
            is BattleMapAutomationAction -> StoredTypedActionPayload.BattleMap(
                action.progressDate, action.categoryId, action.mapCode, action.presetMode,
                action.presetId ?: throw AutomationConfigurationException(), action.battleCount,
                action.resolvedParty.toRequest(action.categoryId, action.mapCode, action.battleCount),
                StoredActionDisplay(mapName = action.mapName),
            )
            is AdventureMapAutomationAction -> StoredTypedActionPayload.AdventureMap(
                action.categoryId, action.mapCode, action.presetMode, action.presetId,
                action.battleCount, action.settingIdentity,
                action.resolvedParty.toRequest(action.categoryId, action.mapCode, action.battleCount),
                StoredActionDisplay(mapName = action.mapName),
            )
        }
        return StoredTypedAutomationActionV1(entryId, executionId, payload)
    }

    private fun ResolvedAutomationParty?.toRequest(categoryId: String, mapCode: String, battleCount: Int) =
        this?.let { app.spammy.hof.battle.dto.RunBattleRequest(categoryId, mapCode, it.characterIds, it.patternLoads, battleCount) }
            ?: throw AutomationConfigurationException("The prepared party is missing.")

    private companion object {
        const val HOF_COOLDOWN_WAKE_REASON = "HOF_503_COOLDOWN"
    }
}
