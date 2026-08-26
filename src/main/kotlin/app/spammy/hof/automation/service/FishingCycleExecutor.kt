package app.spammy.hof.automation.service

import app.spammy.hof.auth.service.AccountExecutionSubmissionGate
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.town.common.service.TownSubmissionBoundary
import app.spammy.hof.town.fishing.dto.FishingResponse
import app.spammy.hof.town.fishing.model.FishingAction
import app.spammy.hof.town.fishing.service.FishingAutomationObservation
import app.spammy.hof.town.fishing.service.FishingOneCastRemoteResult
import app.spammy.hof.town.fishing.service.FishingService
import org.springframework.stereotype.Service

data class FishingCycleCommand(
    val accountId: Long,
    val cycleIdentity: String,
    val startExecutionIdentity: String,
    val catchExecutionIdentity: String,
    val observation: FishingAutomationObservation? = null,
)

data class FishingCycleStepEvidence(
    val executionIdentity: String,
    val action: FishingAction,
    val response: FishingResponse,
)

data class FishingCyclePreparedCatch(
    val executionIdentity: String,
    val observed: FishingResponse,
)

sealed interface FishingCycleResult {
    data class Completed(
        val start: FishingCycleStepEvidence,
        val catch: FishingCycleStepEvidence,
    ) : FishingCycleResult

    data class WaitingForCatch(
        val start: FishingCycleStepEvidence,
    ) : FishingCycleResult
}

interface FishingCycleTransitions {
    fun startAppliedAndCatchPrepared(
        command: FishingCycleCommand,
        start: FishingCycleStepEvidence,
        catch: FishingCyclePreparedCatch,
    )

    fun catchApplied(
        command: FishingCycleCommand,
        catch: FishingCycleStepEvidence,
    )

    fun waitingForCatch(
        command: FishingCycleCommand,
        start: FishingCycleStepEvidence,
    )
}

fun interface FishingCycleExecutor {
    fun executeOneCast(
        command: FishingCycleCommand,
        transitions: FishingCycleTransitions,
    ): FishingCycleResult
}

@Service
class DefaultFishingCycleExecutor(
    private val fishingService: FishingService,
    private val submissionGate: AccountExecutionSubmissionGate,
) : FishingCycleExecutor {
    override fun executeOneCast(
        command: FishingCycleCommand,
        transitions: FishingCycleTransitions,
    ): FishingCycleResult {
        var startEvidence: FishingCycleStepEvidence? = null
        val boundary = TownSubmissionBoundary { submission ->
            var response: HofHttpResponse? = null
            val authorized = submissionGate.executeIfAuthorized(
                command.accountId,
                Runnable { response = submission() },
            )
            if (!authorized) throw FishingSubmissionAuthorizationCancelledException()
            requireNotNull(response)
        }
        return when (val remote = fishingService.executeOneCastForAutomation(
            command.accountId,
            command.observation,
            boundary,
        ) { startResponse ->
            val start = FishingCycleStepEvidence(
                command.startExecutionIdentity,
                FishingAction.START,
                startResponse,
            )
            startEvidence = start
            transitions.startAppliedAndCatchPrepared(
                command,
                start,
                FishingCyclePreparedCatch(command.catchExecutionIdentity, startResponse),
            )
        }) {
            is FishingOneCastRemoteResult.Completed -> {
                val start = startEvidence ?: FishingCycleStepEvidence(
                    command.startExecutionIdentity,
                    FishingAction.START,
                    remote.start,
                )
                val caught = FishingCycleStepEvidence(
                    command.catchExecutionIdentity,
                    FishingAction.CATCH,
                    remote.catch,
                )
                transitions.catchApplied(command, caught)
                FishingCycleResult.Completed(start, caught)
            }
            is FishingOneCastRemoteResult.WaitingForCatch -> {
                val start = FishingCycleStepEvidence(
                    command.startExecutionIdentity,
                    FishingAction.START,
                    remote.start,
                )
                transitions.waitingForCatch(command, start)
                FishingCycleResult.WaitingForCatch(start)
            }
        }
    }
}

class FishingSubmissionAuthorizationCancelledException :
    RuntimeException("Fishing submission was cancelled because the account logged out.")
