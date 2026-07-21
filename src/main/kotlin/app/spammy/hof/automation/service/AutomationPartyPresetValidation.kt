package app.spammy.hof.automation.service

internal fun <T> validAutomationPartyMembersByPreset(
    members: List<T>,
    presetId: (T) -> Long,
    characterId: (T) -> String?,
    patternSlotCode: (T) -> String?,
    canLoadPattern: (T) -> Boolean,
): Map<Long, List<T>> = members
    .filter { member -> characterId(member) != null || patternSlotCode(member) != null }
    .groupBy(presetId)
    .filterValues { configuredMembers ->
        configuredMembers.isNotEmpty() && configuredMembers.all { member ->
            characterId(member) != null &&
                canLoadPattern(member) &&
                patternSlotCode(member)?.toIntOrNull() != null
        }
    }
