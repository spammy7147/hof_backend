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
            automationPartyMemberProblem(characterId(member), patternSlotCode(member), canLoadPattern(member)) == null
        }
    }

internal fun automationPartyMemberProblem(
    characterId: String?,
    patternSlotCode: String?,
    canLoadPattern: Boolean,
): String? = when {
    characterId == null && patternSlotCode == null -> null // 완전히 빈 파티 자리는 사용하지 않는다.
    characterId == null -> "저장 패턴 $patternSlotCode: 캐릭터를 찾을 수 없어요. 파티 구성을 확인해 주세요."
    patternSlotCode == null -> "저장 패턴이 선택되지 않았거나 선택한 패턴을 찾을 수 없어요."
    !canLoadPattern -> "저장 패턴 $patternSlotCode: 불러올 수 없어요. 불러올 수 있는 패턴을 준비하거나 선택해 주세요."
    patternSlotCode.toIntOrNull() == null -> "저장 패턴 번호 '$patternSlotCode'을 해석할 수 없어요."
    else -> null
}
