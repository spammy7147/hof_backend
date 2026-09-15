package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.automation.entity.QuestAutomationMapEntity
import app.spammy.hof.party.entity.PartyPresetEntity
import app.spammy.hof.party.entity.PartyPresetMemberEntity

/** 같은 파티 문제를 공유하는 활성 퀘스트·맵을 모아 기존 경고 응답으로 설명한다. */
internal fun questAutomationPresetWarnings(
    maps: List<QuestAutomationMapEntity>,
    primaryPresetId: Long?,
    presets: Map<Long, PartyPresetEntity>,
    members: Map<Long, List<PartyPresetMemberEntity>>,
    validPresetIds: Set<Long>,
): List<String> = maps.filter { it.questSelection.enabled }
    .groupBy { map ->
        val id = if (map.presetMode == PresetSelectionMode.PRIMARY) primaryPresetId else map.partyPreset?.id
        // 대표·명시 선택이 같은 파티로 해석되면 합치고, 파티 미지정은 선택 방식별로 설명한다.
        id to map.presetMode.takeIf { id == null }
    }.flatMap { (reference, affectedMaps) ->
        val (presetId, missingMode) = reference
        if (presetId in validPresetIds) return@flatMap emptyList()
        val preset = presets[presetId]
        val partyName = preset?.name?.takeIf { it.isNotBlank() }?.let { name ->
            if (presets.values.count { it.name == name } > 1) "$name (#$presetId)" else name
        } ?: presetId?.let { "파티 #$it" }
            ?: if (missingMode == PresetSelectionMode.PRIMARY) "대표 파티" else "선택한 파티"
        val problems = if (preset == null) {
            listOf("$partyName: 전투 프리셋을 찾을 수 없어요. 사용할 파티를 선택해 주세요.")
        } else {
            members[presetId].orEmpty().sortedBy { it.slotIndex }.mapNotNull { member ->
                val pattern = member.patternSlot
                val problem = automationPartyMemberProblem(
                    member.character?.hofCharacterId, pattern?.slotCode, pattern?.canLoad == true,
                ) ?: return@mapNotNull null
                val character = member.character?.name?.takeIf { it.isNotBlank() }
                    ?: member.character?.hofCharacterId ?: "캐릭터 없음"
                val patternLabel = pattern?.label?.takeIf { !pattern.canLoad && it.isNotBlank() }
                    ?.let { " 현재 표시: $it." }.orEmpty()
                "$partyName · ${member.slotIndex + 1}번 자리 $character: $problem$patternLabel"
            }.ifEmpty { listOf("$partyName: 파티에 설정된 캐릭터가 없어요.") }
        }
        val targets = affectedMaps.map { map ->
            val quest = map.questSelection
            "${quest.questName.ifBlank { quest.displayCode.ifBlank { quest.questKey } }} (${map.categoryId}/${map.mapCode})"
        }.distinct().joinToString(", ")
        problems.map { "$it\n영향 퀘스트·맵: $targets" }
    }
