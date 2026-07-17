package app.spammy.hof.quest.model

enum class QuestState {
    AVAILABLE,
    ACTIVE,
    CLAIMABLE,
    COMPLETED,
    UNAVAILABLE,
}

enum class QuestSection {
    ACTIVE,
    AVAILABLE,
    WAITING,
    COMPLETED,
}

enum class QuestMissionType {
    IMMEDIATE,
    ITEM_TURN_IN,
    MONSTER_KILL,
    MAP_CLEAR,
    OTHER,
}

data class QuestProgress(
    val current: Int,
    val required: Int,
)

data class QuestMission(
    val key: String,
    val type: QuestMissionType,
    val target: String?,
    val progress: QuestProgress?,
    val completable: Boolean,
)

data class QuestSnapshot(
    val questId: String,
    val name: String,
    val state: QuestState,
    val section: QuestSection,
    val sourceOrder: Int,
    val missions: List<QuestMission>,
    val actionNo: String?,
    val rewards: List<String> = emptyList(),
) {
    /** Keeps policy fixtures source-compatible while quest parsing moves to the richer model. */
    constructor(
        questId: String,
        name: String,
        state: QuestState,
        progress: QuestProgress?,
        actionNo: String?,
    ) : this(
        questId = questId,
        name = name,
        state = state,
        section = when (state) {
            QuestState.AVAILABLE -> QuestSection.AVAILABLE
            QuestState.ACTIVE, QuestState.CLAIMABLE -> QuestSection.ACTIVE
            QuestState.COMPLETED -> QuestSection.COMPLETED
            QuestState.UNAVAILABLE -> QuestSection.WAITING
        },
        sourceOrder = 0,
        missions = progress?.let {
            listOf(
                QuestMission(
                    key = "$questId:0",
                    type = QuestMissionType.OTHER,
                    target = null,
                    progress = it,
                    completable = state == QuestState.CLAIMABLE,
                ),
            )
        }.orEmpty(),
        actionNo = actionNo,
    )
}
