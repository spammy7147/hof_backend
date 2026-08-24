package app.spammy.hof.automation.entity

fun automationEntryDisplayNames(entries: Collection<AutomationEntryEntity>): Map<Long, String> {
    val positions = mutableMapOf<AutomationType, Int>()
    return entries
        .sortedWith(compareBy<AutomationEntryEntity> { it.priority }.thenBy { it.id })
        .mapNotNull { entry ->
            val position = (positions[entry.type] ?: 0) + 1
            positions[entry.type] = position
            val name = entry.displayName?.trim()?.takeIf(String::isNotEmpty) ?: when (entry.type) {
                AutomationType.BATTLE_MAP -> "전투 맵 $position"
                AutomationType.ADVENTURE_MAP -> "모험 맵 $position"
                else -> null
            }
            name?.let { entry.id to it }
        }
        .toMap()
}
