package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.AutomationModuleType
import app.spammy.hof.automation.repository.AutomationModuleAggregate
import app.spammy.hof.party.repository.PartyPresetQueryRepository
import org.springframework.stereotype.Component

/**
 * 한 번의 프리셋 멤버 조회로 여러 자동화 모듈의 실제 실행 가능 상태를 계산한다.
 *
 * 프리셋 FK 존재만으로 준비 완료로 보지 않는다. 캐릭터가 들어간 슬롯이 하나 이상이고, 캐릭터가 있는
 * 모든 슬롯에 로드할 패턴이 연결된 프리셋만 실행 가능하다. 캐릭터가 없는 빈 슬롯 행은 무시한다.
 */
@Component
class AutomationModuleReadinessEvaluator(
    private val partyPresetQueryRepository: PartyPresetQueryRepository,
) {
    /** 요청 모듈이 참조하는 모든 프리셋 멤버를 batch 조회하고 모듈별 ready 결과를 반환한다. */
    fun evaluate(modules: Collection<AutomationModuleAggregate>): AutomationModuleReadiness {
        val presetIds = modules
            .flatMap { module ->
                module.maps.mapNotNull { it.partyPreset?.id } +
                    module.quests.flatMap { quest -> quest.maps.mapNotNull { it.partyPreset?.id } }
            }
            .toSet()
        val membersByPresetId = partyPresetQueryRepository.findMembersByPresetIds(presetIds)
            .groupBy { it.preset.id }
        val executablePresetIds = presetIds.filterTo(mutableSetOf()) { presetId ->
            val assignedMembers = membersByPresetId[presetId].orEmpty().filter { it.character != null }
            assignedMembers.isNotEmpty() && assignedMembers.all { it.patternSlot?.canLoad == true }
        }
        val readyModuleIds = modules
            .filter { it.isReadyForExecution(executablePresetIds) }
            .mapTo(mutableSetOf()) { it.config.id }
        return AutomationModuleReadiness(readyModuleIds)
    }
}

/** 한 batch 평가 안에서 모듈별 ready 결과를 재사용하는 불변 스냅샷이다. */
class AutomationModuleReadiness internal constructor(
    private val readyModuleIds: Set<Long>,
) {
    fun isReady(module: AutomationModuleAggregate): Boolean = module.config.id in readyModuleIds
}

/** 실제 캐릭터와 패턴이 완성된 프리셋 집합까지 포함해 실행 준비 상태를 판정한다. */
private fun AutomationModuleAggregate.isReadyForExecution(executablePresetIds: Set<Long>): Boolean =
    when (config.moduleType) {
        AutomationModuleType.KEY_QUEST -> quests.isNotEmpty() && quests.all { quest ->
            quest.maps.isNotEmpty() && quest.maps.all { it.partyPreset?.id in executablePresetIds }
        }

        AutomationModuleType.TIME_BURN ->
            config.thresholdPercent in 1..100 && maps.isNotEmpty() &&
                maps.all { it.partyPreset?.id in executablePresetIds }

        AutomationModuleType.COOLDOWN_ADVENTURE,
        AutomationModuleType.DAILY_ADVENTURE,
        -> maps.isNotEmpty() && maps.all { it.partyPreset?.id in executablePresetIds }

        AutomationModuleType.OTHER_QUEST -> quests.isNotEmpty()
        AutomationModuleType.UNION,
        AutomationModuleType.NORMAL_MAP,
        -> false
    }
