package app.spammy.hof.town.raid.dto

import app.spammy.hof.town.fishing.dto.TownActionResultResponse
import app.spammy.hof.town.raid.model.*
import jakarta.validation.constraints.Size

data class RaidBattleTargetResponse(val categoryId: String, val mapCode: String)
data class RaidPubRaidResponse(
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
    val battleTarget: RaidBattleTargetResponse?,
)
data class RaidPubResponse(
    val raids: List<RaidPubRaidResponse>,
    val applied: Boolean,
    val applyWait: Boolean,
    val applyWaitSeconds: Int?,
    val myStatus: String?,
    val globalActions: Set<RaidAction>,
    val result: TownActionResultResponse?,
) {
    companion object {
        fun from(value: RaidPubSnapshot) = RaidPubResponse(
            raids = value.raids.map { raid -> RaidPubRaidResponse(
                raid.id, raid.name, raid.playable, raid.difficulty, raid.maxPartySize, raid.rewardDamage,
                raid.status, raid.statusText, raid.waitSeconds, raid.applicants, raid.joined, raid.actions,
                raid.battleTarget?.let { RaidBattleTargetResponse(it.categoryId, it.mapCode) },
            ) },
            applied = value.applied,
            applyWait = value.applyWait,
            applyWaitSeconds = value.applyWaitSeconds,
            myStatus = value.myStatus,
            globalActions = value.globalActions,
            result = value.result?.let(TownActionResultResponse::from),
        )
    }
}

data class RaidPubActionRequest(
    val action: RaidAction,
    @field:Size(max = 200) val raidId: String? = null,
)
