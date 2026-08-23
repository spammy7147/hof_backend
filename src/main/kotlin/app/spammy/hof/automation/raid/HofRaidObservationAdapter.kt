package app.spammy.hof.automation.raid

import app.spammy.hof.automation.service.HofSessionRecoveryExecutor
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.town.raid.dto.RaidPubResponse
import app.spammy.hof.town.raid.model.RaidAction
import app.spammy.hof.town.raid.model.RaidBattleObservationStatus
import app.spammy.hof.town.raid.model.RaidStatus
import app.spammy.hof.town.raid.model.RaidRewardWindowStatus
import app.spammy.hof.town.raid.model.RaidCooldownObservationSource
import app.spammy.hof.town.raid.service.RaidPubService
import org.springframework.stereotype.Component

@Component
class HofRaidObservationAdapter(
    private val raidPubService: RaidPubService,
    private val sessionRecovery: HofSessionRecoveryExecutor,
    private val timeProvider: TimeProvider,
) : RaidObservationReader {
    override fun read(accountId: Long): RaidObservation {
        val load = {
            from(raidPubService.load(accountId))
        }
        return sessionRecovery.execute(accountId, load)
    }

    fun from(response: RaidPubResponse): RaidObservation = RaidObservation(
        raids = response.raids.map { raid ->
            RaidObservedTarget(
                id = raid.id,
                name = raid.name,
                playable = raid.playable,
                status = raid.status.toObservedStatus(),
                statusText = raid.statusText,
                waitSeconds = raid.waitSeconds,
                joined = raid.joined,
                actions = raid.actions.mapNotNull { action -> action.toIntentKind() }.toSet(),
                battle = raid.battleTarget?.let { target ->
                    RaidObservedBattle(
                        categoryId = target.categoryId,
                        mapCode = target.mapCode,
                        cooldownRemainingSeconds = target.cooldownRemainingSeconds,
                        cooldownSource = when (target.cooldownSource) {
                            RaidCooldownObservationSource.HOF_DIRECT -> RaidCooldownSource.HOF_DIRECT
                            RaidCooldownObservationSource.HOF_SINGLE_TARGET_INFERENCE ->
                                RaidCooldownSource.HOF_SINGLE_TARGET_INFERENCE
                            null -> target.cooldownRemainingSeconds
                                ?.takeIf { it > 0 }
                                ?.let { RaidCooldownSource.HOF_DIRECT }
                        },
                    )
                },
                battleAvailability = when {
                    response.battleObservationStatus == RaidBattleObservationStatus.INCOMPLETE ->
                        RaidBattleAvailability.INCOMPLETE
                    raid.battleTarget?.cooldownRemainingSeconds?.let { it > 0 } == true ->
                        RaidBattleAvailability.COOLDOWN
                    raid.battleTarget != null -> RaidBattleAvailability.RUNNABLE
                    response.battleObservationStatus == RaidBattleObservationStatus.ABSENT ->
                        RaidBattleAvailability.ABSENT
                    else -> RaidBattleAvailability.INCOMPLETE
                },
                battleEvidenceCaseId = response.battleObservationEvidence?.caseId,
                rewardWindow = when (raid.rewardWindowStatus) {
                    RaidRewardWindowStatus.AVAILABLE -> RaidRewardWindowObservation.Available
                    RaidRewardWindowStatus.WAIT -> raid.rewardWaitSeconds
                        ?.takeIf { it > 0 }
                        ?.let { RaidRewardWindowObservation.Wait(it.toLong()) }
                        ?: RaidRewardWindowObservation.Incomplete()
                    RaidRewardWindowStatus.ABSENT -> RaidRewardWindowObservation.Absent
                    RaidRewardWindowStatus.INCOMPLETE -> RaidRewardWindowObservation.Incomplete()
                },
            )
        },
        applied = response.applied,
        actionSuccessMarker = response.result?.status == "SUCCESS",
        registrationWait = response.applyWait,
        registrationWaitSeconds = response.applyWaitSeconds,
        globalActions = response.globalActions.mapNotNull { action -> action.toIntentKind() }.toSet(),
        resultMessages = response.result?.messages.orEmpty(),
        observedAt = timeProvider.now(),
        fresh = response.pageComplete,
    )

    private fun RaidStatus.toObservedStatus(): RaidObservedStatus = when (this) {
        RaidStatus.RECRUITING -> RaidObservedStatus.RECRUITING
        RaidStatus.WAITING -> RaidObservedStatus.WAITING
        RaidStatus.READY -> RaidObservedStatus.READY
        RaidStatus.IN_BATTLE -> RaidObservedStatus.IN_BATTLE
        RaidStatus.COMPLETED -> RaidObservedStatus.COMPLETED
        RaidStatus.CLOSED -> RaidObservedStatus.CLOSED
        RaidStatus.TESTING -> RaidObservedStatus.TESTING
        RaidStatus.UNKNOWN -> RaidObservedStatus.UNKNOWN
    }

    private fun RaidAction.toIntentKind(): RaidIntentKind? = when (this) {
        RaidAction.REGISTER -> RaidIntentKind.REGISTER
        RaidAction.START -> RaidIntentKind.START
        RaidAction.RESET -> RaidIntentKind.RESET
        RaidAction.REWARD -> RaidIntentKind.REWARD
        RaidAction.REFRESH -> RaidIntentKind.REFRESH
        RaidAction.LEAVE,
        RaidAction.WAIT_RESET,
        -> null
    }
}
