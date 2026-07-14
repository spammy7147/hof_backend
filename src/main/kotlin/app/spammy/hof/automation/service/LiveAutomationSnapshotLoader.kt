package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.AutomationModuleMapEntity
import app.spammy.hof.automation.entity.AutomationModuleType
import app.spammy.hof.automation.policy.AdventureCandidate
import app.spammy.hof.automation.policy.AdventureMapPolicy
import app.spammy.hof.automation.policy.AutomationMapCandidate
import app.spammy.hof.automation.policy.AutomationSnapshot
import app.spammy.hof.automation.policy.KeyQuestDefaultCatalog
import app.spammy.hof.automation.policy.KeyQuestMapCandidate
import app.spammy.hof.automation.policy.KeyQuestPolicy
import app.spammy.hof.automation.policy.QuestExecutionConfig
import app.spammy.hof.automation.policy.SelectedQuestPolicy
import app.spammy.hof.automation.repository.AutomationModuleAggregate
import app.spammy.hof.automation.repository.UnifiedAutomationQueryRepository
import app.spammy.hof.battle.entity.AccountBattleMapStateEntity
import app.spammy.hof.battle.repository.BattleMapQueryRepository
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.quest.model.QuestState
import app.spammy.hof.quest.service.QuestGatewayService
import app.spammy.hof.status.service.HofStatusService
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * HOF의 현재 상태와 정규화된 자동화 설정을 기존 실행 정책이 소비하는 스냅샷으로 변환한다.
 *
 * 이 로더는 JSON 설정을 역직렬화하지 않는다. 동일 유형이 여러 개인 경우 현재 실행기와 호환하기 위해
 * 우선순위가 가장 높은 첫 모듈만 선택하며, 전체 모듈을 순차 평가하는 동작은 우선순위 실행기 전환에서
 * 담당한다.
 */
@Component
class LiveAutomationSnapshotLoader(
    private val questGatewayService: QuestGatewayService,
    private val statusService: HofStatusService,
    private val unifiedQueryRepository: UnifiedAutomationQueryRepository,
    private val battleMapQueryRepository: BattleMapQueryRepository,
    private val keyQuestPolicy: KeyQuestPolicy,
    private val adventureMapPolicy: AdventureMapPolicy,
    private val selectedQuestPolicy: SelectedQuestPolicy,
    private val timeProvider: TimeProvider,
) : AutomationSnapshotLoader {
    /** 계정의 최신 HOF 상태와 가장 높은 우선순위의 유형별 모듈을 한 실행 판단 스냅샷으로 조립한다. */
    @Transactional(readOnly = true)
    override fun load(accountId: Long): AutomationSnapshot {
        val quests = questGatewayService.load(accountId)
        val status = statusService.fetch(accountId)
        val profile = unifiedQueryRepository.findProfile(accountId)
            ?: throw AutomationConfigurationException("통합 자동화 설정을 저장해 주세요.")
        val modules = unifiedQueryRepository.findModules(profile.id)
        val keyModule = modules.firstEnabled(AutomationModuleType.KEY_QUEST)
        val timeModule = modules.firstEnabled(AutomationModuleType.TIME_BURN)
        val cooldownModule = modules.firstEnabled(AutomationModuleType.COOLDOWN_ADVENTURE)
        val dailyModule = modules.firstEnabled(AutomationModuleType.DAILY_ADVENTURE)
        val otherQuestModule = modules.firstEnabled(AutomationModuleType.OTHER_QUEST)

        val keyConfigs = keyModule?.quests.orEmpty().associate { quest ->
            quest.quest.questCode to QuestExecutionConfig(
                quest.maps.map { selected ->
                    val state = battleMapQueryRepository.findStateForExecution(
                        accountId,
                        selected.battleMap.categoryId,
                        selected.battleMap.mapCode,
                    )
                    KeyQuestMapCandidate(
                        mapCode = selected.battleMap.mapCode,
                        mapName = state?.battleMap?.name
                            ?: KeyQuestDefaultCatalog.defaults[quest.quest.questCode]
                                ?.mapNames
                                ?.get(selected.battleMap.mapCode)
                            ?: selected.battleMap.name,
                        keyCount = state?.keyCount,
                        executionOrder = selected.executionOrder,
                        partyPresetId = selected.partyPreset?.id,
                        categoryId = selected.battleMap.categoryId,
                    )
                },
            )
        }
        val now = timeProvider.now()
        val cooldownSettings = cooldownModule?.maps.orEmpty().map { it.toSetting() }
        val dailySettings = dailyModule?.maps.orEmpty().map { it.toSetting() }
        val cooldownCandidates = cooldownSettings.mapNotNull { map -> stateCandidate(accountId, map) }
        val dailyCandidates = dailySettings.mapNotNull { map -> stateCandidate(accountId, map) }
        val readyCooldown = adventureMapPolicy.selectCooldown(cooldownCandidates, now)
        val readyDaily = adventureMapPolicy.selectDaily(dailyCandidates)
        val earliestCooldown = cooldownCandidates
            .mapNotNull(AdventureCandidate::cooldownUntil)
            .filter { it.isAfter(now) }
            .minOrNull()
        val timeSettings = timeModule?.maps.orEmpty().map { it.toSetting() }

        return AutomationSnapshot(
            claimableQuest = quests.firstOrNull { it.state == QuestState.CLAIMABLE },
            acceptablePriorityQuest = KeyQuestDefaultCatalog.priorityQuestIds
                .asSequence()
                .mapNotNull { id -> quests.firstOrNull { it.questId == id && it.state == QuestState.AVAILABLE } }
                .firstOrNull(),
            priorityQuestDecision = keyModule?.let { keyQuestPolicy.decide(quests, keyConfigs) },
            timeCurrent = status.timeCurrent ?: 0,
            timeMax = status.timeMax ?: 0,
            timeThresholdPercent = timeModule?.config?.thresholdPercent ?: DEFAULT_TIME_THRESHOLD_PERCENT,
            timeMap = timeSettings
                .sortedBy(MapSetting::executionOrder)
                .firstNotNullOfOrNull { map -> stateMap(accountId, map) },
            unionTarget = null,
            readyCooldownMap = readyCooldown?.toMapCandidate(cooldownSettings),
            readyDailyMap = readyDaily?.toMapCandidate(dailySettings),
            normalQuestDecision = otherQuestModule?.let { module ->
                selectedQuestPolicy.decide(quests, module.quests.map { it.quest.questCode }.toSet())
            },
            earliestNextRunAt = earliestCooldown ?: now.plusSeconds(DEFAULT_RECHECK_SECONDS),
        )
    }

    private fun List<AutomationModuleAggregate>.firstEnabled(type: AutomationModuleType): AutomationModuleAggregate? =
        firstOrNull { it.config.moduleType == type && it.config.enabled }

    private fun stateCandidate(
        accountId: Long,
        map: MapSetting,
    ): AdventureCandidate? {
        val state = battleMapQueryRepository.findStateForExecution(accountId, map.categoryId, map.mapCode) ?: return null
        return AdventureCandidate(
            mapCode = map.mapCode,
            mapName = state.battleMap.name,
            executionOrder = map.executionOrder,
            visible = state.visible,
            enabled = state.battleMap.enabled,
            cooldownUntil = state.cooldownUntil,
            winRemaining = state.winRemaining,
            attemptRemaining = state.attemptRemaining,
            availableCount = state.availableCount,
        )
    }

    private fun stateMap(
        accountId: Long,
        map: MapSetting,
    ): AutomationMapCandidate? {
        val state = battleMapQueryRepository.findStateForExecution(accountId, map.categoryId, map.mapCode)
            ?.takeIf(AccountBattleMapStateEntity::visible) ?: return null
        return AutomationMapCandidate(
            map.mapCode,
            state.battleMap.name,
            map.executionOrder,
            map.partyPresetId,
            map.categoryId,
        )
    }

    private fun AdventureCandidate.toMapCandidate(settings: List<MapSetting>): AutomationMapCandidate {
        val selected = settings.first { it.mapCode == mapCode }
        return AutomationMapCandidate(mapCode, mapName, executionOrder, selected.partyPresetId, selected.categoryId)
    }

    private fun AutomationModuleMapEntity.toSetting() = MapSetting(
        categoryId = battleMap.categoryId,
        mapCode = battleMap.mapCode,
        partyPresetId = partyPreset?.id,
        executionOrder = executionOrder,
    )

    private data class MapSetting(
        val categoryId: String,
        val mapCode: String,
        val partyPresetId: Long?,
        val executionOrder: Int,
    )

    private companion object {
        const val DEFAULT_RECHECK_SECONDS = 30L
        const val DEFAULT_TIME_THRESHOLD_PERCENT = 90
    }
}
