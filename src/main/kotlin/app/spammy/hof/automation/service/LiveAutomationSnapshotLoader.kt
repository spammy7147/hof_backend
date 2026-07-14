package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.AutomationModuleType
import app.spammy.hof.automation.policy.AutomationAccountStatus
import app.spammy.hof.automation.policy.AutomationMapState
import app.spammy.hof.automation.policy.AutomationModuleSnapshot
import app.spammy.hof.automation.policy.AutomationSnapshot
import app.spammy.hof.automation.policy.ConfiguredAutomationMap
import app.spammy.hof.automation.policy.ConfiguredAutomationQuest
import app.spammy.hof.automation.repository.AutomationModuleAggregate
import app.spammy.hof.automation.repository.UnifiedAutomationQueryRepository
import app.spammy.hof.battle.entity.AccountBattleMapStateEntity
import app.spammy.hof.battle.repository.BattleMapQueryRepository
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.quest.service.QuestGatewayService
import app.spammy.hof.status.service.HofStatusService
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 최신 HOF 상태와 정규화된 자동화 설정을 한 번의 정책 판단용 불변 스냅샷으로 조립한다.
 *
 * 활성화되고 실제 프리셋까지 준비된 모듈을 `priority`, `id` 순서로 모두 유지한다. 동일 유형을 하나로
 * 합치거나 첫 항목만 선택하지 않으며, 모든 모듈의 맵 상태는 QueryDSL batch 조회 한 번으로 가져온다.
 */
@Component
class LiveAutomationSnapshotLoader(
    private val questGatewayService: QuestGatewayService,
    private val statusService: HofStatusService,
    private val unifiedQueryRepository: UnifiedAutomationQueryRepository,
    private val battleMapQueryRepository: BattleMapQueryRepository,
    private val timeProvider: TimeProvider,
    private val readinessEvaluator: AutomationModuleReadinessEvaluator,
) : AutomationSnapshotLoader {
    /** 계정의 실행 가능한 사용자 모듈과 가변 HOF 상태를 우선순위를 보존해 조립한다. */
    @Transactional(readOnly = true)
    override fun load(accountId: Long): AutomationSnapshot {
        val quests = questGatewayService.load(accountId)
        val status = statusService.fetch(accountId)
        val profile = unifiedQueryRepository.findProfile(accountId)
            ?: throw AutomationConfigurationException("통합 자동화 설정을 저장해 주세요.")
        val aggregates = unifiedQueryRepository.findModules(profile.id)
        val readiness = readinessEvaluator.evaluate(aggregates)
        val executable = aggregates
            .asSequence()
            .filter { it.config.enabled && readiness.isReady(it) }
            .filter { it.config.moduleType in SUPPORTED_TYPES }
            .sortedWith(compareBy<AutomationModuleAggregate> { it.config.priority }.thenBy { it.config.id })
            .toList()
        val requestedMapPairs = executable
            .flatMap { module ->
                module.maps.map { it.battleMap.categoryId to it.battleMap.mapCode } +
                    module.quests.flatMap { quest ->
                        quest.maps.map { it.battleMap.categoryId to it.battleMap.mapCode }
                    }
            }
            .toSet()
        val states = battleMapQueryRepository.findStatesForExecution(accountId, requestedMapPairs)

        return AutomationSnapshot(
            accountId = accountId,
            modules = executable.map { it.toSnapshot() },
            accountStatus = AutomationAccountStatus(
                timeCurrent = status.timeCurrent ?: 0,
                timeMax = status.timeMax ?: 0,
            ),
            questState = quests,
            mapStates = states.map { it.toSnapshot() },
            now = timeProvider.now(),
        )
    }

    /** 한 aggregate의 맵과 퀘스트를 다른 모듈과 공유하지 않는 값 객체로 복사한다. */
    private fun AutomationModuleAggregate.toSnapshot(): AutomationModuleSnapshot =
        AutomationModuleSnapshot(
            id = config.id,
            type = config.moduleType,
            priority = config.priority,
            configRevision = config.updatedAt,
            thresholdPercent = config.thresholdPercent,
            maps = maps.map { selected ->
                ConfiguredAutomationMap(
                    categoryId = selected.battleMap.categoryId,
                    mapCode = selected.battleMap.mapCode,
                    mapName = selected.battleMap.name,
                    partyPresetId = selected.partyPreset?.id,
                    executionOrder = selected.executionOrder,
                )
            },
            quests = quests.map { selectedQuest ->
                ConfiguredAutomationQuest(
                    questCode = selectedQuest.quest.questCode,
                    executionOrder = selectedQuest.quest.executionOrder,
                    maps = selectedQuest.maps.map { selectedMap ->
                        ConfiguredAutomationMap(
                            categoryId = selectedMap.battleMap.categoryId,
                            mapCode = selectedMap.battleMap.mapCode,
                            mapName = selectedMap.battleMap.name,
                            partyPresetId = selectedMap.partyPreset?.id,
                            executionOrder = selectedMap.executionOrder,
                        )
                    },
                )
            },
        )

    private fun AccountBattleMapStateEntity.toSnapshot(): AutomationMapState =
        AutomationMapState(
            categoryId = battleMap.categoryId,
            mapCode = battleMap.mapCode,
            mapName = battleMap.name,
            visible = visible,
            enabled = battleMap.enabled,
            cooldownUntil = cooldownUntil,
            winRemaining = winRemaining,
            attemptRemaining = attemptRemaining,
            availableCount = availableCount,
            keyCount = keyCount,
        )

    private companion object {
        val SUPPORTED_TYPES = setOf(
            AutomationModuleType.KEY_QUEST,
            AutomationModuleType.TIME_BURN,
            AutomationModuleType.COOLDOWN_ADVENTURE,
            AutomationModuleType.DAILY_ADVENTURE,
            AutomationModuleType.OTHER_QUEST,
        )
    }
}
