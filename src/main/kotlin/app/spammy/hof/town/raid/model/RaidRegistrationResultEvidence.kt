package app.spammy.hof.town.raid.model

object RaidRegistrationResultEvidence {
    fun hasStaleBattleConflict(messages: Iterable<String>): Boolean =
        messages.any(STALE_BATTLE_CONFLICT::containsMatchIn)

    fun findStaleBattleConflict(text: String): String? = STALE_BATTLE_CONFLICT.find(text)?.value

    private val STALE_BATTLE_CONFLICT = Regex(
        "이미\\s*전투\\s*중입니다\\.?\\s*퇴치/보상\\s*확인/상태\\s*갱신을\\s*해주세요\\.?",
    )
}
