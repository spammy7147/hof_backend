package app.spammy.hof.town.home.model

import app.spammy.hof.external.model.HofFormField
import app.spammy.hof.town.common.model.ParsedTownResult

enum class HomeMode { HOME, REST }
enum class HomeQuestState { AVAILABLE, ACTIVE, CLAIMABLE, COMPLETED, WAITING }
enum class HomeActionType { ACCEPT, CLAIM, RESTORE }

data class HomeQuest(
    val id: String,
    val name: String,
    val state: HomeQuestState,
    val mission: String?,
    val reward: String?,
    val details: List<String> = emptyList(),
    val actionId: String?,
    internal val action: String? = null,
    internal val actionNo: String? = null,
    internal val stateObserved: Boolean = false,
)

data class HomeAction(
    val id: String,
    val type: HomeActionType,
    val label: String,
    internal val formActionId: String? = null,
    internal val query: List<HofFormField> = emptyList(),
)

/** 휴식처 원문에서 명시적으로 관측된 값만 담는다. 알 수 없는 값은 추측하지 않고 null로 둔다. */
data class RestStatus(
    val currentTime: Int?,
    val maxTime: Int?,
    val baseRecovery: Int?,
    val facilityRecovery: Int?,
    val usedToday: Boolean?,
    val facilities: List<String>,
)

data class HomeSnapshot(
    val mode: HomeMode,
    val quests: List<HomeQuest>,
    val actions: List<HomeAction>,
    val restStatus: RestStatus? = null,
    val result: ParsedTownResult? = null,
)
