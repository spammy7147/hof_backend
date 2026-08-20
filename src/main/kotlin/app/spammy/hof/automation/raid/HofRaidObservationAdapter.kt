package app.spammy.hof.automation.raid

import app.spammy.hof.automation.service.HofSessionRecoveryExecutor
import app.spammy.hof.battle.service.BattleMapService
import app.spammy.hof.external.model.HofRequestOrigin
import app.spammy.hof.town.raid.dto.RaidPubResponse
import app.spammy.hof.town.raid.model.RaidAction
import app.spammy.hof.town.raid.model.RaidStatus
import app.spammy.hof.town.raid.service.RaidPubService
import org.springframework.stereotype.Component

@Component
class HofRaidObservationAdapter(
    private val raidPubService: RaidPubService,
    private val battleMapService: BattleMapService,
    private val sessionRecovery: HofSessionRecoveryExecutor,
) : RaidObservationReader {
    override fun read(accountId: Long): RaidObservation {
        val load = {
            battleMapService.findMaps(accountId, RAID_CATEGORY, HofRequestOrigin.AUTOMATION)
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
                    )
                },
            )
        },
        applied = response.applied,
        registrationWait = response.applyWait,
        registrationWaitSeconds = response.applyWaitSeconds,
        globalActions = response.globalActions.mapNotNull { action -> action.toIntentKind() }.toSet(),
        resultMessages = response.result?.messages.orEmpty(),
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

    private companion object {
        const val RAID_CATEGORY = "raid"
    }
}
