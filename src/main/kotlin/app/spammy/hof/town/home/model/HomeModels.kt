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
    val actionId: String?,
    internal val action: String? = null,
    internal val actionNo: String? = null,
)

data class HomeAction(
    val id: String,
    val type: HomeActionType,
    val label: String,
    internal val formActionId: String? = null,
    internal val query: List<HofFormField> = emptyList(),
)

data class HomeSnapshot(
    val mode: HomeMode,
    val quests: List<HomeQuest>,
    val actions: List<HomeAction>,
    val result: ParsedTownResult? = null,
)
