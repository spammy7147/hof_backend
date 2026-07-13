package app.spammy.hof.quest.model

enum class QuestState {
    AVAILABLE,
    ACTIVE,
    CLAIMABLE,
    COMPLETED,
    UNAVAILABLE,
}

data class QuestProgress(
    val current: Int,
    val target: Int,
)

data class QuestSnapshot(
    val questId: String,
    val name: String,
    val state: QuestState,
    val progress: QuestProgress?,
    val actionNo: String?,
)
