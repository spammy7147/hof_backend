package app.spammy.hof.battle.model

enum class BattleMapKeyMode {
    NOT_REQUIRED,
    LIMITED,
    UNLIMITED,
    UNKNOWN,
}

fun BattleMapKeyMode.hasUsableKey(keyCount: Int?): Boolean = when (this) {
    BattleMapKeyMode.LIMITED -> keyCount != null && keyCount > 0
    BattleMapKeyMode.NOT_REQUIRED,
    BattleMapKeyMode.UNLIMITED,
    BattleMapKeyMode.UNKNOWN,
    -> true
}
