package app.spammy.hof.character.transfer

import app.spammy.hof.character.command.CharacterStat
import app.spammy.hof.character.pattern.CharacterPatternSetting

class CharacterTransferPlanner {
    fun preview(
        source: CharacterTransferSource,
        target: CharacterTransferTarget,
        request: CharacterTransferRequest,
    ): CharacterTransferPreview {
        require(source.accountId == target.accountId) { "다른 HOF 계정의 캐릭터 설정은 가져올 수 없습니다." }
        require(source.characterId != target.characterId) { "같은 캐릭터로 설정을 가져올 수 없습니다." }
        val issues = mutableListOf<CharacterTransferIssue>()
        val steps = mutableListOf<CharacterTransferStep>()

        if (request.savedPatternMappings.map { it.targetSlot }.distinct().size != request.savedPatternMappings.size) {
            issues += blocking("DUPLICATE_TARGET_SLOT", "saved-pattern", "같은 대상 슬롯을 여러 번 선택할 수 없습니다.")
        }

        if (request.includeStats) planStats(source, target, steps, issues)
        if (request.includeSkills) planSkills(source, target, steps, issues)

        if (request.includeCurrentPattern) {
            normalizePattern("current-pattern", source.currentPattern, target, issues)?.let { setting ->
                patternDependencies("current-pattern", setting, source, target, request, issues)?.let { dependencies ->
                    steps += CharacterTransferStep.ApplyCurrentPattern("current-pattern", setting, dependencies)
                }
            }
        }

        request.savedPatternMappings.forEach { mapping ->
            val key = "saved-pattern:${mapping.sourceSlot}:${mapping.targetSlot}"
            val sourceSetting = source.savedPatterns[mapping.sourceSlot]
            if (sourceSetting == null) {
                issues += selection("SOURCE_SLOT_MISSING", key, "가져올 저장 패턴 슬롯을 찾지 못했습니다.")
                return@forEach
            }
            normalizePattern(key, sourceSetting, target, issues)?.let { setting ->
                patternDependencies(key, setting, source, target, request, issues)?.let { dependencies ->
                    steps += CharacterTransferStep.SavePatternSlot(
                        id = key,
                        sourceSlot = mapping.sourceSlot,
                        targetSlot = mapping.targetSlot,
                        name = source.savedPatternNames[mapping.sourceSlot].orEmpty().ifBlank { "복사" },
                        setting = setting,
                        replacesExisting = mapping.targetSlot in target.occupiedPatternSlots,
                        dependsOn = dependencies,
                    )
                }
            }
        }

        if (request.includeEquipment) {
            source.equipmentPresets.toSortedMap().forEach { (slotNumber, items) ->
                planEquipmentSet("equipment-preset:$slotNumber", items, target, steps, issues, slotNumber)
            }
            // 저장 슬롯을 만들며 바뀐 현재 장비는 마지막에 원본 캐릭터의 현재 장비로 되돌린다.
            planEquipmentSet("equipment-current", source.equipment, target, steps, issues)
        }

        return CharacterTransferPreview(source.characterId, target.characterId, steps.distinctBy { it.id }, issues)
    }

    private fun planEquipmentSet(
        key: String,
        items: List<CharacterTransferEquipment>,
        target: CharacterTransferTarget,
        steps: MutableList<CharacterTransferStep>,
        issues: MutableList<CharacterTransferIssue>,
        saveSlotNumber: Int? = null,
    ) {
        val missing = items.filter { it.sourceValue !in target.equipmentCandidateValues }
        if (missing.isNotEmpty()) {
            missing.forEach { item ->
                issues += selection(
                    "EQUIPMENT_NOT_AVAILABLE",
                    "$key:${item.equipmentPart}",
                    "대상 캐릭터에서 고유하게 확인할 수 없는 장비가 있어 이 장비 묶음은 제외했습니다.",
                )
            }
            return
        }
        val clearId = "$key:clear"
        steps += CharacterTransferStep.RemoveAllEquipment(clearId)
        val itemStepIds = items.mapIndexed { index, item ->
            val itemId = "$key:item:$index"
            steps += CharacterTransferStep.EquipItem(itemId, item.equipmentPart, item.sourceValue, setOf(clearId))
            itemId
        }.toSet()
        if (saveSlotNumber != null) {
            steps += CharacterTransferStep.SaveEquipmentPreset(
                "$key:save",
                saveSlotNumber,
                itemStepIds + clearId,
            )
        }
    }

    private fun planStats(
        source: CharacterTransferSource,
        target: CharacterTransferTarget,
        steps: MutableList<CharacterTransferStep>,
        issues: MutableList<CharacterTransferIssue>,
    ) {
        val amounts = CharacterStat.entries.associateWith { stat ->
            ((source.realStats[stat] ?: 0) - (target.realStats[stat] ?: 0)).coerceAtLeast(0)
        }.filterValues { it > 0 }
        if (amounts.values.sum() > target.statusPoints) {
            issues += selection("STATUS_POINTS_INSUFFICIENT", "stats", "보유한 Status Point로 원본 배분을 모두 따라갈 수 없습니다.")
        } else if (amounts.isNotEmpty()) {
            steps += CharacterTransferStep.AllocateStats("stats", amounts)
        }
    }

    private fun planSkills(
        source: CharacterTransferSource,
        target: CharacterTransferTarget,
        steps: MutableList<CharacterTransferStep>,
        issues: MutableList<CharacterTransferIssue>,
    ) {
        (source.learnedSkills - target.learnedSkills).forEach { skill ->
            if (skill in target.learnableSkills) {
                steps += CharacterTransferStep.LearnSkill("skill:$skill", skill)
            } else {
                issues += selection("SKILL_NOT_LEARNABLE", "skill:$skill", "현재 배울 수 없는 스킬이라 선택에서 제외했습니다.")
            }
        }
    }

    private fun normalizePattern(
        key: String,
        source: CharacterPatternSetting,
        target: CharacterTransferTarget,
        issues: MutableList<CharacterTransferIssue>,
    ): CharacterPatternSetting? {
        if (source.rows.size > target.patternCapacity) {
            issues += blocking(
                "PATTERN_OVERFLOW",
                key,
                "원본 ${source.rows.size}행이 대상 ${target.patternCapacity}행을 넘어 잘릴 행을 먼저 확인해야 합니다.",
            )
            return null
        }
        if (source.position !in target.allowedPositions || source.guard !in target.allowedGuards) {
            issues += selection("POSITION_GUARD_UNAVAILABLE", key, "대상 캐릭터에서 선택할 수 없는 위치 또는 호위 설정입니다.")
            return null
        }
        if (source.rows.any { it.judge !in target.allowedJudges }) {
            issues += selection("CONDITION_UNAVAILABLE", key, "대상 캐릭터에서 선택할 수 없는 조건이 포함되어 있습니다.")
            return null
        }
        return source.copy(rows = source.rows + List(target.patternCapacity - source.rows.size) { target.defaultPatternRow })
    }

    private fun patternDependencies(
        key: String,
        setting: CharacterPatternSetting,
        source: CharacterTransferSource,
        target: CharacterTransferTarget,
        request: CharacterTransferRequest,
        issues: MutableList<CharacterTransferIssue>,
    ): Set<String>? {
        val missing = setting.rows.map { it.skill }.filter { it !in target.allowedSkills }.toSet()
        if (missing.isEmpty()) return emptySet()
        val learnableDuringTransfer = if (request.includeSkills) {
            missing.filter { it in source.learnedSkills && it in target.learnableSkills }.toSet()
        } else {
            emptySet()
        }
        if (learnableDuringTransfer != missing) {
            issues += selection("PATTERN_SKILL_UNAVAILABLE", key, "현재 사용할 수 없는 스킬이 포함되어 있어 이 패턴은 제외했습니다.")
            return null
        }
        return missing.map { "skill:$it" }.toSet()
    }

    private fun blocking(code: String, key: String, message: String) =
        CharacterTransferIssue(code, key, message, CharacterTransferIssueSeverity.BLOCKING)

    private fun selection(code: String, key: String, message: String) =
        CharacterTransferIssue(code, key, message, CharacterTransferIssueSeverity.NEEDS_SELECTION)
}
