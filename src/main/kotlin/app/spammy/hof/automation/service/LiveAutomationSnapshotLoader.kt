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
 * 우선순위가 가장 높은 첫 실행 가능 모듈만 선택하며, 전체 모듈을 순차 평가하는 동작은 우선순위 실행기
 * 전환에서 담당한다. 퀘스트와 맵은 사용자가 실제 저장한 항목만 사용한다.
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
        val keyModule = modules.firstActiveAndReady(AutomationModuleType.KEY_QUEST)
        val timeModule = modules.firstActiveAndReady(AutomationModuleType.TIME_BURN)
        val cooldownModule = modules.firstActiveAndReady(AutomationModuleType.COOLDOWN_ADVENTURE)
        val dailyModule = modules.firstActiveAndReady(AutomationModuleType.DAILY_ADVENTURE)
        val otherQuestModule = modules.firstActiveAndReady(AutomationModuleType.OTHER_QUEST)
        val selectedModules = listOfNotNull(keyModule, timeModule, cooldownModule, dailyModule, otherQuestModule)
        val requestedMapPairs = selectedModules
            .flatMap { module ->
                module.maps.map { it.battleMap } + module.quests.flatMap { quest -> quest.maps.map { it.battleMap } }
            }
            .map { it.categoryId to it.mapCode }
            .toSet()
        val stateByMap = battleMapQueryRepository.findStatesForExecution(accountId, requestedMapPairs)
            .associateBy { it.battleMap.categoryId to it.battleMap.mapCode }

        val keyConfigs = keyModule?.quests.orEmpty().associate { quest ->
            quest.quest.questCode to QuestExecutionConfig(
                quest.maps.map { selected ->
                    val state = stateByMap[selected.battleMap.categoryId to selected.battleMap.mapCode]
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
        val cooldownCandidates = cooldownSettings.mapNotNull { map -> stateCandidate(map, stateByMap) }
        val dailyCandidates = dailySettings.mapNotNull { map -> stateCandidate(map, stateByMap) }
        val readyCooldown = adventureMapPolicy.selectCooldown(cooldownCandidates, now)
        val readyDaily = adventureMapPolicy.selectDaily(dailyCandidates)
        val earliestCooldown = cooldownCandidates
            .mapNotNull(AdventureCandidate::cooldownUntil)
            .filter { it.isAfter(now) }
            .minOrNull()
        val timeSettings = timeModule?.maps.orEmpty().map { it.toSetting() }
        val configuredQuests = listOfNotNull(keyModule, otherQuestModule)
            .sortedWith(compareBy({ it.config.priority }, { it.config.id }))
            .flatMap { module ->
                module.quests
                    .sortedWith(compareBy({ it.quest.executionOrder }, { it.quest.id }))
                    .map { quest -> ConfiguredQuest(quest.quest.questCode, module.config.moduleType) }
            }
        val questById = quests.associateBy { it.questId }
        val configuredQuestSnapshots = configuredQuests.mapNotNull { configured ->
            questById[configured.questCode]?.let { configured to it }
        }
        val claimable = configuredQuestSnapshots.firstOrNull { (_, quest) -> quest.state == QuestState.CLAIMABLE }
        val acceptable = configuredQuestSnapshots.firstOrNull { (_, quest) -> quest.state == QuestState.AVAILABLE }

        return AutomationSnapshot(
            claimableQuest = claimable?.second,
            acceptablePriorityQuest = acceptable?.second,
            priorityQuestDecision = keyModule?.let { keyQuestPolicy.decide(quests, keyConfigs) },
            timeCurrent = status.timeCurrent ?: 0,
            timeMax = status.timeMax ?: 0,
            timeThresholdPercent = timeModule?.config?.thresholdPercent ?: DEFAULT_TIME_THRESHOLD_PERCENT,
            timeMap = timeSettings
                .sortedBy(MapSetting::executionOrder)
                .firstNotNullOfOrNull { map -> stateMap(map, stateByMap) },
            unionTarget = null,
            readyCooldownMap = readyCooldown?.toMapCandidate(cooldownSettings),
            readyDailyMap = readyDaily?.toMapCandidate(dailySettings),
            normalQuestDecision = otherQuestModule?.let { module ->
                selectedQuestPolicy.decide(
                    quests,
                    module.quests.sortedBy { it.quest.executionOrder }.map { it.quest.questCode },
                )
            },
            earliestNextRunAt = earliestCooldown ?: now.plusSeconds(DEFAULT_RECHECK_SECONDS),
            claimableQuestModuleType = claimable?.first?.moduleType,
            acceptablePriorityQuestModuleType = acceptable?.first?.moduleType,
        )
    }

    /** 같은 유형에서 우선순위가 가장 높은 활성·준비 완료 모듈을 Task 4 전까지 호환 대상으로 선택한다. */
    private fun List<AutomationModuleAggregate>.firstActiveAndReady(
        type: AutomationModuleType,
    ): AutomationModuleAggregate? =
        asSequence()
            .filter { it.config.moduleType == type && it.config.enabled && it.isReadyForExecution() }
            .minWithOrNull(compareBy({ it.config.priority }, { it.config.id }))

    private fun stateCandidate(
        map: MapSetting,
        stateByMap: Map<Pair<String, String>, AccountBattleMapStateEntity>,
    ): AdventureCandidate? {
        val state = stateByMap[map.categoryId to map.mapCode] ?: return null
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
        map: MapSetting,
        stateByMap: Map<Pair<String, String>, AccountBattleMapStateEntity>,
    ): AutomationMapCandidate? {
        val state = stateByMap[map.categoryId to map.mapCode]
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

    private data class ConfiguredQuest(
        val questCode: String,
        val moduleType: AutomationModuleType,
    )

    private companion object {
        const val DEFAULT_RECHECK_SECONDS = 30L
        const val DEFAULT_TIME_THRESHOLD_PERCENT = 90
    }
}
