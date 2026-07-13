package app.spammy.hof.battle.model

/**
 * HOF 원본 URL과 API에서 사용하는 전투 카테고리 식별자다.
 */
enum class BattleCategoryId(
    val value: String,
) {
    BATTLE_MAP("battle_map"),
    ADVENTURE_MAP("adventure_map"),
    UNION("union"),
    SCENARIO_OCEAN("scenario_ocean"),
    RAID("raid"),
    ;

    companion object {
        /**
         * 문자열 id를 enum으로 변환한다. 지원하지 않는 값이면 null을 반환한다.
         */
        fun fromValue(value: String): BattleCategoryId? =
            entries.firstOrNull { it.value == value }
    }
}
