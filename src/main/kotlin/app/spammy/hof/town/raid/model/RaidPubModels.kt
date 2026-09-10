package app.spammy.hof.town.raid.model

import app.spammy.hof.town.common.model.ParsedTownResult

enum class RaidAction { REGISTER, LEAVE, START, RESET, REWARD, WAIT_RESET, REFRESH }

enum class RaidStatus { RECRUITING, WAITING, READY, IN_BATTLE, COMPLETED, CLOSED, TESTING, UNKNOWN }

enum class RaidBattleObservationStatus { OBSERVED, ABSENT, INCOMPLETE }

enum class RaidRewardWindowStatus { AVAILABLE, CLAIM_WINDOW, ABSENT, INCOMPLETE }

enum class RaidCooldownObservationSource { HOF_DIRECT, HOF_SINGLE_TARGET_INFERENCE }

const val RAID_NOTHING_AVAILABLE_MESSAGE = "수령 가능한 보상이 없습니다."

fun isRaidNothingAvailableMessage(message: String): Boolean =
    message.replace(Regex("[\\s\\u00a0]+"), " ").trim().removeSuffix(".").trim() ==
        RAID_NOTHING_AVAILABLE_MESSAGE.removeSuffix(".")

data class RaidBattleObservationEvidence(
    val caseId: String,
    val reasonCode: String,
    val candidateSeconds: List<Long>,
    val candidateCount: Int,
    val mapCount: Int,
    val domFingerprint: String,
    val responseShapeFingerprint: String,
)

fun isRaidResetRequiredStatus(statusText: String?): Boolean =
    statusText?.let { RESET_REQUIRED_STATUS.containsMatchIn(it) } == true

fun isRaidRegistrationAvailable(status: RaidStatus): Boolean =
    status in setOf(RaidStatus.RECRUITING, RaidStatus.WAITING, RaidStatus.READY)

private val RESET_REQUIRED_STATUS = Regex("보상\\s*확인\\s*종료\\s*\\(\\s*리셋\\s*가능\\s*\\)")
data class RaidBattleTarget(
    val categoryId: String = "raid",
    val mapCode: String,
    val cooldownRemainingSeconds: Long? = null,
    val cooldownSource: RaidCooldownObservationSource? = null,
)

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
    val rewardWindowStatus: RaidRewardWindowStatus = RaidRewardWindowStatus.ABSENT,
    val rewardWaitSeconds: Int? = null,
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
    internal val pageComplete: Boolean,
    val battleObservationStatus: RaidBattleObservationStatus = RaidBattleObservationStatus.INCOMPLETE,
    val battleObservationEvidence: RaidBattleObservationEvidence? = null,
    val registrationStateObserved: Boolean = false,
)
