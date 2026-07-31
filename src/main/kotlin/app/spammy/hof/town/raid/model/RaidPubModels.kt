package app.spammy.hof.town.raid.model

import app.spammy.hof.town.common.model.ParsedTownResult

enum class RaidAction { REGISTER, LEAVE, START, RESET, REWARD, WAIT_RESET, REFRESH }

enum class RaidStatus { RECRUITING, WAITING, READY, IN_BATTLE, COMPLETED, CLOSED, TESTING, UNKNOWN }

data class RaidBattleTarget(val categoryId: String = "raid", val mapCode: String)

data class RaidPubRaid(
    val id: String,
    val name: String,
    val playable: Boolean,
    val difficulty: String?,
    val maxPartySize: Int?,
    val rewardDamage: String?,
    val status: RaidStatus,
    val statusText: String?,
    val waitSeconds: Int?,
    val applicants: List<String>,
    val joined: Boolean,
    val actions: Set<RaidAction>,
    val battleTarget: RaidBattleTarget?,
    internal val actionIds: Map<RaidAction, String>,
)

data class RaidPubSnapshot(
    val raids: List<RaidPubRaid>,
    val applied: Boolean,
    val applyWait: Boolean,
    val applyWaitSeconds: Int?,
    val myStatus: String?,
    val globalActions: Set<RaidAction>,
    val result: ParsedTownResult?,
    internal val globalActionIds: Map<RaidAction, String>,
    internal val observedRaidPubForm: Boolean,
)
