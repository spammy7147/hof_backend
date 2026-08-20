package app.spammy.hof.automation.service

import app.spammy.hof.town.raid.model.RaidAction

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
