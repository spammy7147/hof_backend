package app.spammy.hof.automation.service

import app.spammy.hof.automation.dto.KeyQuestSettingsRequest
import app.spammy.hof.automation.dto.NormalQuestSettingsRequest
import app.spammy.hof.automation.dto.TimeSettingsRequest
import app.spammy.hof.automation.dto.ToggleModuleRequest
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
import app.spammy.hof.automation.repository.UnifiedAutomationQueryRepository
import app.spammy.hof.battle.entity.AccountBattleMapStateEntity
import app.spammy.hof.battle.repository.BattleMapQueryRepository
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.quest.model.QuestState
import app.spammy.hof.quest.service.QuestGatewayService
import app.spammy.hof.status.service.HofStatusService
import java.time.Instant
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

@Component
class LiveAutomationSnapshotLoader(
    private val questGatewayService: QuestGatewayService,
    private val statusService: HofStatusService,
    private val unifiedQueryRepository: UnifiedAutomationQueryRepository,
    private val battleMapQueryRepository: BattleMapQueryRepository,
    private val keyQuestPolicy: KeyQuestPolicy,
    private val adventureMapPolicy: AdventureMapPolicy,
    private val selectedQuestPolicy: SelectedQuestPolicy,
    private val objectMapper: ObjectMapper,
    private val timeProvider: TimeProvider,
) : AutomationSnapshotLoader {
    @Transactional(readOnly = true)
    override fun load(accountId: Long): AutomationSnapshot {
        val quests = questGatewayService.load(accountId)
        val status = statusService.fetch(accountId)
        val profile = unifiedQueryRepository.findProfile(accountId)
            ?: throw AutomationConfigurationException("통합 자동화 설정을 저장해 주세요.")
        val configs = unifiedQueryRepository.findConfigs(profile.id).associateBy { it.moduleType }
        val keySettings = configs[AutomationModuleType.KEY_QUEST]
            ?.let { objectMapper.readValue(it.settingsJson, KeyQuestSettingsRequest::class.java) }
            ?: KeyQuestSettingsRequest()
        val timeSettings = configs[AutomationModuleType.TIME_BURN]
            ?.let { objectMapper.readValue(it.settingsJson, TimeSettingsRequest::class.java) }
            ?: TimeSettingsRequest()
        val cooldownSettings = configs[AutomationModuleType.COOLDOWN_ADVENTURE]
            ?.let { objectMapper.readValue(it.settingsJson, ToggleModuleRequest::class.java) }
            ?: ToggleModuleRequest()
        val dailySettings = configs[AutomationModuleType.DAILY_ADVENTURE]
            ?.let { objectMapper.readValue(it.settingsJson, ToggleModuleRequest::class.java) }
            ?: ToggleModuleRequest()
        val normalSettings = configs[AutomationModuleType.OTHER_QUEST]
            ?.let { objectMapper.readValue(it.settingsJson, NormalQuestSettingsRequest::class.java) }
            ?: NormalQuestSettingsRequest()
        val keyConfigs = keySettings.quests.associate { quest ->
            quest.questId to QuestExecutionConfig(
                quest.maps.map { selected ->
                    val state = battleMapQueryRepository.findStateForExecution(
                        accountId,
                        selected.categoryId,
                        selected.mapCode,
                    )
                    KeyQuestMapCandidate(
                        mapCode = selected.mapCode,
                        mapName = state?.battleMap?.name
                            ?: KeyQuestDefaultCatalog.defaults[quest.questId]?.mapNames?.get(selected.mapCode)
                            ?: selected.mapCode,
                        keyCount = state?.keyCount,
                        executionOrder = selected.executionOrder,
                        partyPresetId = selected.partyPresetId,
                        categoryId = selected.categoryId,
                    )
                },
            )
        }
        val now = timeProvider.now()
        val cooldownCandidates = cooldownSettings.maps.mapNotNull { map -> stateCandidate(accountId, map) }
        val dailyCandidates = dailySettings.maps.mapNotNull { map -> stateCandidate(accountId, map) }
        val readyCooldown = if (cooldownSettings.enabled) adventureMapPolicy.selectCooldown(cooldownCandidates, now) else null
        val readyDaily = if (dailySettings.enabled) adventureMapPolicy.selectDaily(dailyCandidates) else null
        val earliestCooldown = cooldownCandidates.mapNotNull(AdventureCandidate::cooldownUntil).filter { it.isAfter(now) }.minOrNull()

        return AutomationSnapshot(
            claimableQuest = quests.firstOrNull { it.state == QuestState.CLAIMABLE },
            acceptablePriorityQuest = KeyQuestDefaultCatalog.priorityQuestIds
                .asSequence()
                .mapNotNull { id -> quests.firstOrNull { it.questId == id && it.state == QuestState.AVAILABLE } }
                .firstOrNull(),
            priorityQuestDecision = if (keySettings.enabled) keyQuestPolicy.decide(quests, keyConfigs) else null,
            timeCurrent = status.timeCurrent ?: 0,
            timeMax = status.timeMax ?: 0,
            timeThresholdPercent = timeSettings.thresholdPercent,
            timeMap = if (timeSettings.enabled) timeSettings.maps
                .sortedBy { it.executionOrder }
                .firstNotNullOfOrNull { map -> stateMap(accountId, map) } else null,
            unionTarget = null,
            readyCooldownMap = readyCooldown?.toMapCandidate(cooldownSettings),
            readyDailyMap = readyDaily?.toMapCandidate(dailySettings),
            normalQuestDecision = if (normalSettings.enabled) {
                selectedQuestPolicy.decide(quests, normalSettings.questIds.toSet())
            } else null,
            earliestNextRunAt = earliestCooldown ?: now.plusSeconds(DEFAULT_RECHECK_SECONDS),
        )
    }

    private fun stateCandidate(
        accountId: Long,
        map: app.spammy.hof.automation.dto.ModuleMapRequest,
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
        map: app.spammy.hof.automation.dto.ModuleMapRequest,
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

    private fun AdventureCandidate.toMapCandidate(settings: ToggleModuleRequest): AutomationMapCandidate {
        val selected = settings.maps.first { it.mapCode == mapCode }
        return AutomationMapCandidate(mapCode, mapName, executionOrder, selected.partyPresetId, selected.categoryId)
    }

    private companion object {
        const val DEFAULT_RECHECK_SECONDS = 30L
    }
}
