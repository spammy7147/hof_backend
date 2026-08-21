package app.spammy.hof.automation.service

import app.spammy.hof.automation.raid.HofRaidObservationAdapter
import app.spammy.hof.automation.raid.RaidAttempt
import app.spammy.hof.automation.raid.RaidCycleModule
import app.spammy.hof.automation.raid.RaidCycleOutcomeKind
import app.spammy.hof.automation.raid.RaidIntentKind
import app.spammy.hof.automation.raid.RaidRecordResult
import app.spammy.hof.automation.raid.RaidResultObservation
import app.spammy.hof.common.time.TimeProvider
import org.springframework.stereotype.Service

@Service
class DefaultAutomationAmbiguousActionReconciler(
    private val battleOutcomeReconciler: BattleOutcomeReconciler,
    private val timeProvider: TimeProvider,
    private val workLifecycle: AutomationWorkLifecycle,
    private val raidCycleModule: RaidCycleModule,
    private val raidObservationAdapter: HofRaidObservationAdapter,
) : AutomationAmbiguousActionReconciler {
    override fun reconcile(
        accountId: Long,
        action: StoredTypedAutomationAction,
    ): AmbiguousActionResolution = when (val payload = action.payload) {
        is StoredTypedActionPayload.BattleMap -> {
            if (payload.source == BattleAutomationActionSource.RAID_AUTOMATION) {
                reconcileRaidBattle(accountId, action.entryId, action.executionIdentity, payload)
            } else {
                error("Stored battle source ${payload.source} belongs to the action lifecycle module.")
            }
        }
        is StoredTypedActionPayload.RaidTown -> reconcileRaid(accountId, action.entryId, payload)
        is StoredTypedActionPayload.RaidCycleAbort -> reconcileRaidAbort(accountId, action.entryId, payload)
        else -> error("Stored action ${payload.kind()} belongs to the action lifecycle module.")
    }

    private fun reconcileRaid(accountId: Long, entryId: Long, payload: StoredTypedActionPayload.RaidTown): AmbiguousActionResolution {
        val targetRaidId = payload.targetRaidId ?: payload.raidId
            ?: return AmbiguousActionResolution.VerifyLater(retryAt(), "저장된 레이드 대상이 없습니다.")
        val attempt = RaidAttempt(entryId, payload.action.toRaidIntentKind(), targetRaidId, payload.raidId)
        val observation = RaidResultObservation.Page(raidObservationAdapter.read(accountId))
        return recordRaid(accountId, attempt, observation)
    }

    private fun reconcileRaidAbort(
        accountId: Long,
        entryId: Long,
        payload: StoredTypedActionPayload.RaidCycleAbort,
    ): AmbiguousActionResolution {
        val outcome = when (payload.reason) {
            RaidCycleAbortReason.CLOSED -> RaidCycleOutcomeKind.ABORTED_CLOSED
            RaidCycleAbortReason.REGISTRATION_LOST -> RaidCycleOutcomeKind.ABORTED_REGISTRATION_LOST
        }
        return recordRaid(
            accountId,
            RaidAttempt(entryId, RaidIntentKind.REFRESH, payload.raidId, null),
            RaidResultObservation.LegacyCycleAbort(outcome),
        )
    }

    private fun recordRaid(
        accountId: Long,
        attempt: RaidAttempt,
        observation: RaidResultObservation,
        execution: TypedAutomationExecution = TypedAutomationExecution.Completed,
    ): AmbiguousActionResolution {
        return when (val result = raidCycleModule.recordObservedResult(accountId, attempt, observation)) {
            is RaidRecordResult.Recorded -> {
                result.completion?.let { workLifecycle.completeRaidCycle(accountId, attempt.entryId) }
                AmbiguousActionResolution.Applied(
                    result.completion?.let(TypedAutomationExecution::RaidCycleFinished) ?: execution,
                )
            }
            is RaidRecordResult.NotApplied -> AmbiguousActionResolution.Resubmit
            is RaidRecordResult.NeedsRecheck -> AmbiguousActionResolution.VerifyLater(result.at, result.message)
        }
    }

    private fun reconcileRaidBattle(
        accountId: Long,
        entryId: Long,
        executionIdentity: String,
        payload: StoredTypedActionPayload.BattleMap,
    ): AmbiguousActionResolution {
        val raidId = payload.sourceTargetKey
            ?: return AmbiguousActionResolution.VerifyLater(retryAt(), "저장된 레이드 전투 대상이 없습니다.")
        val action = BattleMapAutomationAction(
            accountId = accountId,
            progressDate = payload.progressDate,
            categoryId = payload.categoryId,
            mapCode = payload.mapCode,
            presetMode = payload.presetMode,
            presetId = payload.presetId,
            battleCount = payload.battleCount,
            executionIdentity = executionIdentity,
            source = payload.source,
            sourceTargetKey = raidId,
        )
        return when (val reconciliation = battleOutcomeReconciler.reloadRecentAuthoritativeEvidence(action)) {
            is BattleOutcomeReconciliation.Proven -> {
                if (!reconciliation.evidence.binds(action) || !reconciliation.evidence.isCompleteTerminal()) {
                    AmbiguousActionResolution.VerifyLater(
                        retryAt(),
                        "다시 읽은 레이드 전투 결과가 저장 행동과 정확히 일치하지 않습니다.",
                    )
                } else {
                    recordRaid(
                        accountId,
                        RaidAttempt(entryId, RaidIntentKind.BATTLE, raidId, null),
                        RaidResultObservation.BattleCompleted,
                        TypedAutomationExecution.BattleCompleted(payload.categoryId, payload.mapCode),
                    )
                }
            }
            is BattleOutcomeReconciliation.Unproven -> AmbiguousActionResolution.VerifyLater(
                retryAt(),
                reconciliation.message,
            )
        }
    }

    private fun retryAt() = timeProvider.now().plusSeconds(10)
}
