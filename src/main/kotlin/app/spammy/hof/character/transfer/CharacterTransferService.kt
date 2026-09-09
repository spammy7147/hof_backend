package app.spammy.hof.character.transfer

import app.spammy.hof.character.command.CharacterCommand
import app.spammy.hof.character.command.CharacterCommandExecutor
import app.spammy.hof.character.command.CharacterCommandResult
import app.spammy.hof.character.command.CharacterAutomationGate
import app.spammy.hof.character.command.CharacterStat
import app.spammy.hof.character.command.CharacterEquipmentCommandRules
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
import app.spammy.hof.character.service.CharacterDeepSyncService
import app.spammy.hof.character.service.CharacterRestoreState
import app.spammy.hof.town.common.service.TownAuthenticatedExecutor
import app.spammy.hof.external.model.HofEquipment
import app.spammy.hof.external.model.HofEquipmentCandidate
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
    private val currentSettings: CharacterDeepSyncService,
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
            val current = if (selection.request.includeEquipment) {
                val observed = observeSettings(accountId, selection.targetCharacterId)
                CharacterPatternSetting(observed.patterns.map { CharacterPatternRowValue(it.judge, it.quantity, it.skill) },
                    observed.position, observed.guard)
            } else patternRemotes.withRemote(accountId, selection.targetCharacterId) { it.observe().setting }
            val pair = readPair(accountId, selection.sourceCharacterId, selection.targetCharacterId)
            val original = snapshot ?: CharacterTransferSnapshot(pair.first, current,
                pair.second.currentEquipment.takeIf { selection.request.includeEquipment })
            require(original.source.accountId == accountId && original.source.characterId == selection.sourceCharacterId)
            if (selection.request.includeEquipment) {
                require((original.source.equipment + original.source.equipmentPresets.values.flatten())
                    .all { !it.identity?.name.isNullOrBlank() }) {
                    "중단 전 장비의 식별 근거가 없어 기존 후보 값으로 재개할 수 없습니다. 현재 캐릭터 설정을 확인해 주세요."
                }
            }
            val candidates = equipmentCandidates(selection.targetCharacterId)
            val source = original.source.copy(
                equipment = original.source.equipment.map { resolveEquipment(it, candidates) },
                equipmentPresets = original.source.equipmentPresets.mapValues { (_, items) ->
                    items.map { resolveEquipment(it, candidates) }
                },
            )
            val preview = planner.preview(source, pair.second.copy(currentPattern = original.originalCurrentPattern,
                currentEquipment = original.originalEquipment?.map { resolveEquipment(it, candidates) }), selection.request)
            require(preview.executable) { "차단된 항목을 해결한 뒤 실행해 주세요." }
            check(snapshot != null || completedStepIds.isEmpty() ||
                (selection.request.includeCurrentPattern && preview.steps.none { it.id.startsWith("preserve-current-") })) {
                "중단 전 대상의 현재 설정 기록이 없어 임시 설정을 원래 설정으로 사용할 수 없습니다."
            }
            // 완료 ID만 있는 구형 작업의 임시 패턴·장비를 최초 원본으로 영속화하지 않는다.
            if (snapshot == null) onSnapshot(original)
            val result = CharacterTransferExecutor { targetCharacterId, step ->
                runCatching { executeStep(accountId, targetCharacterId, step) }
            }.execute(preview, completedStepIds, onStepResult)
            observeResult(accountId, selection, original, source, preview, result)
        }
    }

    private fun observeResult(
        accountId: Long,
        selection: CharacterTransferSelection,
        original: CharacterTransferSnapshot,
        source: CharacterTransferSource,
        preview: CharacterTransferPreview,
        result: CharacterTransferExecutionResult,
    ): CharacterTransferExecutionResult = runCatching {
        val (observed, defaultRow) = if (selection.request.includeEquipment) {
            val current = observeSettings(accountId, selection.targetCharacterId)
            val options = query.findPatternOptions(selection.targetCharacterId)
            CharacterTransferCurrentSettings(
                CharacterPatternSetting(current.patterns.map { CharacterPatternRowValue(it.judge, it.quantity, it.skill) },
                    current.position, current.guard), current.equipment,
            ) to CharacterPatternRowValue(
                options.first { it.optionType == CharacterPatternOptionType.CONDITION }.sourceValue,
                "0", options.first { it.optionType == CharacterPatternOptionType.SKILL }.sourceValue,
            )
        } else {
            val current = patternRemotes.withRemote(accountId, selection.targetCharacterId) { it.observe() }
            CharacterTransferCurrentSettings(current.setting) to
                CharacterPatternRowValue(current.judgeValues.first(), "0", current.skillValues.first())
        }
        val intended = preview.steps.filterIsInstance<CharacterTransferStep.ApplyCurrentPattern>().lastOrNull()?.setting
            ?: original.originalCurrentPattern
        val expectedPattern = intended.copy(rows = intended.rows +
            List((observed.pattern.rows.size - intended.rows.size).coerceAtLeast(0)) { defaultRow })
        val expectedEquipment = if (preview.steps.any { it.id == "equipment-current:clear" }) source.equipment
            else original.originalEquipment
        val equipmentMatches = !selection.request.includeEquipment ||
            (expectedEquipment?.map { it.identity?.copy(slot = "", checked = false) }?.groupingBy { it }?.eachCount() ==
                observed.equipment?.map { it.copy(slot = "", checked = false) }?.groupingBy { it }?.eachCount())
        val confirmed = observed.pattern == expectedPattern && equipmentMatches
        val completed = confirmed && result.results.all { it.status == CharacterTransferStepStatus.COMPLETED }
        result.copy(
            outcome = if (completed) CharacterTransferOutcome.COMPLETED else CharacterTransferOutcome.PARTIALLY_APPLIED,
            currentSettings = observed,
            finalSettingsConfirmed = confirmed,
            message = when {
                !confirmed -> "현재 캐릭터 설정이 가져오기 계획과 다릅니다. 현재 설정을 확인해 주세요."
                !completed -> "일부 항목을 완료하지 못했습니다. 실제 현재 캐릭터 설정은 확인했습니다."
                else -> null
            },
        )
    }.getOrElse {
        result.copy(outcome = CharacterTransferOutcome.RECHECK_REQUIRED,
            message = "단계별 결과는 보존했지만 현재 캐릭터 설정을 확인하지 못했습니다. 다시 확인해 주세요.")
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
            is CharacterTransferStep.EquipItem -> {
                commandSucceeded(commands.execute(accountId,
                    CharacterCommand.EquipItem(character.id, character.updatedAt, step.itemValue, step.identity)))
                step.identity?.let { expected ->
                    check(expected.copy(slot = "", checked = false) in observeEquipment(accountId, character.id)) {
                        "장비 장착을 현재 설정에서 확인하지 못했습니다: ${expected.part} / ${expected.name}"
                    }
                }
            }
            is CharacterTransferStep.RemoveAllEquipment -> {
                commandSucceeded(commands.execute(accountId, CharacterCommand.RemoveAllEquipment(character.id, character.updatedAt)))
                check(observeEquipment(accountId, character.id).isEmpty()) { "장비 전체 해제를 현재 설정에서 확인하지 못했습니다." }
            }
            is CharacterTransferStep.SaveEquipmentPreset -> {
                val expected = observeEquipment(accountId, character.id)
                val revision = checkNotNull(query.findByAccountIdAndId(accountId, character.id)).updatedAt
                val saved = commandSucceeded(commands.execute(accountId,
                    CharacterCommand.SaveEquipmentPreset(character.id, revision, step.slotNumber)))
                if (expected.isNotEmpty()) {
                    commandSucceeded(commands.execute(accountId, CharacterCommand.RemoveAllEquipment(character.id, saved.revision)))
                    check(observeEquipment(accountId, character.id).isEmpty()) { "장비 저장 검증을 위한 해제를 확인하지 못했습니다." }
                } else {
                    // 이미 빈 현재 장비로 LOAD를 검증하면 미적용도 성공으로 보인다.
                    // 관측한 후보만 잠시 장착하고 실제 변화를 확인한 뒤 빈 저장을 불러온다.
                    val probe = equipmentCandidates(character.id).firstOrNull { it.quantity == null || it.quantity > 0 }
                        ?: error("빈 장비 저장의 결과를 구분할 장착 후보가 없어 현재 상태 재확인이 필요합니다.")
                    commandSucceeded(commands.execute(accountId, CharacterCommand.EquipItem(character.id, saved.revision, probe.value)))
                    check(observeEquipment(accountId, character.id).isNotEmpty()) { "빈 장비 저장 검증을 위한 임시 장착을 확인하지 못했습니다." }
                }
                val loadRevision = checkNotNull(query.findByAccountIdAndId(accountId, character.id)).updatedAt
                commandSucceeded(commands.execute(accountId,
                    CharacterCommand.LoadEquipmentPreset(character.id, loadRevision, step.slotNumber)))
                check(observeEquipment(accountId, character.id).groupingBy { it }.eachCount() == expected.groupingBy { it }.eachCount()) {
                    "장비 저장 ${step.slotNumber}의 실제 내용을 확인하지 못했습니다."
                }
            }
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

    private fun observeEquipment(accountId: Long, characterId: Long): List<HofEquipment> =
        observeSettings(accountId, characterId).equipment.map { it.copy(slot = "", checked = false) }

    private fun observeSettings(accountId: Long, characterId: Long): CharacterRestoreState {
        val page = currentSettings.observeCurrent(accountId, characterId)
        currentSettings.recordCurrentObservation(accountId, characterId, page)
        return CharacterRestoreState.capture(page)
    }

    private fun applyPattern(
        accountId: Long,
        characterId: Long,
        setting: CharacterPatternSetting,
        slotAfterApply: PatternSlotAfterApply,
    ) {
        val current = patternRemotes.withRemote(accountId, characterId) { it.observe() }
        check(setting.rows.size <= current.capacity) {
            "가져올 ${setting.rows.size}행이 장비 적용 후 대상의 ${current.capacity}행을 넘습니다. 패턴을 임의로 자르지 않았습니다."
        }
        val desired = setting.copy(rows = setting.rows + List(current.capacity - setting.rows.size) {
            CharacterPatternRowValue(current.judgeValues.first(), "0", current.skillValues.first())
        })
        if (slotAfterApply == PatternSlotAfterApply.None && current.setting == desired) return
        val result = patterns.applyDraft(
            accountId,
            characterId,
            current.setting,
            current.revision,
            CharacterPatternDraft(current.revision, desired.rows, desired.position, desired.guard),
            slotAfterApply,
        )
        if (result !is CharacterPatternOperationResult.Completed) error(patternFailure(result))
    }

    private fun commandSucceeded(result: CharacterCommandResult): CharacterCommandResult.Completed {
        if (result !is CharacterCommandResult.Completed) error("캐릭터 명령을 완료하지 못했습니다: $result")
        return result
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
        val targetCandidates = equipmentCandidates(targetCharacterId)
        fun targetEquipment(part: String, name: String, iconUrl: String, description: String) = resolveEquipment(
            CharacterTransferEquipment(part, "", HofEquipment(part = part, name = name, iconUrl = iconUrl, description = description)),
            targetCandidates,
        )
        fun currentEquipment(characterId: Long) = query.findEquipmentByCharacterIds(listOf(characterId))
            .filter { it.checked && it.name.isNotBlank() }
            .map { item ->
                targetEquipment(item.part.ifBlank { item.slot }, item.name, item.iconUrl, item.description)
            }
        val sourceEquipmentPresets = (1..2).mapNotNull { slotNumber ->
            val slot = query.findEquipmentSavedSlot(sourceCharacterId, slotNumber) ?: return@mapNotNull null
            slotNumber to query.findEquipmentSavedItems(slot.id).map { item ->
                targetEquipment(item.equipmentPart, item.name, item.iconUrl, item.description)
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
            equipment = currentEquipment(sourceCharacterId),
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
            equipmentCandidateValues = targetCandidates.map { it.value }.toSet(),
            currentPattern = currentPattern(targetCharacterId),
            currentEquipment = currentEquipment(targetCharacterId),
        )
    }

    private fun equipmentCandidates(characterId: Long) = query.findEquipmentCandidates(characterId).map {
        HofEquipmentCandidate(it.sourceValue, it.typeCode, it.name, it.iconUrl, it.description, it.quantity)
    }

    private fun resolveEquipment(item: CharacterTransferEquipment, candidates: List<HofEquipmentCandidate>): CharacterTransferEquipment {
        val identity = item.identity ?: return item
        val value = try {
            CharacterEquipmentCommandRules.requireRestoreCandidate(identity.name, identity.iconUrl, identity.description, candidates).value
        } catch (_: IllegalArgumentException) {
            "missing:${item.equipmentPart}:${identity.name}"
        }
        return item.copy(sourceValue = value)
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
