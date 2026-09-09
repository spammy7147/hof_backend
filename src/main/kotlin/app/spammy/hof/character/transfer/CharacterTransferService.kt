package app.spammy.hof.character.transfer

import app.spammy.hof.character.command.CharacterCommand
import app.spammy.hof.character.command.CharacterCommandExecutor
import app.spammy.hof.character.command.CharacterCommandResult
import app.spammy.hof.character.command.CharacterAutomationGate
import app.spammy.hof.character.command.CharacterStat
import app.spammy.hof.character.entity.CharacterLifecycle
import app.spammy.hof.character.entity.CharacterPatternOptionType
import app.spammy.hof.character.entity.CharacterSkillType
import app.spammy.hof.character.pattern.CharacterPatternDraft
import app.spammy.hof.character.pattern.CharacterPatternOperationResult
import app.spammy.hof.character.pattern.CharacterPatternRowValue
import app.spammy.hof.character.pattern.CharacterPatternSetting
import app.spammy.hof.character.pattern.CharacterPatternRemoteFactory
import app.spammy.hof.character.pattern.PatternSlotAfterApply
import app.spammy.hof.character.repository.CharacterQueryRepository
import app.spammy.hof.character.service.CharacterPatternService
import app.spammy.hof.town.common.service.TownAuthenticatedExecutor
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class CharacterTransferSelection(
    val sourceCharacterId: Long,
    val targetCharacterId: Long,
    val request: CharacterTransferRequest,
)

/** DB 스냅샷으로 미리보기를 만들고 모든 명령을 대상 캐릭터에만 실행한다. */
@Service
class CharacterTransferService(
    private val query: CharacterQueryRepository,
    private val commands: CharacterCommandExecutor,
    private val patterns: CharacterPatternService,
    private val automationGate: CharacterAutomationGate,
    private val executor: TownAuthenticatedExecutor,
    private val patternRemotes: CharacterPatternRemoteFactory,
) {
    private val planner = CharacterTransferPlanner()

    @Transactional(readOnly = true)
    fun preview(accountId: Long, selection: CharacterTransferSelection): CharacterTransferPreview {
        val pair = readPair(accountId, selection.sourceCharacterId, selection.targetCharacterId)
        return planner.preview(pair.first, pair.second, selection.request)
    }

    fun execute(
        accountId: Long,
        selection: CharacterTransferSelection,
        completedStepIds: Set<String> = emptySet(),
        snapshot: CharacterTransferSnapshot? = null,
        onSnapshot: (CharacterTransferSnapshot) -> Unit = {},
        onStepResult: (CharacterTransferStepResult) -> Unit = {},
    ): CharacterTransferExecutionResult = automationGate.execute(
        accountId,
        unavailable = { error("자동화 일시정지를 기다리고 있습니다.") },
    ) {
        executor.executeAccountSequence(accountId) {
            // 임시 슬롯 복사 이전의 실제 현재 설정과 선택지를 같은 계정 실행 범위에서 확인한다.
            val current = patternRemotes.withRemote(accountId, selection.targetCharacterId) { it.observe() }
            check(snapshot != null || completedStepIds.isEmpty() || selection.request.includeCurrentPattern) {
                "중단 전 대상의 현재 패턴 기록이 없어 임시 설정을 원래 설정으로 사용할 수 없습니다."
            }
            val pair = readPair(accountId, selection.sourceCharacterId, selection.targetCharacterId)
            val original = snapshot ?: CharacterTransferSnapshot(pair.first, current.setting).also(onSnapshot)
            require(original.source.accountId == accountId && original.source.characterId == selection.sourceCharacterId)
            val preview = planner.preview(original.source, pair.second.copy(currentPattern = original.originalCurrentPattern), selection.request)
            CharacterTransferExecutor { targetCharacterId, step ->
                runCatching { executeStep(accountId, targetCharacterId, step) }
            }.execute(preview, completedStepIds, onStepResult)
        }
    }

    private fun executeStep(accountId: Long, targetCharacterId: Long, step: CharacterTransferStep) {
        val character = query.findByAccountIdAndId(accountId, targetCharacterId)
            ?: error("대상 캐릭터를 찾지 못했습니다.")
        when (step) {
            is CharacterTransferStep.AllocateStats -> commandSucceeded(
                commands.execute(accountId, CharacterCommand.AllocateStats(character.id, character.updatedAt, step.amounts)),
            )
            is CharacterTransferStep.LearnSkill -> commandSucceeded(
                commands.execute(accountId, CharacterCommand.LearnSkill(character.id, character.updatedAt, step.skillValue)),
            )
            is CharacterTransferStep.EquipItem -> commandSucceeded(
                commands.execute(accountId, CharacterCommand.EquipItem(character.id, character.updatedAt, step.itemValue)),
            )
            is CharacterTransferStep.RemoveAllEquipment -> commandSucceeded(
                commands.execute(accountId, CharacterCommand.RemoveAllEquipment(character.id, character.updatedAt)),
            )
            is CharacterTransferStep.SaveEquipmentPreset -> commandSucceeded(
                commands.execute(
                    accountId,
                    CharacterCommand.SaveEquipmentPreset(character.id, character.updatedAt, step.slotNumber),
                ),
            )
            is CharacterTransferStep.ApplyCurrentPattern -> applyPattern(accountId, character.id, step.setting, PatternSlotAfterApply.None)
            is CharacterTransferStep.SavePatternSlot -> applyPattern(
                accountId,
                character.id,
                step.setting,
                if (step.replacesExisting) PatternSlotAfterApply.Replace(step.targetSlot, step.name.take(6))
                else PatternSlotAfterApply.SaveEmpty(step.targetSlot, step.name.take(6)),
            )
        }
    }

    private fun applyPattern(
        accountId: Long,
        characterId: Long,
        setting: CharacterPatternSetting,
        slotAfterApply: PatternSlotAfterApply,
    ) {
        if (slotAfterApply == PatternSlotAfterApply.None &&
            patternRemotes.withRemote(accountId, characterId) { it.observe().setting } == setting) return
        val character = query.findByAccountIdAndId(accountId, characterId) ?: error("대상 캐릭터를 찾지 못했습니다.")
        val current = currentPattern(characterId)
        val result = patterns.applyDraft(
            accountId,
            characterId,
            current,
            character.updatedAt,
            CharacterPatternDraft(character.updatedAt, setting.rows, setting.position, setting.guard),
            slotAfterApply,
        )
        if (result !is CharacterPatternOperationResult.Completed) error(patternFailure(result))
    }

    private fun commandSucceeded(result: CharacterCommandResult) {
        if (result !is CharacterCommandResult.Completed) error("캐릭터 명령을 완료하지 못했습니다: $result")
    }

    private fun patternFailure(result: CharacterPatternOperationResult): String = when (result) {
        is CharacterPatternOperationResult.Conflict -> "서버 패턴이 변경되었습니다."
        is CharacterPatternOperationResult.PartiallyApplied -> result.message
        is CharacterPatternOperationResult.RefreshRequired -> result.message
        is CharacterPatternOperationResult.Rejected -> result.message
        is CharacterPatternOperationResult.Completed -> ""
    }

    private fun readPair(
        accountId: Long,
        sourceCharacterId: Long,
        targetCharacterId: Long,
    ): Pair<CharacterTransferSource, CharacterTransferTarget> {
        val sourceCharacter = query.findByAccountIdAndId(accountId, sourceCharacterId)
            ?: throw IllegalArgumentException("원본 캐릭터를 찾지 못했습니다.")
        val targetCharacter = query.findByAccountIdAndId(accountId, targetCharacterId)
            ?: throw IllegalArgumentException("대상 캐릭터를 찾지 못했습니다.")
        require(targetCharacter.lifecycle == CharacterLifecycle.ACTIVE) { "대상은 현재 사용 중인 캐릭터여야 합니다." }

        val targetOptions = query.findPatternOptions(targetCharacterId)
        val targetRows = query.findActionPatternsByCharacterIds(listOf(targetCharacterId))
        require(targetRows.isNotEmpty()) { "대상 캐릭터의 패턴 정보를 먼저 동기화해 주세요." }
        val judgeValues = targetOptions.filter { it.optionType == CharacterPatternOptionType.CONDITION }.map { it.sourceValue }.toSet()
        val skillValues = targetOptions.filter { it.optionType == CharacterPatternOptionType.SKILL }.map { it.sourceValue }.toSet()
        val defaultRow = CharacterPatternRowValue(
            judgeValues.firstOrNull() ?: error("대상 캐릭터의 조건 선택지를 찾지 못했습니다."),
            "0",
            skillValues.firstOrNull() ?: error("대상 캐릭터의 스킬 선택지를 찾지 못했습니다."),
        )
        val targetSkills = query.findSkillsByCharacterIds(listOf(targetCharacterId))
        val targetLearnedNames = targetSkills.filter { it.skillType == CharacterSkillType.LEARNED }.map { it.name }.toSet()
        val targetLearnableByName = targetSkills.filter { it.skillType == CharacterSkillType.LEARNABLE }.groupBy { it.name }
        val sourceSkills = query.findSkillsByCharacterIds(listOf(sourceCharacterId))
            .filter { it.skillType == CharacterSkillType.LEARNED }
            .map { skill ->
                when {
                    skill.name in targetLearnedNames -> "learned:${skill.name}"
                    targetLearnableByName[skill.name]?.size == 1 -> targetLearnableByName.getValue(skill.name).single().sourceValue
                    else -> "missing:${skill.name}"
                }
            }.toSet()
        val targetCandidates = query.findEquipmentCandidates(targetCharacterId)
        val targetCandidatesByName = targetCandidates.groupBy { it.name }
        fun targetEquipment(part: String, name: String): String = targetCandidatesByName[name]
            ?.singleOrNull { it.typeCode.equals(part, true) }
            ?.sourceValue
            ?: "missing:$part:$name"
        val sourceEquipment = query.findEquipmentByCharacterIds(listOf(sourceCharacterId))
            .filter { it.checked && it.name.isNotBlank() }
            .map { item ->
                CharacterTransferEquipment(
                    item.part.ifBlank { item.slot },
                    targetEquipment(item.part.ifBlank { item.slot }, item.name),
                )
            }
        val sourceEquipmentPresets = (1..2).mapNotNull { slotNumber ->
            val slot = query.findEquipmentSavedSlot(sourceCharacterId, slotNumber) ?: return@mapNotNull null
            slotNumber to query.findEquipmentSavedItems(slot.id).map { item ->
                CharacterTransferEquipment(
                    item.equipmentPart,
                    targetEquipment(item.equipmentPart, item.name),
                )
            }
        }.toMap()
        val sourceSlots = query.findPatternSlotsByCharacterIds(listOf(sourceCharacterId)).filter { it.canLoad }
        val savedPatterns = sourceSlots.associate { slot ->
            slot.slotCode to CharacterPatternSetting(
                query.findSavedPatternRows(slot.id).map { CharacterPatternRowValue(it.judge, it.quantity, it.skill) },
                slot.selectedPosition.orEmpty(),
                slot.guardValue.orEmpty(),
            )
        }

        return CharacterTransferSource(
            accountId = accountId,
            characterId = sourceCharacterId,
            currentPattern = currentPattern(sourceCharacterId),
            savedPatterns = savedPatterns,
            savedPatternNames = sourceSlots.associate { it.slotCode to it.label },
            realStats = realStats(sourceCharacterId),
            learnedSkills = sourceSkills,
            equipment = sourceEquipment,
            equipmentPresets = sourceEquipmentPresets,
        ) to CharacterTransferTarget(
            accountId = accountId,
            characterId = targetCharacterId,
            patternCapacity = targetRows.size,
            defaultPatternRow = defaultRow,
            allowedJudges = judgeValues,
            allowedSkills = skillValues,
            allowedPositions = query.findPositionChoicesByCharacterIds(listOf(targetCharacterId)).map { it.value }.toSet(),
            allowedGuards = GUARD_VALUES,
            occupiedPatternSlots = query.findPatternSlotsByCharacterIds(listOf(targetCharacterId)).filter { it.canLoad }.map { it.slotCode }.toSet(),
            statusPoints = query.findStatsByCharacterId(targetCharacterId)?.statusPoints ?: 0,
            realStats = realStats(targetCharacterId),
            learnedSkills = targetLearnedNames.map { "learned:$it" }.toSet(),
            learnableSkills = targetLearnableByName.values.filter { it.size == 1 }.map { it.single().sourceValue }.toSet(),
            equipmentCandidateValues = targetCandidates.map { it.sourceValue }.toSet(),
            currentPattern = currentPattern(targetCharacterId),
        )
    }

    private fun currentPattern(characterId: Long): CharacterPatternSetting {
        val guard = query.findGuardSettingByCharacterId(characterId)
        return CharacterPatternSetting(
            query.findActionPatternsByCharacterIds(listOf(characterId)).map { CharacterPatternRowValue(it.judge, it.quantity, it.skill) },
            guard?.selectedPosition.orEmpty(),
            guard?.guardValue.orEmpty(),
        )
    }

    private fun realStats(characterId: Long): Map<CharacterStat, Int> {
        val stats = query.findStatsByCharacterId(characterId) ?: return emptyMap()
        return mapOf(
            CharacterStat.STR to (stats.strReal ?: 0), CharacterStat.INT to (stats.intReal ?: 0),
            CharacterStat.DEX to (stats.dexReal ?: 0), CharacterStat.SPD to (stats.spdReal ?: 0),
            CharacterStat.LUK to (stats.lukReal ?: 0),
        )
    }

    private companion object {
        val GUARD_VALUES = setOf("always", "never", "life25", "life50", "life75", "prob25", "prpb50", "prob75")
    }
}
