package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.AutomationModuleType
import app.spammy.hof.automation.repository.AutomationModuleAggregate

/**
 * 저장된 모듈이 외부 요청을 만들 수 있을 만큼 설정되었는지 판정한다.
 *
 * API의 `ready`, 자동화 시작 검증, 호환 snapshot loader가 같은 기준을 사용해야 미완성 모듈을 실행기로
 * 넘기지 않는다. 활성 여부는 호출자가 별도로 검사해 비활성 모듈의 설정 완성도도 화면에 표시할 수 있다.
 */
internal fun AutomationModuleAggregate.isReadyForExecution(): Boolean = when (config.moduleType) {
    AutomationModuleType.KEY_QUEST -> quests.isNotEmpty() && quests.all { quest ->
        quest.maps.isNotEmpty() && quest.maps.all { it.partyPreset != null }
    }

    AutomationModuleType.TIME_BURN ->
        config.thresholdPercent in 1..100 && maps.isNotEmpty() && maps.all { it.partyPreset != null }

    AutomationModuleType.COOLDOWN_ADVENTURE,
    AutomationModuleType.DAILY_ADVENTURE,
    -> maps.isNotEmpty() && maps.all { it.partyPreset != null }

    AutomationModuleType.OTHER_QUEST -> quests.isNotEmpty()
    AutomationModuleType.UNION,
    AutomationModuleType.NORMAL_MAP,
    -> false
}
