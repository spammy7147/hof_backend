package app.spammy.hof.automation.service

import app.spammy.hof.automation.raid.HofRaidObservationAdapter
import app.spammy.hof.automation.raid.RaidAttempt
import app.spammy.hof.automation.raid.RaidCycleModule
import app.spammy.hof.automation.raid.RaidCycleOutcome
import app.spammy.hof.automation.raid.RaidCycleOutcomeKind
import app.spammy.hof.automation.raid.RaidIntentKind
import app.spammy.hof.automation.raid.RaidRecordResult
import app.spammy.hof.automation.raid.RaidResultObservation
import app.spammy.hof.town.fishing.service.FishingService
import app.spammy.hof.town.raid.dto.RaidPubActionRequest
import app.spammy.hof.town.raid.service.RaidPubService
import org.springframework.stereotype.Service

@Service
class DefaultAutomationActionExecutor(
    private val battleSubmission: AutomationBattleSubmission,
    private val executionSignals: AutomationExecutionSignals,
    private val workLifecycle: AutomationWorkLifecycle,
    private val raidPubService: RaidPubService,
    private val raidCycleModule: RaidCycleModule,
    private val raidObservationAdapter: HofRaidObservationAdapter,
    private val fishingService: FishingService? = null,
) : TypedAutomationActionExecutor {
    override fun execute(accountId: Long, action: StoredTypedAutomationAction): TypedAutomationExecution =
        when (val payload = action.payload) {
                is StoredTypedActionPayload.BattleMap -> {
                    require(payload.source in setOf(
                        BattleAutomationActionSource.FISHING_AUTOMATION,
                        BattleAutomationActionSource.RAID_AUTOMATION,
                    )) { "Stored battle source ${payload.source} belongs to the action lifecycle module." }
                    when (val submission = battleSubmission.submit(
                        accountId,
                        action.executionIdentity,
                        payload.battleRequest,
                        payload.source,
                    )) {
                        is AutomationBattleSubmissionResult.SharedCooldown -> TypedAutomationExecution.SharedCooldown(
                            payload.categoryId,
                            payload.mapCode,
                            submission.retryAt,
                        )
                        is AutomationBattleSubmissionResult.Completed -> {
                            if (payload.source == BattleAutomationActionSource.RAID_AUTOMATION) {
                                payload.sourceTargetKey?.let { raidId ->
                                    recordRaidResult(
                                        accountId,
                                        RaidAttempt(action.entryId, RaidIntentKind.BATTLE, raidId, null),
                                        RaidResultObservation.BattleCompleted,
                                    )
                                }
                            }
                            val rounds = submission.response.rounds.takeIf(List<*>::isNotEmpty)
                            executionSignals.afterBattle(
                                accountId = accountId,
                                source = BattleAutomationActionSource.BATTLE_MAP_AUTOMATION,
                                outcomes = submission.outcomes,
                                lootNames = rounds?.flatMap { it.loots.map { loot -> loot.name } }
                                    ?: submission.response.loots.map { it.name },
                                questTexts = rounds?.mapNotNull { it.quest?.takeIf(String::isNotBlank) }
                                    ?: listOfNotNull(submission.response.quest?.takeIf(String::isNotBlank)),
                            )
                            TypedAutomationExecution.BattleCompleted(payload.categoryId, payload.mapCode)
                        }
                    }
                }
                is StoredTypedActionPayload.FishingTown -> {
                    (fishingService ?: error("Fishing automation gateway is unavailable.")).act(accountId, payload.action)
                    TypedAutomationExecution.Completed
                }
                is StoredTypedActionPayload.RaidTown -> {
                    val response = raidPubService.action(accountId, RaidPubActionRequest(payload.action, payload.raidId))
                    val targetRaidId = payload.targetRaidId ?: payload.raidId
                        ?: error("Stored raid action has no target raid id.")
                    val completion = recordRaidResult(
                        accountId,
                        RaidAttempt(
                            entryId = action.entryId,
                            kind = payload.action.toRaidIntentKind(),
                            raidId = targetRaidId,
                            requestRaidId = payload.raidId,
                        ),
                        RaidResultObservation.Page(
                            raidObservationAdapter.from(response),
                        ),
                    )
                    completion?.let(TypedAutomationExecution::RaidCycleFinished)
                        ?: TypedAutomationExecution.Completed
                }
                is StoredTypedActionPayload.RaidCycleAbort -> {
                    val completion = recordRaidResult(
                        accountId,
                        RaidAttempt(action.entryId, RaidIntentKind.REFRESH, payload.raidId, null),
                        RaidResultObservation.LegacyCycleAbort(
                            when (payload.reason) {
                                RaidCycleAbortReason.CLOSED -> RaidCycleOutcomeKind.ABORTED_CLOSED
                                RaidCycleAbortReason.REGISTRATION_LOST -> RaidCycleOutcomeKind.ABORTED_REGISTRATION_LOST
                            },
                        ),
                    )
                    completion?.let(TypedAutomationExecution::RaidCycleFinished)
                        ?: TypedAutomationExecution.Completed
                }
                else -> error("Stored action ${payload.kind()} belongs to the action lifecycle module.")
            }

    private fun recordRaidResult(
        accountId: Long,
        attempt: RaidAttempt,
        observation: RaidResultObservation,
    ): RaidCycleOutcome? =
        when (val result = raidCycleModule.recordObservedResult(accountId, attempt, observation)) {
            is RaidRecordResult.Recorded -> result.completion?.also {
                workLifecycle.completeRaidCycle(accountId, attempt.entryId)
            }
            is RaidRecordResult.NotApplied -> throw AmbiguousAutomationSubmissionException(result.message)
            is RaidRecordResult.NeedsRecheck -> throw AmbiguousAutomationSubmissionException(result.message)
        }

}
