package app.spammy.hof.automation.service

import app.spammy.hof.town.raid.model.RaidAction
import app.spammy.hof.automation.raid.RaidIntentKind

data class RaidTownAutomationAction(
    val accountId: Long,
    val action: RaidAction,
    val raidId: String? = null,
    val targetRaidId: String? = raidId,
    val raidName: String? = null,
    val observedStatus: String? = null,
) : PreparedAutomationAction

/** Kept only so stored actions written by an older deployment remain decodable and reconcilable. */
enum class RaidCycleAbortReason {
    CLOSED,
    REGISTRATION_LOST,
}

/** Kept only for compatibility with already prepared legacy actions. New decisions close cycles inside RaidCycleModule. */
data class RaidCycleAbortAutomationAction(
    val accountId: Long,
    val raidId: String,
    val reason: RaidCycleAbortReason = RaidCycleAbortReason.CLOSED,
) : PreparedAutomationAction

internal fun RaidAction.toRaidIntentKind(): RaidIntentKind = when (this) {
    RaidAction.RESET -> RaidIntentKind.RESET
    RaidAction.REGISTER -> RaidIntentKind.REGISTER
    RaidAction.START -> RaidIntentKind.START
    RaidAction.REWARD -> RaidIntentKind.REWARD
    RaidAction.REFRESH -> RaidIntentKind.REFRESH
    RaidAction.LEAVE,
    RaidAction.WAIT_RESET,
    -> error("Unsupported raid automation action: $this")
}
